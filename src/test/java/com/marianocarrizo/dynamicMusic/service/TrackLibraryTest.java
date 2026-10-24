package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackLibraryTest {

    @TempDir
    Path root;

    private Path cache;
    private Path library;

    @BeforeEach
    void setUp() throws IOException {
        cache = Files.createDirectories(root.resolve("yt-cache"));
        library = root.resolve("library"); // intentionally not created: pin must create it
    }

    @Test
    void cacheFileIsCopiedIntoTheLibrary() throws IOException {
        Path src = cache.resolve("abc.mp3");
        Files.writeString(src, "audio-bytes");

        String pinned = TrackLibrary.pin(src.toString(), cache, library);

        assertEquals(library.resolve("abc.mp3"), Path.of(pinned));
        assertEquals("audio-bytes", Files.readString(Path.of(pinned)));
        assertTrue(Files.exists(src), "the cache file is kept (copy, not move)");
        assertFalse(Files.exists(library.resolve("abc.mp3.tmp")), "no temp leftovers");
    }

    @Test
    void filesOutsideTheCacheAreReturnedUnchanged() throws IOException {
        Path other = root.resolve("music").resolve("song.mp3");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "x");

        assertEquals(other.toString(), TrackLibrary.pin(other.toString(), cache, library));
        assertFalse(Files.exists(library));
    }

    @Test
    void siblingDirectoryWithSharedPrefixIsNotTheCache() throws IOException {
        Path lookalike = Files.createDirectories(root.resolve("yt-cache-other")).resolve("a.mp3");
        Files.writeString(lookalike, "x");

        assertEquals(lookalike.toString(), TrackLibrary.pin(lookalike.toString(), cache, library));
    }

    @Test
    void pathTraversalOutOfTheCacheIsNotPinned() throws IOException {
        Path outside = root.resolve("secret.mp3");
        Files.writeString(outside, "x");
        String sneaky = cache.resolve("..").resolve("secret.mp3").toString();

        assertEquals(sneaky, TrackLibrary.pin(sneaky, cache, library));
        assertFalse(Files.exists(library.resolve("secret.mp3")));
    }

    @Test
    void sameSizeTargetIsNotCopiedAgain() throws IOException {
        Path src = cache.resolve("abc.mp3");
        Files.writeString(src, "12345");
        Files.createDirectories(library);
        Path target = library.resolve("abc.mp3");
        Files.writeString(target, "ABCDE"); // same size, different content
        FileTime stamp = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(target, stamp);

        String pinned = TrackLibrary.pin(src.toString(), cache, library);

        assertEquals(target.toString(), pinned);
        assertEquals("ABCDE", Files.readString(target), "target untouched");
        assertEquals(stamp, Files.getLastModifiedTime(target));
    }

    @Test
    void differentSizeTargetIsReplaced() throws IOException {
        Path src = cache.resolve("abc.mp3");
        Files.writeString(src, "new-longer-content");
        Files.createDirectories(library);
        Files.writeString(library.resolve("abc.mp3"), "old");

        TrackLibrary.pin(src.toString(), cache, library);

        assertEquals("new-longer-content", Files.readString(library.resolve("abc.mp3")));
    }

    @Test
    void missingCacheFileReturnsTheOriginalPath() {
        String missing = cache.resolve("gone.mp3").toString();
        assertEquals(missing, TrackLibrary.pin(missing, cache, library));
        assertFalse(Files.exists(library.resolve("gone.mp3")));
    }

    @Test
    void directoryInsideTheCacheIsNotPinned() throws IOException {
        Path dir = Files.createDirectories(cache.resolve("folder"));
        assertEquals(dir.toString(), TrackLibrary.pin(dir.toString(), cache, library));
    }

    @Test
    void copyFailureReturnsTheOriginalPath() throws IOException {
        Path src = cache.resolve("abc.mp3");
        Files.writeString(src, "x");
        Path blockedLibrary = root.resolve("library-is-a-file");
        Files.writeString(blockedLibrary, "not a directory");

        assertEquals(src.toString(), TrackLibrary.pin(src.toString(), cache, blockedLibrary));
    }

    @Test
    void nullBlankAndInvalidInputNeverThrow() {
        assertNull(TrackLibrary.pin(null, cache, library));
        assertEquals("", TrackLibrary.pin("", cache, library));
        assertEquals("  ", TrackLibrary.pin("  ", cache, library));
        String invalid = "bad\0path";
        assertEquals(invalid, TrackLibrary.pin(invalid, cache, library));
    }
}
