package com.marianocarrizo.dynamicMusic.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class ExecutableResolver {

    private static final String DEFAULT_PATHEXT = ".COM;.EXE";
    private static final String WINDOWS_PATH_SEPARATOR = ";";
    private static final String EXE_EXTENSION = ".exe";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(EXE_EXTENSION, ".com");

    private ExecutableResolver() {}

    public static Optional<Path> find(String name) {
        return find(name, System.getenv("PATH"), System.getenv("PATHEXT"),
                YtDlpManager.isWindows(System.getProperty("os.name", "")));
    }

    static Optional<Path> find(String name, String pathEnv, String pathExt, boolean windows) {
        if (name == null || name.isBlank() || pathEnv == null || pathEnv.isBlank()) return Optional.empty();
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) return Optional.empty();
        List<String> candidates = candidateNames(name, pathExt, windows);
        String separator = windows ? WINDOWS_PATH_SEPARATOR : File.pathSeparator;
        for (String entry : pathEnv.split(separator, -1)) {
            Path dir = directory(entry);
            if (dir == null) continue;
            for (String candidate : candidates) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file) && (windows || Files.isExecutable(file))) {
                    return Optional.of(file.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }

    private static Path directory(String entry) {
        String trimmed = entry.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return null;
        try {
            Path dir = Paths.get(trimmed);
            return dir.isAbsolute() ? dir : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static List<String> candidateNames(String name, String pathExt, boolean windows) {
        List<String> names = new ArrayList<>();
        if (!windows) {
            names.add(name);
            return names;
        }
        List<String> extensions = executableExtensions(pathExt);
        String lower = name.toLowerCase(Locale.ROOT);
        if (extensions.stream().anyMatch(lower::endsWith)) names.add(name);
        for (String ext : extensions) names.add(name + ext);
        return names;
    }

    private static List<String> executableExtensions(String pathExt) {
        String source = pathExt == null || pathExt.isBlank() ? DEFAULT_PATHEXT : pathExt;
        List<String> extensions = new ArrayList<>();
        for (String ext : source.split(WINDOWS_PATH_SEPARATOR)) {
            String e = ext.strip().toLowerCase(Locale.ROOT);
            if (ALLOWED_EXTENSIONS.contains(e) && !extensions.contains(e)) extensions.add(e);
        }
        if (extensions.isEmpty()) extensions.add(EXE_EXTENSION);
        return extensions;
    }
}
