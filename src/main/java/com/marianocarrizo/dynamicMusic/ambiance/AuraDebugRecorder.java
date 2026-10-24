package com.marianocarrizo.dynamicMusic.ambiance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

final class AuraDebugRecorder {

    static final String HEADER = "wall_s,media_s,raw_bass,bass,mid,high,energy,beat_count,beat_strength,"
            + "bass_s,mid_s,high_s,energy_s,bass_avg,energy_avg,"
            + "bass_g,mid_g,high_g,energy_g,beat_g,reactivity,intensity,zoom,"
            + "kick,snare,tonal,kick_hit,snare_hit,kick_g,snare_g,tonal_g,kick_hit_g";

    private static final int FLUSH_EVERY = 40;

    private BufferedWriter out;
    private long startNs;
    private int rows;

    boolean open() {
        close();
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write(HEADER);
            out.newLine();
            startNs = System.nanoTime();
            rows = 0;
            return true;
        } catch (IOException e) {
            System.err.println("Aura debug recorder: " + e.getMessage());
            out = null;
            return false;
        }
    }

    boolean isOpen() {
        return out != null;
    }

    void write(double... values) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append(fmt((System.nanoTime() - startNs) / 1e9));
        for (double v : values) sb.append(',').append(fmt(v));
        try {
            out.write(sb.toString());
            out.newLine();
            if (++rows % FLUSH_EVERY == 0) out.flush();
        } catch (IOException e) {
            System.err.println("Aura debug recorder: " + e.getMessage());
            close();
        }
    }

    void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    static Path file() {
        String appdata = System.getenv("APPDATA");
        Path dir = (appdata != null && !appdata.isBlank())
                ? Paths.get(appdata, "DynamicMusic")
                : Paths.get(System.getProperty("user.home"), "DynamicMusic");
        return dir.resolve("audio-debug.csv");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
