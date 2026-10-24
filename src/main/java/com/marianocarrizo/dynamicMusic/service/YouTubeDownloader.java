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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wraps yt-dlp to download a YouTube video's audio as an MP3 and cache it locally.
 * No JavaFX imports — callbacks fire on the ForkJoin pool thread;
 * callers must marshal to Platform.runLater.
 */
public final class YouTubeDownloader {

    private static final int  MAX_CACHE_ENTRIES        = 20;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 120;
    private static final long TITLE_TIMEOUT_SECONDS    = 15;
    private static final int  STDERR_TAIL_BYTES        = 2048;
    private static final long SEARCH_TIMEOUT_SECONDS   = 30;

    // --- Search result DTO ---

    public record SearchResult(String id, String title, String channel, long durationSeconds) {

        public String url() {
            return "https://www.youtube.com/watch?v=" + id;
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

    // --- URL validation ---

    private static final Pattern PATTERN_SHORT =
            Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");
    private static final Pattern PATTERN_WATCH =
            Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern PATTERN_PATH =
            Pattern.compile("youtube\\.com/(?:shorts|embed)/([A-Za-z0-9_-]{11})");

    /**
     * Extracts the 11-character video ID from a YouTube URL.
     * Returns {@link Optional#empty()} for non-YouTube or unrecognised inputs.
     * This is the URL validation gate — an empty result means the URL should be rejected.
     */
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

    // --- yt-dlp availability ---

    private static volatile boolean availableCached;
    private static volatile long    unavailableUntilMs;

    /**
     * True when the managed yt-dlp exists or yt-dlp on PATH runs. A positive result is cached for the
     * session; a negative one for 30 s, so repeated clicks on the FX thread never re-spawn a probe.
     */
    public static boolean isYtDlpAvailable() {
        if (availableCached || YtDlpManager.get().hasManagedBinary()) return true;
        if (System.currentTimeMillis() < unavailableUntilMs) return false;
        boolean ok = false;
        try {
            YtDlpManager.ProcResult r = YtDlpManager.run(List.of("yt-dlp", "--version"), 3);
            ok = !r.timedOut() && r.exitCode() == 0;
        } catch (IOException e) {
            ok = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ok) availableCached = true;
        else unavailableUntilMs = System.currentTimeMillis() + 30_000;
        return ok;
    }

    // --- Command execution with update-and-retry ---

    private static boolean isRetryable(String output) {
        return output.contains("HTTP Error 403")
                || output.contains("older than 90 days")
                || output.contains("Sign in to confirm")
                || output.contains("nsig");
    }

    /**
     * Runs yt-dlp with {@code args} (executable and JS runtime args are prepended). If it fails with a
     * known "outdated yt-dlp" signature, updates yt-dlp once and retries once with the new executable.
     * Each attempt is bounded by a watchdog.
     */
    private static YtDlpManager.ProcResult runYtDlp(List<String> args, long timeoutSec)
            throws IOException, InterruptedException {
        YtDlpManager mgr = YtDlpManager.get();
        YtDlpManager.ProcResult r = YtDlpManager.run(buildCmd(mgr, args), timeoutSec);
        if (r.timedOut() || r.exitCode() == 0 || !isRetryable(r.combined())) return r;
        mgr.forceUpdate();
        return YtDlpManager.run(buildCmd(mgr, args), timeoutSec);
    }

    private static List<String> buildCmd(YtDlpManager mgr, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(mgr.executable());
        cmd.addAll(mgr.jsRuntimeArgs());
        cmd.addAll(args);
        return cmd;
    }

    // --- Async search ---

    public void searchAsync(String query, int maxResults,
                            Consumer<List<SearchResult>> onResults,
                            Consumer<String> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!isYtDlpAvailable()) {
                    Platform.runLater(() -> onError.accept("yt-dlp not found."));
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
                        // one bad line never aborts the batch
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
        }).exceptionally(ex -> {
            Platform.runLater(() -> onError.accept(ex.getMessage()));
            return null;
        });
    }

    // --- Cache directory ---

