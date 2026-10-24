package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.service.UpdateChecker.ReleaseInfo;
import com.marianocarrizo.dynamicMusic.service.UpdateChecker.Version;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class UpdateManager {

    static final String VERSION_PROPERTY = "dynamicmusic.version";
    private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
    private static final Pattern INSTALLER_FILE = Pattern.compile("^DynamicMusic-Setup-(\\d+\\.\\d+\\.\\d+)\\.exe$");

    static final List<String> INSTALLER_ARGS =
            List.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS");

    public record ReadyUpdate(Version version, Path installer) {}

    private static final UpdateManager INSTANCE = new UpdateManager(YouTubeDownloader::appDir,
            Version.parse(System.getProperty(VERSION_PROPERTY)), System.getProperty("os.name", ""),
            URI.create(UpdateChecker.LATEST_RELEASE_URL));

    public static UpdateManager get() { return INSTANCE; }

    private final Supplier<Path> appDirSupplier;
    private final Optional<Version> current;
    private final String osName;
    private final URI api;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean launched = new AtomicBoolean();
    private final AtomicBoolean hookRegistered = new AtomicBoolean();
    private volatile ReadyUpdate installOnClose;

    UpdateManager(Supplier<Path> appDirSupplier, Optional<Version> current, String osName, URI api) {
        this.appDirSupplier = appDirSupplier;
        this.current = current;
        this.osName = osName;
        this.api = api;
    }

    public boolean isEnabled() {
        return current.isPresent() && YtDlpManager.isWindows(osName);
    }

    private Path updatesDir()    { return appDirSupplier.get().resolve("updates"); }
    private Path lastCheckPath() { return appDirSupplier.get().resolve("update.lastcheck"); }

    static boolean shouldCheck(long lastCheckMs, long nowMs) {
        return lastCheckMs > nowMs || nowMs - lastCheckMs > CHECK_INTERVAL_MS;
    }

    static Optional<Version> installerVersion(String fileName) {
        Matcher m = INSTALLER_FILE.matcher(fileName);
        return m.matches() ? Version.parse(m.group(1)) : Optional.empty();
    }

    public void checkAsync(Consumer<ReadyUpdate> onReady) {
        if (!isEnabled() || !started.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                runCheck(onReady, newClient());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                System.err.println("[update] check failed: " + e);
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    void runCheck(Consumer<ReadyUpdate> onReady, HttpClient client) throws IOException, InterruptedException {
        Version running = current.orElseThrow();
        Path dir = updatesDir();
        Files.createDirectories(dir);

        ReadyUpdate pending = cleanAndFindPending(dir, running);
        if (pending != null) onReady.accept(pending);

        if (!shouldCheck(readLastCheck(), System.currentTimeMillis())) return;
        UpdateChecker.Lookup lookup;
        try {
            lookup = UpdateChecker.fetchLatest(client, api);
        } catch (IOException e) {
            return;
        }
        Optional<ReleaseInfo> found = lookup.release();
        boolean nothingToDownload = found.isEmpty()
                || !found.get().version().isNewerThan(running)
                || (pending != null && !found.get().version().isNewerThan(pending.version()));
        if (nothingToDownload) {
            if (lookup.answered()) writeLastCheck();
            return;
        }

        ReleaseInfo rel = found.get();
        Path installer = downloadAndVerify(client, rel, dir);
        writeLastCheck();
        onReady.accept(new ReadyUpdate(rel.version(), installer));
    }

    static Path downloadAndVerify(HttpClient client, ReleaseInfo rel, Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path target = dir.resolve(rel.installerName());
        Path sumsFile = dir.resolve(rel.checksumName());
        Path part = dir.resolve(rel.installerName() + ".part");
        try {
            HttpResponse<String> sumsResp = client.send(
                    HttpRequest.newBuilder(URI.create(rel.checksumUrl()))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (sumsResp.statusCode() != 200) throw new IOException("HTTP " + sumsResp.statusCode() + " fetching checksum");
            String sums = sumsResp.body();
            if (Checksums.parse(sums, rel.installerName()).isEmpty()) throw new IOException("checksum not listed");

            HttpResponse<Path> resp = client.send(
                    HttpRequest.newBuilder(URI.create(rel.installerUrl()))
                            .timeout(Duration.ofMinutes(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(part));
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " downloading installer");
            if (!Checksums.matches(part, sums, rel.installerName())) throw new IOException(YtDlpManager.VERIFY_FAILED);

            Files.writeString(sumsFile, sums, StandardCharsets.UTF_8);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(sumsFile);
            throw e;
        } finally {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
        }
    }

    static ReadyUpdate cleanAndFindPending(Path dir, Version running) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(files::add);
        } catch (IOException e) {
            return null;
        }
        ReadyUpdate best = null;
        for (Path p : files) {
            String name = p.getFileName().toString();
            if (name.endsWith(".part")) { deleteQuietly(p); continue; }
            Optional<Version> v = installerVersion(name);
            if (v.isEmpty()) continue;
            if (!v.get().isNewerThan(running) || !verifyOnDisk(p)) {
                deleteQuietly(p);
                deleteQuietly(dir.resolve(name + ".sha256"));
                continue;
            }
            if (best == null || v.get().isNewerThan(best.version())) best = new ReadyUpdate(v.get(), p);
        }
        return best;
    }

    static boolean verifyOnDisk(Path installer) {
        try {
            String name = installer.getFileName().toString();
            String sums = Files.readString(installer.resolveSibling(name + ".sha256"), StandardCharsets.UTF_8);
            return Checksums.matches(installer, sums, name);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean verify(ReadyUpdate update) {
        if (verifyOnDisk(update.installer())) return true;
        deleteQuietly(update.installer());
        deleteQuietly(update.installer().resolveSibling(update.installer().getFileName() + ".sha256"));
        return false;
    }

    public void installOnClose(ReadyUpdate update) {
        installOnClose = update;
        if (hookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::launchPendingInstaller, "update-install-on-exit"));
        }
    }

    public boolean launchPendingInstaller() {
        ReadyUpdate u = installOnClose;
        if (u == null || !launched.compareAndSet(false, true)) return false;
        if (!verifyOnDisk(u.installer())) {
            System.err.println("[update] installer failed verification, not starting it: " + u.installer());
            launched.set(false);
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(u.installer().toString());
        cmd.addAll(INSTALLER_ARGS);
        try {
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException e) {
            System.err.println("[update] could not start installer: " + e);
            launched.set(false);
            return false;
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
            Files.createDirectories(lastCheckPath().getParent());
            Files.writeString(lastCheckPath(), Long.toString(System.currentTimeMillis()));
        } catch (IOException e) {
            System.err.println("[update] could not record update check: " + e);
        }
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
