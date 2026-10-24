package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline evaluation of the kick detector against a multi-genre corpus. Not part of the default
 * test run (surefire excludes the "corpus" group).
 *
 * Run: ./mvnw test -Dtest=AudioAnalyzerCorpusTest -Dgroups=corpus -DexcludedGroups=none -Dcorpus.dir=<dir>
 * (also reads the CORPUS_DIR environment variable). Corpus layout: {@code <i>.bin} holds raw
 * little-endian float32 frames x 256 bands in dB (clamped to [-75, 0], 25 ms per frame) and
 * {@code <i>.ref} holds reference kick onset times in seconds, one per line.
 */
@Tag("corpus")
class AudioAnalyzerCorpusTest {

    private static final double FRAME_S = 0.025;
    private static final double TOLERANCE_S = 0.070;
    private static final int MAX_LAG_MS = 75;
    private static final int LAG_STEP_MS = 5;
    // Detections farther than this from any reference onset are outside the proxy's coverage
    private static final double COVERAGE_S = 2.0;

    /** Per-track result at the best constant lag. */
    record Result(int track, double seconds, double beatsPerSec, double refPerSec,
                  double ratioCovered, double precision, double recall, double f1, double f1Covered, int lagMs,
                  double hit10, double hit50, double hit90) {}

    @Test
    void evaluatesCorpus() throws IOException {
        String dir = System.getProperty("corpus.dir", System.getenv("CORPUS_DIR"));
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "corpus.dir not set or missing");
        List<Result> results = evaluate(Path.of(dir));
        Assumptions.assumeTrue(!results.isEmpty(), "no tracks found");
        print(results);
        double mean = results.stream().mapToDouble(Result::f1).average().orElse(0);
        assertTrue(mean > 0, "mean F1 should be positive");
    }

    /** Standalone entry point for quick iteration without Maven: args = corpus dir. */
    public static void main(String[] args) throws IOException {
        print(evaluate(Path.of(args[0])));
    }

    static List<Result> evaluate(Path dir) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int i = 0; Files.exists(dir.resolve(i + ".bin")) && Files.exists(dir.resolve(i + ".ref")); i++) {
            out.add(evaluateTrack(i, dir.resolve(i + ".bin"), dir.resolve(i + ".ref")));
        }
        return out;
    }

    private static Result evaluateTrack(int track, Path bin, Path refFile) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(bin)).order(ByteOrder.LITTLE_ENDIAN);
        int frames = buf.remaining() / 4 / AudioAnalyzer.NUM_BANDS;
        double[] ref = Files.readAllLines(refFile).stream().map(String::trim).filter(s -> !s.isEmpty())
                .mapToDouble(Double::parseDouble).sorted().toArray();

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AudioAnalyzer analyzer = new AudioAnalyzer(clock::get);
        float[] frame = new float[AudioAnalyzer.NUM_BANDS];
        List<Double> beats = new ArrayList<>();
        double[] kickHit = new double[frames];
        long lastCount = 0;
        for (int f = 0; f < frames; f++) {
            for (int b = 0; b < frame.length; b++) frame[b] = buf.getFloat();
            clock.addAndGet(25_000_000L);
            AudioFeatures af = analyzer.process(frame);
            if (af.beatCount() > lastCount) beats.add(f * FRAME_S);
            lastCount = af.beatCount();
            kickHit[f] = af.kickHit();
        }

        Result best = null;
        for (int lag = 0; lag <= MAX_LAG_MS; lag += LAG_STEP_MS) {
            double[] det = new double[beats.size()];
            for (int k = 0; k < det.length; k++) det[k] = beats.get(k) - lag / 1000.0;
            int tp = match(det, ref);
            double p = det.length == 0 ? 0 : (double) tp / det.length;
            double r = ref.length == 0 ? 0 : (double) tp / ref.length;
            double f1 = p + r == 0 ? 0 : 2 * p * r / (p + r);
            int covered = 0;
            for (double d : det) if (nearest(d, ref) <= COVERAGE_S) covered++;
            double pc = covered == 0 ? 0 : (double) tp / covered;
            double f1c = pc + r == 0 ? 0 : 2 * pc * r / (pc + r);
            if (best == null || f1 > best.f1()) {
                double secs = frames * FRAME_S;
                double[] sorted = kickHit.clone();
                Arrays.sort(sorted);
                best = new Result(track, secs, det.length / secs, ref.length / secs,
                        (double) covered / ref.length, p, r, f1, f1c, lag,
                        sorted[(int) (0.10 * (frames - 1))], sorted[(int) (0.50 * (frames - 1))],
                        sorted[(int) (0.90 * (frames - 1))]);
            }
        }
        return best;
    }

    private static double nearest(double t, double[] sorted) {
        double best = Double.MAX_VALUE;
        for (double r : sorted) best = Math.min(best, Math.abs(t - r));
        return best;
    }

    /** Greedy one-to-one matching of sorted detections to sorted references within the tolerance. */
    private static int match(double[] det, double[] ref) {
        int i = 0, j = 0, tp = 0;
        while (i < det.length && j < ref.length) {
            double d = det[i] - ref[j];
            if (Math.abs(d) <= TOLERANCE_S) { tp++; i++; j++; }
            else if (d < 0) i++;
            else j++;
        }
        return tp;
    }

    private static void print(List<Result> results) {
        System.out.println("track  beats/s  ref/s  ratio  ratioC prec   rec    F1     F1c    lag  kickHit p10/p50/p90");
        for (Result r : results) {
            System.out.println(String.format(Locale.ROOT,
                    "%-6d %-8.2f %-6.2f %-6.2f %-6.2f %-6.3f %-6.3f %-6.3f %-6.3f %3dms  %.2f/%.2f/%.2f",
                    r.track(), r.beatsPerSec(), r.refPerSec(), r.beatsPerSec() / r.refPerSec(), r.ratioCovered(),
                    r.precision(), r.recall(), r.f1(), r.f1Covered(), r.lagMs(), r.hit10(), r.hit50(), r.hit90()));
        }
        double meanF1 = results.stream().mapToDouble(Result::f1).average().orElse(0);
        double meanLag = results.stream().mapToDouble(Result::lagMs).average().orElse(0);
        double meanF1c = results.stream().mapToDouble(Result::f1Covered).average().orElse(0);
        double maxRatio = results.stream().mapToDouble(r -> r.beatsPerSec() / r.refPerSec()).max().orElse(0);
        double maxRatioC = results.stream().mapToDouble(Result::ratioCovered).max().orElse(0);
        System.out.println(String.format(Locale.ROOT,
                "mean F1 %.3f  mean F1c %.3f  mean best lag %.0f ms  max ratio %.2f  max ratioC %.2f",
                meanF1, meanF1c, meanLag, maxRatio, maxRatioC));
        System.out.println("ratioC and F1c ignore detections more than 2 s away from any reference onset (proxy gaps)");
    }
}
