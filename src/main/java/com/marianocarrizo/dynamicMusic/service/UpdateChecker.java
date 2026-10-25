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

public final class UpdateChecker {

    static final String REPO = "MarianoCarrizo/DynamicMusic";
    static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String ASSET_URL_PREFIX = "https://github.com/" + REPO + "/releases/download/";

    private UpdateChecker() {}

    public record Version(int major, int minor, int patch) implements Comparable<Version> {

        private static final Pattern PATTERN = Pattern.compile("^[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$");

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

    public record ReleaseInfo(Version version, String installerName, String installerUrl, String checksumUrl,
                              String signatureUrl) {
        public String checksumName() { return installerName + ".sha256"; }
        public String signatureName() { return installerName + ".sha256.sig"; }
    }

    static String installerName(Version version) {
        return "DynamicMusic-Setup-" + version + ".exe";
    }

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
            String signatureUrl = null;
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
                    else if ((installer + ".sha256.sig").equals(name)) signatureUrl = url;
                }
            }
            if (installerUrl == null || checksumUrl == null) return Optional.empty();
            if (signatureUrl == null) {
                System.err.println("[update] release " + version.get() + " has no signature asset, ignoring it");
                return Optional.empty();
            }
            return Optional.of(new ReleaseInfo(version.get(), installer, installerUrl, checksumUrl, signatureUrl));
        } catch (RuntimeException e) {
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

    public record Lookup(boolean answered, Optional<ReleaseInfo> release) {}

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
