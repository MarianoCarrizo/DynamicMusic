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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private KeyPair keys;
    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
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

    private void serveSigned(byte[] sums) throws Exception {
        serve("/sums", 200, sums);
        serve("/sig", 200, UpdateSignatureTest.sign(sums, keys));
    }

    private static String sha(byte[] data, Path scratch) throws IOException {
        Path f = Files.createTempFile(scratch, "h", ".bin");
        Files.write(f, data);
        String hex = Checksums.sha256(f);
        Files.delete(f);
        return hex;
    }

    private static byte[] sums(String hex) {
        return (hex + "  " + NAME + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private ReleaseInfo release() {
        return new ReleaseInfo(new Version(2, 0, 0), NAME, base + "/exe", base + "/sums", base + "/sig");
    }

    private PublicKey key() { return keys.getPublic(); }

    private UpdateManager.DownloadPolicy policy() {
        return new UpdateManager.DownloadPolicy(key(), uri -> true, 1024);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void installerVersionFromFileName() {
        assertEquals(Optional.of(new Version(1, 2, 3)), UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe"));
        assertTrue(UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe.sha256").isEmpty());
        assertTrue(UpdateManager.installerVersion("DynamicMusic-Setup-1.2.3.exe.sha256.sig").isEmpty());
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
        serveSigned(sums(sha(PAYLOAD, dir)));
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        Path installer = UpdateManager.downloadAndVerify(client(), release(), updates, policy());

        assertEquals(updates.resolve(NAME), installer);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(installer));
        assertFalse(Files.exists(updates.resolve(NAME + ".part")));
        assertTrue(Files.exists(updates.resolve(NAME + ".sha256.sig")));
        assertTrue(UpdateManager.verifyOnDisk(installer, key()));
        assertFalse(UpdateManager.verifyOnDisk(installer), "the embedded key must not accept a test signature");

        UpdateManager.ReadyUpdate pending = UpdateManager.cleanAndFindPending(updates, new Version(1, 0, 0), key());
        assertNotNull(pending);
        assertEquals(new Version(2, 0, 0), pending.version());
        assertNull(UpdateManager.cleanAndFindPending(updates, new Version(2, 0, 0), key()));
        assertFalse(Files.exists(installer), "installer for an already-installed version is cleaned up");
        assertFalse(Files.exists(updates.resolve(NAME + ".sha256")));
        assertFalse(Files.exists(updates.resolve(NAME + ".sha256.sig")));
    }

    @Test
    void checksumMismatchDeletesTheDownload(@TempDir Path dir) throws Exception {
        serveSigned(sums("a".repeat(64)));
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        assertEquals(YtDlpManager.VERIFY_FAILED, e.getMessage());
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count(), "nothing left behind after a mismatch");
        }
    }

    @Test
    void httpErrorsAbortWithoutLeavingFiles(@TempDir Path dir) throws Exception {
        serve("/sums", 404, new byte[0]);
        serve("/sig", 200, new byte[64]);
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void badSignatureRefusesTheUpdateBeforeDownloadingTheInstaller(@TempDir Path dir) throws Exception {
        byte[] sums = sums(sha(PAYLOAD, dir));
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        serve("/sums", 200, sums);
        serve("/sig", 200, UpdateSignatureTest.sign(sums, attacker));
        AtomicBoolean installerRequested = new AtomicBoolean();
        server.createContext("/exe", ex -> {
            installerRequested.set(true);
            ex.sendResponseHeaders(200, PAYLOAD.length);
            ex.getResponseBody().write(PAYLOAD);
            ex.close();
        });
        server.start();

        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        assertEquals(UpdateManager.SIGNATURE_FAILED, e.getMessage());
        assertFalse(installerRequested.get());
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count());
        }
    }

    private void assertNothingLeftIn(Path updates) throws IOException {
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count(), "nothing left behind");
        }
    }

    @Test
    void installerLargerThanTheDeclaredLimitIsRejectedUpFront(@TempDir Path dir) throws Exception {
        byte[] big = new byte[2048];
        serveSigned(sums(sha(big, dir)));
        serve("/exe", 200, big);
        server.start();

        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        assertNothingLeftIn(updates);
    }

    @Test
    void installerStreamedPastTheLimitIsAbortedAndTheFileDeleted(@TempDir Path dir) throws Exception {
        byte[] big = new byte[4096];
        serveSigned(sums(sha(big, dir)));
        server.createContext("/exe", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(big);
            ex.close();
        });
        server.start();

        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        assertNothingLeftIn(updates);
    }

    @Test
    void redirectToAnUntrustedHostIsRejected(@TempDir Path dir) throws Exception {
        serveSigned(sums(sha(PAYLOAD, dir)));
        serve("/elsewhere", 200, PAYLOAD);
        server.createContext("/exe", ex -> {
            ex.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();

        UpdateManager.DownloadPolicy onlyNumericHost =
                new UpdateManager.DownloadPolicy(key(), uri -> "127.0.0.1".equals(uri.getHost()), 1024);
        Path updates = dir.resolve("updates");
        IOException e = assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, onlyNumericHost));
        assertTrue(e.getMessage().contains("unexpected download host"), e.getMessage());
        assertNothingLeftIn(updates);

        Path ok = UpdateManager.downloadAndVerify(client(), release(), dir.resolve("ok"), policy());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(ok));
    }

    @Test
    void missingSignatureFileRefusesTheUpdate(@TempDir Path dir) throws Exception {
        serve("/sums", 200, sums(sha(PAYLOAD, dir)));
        serve("/sig", 404, new byte[0]);
        serve("/exe", 200, PAYLOAD);
        server.start();

        Path updates = dir.resolve("updates");
        assertThrows(IOException.class, () -> UpdateManager.downloadAndVerify(client(), release(), updates, policy()));
        try (var files = Files.list(updates)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void onDiskInstallerWithoutValidSignatureIsRejected(@TempDir Path dir) throws Exception {
        byte[] sumsBytes = sums(sha(PAYLOAD, dir));
        Path exe = dir.resolve(NAME);
        Path sumsFile = dir.resolve(NAME + ".sha256");
        Path sig = dir.resolve(NAME + ".sha256.sig");
        Files.write(exe, PAYLOAD);
        Files.write(sumsFile, sumsBytes);
        assertFalse(UpdateManager.verifyOnDisk(exe, key()), "no signature file");

        Files.write(sig, UpdateSignatureTest.sign(sumsBytes, keys));
        assertTrue(UpdateManager.verifyOnDisk(exe, key()));

        Files.write(sumsFile, (new String(sumsBytes, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(UpdateManager.verifyOnDisk(exe, key()), "checksum file changed after signing");
        assertNull(UpdateManager.cleanAndFindPending(dir, new Version(1, 0, 0), key()));
        assertFalse(Files.exists(sig));
    }

    @Test
    void tamperedInstallerOnDiskIsRejectedAndRemoved(@TempDir Path dir) throws Exception {
        byte[] sumsBytes = sums(sha(PAYLOAD, dir));
        Path exe = dir.resolve(NAME);
        Files.write(exe, PAYLOAD);
        Files.write(dir.resolve(NAME + ".sha256"), sumsBytes);
        Files.write(dir.resolve(NAME + ".sha256.sig"), UpdateSignatureTest.sign(sumsBytes, keys));
        assertTrue(UpdateManager.verifyOnDisk(exe, key()));

        Files.write(exe, "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(UpdateManager.verifyOnDisk(exe, key()));
        assertNull(UpdateManager.cleanAndFindPending(dir, new Version(1, 0, 0), key()));
        assertFalse(Files.exists(exe));
        assertFalse(Files.exists(dir.resolve(NAME + ".sha256")));
        assertFalse(Files.exists(dir.resolve(NAME + ".sha256.sig")));
    }

    @Test
    void partialDownloadsAreCleanedUp(@TempDir Path dir) throws Exception {
        Path part = dir.resolve(NAME + ".part");
        Files.writeString(part, "half");
        assertNull(UpdateManager.cleanAndFindPending(dir, new Version(1, 0, 0)));
        assertFalse(Files.exists(part));
    }
}
