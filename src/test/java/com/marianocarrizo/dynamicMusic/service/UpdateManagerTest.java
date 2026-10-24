package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.service.UpdateChecker.ReleaseInfo;
import com.marianocarrizo.dynamicMusic.service.UpdateChecker.Version;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerTest {

    private static final String NAME = "DynamicMusic-Setup-2.0.0.exe";
    private static final byte[] PAYLOAD = "pretend installer".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, ex -> {
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private static String sha(byte[] data, Path scratch) throws IOException {
        Path f = Files.createTempFile(scratch, "h", ".bin");
        Files.write(f, data);
        String hex = Checksums.sha256(f);
        Files.delete(f);
        return hex;
    }

    private ReleaseInfo release() {
        return new ReleaseInfo(new Version(2, 0, 0), NAME, base + "/exe", base + "/sums");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void installerVersionFromFileName() {
        assertEquals(Optional.of(new Version(1, 2, 3)), UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe"));
        assertTrue(UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe.sha256").isEmpty());
        assertTrue(UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe.part").isEmpty());
        assertTrue(UpdateManager.installerVersion("other.exe").isEmpty());
    }

    @Test
    void updaterIsDisabledWithoutVersionOrOutsideWindows() {
        URI api = URI.create("http://localhost/");
        Optional<Version> ver = Optional.of(new Version(1, 0, 0));
        assertTrue(new UpdateManager(() -> Path.of("x"), ver, "Windows 11", api).isEnabled());
        assertFalse(new UpdateManager(() -> Path.of("x"), Optional.empty(), "Windows 11", api).isEnabled());
        assertFalse(new UpdateManager(() -> Path.of("x"), ver, "Linux", api).isEnabled());
    }

    @Test
    void installerRunsSilentlyAndClosesTheApp() {
        assertEquals(List.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS"),
                UpdateManager.INSTALLER_ARGS);
    }

    @Test
    void downloadsVerifiesAndMovesTheInstaller(@TempDir Path dir) throws Exception {
        String hex = sha(PAYLOAD, dir);
        serve("/sums", 200, (hex + "  " + NAME + "\n").getBytes(StandardCharsets.UTF_8));
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        Path installer = UpdateManager.downloadAndVerify(client(), release(), updates);

        assertEquals(updates.resolve(NAME), installer);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(installer));
        assertFalse(Files.exists(updates.resolve(NAME + ".part")));
        assertTrue(UpdateManager.verifyOnDisk(installer));

        UpdateManager.ReadyUpdate pending = UpdateManager.cleanAndFindPending(updates, new Version(1, 0, 0));
        assertNotNull(pending);
        assertEquals(new Version(2, 0, 0), pending.version());
        assertNull(UpdateManager.cleanAndFindPending(updates, new Version(2, 0, 0)));
        assertFalse(Files.exists(installer), "installer for an already-installed version is cleaned up");
    }

    @Test
    void checksumMismatchDeletesTheDownload(@TempDir Path dir) throws Exception {
        serve("/sums", 200, ("a".repeat(64) + "  " + NAME + "\n").getBytes(StandardCharsets.UTF_8));
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates));
        assertEquals(YtDlpManager.VERIFY_FAILED, e.getMessage());
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count(), "nothing left behind after a mismatch");
        }
    }

    @Test
    void httpErrorsAbortWithoutLeavingFiles(@TempDir Path dir) throws Exception {
        serve("/sums", 404, new byte[0]);
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates));
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void tamperedInstallerOnDiskIsRejectedAndRemoved(@TempDir Path dir) throws Exception {
        String hex = sha(PAYLOAD, dir);
        Path exe = dir.resolve(NAME);
        Files.write(exe, PAYLOAD);
        Files.writeString(dir.resolve(NAME + ".sha256"), hex + "  " + NAME + "\n");
        assertTrue(UpdateManager.verifyOnDisk(exe));

        Files.write(exe, "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(UpdateManager.verifyOnDisk(exe));
        assertNull(UpdateManager.cleanAndFindPending(dir, new Version(1, 0, 0)));
        assertFalse(Files.exists(exe));
        assertFalse(Files.exists(dir.resolve(NAME + ".sha256")));
    }

    @Test
    void partialDownloadsAreCleanedUp(@TempDir Path dir) throws Exception {
        Path part = dir.resolve(NAME + ".part");
        Files.writeString(part, "half");
        assertNull(UpdateManager.cleanAndFindPending(dir, new Version(1, 0, 0)));
        assertFalse(Files.exists(part));
    }
}
