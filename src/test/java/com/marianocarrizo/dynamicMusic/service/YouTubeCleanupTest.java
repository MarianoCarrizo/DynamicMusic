package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YouTubeCleanupTest {

    private static final String ID    = "dQw4w9WgXcQ";
    private static final String OTHER = "abcdefghijk";

    private static void touch(Path dir, String name) throws IOException {
        Files.writeString(dir.resolve(name), "x");
    }

    @Test
    void matchesAllIntermediatesOfTheId() {
        for (String n : new String[]{
                ID + ".mp3", ID + ".mp3.part", ID + ".mp3.ytdl", ID + ".f251.webm", ID + ".f140.m4a",
                ID + ".temp.mp3", ID + ".webm.part", ID + ".webm"}) {
            assertTrue(YouTubeDownloader.isLeftover(n, ID, false), n);
        }
    }

    @Test
    void doesNotMatchOtherIdsOrPrefixCollisions() {
        assertFalse(YouTubeDownloader.isLeftover(OTHER + ".mp3", ID, false));
        assertFalse(YouTubeDownloader.isLeftover(ID + "X.mp3", ID, false)); // needs the '.' boundary
        assertFalse(YouTubeDownloader.isLeftover("notes.txt", ID, false));
    }

    @Test
    void keepFinalProtectsOnlyTheFinishedMp3() {
        assertFalse(YouTubeDownloader.isLeftover(ID + ".mp3", ID, true));
        assertTrue(YouTubeDownloader.isLeftover(ID + ".mp3.part", ID, true));
    }

    @Test
    void cleanupDeletesOnlyThisIdsFiles(@TempDir Path dir) throws IOException {
        touch(dir, ID + ".mp3");
        touch(dir, ID + ".mp3.part");
        touch(dir, ID + ".f251.webm");
        touch(dir, ID + ".temp.mp3");
        touch(dir, OTHER + ".mp3");
        touch(dir, OTHER + ".mp3.part");

        assertEquals(4, YouTubeDownloader.cleanupLeftovers(dir, ID, false));
        assertFalse(Files.exists(dir.resolve(ID + ".mp3")));
        assertFalse(Files.exists(dir.resolve(ID + ".mp3.part")));
        assertTrue(Files.exists(dir.resolve(OTHER + ".mp3")));
        assertTrue(Files.exists(dir.resolve(OTHER + ".mp3.part")));
    }

    @Test
    void cleanupWithKeepFinalPreservesCompletedMp3(@TempDir Path dir) throws IOException {
        touch(dir, ID + ".mp3");
        touch(dir, ID + ".mp3.part");

        assertEquals(1, YouTubeDownloader.cleanupLeftovers(dir, ID, true));
        assertTrue(Files.exists(dir.resolve(ID + ".mp3")));
    }

    @Test
    void cleanupOnMissingDirIsHarmless(@TempDir Path dir) {
        assertEquals(0, YouTubeDownloader.cleanupLeftovers(dir.resolve("nope"), ID, false));
    }
}
