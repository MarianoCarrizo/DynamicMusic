package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableResolverTest {

    private static Path touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.write(f, new byte[]{1});
        f.toFile().setExecutable(true);
        return f;
    }

    @Test
    void findsAnExecutableInAPathDirectoryAndReturnsAnAbsolutePath(@TempDir Path tmp) throws IOException {
        Path file = touch(tmp.resolve("bin"), "tool.exe");
        String pathEnv = tmp.resolve("empty") + ";" + tmp.resolve("bin");

        Optional<Path> found = ExecutableResolver.find("tool", pathEnv, ".COM;.EXE;.BAT", true);

        assertEquals(Optional.of(file.toAbsolutePath().normalize()), found);
        assertTrue(found.orElseThrow().isAbsolute());
    }

    @Test
    void acceptsANameThatAlreadyCarriesTheExtension(@TempDir Path tmp) throws IOException {
        Path file = touch(tmp, "tool.exe");
        assertEquals(Optional.of(file), ExecutableResolver.find("tool.exe", tmp.toString(), ".EXE", true));
    }

    @Test
    void ignoresBatchFilesAndMissingTools(@TempDir Path tmp) throws IOException {
        touch(tmp, "tool.bat");
        touch(tmp, "tool.cmd");
        assertTrue(ExecutableResolver.find("tool", tmp.toString(), ".COM;.EXE;.BAT;.CMD", true).isEmpty());
        assertTrue(ExecutableResolver.find("nothing", tmp.toString(), ".EXE", true).isEmpty());
    }

    @Test
    void skipsRelativeAndBlankPathEntries(@TempDir Path tmp) throws IOException {
        Path file = touch(tmp, "tool.exe");
        assertEquals(Optional.of(file), ExecutableResolver.find("tool", ";.;sub;;\"\";" + tmp, ".EXE", true));
        assertTrue(ExecutableResolver.find("tool", ".;sub;;", ".EXE", true).isEmpty());
        assertTrue(ExecutableResolver.find("tool", "", ".EXE", true).isEmpty());
        assertTrue(ExecutableResolver.find("tool", null, ".EXE", true).isEmpty());
    }

    @Test
    void rejectsNamesThatContainPathSegments(@TempDir Path tmp) throws IOException {
        touch(tmp, "tool.exe");
        assertTrue(ExecutableResolver.find("../tool", tmp.resolve("x").toString() + ";" + tmp, ".EXE", true).isEmpty());
        assertTrue(ExecutableResolver.find("sub/tool", tmp.toString(), ".EXE", true).isEmpty());
        assertTrue(ExecutableResolver.find("", tmp.toString(), ".EXE", true).isEmpty());
    }

    @Test
    void defaultsToExeWhenPathextIsUnset(@TempDir Path tmp) throws IOException {
        Path file = touch(tmp, "tool.exe");
        assertEquals(Optional.of(file), ExecutableResolver.find("tool", tmp.toString(), null, true));
    }

    @Test
    void usesTheExactNameOutsideWindows(@TempDir Path tmp) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path file = touch(tmp, "tool");
        assertEquals(Optional.of(file), ExecutableResolver.find("tool", tmp.toString(), null, false));
    }

    @Test
    void theFirstPathDirectoryWins(@TempDir Path tmp) throws IOException {
        Path first = touch(tmp.resolve("a"), "tool.exe");
        touch(tmp.resolve("b"), "tool.exe");
        assertEquals(Optional.of(first), ExecutableResolver.find("tool", tmp.resolve("a") + ";" + tmp.resolve("b"), ".EXE", true));
    }
}
