package com.marianocarrizo.dynamicMusic.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure release-metadata logic for the auto-updater: versions, GitHub release JSON and asset selection. */
public final class UpdateChecker {

    static final String REPO = "MarianoCarrizo/DynamicMusic";
    static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Only assets served from this repository's releases are ever downloaded. */
    static final String ASSET_URL_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    private UpdateChecker() {}

    // --- Versions ---

    /** Semantic version x.y.z (pre-release and build suffixes are not supported). */
    public record Version(int major, int minor, int patch) implements Comparable<Version> {

        private static final Pattern PATTERN = Pattern.compile("^[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$");

        /** Parses {@code 1.2.3} or {@code v1.2.3}; empty for anything else (including {@code 1.2.3-rc1}). */
        public static Optional<Version> parse(String text) {
            if (text == null) return Optional.empty();
            Matcher m = PATTERN.matcher(text.strip());
            if (!m.matches()) return Optional.empty();
            return Optional.of(new Version(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }

        @Override
        public int compareTo(Version o) {
            int c = Integer.compare(major, o.major);
            if (c != 0) return c;
            c = Integer.compare(minor, o.minor);
            return c != 0 ? c : Integer.compare(patch, o.patch);
        }

        public boolean isNewerThan(Version other) { return compareTo(other) > 0; }

        @Override
        public String toString() { return major + "." + minor + "." + patch; }
    }

    // --- Release metadata ---

    /** A published release with its installer and checksum assets. */
    public record ReleaseInfo(Version version, String installerName, String installerUrl, String checksumUrl) {
        public String checksumName() { return installerName + ".sha256"; }
    }

    /** File name of the installer asset for {@code version}. */
    static String installerName(Version version) {
        return "DynamicMusic-Setup-" + version + ".exe";
    }

    /**
     * Parses a GitHub "latest release" response. Empty when the release is a draft or pre-release, the tag is not
     * {@code vX.Y.Z}, or the installer and its {@code .sha256} asset are not both present with trusted URLs.
     */
    public static Optional<ReleaseInfo> parseRelease(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return Optional.empty();
            JsonObject o = root.getAsJsonObject();
            if (flag(o, "draft") || flag(o, "prerelease")) return Optional.empty();
            Optional<Version> version = Version.parse(string(o, "tag_name"));
            if (version.isEmpty()) return Optional.empty();

            String installer = installerName(version.get());
            String installerUrl = null;
            String checksumUrl = null;
            JsonElement assets = o.get("assets");
            if (assets != null && assets.isJsonArray()) {
                JsonArray arr = assets.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject a = e.getAsJsonObject();
                    String name = string(a, "name");
                    String url = string(a, "browser_download_url");
                    if (url == null || !url.startsWith(ASSET_URL_PREFIX)) continue;
                    if (installer.equals(name)) installerUrl = url;
                    else if ((installer + ".sha256").equals(name)) checksumUrl = url;
                }
            }
            if (installerUrl == null || checksumUrl == null) return Optional.empty();
            return Optional.of(new ReleaseInfo(version.get(), installer, installerUrl, checksumUrl));
        } catch (RuntimeException e) { // JsonParseException and friends: malformed response
            return Optional.empty();
        }
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // --- Network ---

    /** Outcome of a latest-release lookup: {@code answered} is false only when the server could not be reached. */
    public record Lookup(boolean answered, Optional<ReleaseInfo> release) {}

    /**
     * Asks GitHub for the latest release. Rate limits (403/429) and other HTTP errors count as "answered, nothing
     * usable" so the caller does not retry until the next interval; IO failures (offline) do not.
     */
    static Lookup fetchLatest(HttpClient client, URI api) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(api)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "DynamicMusic-Updater")
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) return new Lookup(true, Optional.empty());
        return new Lookup(true, parseRelease(resp.body()));
    }
}
