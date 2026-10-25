package com.marianocarrizo.dynamicMusic.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

enum DownloadKind {
    INSTALLER(300L * 1024 * 1024, Duration.ofMinutes(15), "installer"),
    YTDLP_BINARY(100L * 1024 * 1024, Duration.ofSeconds(120), "yt-dlp"),
    CHECKSUM(1024L * 1024, Duration.ofSeconds(30), "checksum"),
    SIGNATURE(1024L * 1024, Duration.ofSeconds(30), "signature"),
    YTDLP_SUMS(1024L * 1024, Duration.ofSeconds(30), "yt-dlp checksums");

    private final long maxBytes;
    private final Duration maxTime;
    private final String label;

    DownloadKind(long maxBytes, Duration maxTime, String label) {
        this.maxBytes = maxBytes;
        this.maxTime = maxTime;
        this.label = label;
    }

    long maxBytes() { return maxBytes; }

    Duration maxTime() { return maxTime; }

    String label() { return label; }

    HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(maxTime).GET().build();
    }
}
