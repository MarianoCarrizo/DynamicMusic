package com.marianocarrizo.dynamicMusic.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Resolves which ffmpeg to run: the copy bundled next to the app image
 * ({@code <installDir>/ffmpeg/ffmpeg[.exe]}) when present, otherwise {@code ffmpeg} from PATH.
 */
public final class FfmpegLocator {

    static final String BUNDLED_DIR = "ffmpeg";

    private FfmpegLocator() {}

    /** Bundled ffmpeg directory, resolved once (null when there is none). */
    private static final Path BUNDLED = detect();

    /** Absolute path of the bundled ffmpeg, or {@code "ffmpeg"} to use the one on PATH. */
    public static String executable() {
        return BUNDLED != null ? BUNDLED.resolve(binaryName(osName())).toString() : "ffmpeg";
    }

    /** Directory holding the bundled ffmpeg, for yt-dlp's {@code --ffmpeg-location}; empty when using PATH. */
    public static Optional<Path> directory() {
        return Optional.ofNullable(BUNDLED);
    }

    private static Path detect() {
        Path root = installRoot(System.getProperty("jpackage.app-path"), System.getProperty("java.home"));
        return findBundled(root, osName(), Files::isRegularFile).orElse(null);
    }

    private static String osName() {
        return System.getProperty("os.name", "");
    }

    // --- Pure resolution ---

    /**
     * Root of a jpackage app image. The launcher sets {@code jpackage.app-path} to the launcher exe, which sits in
     * the root; failing that, {@code java.home} is {@code <root>/runtime}. Null for a plain JDK (dev runs).
     */
    static Path installRoot(String appPathProp, String javaHome) {
        if (appPathProp != null && !appPathProp.isBlank()) {
            Path parent = Paths.get(appPathProp).toAbsolutePath().getParent();
            if (parent != null) return parent;
        }
        if (javaHome != null && !javaHome.isBlank()) {
            Path home = Paths.get(javaHome).toAbsolutePath();
            if (home.getFileName() != null && home.getFileName().toString().equals("runtime")) return home.getParent();
        }
        return null;
    }

    /** The {@code <root>/ffmpeg} directory if it contains the ffmpeg binary, per {@code isFile}. */
    static Optional<Path> findBundled(Path installRoot, String osName, Predicate<Path> isFile) {
        if (installRoot == null) return Optional.empty();
        Path dir = installRoot.resolve(BUNDLED_DIR);
        return isFile.test(dir.resolve(binaryName(osName))) ? Optional.of(dir) : Optional.empty();
    }

    static String binaryName(String osName) {
        return YtDlpManager.isWindows(osName) ? "ffmpeg.exe" : "ffmpeg";
    }
}
