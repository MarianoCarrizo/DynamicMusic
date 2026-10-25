package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedDownloadTest {

    @Test
    void acceptsGitHubAndItsAssetHostsOverHttps() {
        assertTrue(BoundedDownload.isGitHubHost(URI.create("https://github.com/a/b/releases/download/v1/x.exe")));
        assertTrue(BoundedDownload.isGitHubHost(URI.create("https://objects.githubusercontent.com/x")));
        assertTrue(BoundedDownload.isGitHubHost(URI.create("https://release-assets.githubusercontent.com/x")));
        assertTrue(BoundedDownload.isGitHubHost(URI.create("https://GitHub.com/x")));
    }

    @Test
    void rejectsLookalikeHostsAndPlainHttp() {
        assertFalse(BoundedDownload.isGitHubHost(URI.create("http://github.com/x")));
        assertFalse(BoundedDownload.isGitHubHost(URI.create("https://evilgithub.com/x")));
        assertFalse(BoundedDownload.isGitHubHost(URI.create("https://github.com.evil.example/x")));
        assertFalse(BoundedDownload.isGitHubHost(URI.create("https://githubusercontent.com.evil.example/x")));
        assertFalse(BoundedDownload.isGitHubHost(URI.create("https://evil.example/github.com")));
        assertFalse(BoundedDownload.isGitHubHost(URI.create("https://notgithubusercontent.com/x")));
        assertFalse(BoundedDownload.isGitHubHost(null));
    }

    @Test
    void limitsMatchTheIntendedSizes() {
        assertEquals(300L * 1024 * 1024, DownloadKind.INSTALLER.maxBytes());
        assertEquals(100L * 1024 * 1024, DownloadKind.YTDLP_BINARY.maxBytes());
        assertEquals(1024L * 1024, DownloadKind.CHECKSUM.maxBytes());
    }

    @Test
    void stalledBodyIsAbortedAtTheDeadlineAndTheFileDeleted(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write(new byte[10]);
            ex.getResponseBody().flush();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            ex.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")).build();
            Path target = dir.resolve("out.bin");

            long start = System.nanoTime();
            IOException e = assertThrows(IOException.class, () -> BoundedDownload.toFile(client, req, target,
                    uri -> true, 1024, Duration.ofMillis(500), "slow file"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(e.getMessage().contains("timed out"), e.getMessage());
            assertTrue(elapsedMs < 5000, "took " + elapsedMs + " ms");
            assertFalse(Files.exists(target));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
