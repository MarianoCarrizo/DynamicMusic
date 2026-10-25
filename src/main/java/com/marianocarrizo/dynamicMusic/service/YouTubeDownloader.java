package com.marianocarrizo.dynamicMusic.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.marianocarrizo.dynamicMusic.model.PlaylistItem;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class YouTubeDownloader {

    private static final int  MAX_CACHE_ENTRIES        = 20;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 120;
    private static final long TITLE_TIMEOUT_SECONDS    = 15;
    private static final int  STDERR_TAIL_BYTES        = 2048;
    private static final long SEARCH_TIMEOUT_SECONDS   = 30;

    public record SearchResult(String id, String title, String channel, long durationSeconds) {

        public String url() {
            return canonicalUrl(id);
        }

        public String durationLabel() {
            if (durationSeconds <= 0) return "";
            long h = durationSeconds / 3600;
            long m = (durationSeconds % 3600) / 60;
            long s = durationSeconds % 60;
            if (h > 0) {
                return String.format("%d:%02d:%02d", h, m, s);
            }
            return String.format("%d:%02d", m, s);
        }
    }

    private static final Pattern PATTERN_SHORT =
            Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");
    private static final Pattern PATTERN_WATCH =
            Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern PATTERN_PATH =
            Pattern.compile("youtube\\.com/(?:shorts|embed)/([A-Za-z0-9_-]{11})");

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    static boolean isVideoId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    static String canonicalUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public static Optional<String> extractVideoId(String url) {
        if (url == null) return Optional.empty();
        Matcher m1 = PATTERN_SHORT.matcher(url);
        if (m1.find()) return Optional.of(m1.group(1));
        Matcher m2 = PATTERN_WATCH.matcher(url);
        if (m2.find()) return Optional.of(m2.group(1));
        Matcher m3 = PATTERN_PATH.matcher(url);
        if (m3.find()) return Optional.of(m3.group(1));
        return Optional.empty();
    }

    private static volatile boolean availableCached;
    private static volatile long    unavailableUntilMs;

    private static final long SETUP_WAIT_SECONDS = 180;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "youtube-task");
        t.setDaemon(true);
        return t;
    });

    public static boolean isYtDlpAvailable() {
        YtDlpManager mgr = YtDlpManager.get();
        if (availableCached || mgr.hasManagedBinary()) return true;
        if (System.currentTimeMillis() < unavailableUntilMs) return false;
        boolean ok = probePath();
        if (ok) availableCached = true;
        else if (!mgr.isSetupRunning()) unavailableUntilMs = System.currentTimeMillis() + 30_000;
        return ok;
    }

    private static boolean probePath() {
        Optional<Path> exe = ExecutableResolver.find("yt-dlp");
        if (exe.isEmpty()) return false;
        try {
            YtDlpManager.ProcResult r = YtDlpManager.run(List.of(exe.get().toString(), "--version"), 3);
            return !r.timedOut() && r.exitCode() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean awaitYtDlp() {
        if (isYtDlpAvailable()) return true;
        YtDlpManager mgr = YtDlpManager.get();
        mgr.ensureReadyAsync();
        if (mgr.isSetupRunning()) {
            mgr.announce("Downloading yt-dlp...");
            try {
                mgr.readiness().get(SETUP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException e) {
            }
        }
        if (mgr.hasManagedBinary()) return true;
        boolean ok = probePath();
        if (ok) availableCached = true;
        return ok;
    }

    private static final String NOT_FOUND_MESSAGE =
            "yt-dlp is not available. Check your internet connection and try again.";

    private static boolean isRetryable(String output) {
        return output.contains("HTTP Error 403")
                || output.contains("older than 90 days")
                || output.contains("Sign in to confirm")
                || output.contains("nsig");
    }

    private static YtDlpManager.ProcResult runYtDlp(List<String> args, long timeoutSec)
            throws IOException, InterruptedException {
        YtDlpManager mgr = YtDlpManager.get();
        YtDlpManager.ProcResult r = YtDlpManager.run(buildCmd(mgr, args), timeoutSec);
        if (r.timedOut() || r.exitCode() == 0 || !isRetryable(r.combined())) return r;
        mgr.forceUpdate();
        return YtDlpManager.run(buildCmd(mgr, args), timeoutSec);
    }

    private static List<String> buildCmd(YtDlpManager mgr, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mgr.executable().orElseThrow(() -> new IOException(NOT_FOUND_MESSAGE)));
        cmd.addAll(mgr.jsRuntimeArgs());
        cmd.addAll(args);
        return cmd;
    }

    public void searchAsync(String query, int maxResults,
                            Consumer<List<SearchResult>> onResults,
                            Consumer<String> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!awaitYtDlp()) {
                    Platform.runLater(() -> onError.accept(NOT_FOUND_MESSAGE));
                    return;
                }
                YtDlpManager.ProcResult r = runYtDlp(List.of(
                        "--flat-playlist",
                        "--dump-json",
                        "ytsearch" + maxResults + ":" + query
                ), SEARCH_TIMEOUT_SECONDS);

                if (r.timedOut()) {
                    Platform.runLater(() -> onError.accept("Search timed out after " + SEARCH_TIMEOUT_SECONDS + "s"));
                    return;
                }

                List<SearchResult> results = new ArrayList<>();
                for (String line : r.stdout().split("\\R")) {
                    if (line.isBlank()) continue;
                    try {
                        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

                        if (!obj.has("id") || obj.get("id").isJsonNull()) continue;
                        if (!obj.has("title") || obj.get("title").isJsonNull()) continue;

                        String id    = obj.get("id").getAsString();
                        String title = obj.get("title").getAsString();

                        String channel = "";
                        if (obj.has("channel") && !obj.get("channel").isJsonNull()) {
                            channel = obj.get("channel").getAsString();
                        } else if (obj.has("uploader") && !obj.get("uploader").isJsonNull()) {
                            channel = obj.get("uploader").getAsString();
                        }

                        long duration = 0;
                        if (obj.has("duration") && !obj.get("duration").isJsonNull()) {
                            duration = (long) obj.get("duration").getAsDouble();
                        }

                        results.add(new SearchResult(id, title, channel, duration));
                    } catch (Exception ignored) {
                    }
                }

                if (results.isEmpty() && r.exitCode() != 0) {
                    String t = tail(r.combined(), STDERR_TAIL_BYTES);
                    Platform.runLater(() -> onError.accept(t.isBlank()
                            ? "yt-dlp exited with code " + r.exitCode() : t));
                    return;
                }

                Platform.runLater(() -> onResults.accept(results));

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                final String msg = e.getMessage();
                Platform.runLater(() -> onError.accept(msg));
            }
        }, EXECUTOR).exceptionally(ex -> {
            Platform.runLater(() -> onError.accept(ex.getMessage()));
            return null;
        });
    }

    static Path appDir() {
        return appDir(System.getProperty("os.name", ""), System.getenv("APPDATA"),
                System.getProperty("user.home"));
    }

    static Path appDir(String osName, String appdata, String home) {
        return (YtDlpManager.isWindows(osName) && appdata != null && !appdata.isBlank())
                ? Paths.get(appdata, "DynamicMusic")
                : Paths.get(home, "DynamicMusic");
    }

    static Path cacheDirPath() {
        return appDir().resolve("yt-cache");
    }

    private Path cacheDir() throws IOException {
        Path dir = cacheDirPath();
        Files.createDirectories(dir);
        return dir;
    }

    public void downloadAsync(String url, String videoId,
                              Consumer<PlaylistItem> onSuccess,
                              Consumer<String> onFailure) {
        Path out;
        try {
            out = cacheDir().resolve(videoId + ".mp3");
        } catch (IOException e) {
            onFailure.accept("Cannot resolve cache directory: " + e.getMessage());
            return;
        }

        CompletableFuture<PlaylistItem> inFlight = IN_FLIGHT.get(videoId);
        if (inFlight != null) {
            subscribe(inFlight, onSuccess, onFailure);
            return;
        }

        if (Files.exists(out)) {
            onSuccess.accept(new PlaylistItem(out.toString(), url, null));
            return;
        }

        CompletableFuture<PlaylistItem> mine = new CompletableFuture<>();
        CompletableFuture<PlaylistItem> running = IN_FLIGHT.putIfAbsent(videoId, mine);
        if (running != null) {
            subscribe(running, onSuccess, onFailure);
            return;
        }
        mine.whenComplete((r, ex) -> IN_FLIGHT.remove(videoId, mine));
        subscribe(mine, onSuccess, onFailure);

        final Path outFinal = out;
        EXECUTOR.execute(() -> {
            try {
                mine.complete(runDownload(url, videoId, outFinal));
            } catch (Throwable t) {
                mine.completeExceptionally(t);
            }
        });
    }

    private static final Map<String, CompletableFuture<PlaylistItem>> IN_FLIGHT = new ConcurrentHashMap<>();

    private static void subscribe(CompletableFuture<PlaylistItem> f,
                                  Consumer<PlaylistItem> onSuccess, Consumer<String> onFailure) {
        f.whenComplete((item, ex) -> {
            if (ex == null) onSuccess.accept(item);
            else onFailure.accept(ex.getMessage() != null ? ex.getMessage() : ex.toString());
        });
    }

    private PlaylistItem runDownload(String url, String videoId, Path out) throws IOException {
        Path dir = out.getParent();
        if (!isVideoId(videoId)) throw new IOException("Invalid YouTube video id");
        if (!awaitYtDlp()) throw new IOException(NOT_FOUND_MESSAGE);

        String target = canonicalUrl(videoId);
        YtDlpManager.ProcResult r;
        try {
            r = runYtDlp(downloadArgs(out, target, FfmpegLocator.directory()), DOWNLOAD_TIMEOUT_SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            cleanupLeftovers(dir, videoId, false);
            throw new IOException(e.getMessage(), e);
        }

        if (r.timedOut()) {
            cleanupLeftovers(dir, videoId, false);
            throw new IOException("Download timed out after " + DOWNLOAD_TIMEOUT_SECONDS + "s");
        }
        if (r.exitCode() != 0) {
            cleanupLeftovers(dir, videoId, false);
            String t = tail(r.combined(), STDERR_TAIL_BYTES);
            throw new IOException(t.isBlank() ? "yt-dlp exited with code " + r.exitCode() : t);
        }
        if (!Files.exists(out)) throw new IOException("Output file not found after download");

        String title = fetchTitle(target);
        evictOldEntries();
        return new PlaylistItem(out.toString(), url, title);
    }

    static int cleanupLeftovers(Path dir, String videoId, boolean keepFinal) {
        int deleted = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.collect(Collectors.toList())) {
                if (isLeftover(p.getFileName().toString(), videoId, keepFinal)) {
                    try { if (Files.deleteIfExists(p)) deleted++; } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return deleted;
    }

    static boolean isLeftover(String fileName, String videoId, boolean keepFinal) {
        if (!fileName.startsWith(videoId + ".")) return false;
        return !(keepFinal && fileName.equals(videoId + ".mp3"));
    }

    static List<String> downloadArgs(Path out, String url) {
        return downloadArgs(out, url, Optional.empty());
    }

    static List<String> downloadArgs(Path out, String url, Optional<Path> ffmpegDir) {
        List<String> args = new ArrayList<>(List.of(
                "-x",
                "--audio-format", "mp3",
                "--no-playlist",
                "-o", out.toString()));
        ffmpegDir.ifPresent(dir -> {
            args.add("--ffmpeg-location");
            args.add(dir.toString());
        });
        args.add("--");
        args.add(url);
        return List.copyOf(args);
    }

    static List<String> titleArgs(String url) {
        return List.of("--get-title", "--no-playlist", "--", url);
    }

    private String fetchTitle(String url) {
        try {
            YtDlpManager.ProcResult r = runYtDlp(titleArgs(url), TITLE_TIMEOUT_SECONDS);
            if (r.timedOut() || r.exitCode() != 0) return null;
            String title = r.stdout().trim();
            return title.isBlank() ? null : title;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private void evictOldEntries() {
        try {
            Path dir = cacheDir();
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream
                        .filter(p -> p.toString().endsWith(".mp3"))
                        .collect(Collectors.toList());
            }
            if (files.size() <= MAX_CACHE_ENTRIES) return;

            files.sort(Comparator.comparing(p -> {
                try {
                    return Files.getLastModifiedTime((Path) p);
                } catch (IOException e) {
                    return FileTime.fromMillis(0);
                }
            }));

            int toDelete = files.size() - MAX_CACHE_ENTRIES;
            for (int i = 0; i < toDelete; i++) {
                try { Files.deleteIfExists(files.get(i)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private static String tail(String s, int maxChars) {
        String t = s.strip();
        return t.length() <= maxChars ? t : t.substring(t.length() - maxChars);
    }
}
