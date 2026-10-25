package com.marianocarrizo.dynamicMusic.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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

public final class YtDlpManager {

    private static final String RELEASE_BASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String CHECKSUMS_ASSET = "SHA2-256SUMS";
    static final String VERIFY_FAILED = "Could not verify yt-dlp download";
    static final String ZIPAPP_ASSET = "yt-dlp";
    private static final long MIN_BINARY_BYTES   = 1_000_000L;
    private static final long CHECK_INTERVAL_MS  = TimeUnit.HOURS.toMillis(24);
    private static final long UPDATE_TIMEOUT_SEC = 120;
    private static final long PROBE_TIMEOUT_SEC  = 10;

    private static final YtDlpManager INSTANCE = new YtDlpManager(YouTubeDownloader::appDir,
            System.getProperty("os.name", ""), System.getProperty("os.arch", ""));

    public static YtDlpManager get() { return INSTANCE; }

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
                out = new byte[0];
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

    private final Object lock = new Object();
    private volatile Consumer<String> statusListener;
    private volatile List<String> jsArgs;
    private boolean checkedThisSession;
    private CompletableFuture<Void> setup;
    private final Object updateLock = new Object();
    private final Object jsLock = new Object();

    private final Supplier<Path> appDirSupplier;
    private final String osName;
    private final String osArch;

    YtDlpManager(Supplier<Path> appDirSupplier, String osName, String osArch) {
        this.appDirSupplier = appDirSupplier;
        this.osName = osName;
        this.osArch = osArch;
    }

    public void setStatusListener(Consumer<String> listener) { this.statusListener = listener; }

    void announce(String msg) { status(msg); }

    private void status(String msg) {
        Consumer<String> l = statusListener;
        if (l == null) return;
        try { l.accept(msg); } catch (RuntimeException e) { System.err.println("[yt-dlp] listener failed: " + e); }
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

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

    static String binaryNameFor(String osName) {
        return isWindows(osName) ? "yt-dlp.exe" : "yt-dlp";
    }

    static Optional<String> parseChecksum(String sums, String assetName) {
        return Checksums.parse(sums, assetName);
    }

    static String sha256(Path file) throws IOException {
        return Checksums.sha256(file);
    }

    static void verify(Path file, String sums, String assetName) throws IOException {
        if (!Checksums.matches(file, sums, assetName)) throw new IOException(VERIFY_FAILED);
    }

    private Path binDir()        { return appDirSupplier.get().resolve("bin"); }
    private Path managedPath()   { return binDir().resolve(binaryNameFor(osName)); }
    private Path lastCheckPath() { return binDir().resolve("yt-dlp.lastcheck"); }

    public boolean hasManagedBinary() {
        try {
            Path p = managedPath();
            return Files.isRegularFile(p) && Files.size(p) > MIN_BINARY_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<String> executable() {
        if (hasManagedBinary()) return Optional.of(managedPath().toString());
        return ExecutableResolver.find("yt-dlp").map(Path::toString);
    }

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

    public boolean isSetupRunning() {
        synchronized (lock) { return setup != null && !setup.isDone(); }
    }

    public CompletableFuture<Void> readiness() {
        synchronized (lock) { return setup != null ? setup : CompletableFuture.completedFuture(null); }
    }

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

    private void download() throws IOException, InterruptedException {
        Files.createDirectories(binDir());
        String asset = assetFor(osName, osArch);
        Path target = managedPath();
        Path tmp = binDir().resolve(target.getFileName() + ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            String sums;
            try {
                sums = new String(BoundedDownload.toBytes(client,
                        HttpRequest.newBuilder(URI.create(RELEASE_BASE + CHECKSUMS_ASSET))
                                .timeout(Duration.ofSeconds(30)).GET().build(),
                        BoundedDownload::isGitHubHost, BoundedDownload.MAX_TEXT_BYTES,
                        Duration.ofSeconds(30), "yt-dlp checksums"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IOException(VERIFY_FAILED, e);
            }

            BoundedDownload.toFile(client,
                    HttpRequest.newBuilder(URI.create(RELEASE_BASE + asset))
                            .timeout(Duration.ofSeconds(120)).GET().build(),
                    tmp, BoundedDownload::isGitHubHost, BoundedDownload.MAX_YTDLP_BYTES,
                    Duration.ofSeconds(120), "yt-dlp");
            if (Files.size(tmp) <= MIN_BINARY_BYTES) throw new IOException("downloaded file is too small");
            verify(tmp, sums, asset);

            if (!isWindows(osName) && !tmp.toFile().setExecutable(true, false)) {
                throw new IOException("could not mark yt-dlp as executable");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    public List<String> jsRuntimeArgs() {
        List<String> cached = jsArgs;
        if (cached != null) return cached;
        synchronized (jsLock) {
            if (jsArgs == null) {
                jsArgs = detectJsRuntime();
            }
            return jsArgs;
        }
    }

    private static List<String> detectJsRuntime() {
        for (String runtime : List.of("deno", "node")) {
            Optional<Path> exe = ExecutableResolver.find(runtime);
            if (exe.isPresent() && works(exe.get().toString())) {
                return List.of("--js-runtimes", runtime + ":" + exe.get());
            }
        }
        return List.of();
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
