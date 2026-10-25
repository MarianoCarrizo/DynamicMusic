package com.marianocarrizo.dynamicMusic.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

final class BoundedDownload {

    static final long MAX_INSTALLER_BYTES = 300L * 1024 * 1024;
    static final long MAX_YTDLP_BYTES = 100L * 1024 * 1024;
    static final long MAX_TEXT_BYTES = 1024L * 1024;

    private BoundedDownload() {}

    static boolean isGitHubHost(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("github.com") || host.endsWith(".githubusercontent.com");
    }

    static void toFile(HttpClient client, HttpRequest request, Path target, Predicate<URI> trusted,
                       long maxBytes, Duration maxTime, String what) throws IOException, InterruptedException {
        try (InputStream in = open(client, request, trusted, maxBytes, what).body();
             OutputStream out = Files.newOutputStream(target)) {
            copyBounded(in, out, maxBytes, maxTime, what);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw e;
        }
    }

    static byte[] toBytes(HttpClient client, HttpRequest request, Predicate<URI> trusted,
                          long maxBytes, Duration maxTime, String what) throws IOException, InterruptedException {
        try (InputStream in = open(client, request, trusted, maxBytes, what).body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copyBounded(in, out, maxBytes, maxTime, what);
            return out.toByteArray();
        }
    }

    private static HttpResponse<InputStream> open(HttpClient client, HttpRequest request, Predicate<URI> trusted,
                                                  long maxBytes, String what) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " fetching " + what);
            if (!trusted.test(resp.uri())) {
                throw new IOException("unexpected download host for " + what + ": " + resp.uri().getHost());
            }
            long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) throw new IOException(what + " is larger than " + maxBytes + " bytes");
            return resp;
        } catch (IOException | RuntimeException e) {
            try { resp.body().close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "download-watchdog");
        t.setDaemon(true);
        return t;
    });

    private static void copyBounded(InputStream in, OutputStream out, long maxBytes, Duration maxTime, String what)
            throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            expired.set(true);
            try { in.close(); } catch (IOException ignored) {}
        }, maxTime.toNanos(), TimeUnit.NANOSECONDS);
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) throw new IOException(what + " is larger than " + maxBytes + " bytes");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            if (expired.get()) throw new IOException("timed out fetching " + what, e);
            throw e;
        } finally {
            guard.cancel(false);
        }
        if (expired.get()) throw new IOException("timed out fetching " + what);
    }
}
