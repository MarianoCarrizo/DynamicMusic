package com.marianocarrizo.dynamicMusic.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class TrackLibrary {

    private TrackLibrary() {}

    public static Path libraryDir() {
        return YouTubeDownloader.appDir().resolve("library");
    }

    public static String pin(String path) {
        return pin(path, YouTubeDownloader.cacheDirPath(), libraryDir());
    }

    static String pin(String path, Path cacheDir, Path libraryDir) {
        if (path == null || path.isBlank()) return path;
        try {
            Path src = Path.of(path).toAbsolutePath().normalize();
            Path cache = cacheDir.toAbsolutePath().normalize();
            if (!src.startsWith(cache) || !Files.isRegularFile(src)) return path;

            Path dir = libraryDir;
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
