package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.service.UpdateChecker.ReleaseInfo;
import com.marianocarrizo.dynamicMusic.service.UpdateChecker.Version;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    private static String fixture() throws IOException {
        try (InputStream in = UpdateCheckerTest.class.getResourceAsStream("/update/latest-release.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Version v(String s) { return Version.parse(s).orElseThrow(); }

    @Test
    void parsesPlainAndPrefixedVersions() {
        assertEquals(new Version(1, 2, 3), v("1.2.3"));
        assertEquals(new Version(1, 2, 3), v("v1.2.3"));
        assertEquals(new Version(10, 0, 21), v(" V10.0.21 "));
        assertEquals("1.2.3", v("v1.2.3").toString());
    }

    @Test
    void rejectsNonSemanticVersions() {
        for (String bad : new String[]{null, "", "1.2", "1.2.3.4", "1.2.3-rc1", "latest", "v1.x.3", "-1.2.3"}) {
            assertTrue(Version.parse(bad).isEmpty(), "should reject: " + bad);
        }
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertTrue(v("1.10.0").isNewerThan(v("1.9.9")));
        assertTrue(v("2.0.0").isNewerThan(v("1.99.99")));
        assertTrue(v("1.0.1").isNewerThan(v("1.0.0")));
        assertFalse(v("1.0.0").isNewerThan(v("1.0.0")));
        assertFalse(v("0.9.0").isNewerThan(v("1.0.0")));
        assertTrue(v("1.0.0").isNewerThan(v("0.0.42")));
    }

    @Test
    void parsesReleaseAndSelectsInstallerAndChecksumAssets() throws IOException {
        ReleaseInfo r = UpdateChecker.parseRelease(fixture()).orElseThrow();
        assertEquals(new Version(1, 4, 2), r.version());
        assertEquals("DynamicMusic-Setup-1.4.2.exe", r.installerName());
        assertEquals("DynamicMusic-Setup-1.4.2.exe.sha256", r.checksumName());
        assertTrue(r.installerUrl().endsWith("/v1.4.2/DynamicMusic-Setup-1.4.2.exe"));
        assertTrue(r.checksumUrl().endsWith("/v1.4.2/DynamicMusic-Setup-1.4.2.exe.sha256"));
        assertEquals("DynamicMusic-Setup-1.4.2.exe.sha256.sig", r.signatureName());
        assertTrue(r.signatureUrl().endsWith("/v1.4.2/DynamicMusic-Setup-1.4.2.exe.sha256.sig"));
    }

    @Test
    void ignoresDraftsAndPrereleases() throws IOException {
        assertTrue(UpdateChecker.parseRelease(fixture().replace("\"draft\": false", "\"draft\": true")).isEmpty());
        assertTrue(UpdateChecker.parseRelease(fixture().replace("\"prerelease\": false", "\"prerelease\": true")).isEmpty());
    }

    @Test
    void ignoresReleasesWithoutBothAssets() throws IOException {
        String noChecksum = fixture().replace("DynamicMusic-Setup-1.4.2.exe.sha256\",", "other.txt\",");
        assertTrue(UpdateChecker.parseRelease(noChecksum).isEmpty());
        String noSignature = fixture().replace("DynamicMusic-Setup-1.4.2.exe.sha256.sig\",", "other.sig\",");
        assertTrue(UpdateChecker.parseRelease(noSignature).isEmpty());
        String wrongInstaller = fixture().replace("\"name\": \"DynamicMusic-Setup-1.4.2.exe\"", "\"name\": \"DynamicMusic-Setup-9.9.9.exe\"");
        assertTrue(UpdateChecker.parseRelease(wrongInstaller).isEmpty());
    }

    @Test
    void rejectsAssetsOutsideTheRepositoryReleases() throws IOException {
        String evil = fixture().replace("https://github.com/MarianoCarrizo/DynamicMusic/releases/download/v1.4.2/DynamicMusic-Setup-1.4.2.exe\"",
                "https://evil.example.com/DynamicMusic-Setup-1.4.2.exe\"");
        assertTrue(UpdateChecker.parseRelease(evil).isEmpty());
        String plainHttp = fixture().replace("https://github.com", "http://github.com");
        assertTrue(UpdateChecker.parseRelease(plainHttp).isEmpty());
    }

    @Test
    void rejectsBadTagsAndMalformedJson() throws IOException {
        assertTrue(UpdateChecker.parseRelease(fixture().replace("v1.4.2\",", "nightly\",")).isEmpty());
        assertEquals(Optional.empty(), UpdateChecker.parseRelease("not json"));
        assertEquals(Optional.empty(), UpdateChecker.parseRelease("[]"));
        assertEquals(Optional.empty(), UpdateChecker.parseRelease("{\"message\":\"API rate limit exceeded\"}"));
        assertEquals(Optional.empty(), UpdateChecker.parseRelease(null));
    }
}
