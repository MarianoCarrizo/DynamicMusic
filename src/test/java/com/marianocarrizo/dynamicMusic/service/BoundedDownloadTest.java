package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(300L * 1024 * 1024, BoundedDownload.MAX_INSTALLER_BYTES);
        assertEquals(100L * 1024 * 1024, BoundedDownload.MAX_YTDLP_BYTES);
    }
}
