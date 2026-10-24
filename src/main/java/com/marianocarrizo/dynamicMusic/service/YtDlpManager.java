package com.marianocarrizo.dynamicMusic.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns a private, self-updating copy of yt-dlp under {@code <appDir>/bin}.
 * No JavaFX imports: the status listener fires on arbitrary threads, callers must marshal to the FX thread.
 * Until the managed binary exists, {@link #executable()} falls back to {@code yt-dlp} on PATH.
 */
public final class YtDlpManager {

    private static final String RELEASE_BASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String CHECKSUMS_ASSET = "SHA2-256SUMS";
    static final String VERIFY_FAILED = "Could not verify yt-dlp download";
    /** Platform-independent zipapp; needs python3, so only used when no native asset matches. */
    static final String ZIPAPP_ASSET = "yt-dlp";
    private static final long MIN_BINARY_BYTES   = 1_000_000L;
    private static final long CHECK_INTERVAL_MS  = TimeUnit.HOURS.toMillis(24);
    private static final long UPDATE_TIMEOUT_SEC = 120;
    private static final long PROBE_TIMEOUT_SEC  = 10;

    private static final YtDlpManager INSTANCE = new YtDlpManager(YouTubeDownloader::appDir,
            System.getProperty("os.name", ""), System.getProperty("os.arch", ""));

    public static YtDlpManager get() { return INSTANCE; }

    // --- Process helper with watchdog ---

    /** Outcome of {@link #run}. {@code exitCode} is -1 when the process timed out or could not run. */
    record ProcResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        String combined() {
            if (stderr.isBlank()) return stdout;
            if (stdout.isBlank()) return stderr;
            return stdout + "\n" + stderr;
        }
    }

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "yt-dlp-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * Runs {@code cmd}, capturing stdout (calling thread) and stderr (drainer thread).
     * A watchdog destroys the process after {@code timeoutSec}, which closes its streams and
     * unblocks the reads, so a hung process can never block the caller forever.
     */
    static ProcResult run(List<String> cmd, long timeoutSec) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            if (proc.isAlive()) {
                timedOut.set(true);
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
            }
        }, timeoutSec, TimeUnit.SECONDS);

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream err = proc.getErrorStream()) {
                err.transferTo(errBuf);
            } catch (IOException ignored) {}
        }, "yt-dlp-stderr-drainer");
        drainer.setDaemon(true);
        drainer.start();

        try {
            byte[] out;
            try (InputStream in = proc.getInputStream()) {
                out = in.readAllBytes();
            } catch (IOException e) {
                out = new byte[0]; // stream closed by the watchdog
            }
            proc.waitFor();
            drainer.join(2000);
            String stdout = new String(out, StandardCharsets.UTF_8);
            String stderr;
            synchronized (errBuf) { stderr = errBuf.toString(StandardCharsets.UTF_8); }
            boolean to = timedOut.get();
            return new ProcResult(stdout, stderr, to ? -1 : proc.exitValue(), to);
        } finally {
            guard.cancel(false);
            if (proc.isAlive()) proc.destroyForcibly();
        }
    }

    // --- State ---

    private final Object lock = new Object();
    private volatile Consumer<String> statusListener;
    private volatile List<String> jsArgs;       // cached once
    private boolean checkedThisSession;
    private CompletableFuture<Void> setup;      // guarded by lock; the latest setup attempt
    private final Object updateLock = new Object();
    private final Object jsLock = new Object();

    private final Supplier<Path> appDirSupplier;
    private final String osName;
    private final String osArch;

    /** Package-private so tests can point the manager at a temp dir and a fake OS. */
    YtDlpManager(Supplier<Path> appDirSupplier, String osName, String osArch) {
        this.appDirSupplier = appDirSupplier;
        this.osName = osName;
        this.osArch = osArch;
    }

    public void setStatusListener(Consumer<String> listener) { this.statusListener = listener; }

    /** Pushes a status message to the listener (also used by callers that wait for setup). */
    void announce(String msg) { status(msg); }

    private void status(String msg) {
        Consumer<String> l = statusListener;
        if (l == null) return;
        try { l.accept(msg); } catch (RuntimeException e) { System.err.println("[yt-dlp] listener failed: " + e); }
    }

    // --- Paths ---

    // --- Platform selection (pure) ---

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Official release asset for the given {@code os.name}/{@code os.arch}: Windows yt-dlp.exe,
     * macOS yt-dlp_macos, Linux x86_64 yt-dlp_linux, Linux aarch64 yt-dlp_linux_aarch64,
     * otherwise the python zipapp.
     */
    static String assetFor(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "yt-dlp.exe";
        if (os.contains("mac") || os.contains("darwin")) return "yt-dlp_macos";
        if (os.contains("linux")) {
            if (arch.equals("amd64") || arch.equals("x86_64")) return "yt-dlp_linux";
            if (arch.equals("aarch64") || arch.equals("arm64")) return "yt-dlp_linux_aarch64";
        }
        return ZIPAPP_ASSET;
    }

    /** File name of the managed binary on disk: yt-dlp.exe on Windows, yt-dlp elsewhere. */
    static String binaryNameFor(String osName) {
        return isWindows(osName) ? "yt-dlp.exe" : "yt-dlp";
    }

    // --- Checksums (pure) ---

    /**
     * Finds the SHA-256 for {@code assetName} in a {@code SHA2-256SUMS} text
     * ({@code <hex>  <name>} per line, optional {@code *} binary marker). Returns lower-case hex.
     */
    static Optional<String> parseChecksum(String sums, String assetName) {
        if (sums == null) return Optional.empty();
        for (String line : sums.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+", 2);
            if (parts.length != 2) continue;
            String name = parts[1].strip();
            if (name.startsWith("*")) name = name.substring(1);
            if (name.equals(assetName) && parts[0].matches("[0-9a-fA-F]{64}")) {
                return Optional.of(parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /** Lower-case hex SHA-256 of a file's contents. */
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /** Throws {@link IOException} ({@link #VERIFY_FAILED}) unless {@code file} matches the hash listed for the asset. */
    static void verify(Path file, String sums, String assetName) throws IOException {
        Optional<String> expected = parseChecksum(sums, assetName);
        if (expected.isEmpty() || !expected.get().equals(sha256(file))) {
            throw new IOException(VERIFY_FAILED);
        }
    }

    // --- Paths ---

    private Path binDir()        { return appDirSupplier.get().resolve("bin"); }
    private Path managedPath()   { return binDir().resolve(binaryNameFor(osName)); }
    private Path lastCheckPath() { return binDir().resolve("yt-dlp.lastcheck"); }

    /** True when the managed binary exists and is large enough to be real. */
    public boolean hasManagedBinary() {
        try {
            Path p = managedPath();
            return Files.isRegularFile(p) && Files.size(p) > MIN_BINARY_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /** The managed binary if usable, otherwise {@code yt-dlp} from PATH. */
    public String executable() {
        return hasManagedBinary() ? managedPath().toString() : "yt-dlp";
    }

    // --- Public update API ---

    /**
     * Starts the download/update check on a background thread. Safe to call repeatedly: no-op while a setup
     * is running, and once the managed binary exists it runs once per session. If an earlier attempt failed
     * (offline) and the binary is still missing, the next call retries.
     */
    public void ensureReadyAsync() {
        CompletableFuture<Void> done;
        synchronized (lock) {
            if (setup != null && !setup.isDone()) return;
            if (checkedThisSession && hasManagedBinary()) return;
            checkedThisSession = true;
            done = new CompletableFuture<>();
            setup = done;
        }
        Thread t = new Thread(() -> {
            try {
                if (!hasManagedBinary()) {
                    update(false);
                } else if (System.currentTimeMillis() - readLastCheck() > CHECK_INTERVAL_MS) {
                    update(false);
                }
            } catch (Throwable e) {
                System.err.println("[yt-dlp] ensureReady failed: " + e);
                status("Could not update yt-dlp: " + e.getMessage());
            } finally {
                done.complete(null);
            }
        }, "yt-dlp-ensure-ready");
        t.setDaemon(true);
        t.start();
    }

    /** True while the download / update check started by {@link #ensureReadyAsync} is still running. */
    public boolean isSetupRunning() {
        synchronized (lock) { return setup != null && !setup.isDone(); }
    }

    /** Completes when the current setup finishes (already complete when none is running). Never fails. */
    public CompletableFuture<Void> readiness() {
        synchronized (lock) { return setup != null ? setup : CompletableFuture.completedFuture(null); }
    }

    /** Synchronous update (download if missing, else {@code -U}). Used for retry after a failure. */
    public void forceUpdate() {
        try {
            update(true);
        } catch (Throwable e) {
            System.err.println("[yt-dlp] forceUpdate failed: " + e);
            status("Could not update yt-dlp: " + e.getMessage());
        }
    }

    private void update(boolean force) {
        try {
            // Serialize concurrent updaters; a second caller waits for the first to finish.
            synchronized (updateLock) {
                if (!hasManagedBinary()) {
                    status("Downloading yt-dlp...");
                    download();
                    writeLastCheck();
                    status("yt-dlp updated");
                } else {
                    if (!force && System.currentTimeMillis() - readLastCheck() <= CHECK_INTERVAL_MS) return;
                    status("Updating yt-dlp...");
                    selfUpdate();
                }
            }
        } catch (IOException e) {
            System.err.println("[yt-dlp] update failed: " + e);
            status("Could not update yt-dlp: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status("Could not update yt-dlp: interrupted");
        }
    }

    private void selfUpdate() throws IOException, InterruptedException {
        ProcResult r = run(List.of(managedPath().toString(), "-U"), UPDATE_TIMEOUT_SEC);
        String out = r.combined();
        if (r.timedOut()) throw new IOException("update timed out after " + UPDATE_TIMEOUT_SEC + "s");
        if (r.exitCode() != 0) throw new IOException(lastLine(out.isBlank() ? "yt-dlp -U exited with " + r.exitCode() : out));
        writeLastCheck();
        String lower = out.toLowerCase();
        if (lower.contains("up to date") || lower.contains("is up-to-date")) status("yt-dlp is up to date");
        else status("yt-dlp updated");
    }

    /** Downloads the platform asset, verifies it against the release's SHA2-256SUMS, then moves it into place. */
    private void download() throws IOException, InterruptedException {
        Files.createDirectories(binDir());
        String asset = assetFor(osName, osArch);
        Path target = managedPath();
        Path tmp = binDir().resolve(target.getFileName() + ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL) // never downgrade https to http
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> sumsResp = client.send(
                    HttpRequest.newBuilder(URI.create(RELEASE_BASE + CHECKSUMS_ASSET))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (sumsResp.statusCode() != 200) throw new IOException(VERIFY_FAILED);

            HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASE_BASE + asset))
                    .timeout(Duration.ofSeconds(120))
                    .GET().build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " downloading yt-dlp");
            if (Files.size(tmp) <= MIN_BINARY_BYTES) throw new IOException("downloaded file is too small");
            verify(tmp, sumsResp.body(), asset);

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (!isWindows(osName) && !target.toFile().setExecutable(true, false)) {
                throw new IOException("could not mark yt-dlp as executable");
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private long readLastCheck() {
        try {
            return Long.parseLong(Files.readString(lastCheckPath()).trim());
        } catch (IOException | NumberFormatException e) {
            return 0L;
        }
    }

    private void writeLastCheck() {
        try {
            Files.createDirectories(binDir());
            Files.writeString(lastCheckPath(), Long.toString(System.currentTimeMillis()));
        } catch (IOException e) {
            System.err.println("[yt-dlp] could not record update check: " + e);
        }
    }

    private static String lastLine(String s) {
        String t = s.strip();
        int i = t.lastIndexOf('\n');
        return i >= 0 ? t.substring(i + 1).strip() : t;
    }

    // --- JS runtime ---

    /** Extra args so yt-dlp can solve YouTube's JS challenges: empty for deno (default) or when none found. */
    public List<String> jsRuntimeArgs() {
        List<String> cached = jsArgs;
        if (cached != null) return cached;
        synchronized (jsLock) {
            if (jsArgs == null) {
                if (works("deno")) jsArgs = List.of();
                else if (works("node")) jsArgs = List.of("--js-runtimes", "node");
                else jsArgs = List.of();
            }
            return jsArgs;
        }
    }

    private static boolean works(String exe) {
        try {
            ProcResult r = run(List.of(exe, "--version"), PROBE_TIMEOUT_SEC);
            return !r.timedOut() && r.exitCode() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
