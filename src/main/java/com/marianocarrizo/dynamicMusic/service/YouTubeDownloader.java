package com.marianocarrizo.dynamicMusic.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.marianocarrizo.dynamicMusic.model.PlaylistItem;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    /**
     * Probes whether yt-dlp is available on PATH.
     * Single-shot check with a 3-second timeout — mirrors {@code BoomerangBaker.isFfmpegAvailable()}.
     */
    public static boolean isYtDlpAvailable() {
        try {
            Process p = new ProcessBuilder("yt-dlp", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- Async search ---

    public void searchAsync(String query, int maxResults,
                            Consumer<List<SearchResult>> onResults,
                            Consumer<String> onError) {
        if (!isYtDlpAvailable()) {
            Platform.runLater(() -> onError.accept("yt-dlp not found on PATH."));
            return;
        }

        CompletableFuture.runAsync(() -> {
            Process proc = null;
            try {
                List<String> cmd = List.of(
                        "yt-dlp",
                        "--flat-playlist",
                        "--dump-json",
                        "ytsearch" + maxResults + ":" + query
                );
                proc = new ProcessBuilder(cmd)
                        .redirectErrorStream(false)
                        .start();

                // Drain stderr to avoid blocking
                final Process procFinal = proc;
                Thread stderrDrainer = new Thread(
                        () -> {
                            try { procFinal.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                            catch (IOException ignored) {}
                        },
                        "yt-dlp-stderr-drainer"
                );
                stderrDrainer.setDaemon(true);
                stderrDrainer.start();

                List<SearchResult> results = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
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
                }

                boolean finished = proc.waitFor(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    Platform.runLater(() -> onError.accept("Search timed out after " + SEARCH_TIMEOUT_SECONDS + "s"));
                    return;
                }

                final List<SearchResult> resultsFinal = results;
                Platform.runLater(() -> onResults.accept(resultsFinal));

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                final String msg = e.getMessage();
                Platform.runLater(() -> onError.accept(msg));
            } finally {
                if (proc != null && proc.isAlive()) proc.destroyForcibly();
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
            Process proc = null;
            try {
                List<String> cmd = List.of(
                        "yt-dlp",
                        "-x",
                        "--audio-format", "mp3",
                        "--no-playlist",
                        "-o", outFinal.toString(),
                        "--", // end of options: a pasted string can never be parsed as a yt-dlp flag
                        url
                );
                proc = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();

                String stderrTail = drainAndTail(proc.getInputStream(), STDERR_TAIL_BYTES);

                boolean finished = proc.waitFor(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    onFailure.accept("Download timed out after " + DOWNLOAD_TIMEOUT_SECONDS + "s");
                    return;
                }

                if (proc.exitValue() != 0) {
                    onFailure.accept(stderrTail.isBlank()
                            ? "yt-dlp exited with code " + proc.exitValue()
                            : stderrTail);
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
                onFailure.accept(e.getMessage());
            } finally {
                if (proc != null && proc.isAlive()) proc.destroyForcibly();
            }
        }).exceptionally(ex -> {
            onFailure.accept(ex.getMessage());
            return null;
        });
    }

    // --- Title fetch ---

    /** Runs {@code yt-dlp --get-title} and returns the trimmed output, or null on any failure. */
    private String fetchTitle(String url) {
        try {
            Process p = new ProcessBuilder("yt-dlp", "--get-title", "--no-playlist", "--", url)
                    .redirectErrorStream(false)
                    .start();
            // Drain stderr to avoid blocking
            p.getErrorStream().transferTo(OutputStream.nullOutputStream());
            byte[] stdout = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(TITLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            String title = new String(stdout, StandardCharsets.UTF_8).trim();
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

    private static String drainAndTail(InputStream in, int maxBytes) {
        try {
            byte[] all = in.readAllBytes();
            if (all.length <= maxBytes) return new String(all, StandardCharsets.UTF_8);
            return new String(all, all.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
