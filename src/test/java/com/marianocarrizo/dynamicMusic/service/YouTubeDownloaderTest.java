package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubeDownloaderTest {

    private static final String ID = "dQw4w9WgXcQ";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"
    })
    void extractsIdFromSupportedUrlShapes(String url) {
        assertEquals(Optional.of(ID), YouTubeDownloader.extractVideoId(url));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "hello world",
            "https://example.com/",
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?x=dQw4w9WgXcQ",
            "https://www.youtube.com/channel/UCabcdefghijk",
            "--exec rm -rf /",
            "ytsearch5:something"
    })
    void rejectsGarbage(String input) {
        assertTrue(YouTubeDownloader.extractVideoId(input).isEmpty(), String.valueOf(input));
    }

    @Test
    void downloadArgsPutTheUrlAfterTheEndOfOptionsMarker() {
        String evil = "--exec=calc.exe";
        List<String> args = YouTubeDownloader.downloadArgs(Path.of("out", ID + ".mp3"), evil);

        int marker = args.indexOf("--");
        assertTrue(marker >= 0, "missing -- marker");
        assertEquals(args.size() - 2, marker, "marker is right before the URL");
        assertEquals(evil, args.get(args.size() - 1));
        assertEquals(1, args.stream().filter(evil::equals).count());
        assertTrue(args.indexOf(evil) > marker);
        assertTrue(args.contains("-x"));
        assertTrue(args.contains("--no-playlist"));
    }

    @Test
    void downloadArgsPointYtDlpAtTheBundledFfmpegBeforeTheMarker() {
        Path ffmpegDir = Path.of("install", "ffmpeg");
        List<String> args = YouTubeDownloader.downloadArgs(Path.of("out", ID + ".mp3"), "https://youtu.be/" + ID,
                java.util.Optional.of(ffmpegDir));

        int flag = args.indexOf("--ffmpeg-location");
        assertTrue(flag >= 0 && flag < args.indexOf("--"));
        assertEquals(ffmpegDir.toString(), args.get(flag + 1));
        assertFalse(YouTubeDownloader.downloadArgs(Path.of("out", ID + ".mp3"), "u").contains("--ffmpeg-location"));
    }

    @Test
    void titleArgsPutTheUrlAfterTheEndOfOptionsMarker() {
        String evil = "-o /etc/passwd";
        List<String> args = YouTubeDownloader.titleArgs(evil);

        assertEquals(List.of("--get-title", "--no-playlist", "--", evil), args);
    }

    @Test
    void searchResultFormatsUrlAndDuration() {
        var r = new YouTubeDownloader.SearchResult(ID, "t", "c", 3725);
        assertEquals("https://www.youtube.com/watch?v=" + ID, r.url());
        assertEquals("1:02:05", r.durationLabel());
        assertEquals("3:05", new YouTubeDownloader.SearchResult(ID, "t", "c", 185).durationLabel());
        assertEquals("", new YouTubeDownloader.SearchResult(ID, "t", "c", 0).durationLabel());
    }
}
