package com.marianocarrizo.dynamicMusic.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

final class Checksums {

    private Checksums() {}

    static Optional<String> parse(String sums, String assetName) {
        if (sums == null) return Optional.empty();
        for (String line : sums.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+", 2);
            if (parts.length != 2) continue;
            String name = parts[1].strip();
            if (name.startsWith("*")) name = name.substring(1);
            if (name.equals(assetName) && parts[0].matches("[0-9a-fA-F]{64}")) {
                return Optional.of(parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    static boolean matches(Path file, String sums, String assetName) throws IOException {
        Optional<String> expected = parse(sums, assetName);
        return expected.isPresent() && expected.get().equals(sha256(file));
    }
}
