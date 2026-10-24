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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns a private, self-updating copy of yt-dlp under {@code <appDir>/bin}.
 * No JavaFX imports: the status listener fires on arbitrary threads, callers must marshal to the FX thread.
 * Until the managed binary exists, {@link #executable()} falls back to {@code yt-dlp} on PATH.
 */
public final class YtDlpManager {

    private static final String DOWNLOAD_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final long MIN_BINARY_BYTES   = 1_000_000L;
    private static final long CHECK_INTERVAL_MS  = TimeUnit.HOURS.toMillis(24);
    private static final long UPDATE_TIMEOUT_SEC = 120;
    private static final long PROBE_TIMEOUT_SEC  = 10;

    private static final YtDlpManager INSTANCE = new YtDlpManager();

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
    private boolean asyncStarted;
    private final Object updateLock = new Object();
    private final Object jsLock = new Object();

    private YtDlpManager() {}

    public void setStatusListener(Consumer<String> listener) { this.statusListener = listener; }

    private void status(String msg) {
        Consumer<String> l = statusListener;
        if (l == null) return;
        try { l.accept(msg); } catch (RuntimeException e) { System.err.println("[yt-dlp] listener failed: " + e); }
    }

    // --- Paths ---

    private static Path binDir()        { return YouTubeDownloader.appDir().resolve("bin"); }
    private static Path managedPath()   { return binDir().resolve("yt-dlp.exe"); }
    private static Path lastCheckPath() { return binDir().resolve("yt-dlp.lastcheck"); }

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

    /** Starts the download/update check on a background thread. Safe to call repeatedly; runs once per session. */
    public void ensureReadyAsync() {
        synchronized (lock) {
            if (asyncStarted) return;
            asyncStarted = true;
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
            }
        }, "yt-dlp-ensure-ready");
        t.setDaemon(true);
        t.start();
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
                    status("Updating yt-dlp...");
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
        Path tmp = binDir().resolve("yt-dlp.exe.tmp");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL) // never downgrade https to http
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                    .timeout(Duration.ofSeconds(120))
                    .GET().build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " downloading yt-dlp");
            if (Files.size(tmp) <= MIN_BINARY_BYTES) throw new IOException("downloaded file is too small");
            Files.move(tmp, managedPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static long readLastCheck() {
        try {
            return Long.parseLong(Files.readString(lastCheckPath()).trim());
        } catch (IOException | NumberFormatException e) {
            return 0L;
        }
    }

    private static void writeLastCheck() {
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
