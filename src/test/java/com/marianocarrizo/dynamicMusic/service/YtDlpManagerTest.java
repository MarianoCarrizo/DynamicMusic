package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpManagerTest {

    private static final String HASH_EXE   = "a".repeat(64);
    private static final String HASH_LINUX = "b".repeat(63) + "C";

    private static final String SUMS =
            HASH_EXE + "  yt-dlp.exe\n"
          + HASH_LINUX + " *yt-dlp_linux\r\n"
          + "\n"
          + "not a checksum line\n"
          + "short  yt-dlp_macos\n";

    @Test
    void assetSelectionPerPlatform() {
        assertEquals("yt-dlp.exe", YtDlpManager.assetFor("Windows 11", "amd64"));
        assertEquals("yt-dlp.exe", YtDlpManager.assetFor("Windows 10", "aarch64"));
        assertEquals("yt-dlp_macos", YtDlpManager.assetFor("Mac OS X", "aarch64"));
        assertEquals("yt-dlp_macos", YtDlpManager.assetFor("Mac OS X", "x86_64"));
        assertEquals("yt-dlp_linux", YtDlpManager.assetFor("Linux", "amd64"));
        assertEquals("yt-dlp_linux", YtDlpManager.assetFor("Linux", "x86_64"));
        assertEquals("yt-dlp_linux_aarch64", YtDlpManager.assetFor("Linux", "aarch64"));
        assertEquals("yt-dlp_linux_aarch64", YtDlpManager.assetFor("Linux", "arm64"));
    }

    @Test
    void assetFallsBackToZipappWhenNoNativeMatch() {
        assertEquals("yt-dlp", YtDlpManager.assetFor("Linux", "riscv64"));
        assertEquals("yt-dlp", YtDlpManager.assetFor("FreeBSD", "amd64"));
        assertEquals("yt-dlp", YtDlpManager.assetFor(null, null));
    }

    @Test
    void binaryNameDependsOnOs() {
        assertEquals("yt-dlp.exe", YtDlpManager.binaryNameFor("Windows 11"));
        assertEquals("yt-dlp", YtDlpManager.binaryNameFor("Linux"));
        assertEquals("yt-dlp", YtDlpManager.binaryNameFor("Mac OS X"));
    }

    @Test
    void parseChecksumFindsAssetAndLowercases() {
        assertEquals(Optional.of(HASH_EXE), YtDlpManager.parseChecksum(SUMS, "yt-dlp.exe"));
        assertEquals(Optional.of(HASH_LINUX.toLowerCase()), YtDlpManager.parseChecksum(SUMS, "yt-dlp_linux"));
    }

    @Test
    void parseChecksumRejectsMissingOrMalformed() {
        assertTrue(YtDlpManager.parseChecksum(SUMS, "yt-dlp_macos").isEmpty());
        assertTrue(YtDlpManager.parseChecksum(SUMS, "yt-dlp").isEmpty());
        assertTrue(YtDlpManager.parseChecksum("", "yt-dlp.exe").isEmpty());
        assertTrue(YtDlpManager.parseChecksum(null, "yt-dlp.exe").isEmpty());
    }

    @Test
    void sha256OfFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("f.bin");
        Files.writeString(f, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", YtDlpManager.sha256(f));
    }

    @Test
    void verifyAcceptsMatchAndRejectsMismatchOrUnlisted(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("f.bin");
        Files.writeString(f, "abc");
        String good = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD  yt-dlp_linux\n";
        assertDoesNotThrow(() -> YtDlpManager.verify(f, good, "yt-dlp_linux"));

        IOException bad = assertThrows(IOException.class, () -> YtDlpManager.verify(f, SUMS, "yt-dlp.exe"));
        assertEquals("Could not verify yt-dlp download", bad.getMessage());
        assertThrows(IOException.class, () -> YtDlpManager.verify(f, good, "yt-dlp_macos"));
    }

    @Test
    void managedBinaryCheckUsesPerOsFileNameAndSizeSanity(@TempDir Path app) throws IOException {
        YtDlpManager linux = new YtDlpManager(() -> app, "Linux", "amd64");
        assertFalse(linux.hasManagedBinary());

        Path bin = Files.createDirectories(app.resolve("bin"));
        Files.write(bin.resolve("yt-dlp.exe"), new byte[2_000_000]);
        assertFalse(linux.hasManagedBinary(), "Linux must not accept the .exe name");

        Files.write(bin.resolve("yt-dlp"), new byte[10]);
        assertFalse(linux.hasManagedBinary(), "too small to be real");

        Files.write(bin.resolve("yt-dlp"), new byte[2_000_000]);
        assertTrue(linux.hasManagedBinary());
        assertEquals(bin.resolve("yt-dlp").toString(), linux.executable());

        YtDlpManager win = new YtDlpManager(() -> app, "Windows 11", "amd64");
        assertTrue(win.hasManagedBinary());
        assertEquals(bin.resolve("yt-dlp.exe").toString(), win.executable());
    }

    @Test
    void readinessIsCompleteWhenNoSetupRan(@TempDir Path app) {
        YtDlpManager m = new YtDlpManager(() -> app, "Linux", "amd64");
        assertFalse(m.isSetupRunning());
        assertTrue(m.readiness().isDone());
    }

    @Test
    void appDirUsesAppdataOnlyOnWindows() {
        assertEquals(Paths.get("C:\\Users\\u\\AppData\\Roaming", "DynamicMusic"),
                YouTubeDownloader.appDir("Windows 11", "C:\\Users\\u\\AppData\\Roaming", "C:\\Users\\u"));
        assertEquals(Paths.get("/home/u", "DynamicMusic"),
                YouTubeDownloader.appDir("Linux", "/stray/appdata", "/home/u"));
        assertEquals(Paths.get("/home/u", "DynamicMusic"),
                YouTubeDownloader.appDir("Windows 11", " ", "/home/u"));
        assertEquals(Paths.get("/Users/u", "DynamicMusic"),
                YouTubeDownloader.appDir("Mac OS X", null, "/Users/u"));
    }
}
