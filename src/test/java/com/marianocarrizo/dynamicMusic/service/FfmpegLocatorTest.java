package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegLocatorTest {

    private static final Path ROOT = Path.of("app-root").toAbsolutePath();

    @Test
    void installRootFromLauncherPath() {
        Path exe = ROOT.resolve("DynamicMusic.exe");
        assertEquals(ROOT, FfmpegLocator.installRoot(exe.toString(), "ignored"));
    }

    @Test
    void installRootFromBundledRuntimeWhenNoLauncherProperty() {
        assertEquals(ROOT, FfmpegLocator.installRoot(null, ROOT.resolve("runtime").toString()));
        assertEquals(ROOT, FfmpegLocator.installRoot(" ", ROOT.resolve("runtime").toString()));
    }

    @Test
    void installRootIsNullForPlainJdk() {
        assertNull(FfmpegLocator.installRoot(null, Path.of("jdk-21").toAbsolutePath().toString()));
        assertNull(FfmpegLocator.installRoot(null, null));
    }

    @Test
    void findsBundledFfmpegOnlyWhenTheBinaryExists() {
        Path dir = ROOT.resolve("ffmpeg");
        Set<Path> files = Set.of(dir.resolve("ffmpeg.exe"));
        assertEquals(Optional.of(dir), FfmpegLocator.findBundled(ROOT, "Windows 11", files::contains));
        assertTrue(FfmpegLocator.findBundled(ROOT, "Windows 11", p -> false).isEmpty());
        assertTrue(FfmpegLocator.findBundled(null, "Windows 11", files::contains).isEmpty());
    }

    @Test
    void binaryNameDependsOnOs() {
        assertEquals("ffmpeg.exe", FfmpegLocator.binaryName("Windows 11"));
        assertEquals("ffmpeg", FfmpegLocator.binaryName("Linux"));
        Path dir = ROOT.resolve("ffmpeg");
        assertTrue(FfmpegLocator.findBundled(ROOT, "Linux", p -> p.equals(dir.resolve("ffmpeg"))).isPresent());
        assertTrue(FfmpegLocator.findBundled(ROOT, "Linux", p -> p.equals(dir.resolve("ffmpeg.exe"))).isEmpty());
    }
}