    /** App data root: %APPDATA%/DynamicMusic (falls back to user.home/DynamicMusic). */
    static Path appDir() {
        String appdata = System.getenv("APPDATA");
        return (appdata != null && !appdata.isBlank())
                ? Paths.get(appdata, "DynamicMusic")
                : Paths.get(System.getProperty("user.home"), "DynamicMusic");
    }

    /** Resolves the yt-cache directory path without creating it. */
    static Path cacheDirPath() {
        return appDir().resolve("yt-cache");
    }

    private Path cacheDir() throws IOException {
        Path dir = cacheDirPath();
        Files.createDirectories(dir);
        return dir;
    }

    // --- Async download ---

    /**
     * Downloads the audio for {@code url} asynchronously, caching the result as
     * {@code {videoId}.mp3} under the yt-cache directory.
     *
     * <p>If a cached file already exists, {@code onSuccess} is called immediately
     * without launching yt-dlp.
     *
     * <p>Callbacks are invoked on the ForkJoin pool thread — callers must use
     * {@code Platform.runLater} to update the UI.
     *
     * @param url       the YouTube URL (already validated by {@link #extractVideoId})
     * @param videoId   the 11-char video ID extracted from {@code url}
     * @param onSuccess receives a {@link PlaylistItem} with path, url, and title on success
     * @param onFailure receives a human-readable error message on failure
     */
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

        // Cache hit: no subprocess needed
        if (Files.exists(out)) {
            onSuccess.accept(new PlaylistItem(out.toString(), url, null));
            return;
        }

        final Path outFinal = out;
        CompletableFuture.runAsync(() -> {
            try {
                YtDlpManager.ProcResult r = runYtDlp(List.of(
                        "-x",
                        "--audio-format", "mp3",
                        "--no-playlist",
                        "-o", outFinal.toString(),
                        "--", // end of options: a pasted string can never be parsed as a yt-dlp flag
                        url
                ), DOWNLOAD_TIMEOUT_SECONDS);

                if (r.timedOut()) {
                    deletePartial(outFinal);
                    onFailure.accept("Download timed out after " + DOWNLOAD_TIMEOUT_SECONDS + "s");
                    return;
                }

                if (r.exitCode() != 0) {
                    deletePartial(outFinal);
                    String t = tail(r.combined(), STDERR_TAIL_BYTES);
                    onFailure.accept(t.isBlank() ? "yt-dlp exited with code " + r.exitCode() : t);
                    return;
                }

                if (!Files.exists(outFinal)) {
                    onFailure.accept("Output file not found after download");
                    return;
                }

                // Fetch the video title as a non-fatal secondary step
                String title = fetchTitle(url);

                evictOldEntries();
                onSuccess.accept(new PlaylistItem(outFinal.toString(), url, title));

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                deletePartial(outFinal);
                onFailure.accept(e.getMessage());
            }
        }).exceptionally(ex -> {
            deletePartial(outFinal);
            onFailure.accept(ex.getMessage());
            return null;
        });
    }

    /** Removes a possibly truncated output (and yt-dlp's .part leftover) so it is never read as a cache hit. */
    private static void deletePartial(Path out) {
        try { Files.deleteIfExists(out); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Paths.get(out + ".part")); } catch (IOException ignored) {}
    }

    // --- Title fetch ---

    /** Runs {@code yt-dlp --get-title} and returns the trimmed output, or null on any failure. */
    private String fetchTitle(String url) {
        try {
            YtDlpManager.ProcResult r = runYtDlp(
                    List.of("--get-title", "--no-playlist", "--", url), TITLE_TIMEOUT_SECONDS);
            if (r.timedOut() || r.exitCode() != 0) return null;
            String title = r.stdout().trim();
            return title.isBlank() ? null : title;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    // --- Cache eviction ---

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

    // --- Internal utility ---

    /** Last {@code maxChars} characters of {@code s}, trimmed. */
    private static String tail(String s, int maxChars) {
        String t = s.strip();
        return t.length() <= maxChars ? t : t.substring(t.length() - maxChars);
    }
}
