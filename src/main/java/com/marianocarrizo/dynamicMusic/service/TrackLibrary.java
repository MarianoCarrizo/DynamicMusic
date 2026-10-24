package com.marianocarrizo.dynamicMusic.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps scene tracks alive: YouTube downloads live in an evicting cache,
 * so tracks referenced by a scene are copied to a permanent library folder.
 */
public final class TrackLibrary {

    private TrackLibrary() {}

    /** %APPDATA%/DynamicMusic/library */
    public static Path libraryDir() {
        return YouTubeDownloader.appDir().resolve("library");
    }

    /**
     * If {@code path} is inside the yt-cache, copies it to the library (skipping the copy
     * when the target already exists with the same size) and returns the new path.
     * Otherwise returns {@code path} unchanged. Never throws; on failure returns the original.
     */
    public static String pin(String path) {
        if (path == null || path.isBlank()) return path;
        try {
            Path src = Path.of(path).toAbsolutePath().normalize();
            Path cache = YouTubeDownloader.cacheDirPath().toAbsolutePath().normalize();
            if (!src.startsWith(cache) || !Files.isRegularFile(src)) return path;

            Path dir = libraryDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(src.getFileName().toString());
            if (!(Files.exists(target) && Files.size(target) == Files.size(src))) {
                Path tmp = dir.resolve(src.getFileName() + ".tmp");
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not pin track to library: " + e.getMessage());
            return path;
        }
    }
}
