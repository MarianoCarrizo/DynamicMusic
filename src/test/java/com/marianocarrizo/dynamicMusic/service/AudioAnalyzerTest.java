package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AudioAnalyzerTest {

    private static final long TICK_NS = 25_000_000L;
    private static final double TICK_S = 0.025;
    private static final float FLOOR_DB = -75f;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AudioAnalyzer analyzer = new AudioAnalyzer(clock::get);

    /** Frame of 256 bands: silent floor, steady tonal bed in bands 3-23, optional kick in bands 0-2. */
    private static float[] frame(boolean kick, float bedDb) {
        float[] f = new float[AudioAnalyzer.NUM_BANDS];
        Arrays.fill(f, FLOOR_DB);
        for (int i = 3; i < 24; i++) f[i] = bedDb;
        if (kick) for (int i = 0; i < 3; i++) f[i] = -8f;
        return f;
    }

    private AudioFeatures tick(float[] frame) {
        clock.addAndGet(TICK_NS);
        return analyzer.process(frame);
    }

    /** Result of a kick-over-bed simulation. */
    private record Run(int hits, long beats, double maxKickBetweenHits, double minTonal, double maxTonal) {}

    /** Kick every ~0.31 s for {@code seconds}; measures tonal steadiness and kick decay after a 4 s warmup. */
    private Run simulateKick(double seconds) {
        int ticks = (int) Math.round(seconds / TICK_S);
        int warmupTicks = 160; // 4 s: the adaptive peaks settle
        long beatsAtWarmup = 0;
        int hits = 0;
        double maxKickBetween = 0, minTonal = 1, maxTonal = 0;
        long lastHitTick = -1000;
        long prevSlot = -1;
        for (int k = 0; k < ticks; k++) {
            long slot = (long) Math.floor(k * TICK_S / 0.31);
            boolean hit = slot != prevSlot;
            prevSlot = slot;
            AudioFeatures f = tick(frame(hit, -30f));
            if (k == warmupTicks) beatsAtWarmup = f.beatCount();
            if (k >= warmupTicks) {
                if (hit) {
                    hits++;
                    lastHitTick = k;
                } else if (k - lastHitTick >= 9) {
                    // late in the gap, right before the next hit
                    maxKickBetween = Math.max(maxKickBetween, f.kick());
                }
                minTonal = Math.min(minTonal, f.tonal());
                maxTonal = Math.max(maxTonal, f.tonal());
            }
        }
        return new Run(hits, analyzer.current().beatCount() - beatsAtWarmup, maxKickBetween, minTonal, maxTonal);
    }

    @Test
    void periodicKickProducesRoughlyOneBeatPerHit() {
        Run run = simulateKick(20.0);

        assertTrue(run.hits() > 40, "sanity: enough hits simulated, got " + run.hits());
        assertTrue(run.beats() >= run.hits() * 0.8, "beats " + run.beats() + " for hits " + run.hits());
        assertTrue(run.beats() <= run.hits() * 1.1, "beats " + run.beats() + " for hits " + run.hits());
    }

    @Test
    void kickDropsNearZeroBetweenHitsWhileTonalStaysSteady() {
        Run run = simulateKick(20.0);

        assertTrue(run.maxKickBetweenHits() < 0.15,
                "kick level should be near zero before the next hit, was " + run.maxKickBetweenHits());
        assertTrue(run.minTonal() > 0.5, "tonal stays up, min " + run.minTonal());
        assertTrue(run.maxTonal() - run.minTonal() < 0.25,
                "tonal is steady, range " + run.minTonal() + ".." + run.maxTonal());
    }

    @Test
    void kickHitJumpsOnABeat() {
        double peakKickHit = 0;
        int ticks = 400;
        long prevSlot = -1;
        for (int k = 0; k < ticks; k++) {
            long slot = (long) Math.floor(k * TICK_S / 0.31);
            boolean hit = slot != prevSlot;
            prevSlot = slot;
            AudioFeatures f = tick(frame(hit, -30f));
            if (k > 160) peakKickHit = Math.max(peakKickHit, f.kickHit());
        }
        assertTrue(peakKickHit > 0.45, "kickHit should reach the onset strength, was " + peakKickHit);
    }

    @Test
    void steadyBedWithoutKickProducesNoBeats() {
        for (int k = 0; k < 400; k++) tick(frame(false, -30f));
        assertEquals(0, analyzer.current().beatCount());
    }

    @Test
    void silenceDecaysLevelsTowardZero() {
        Run ignored = simulateKick(10.0);
        AudioFeatures loud = analyzer.current();
        assertTrue(loud.energy() > 0.05, "sanity: signal present, energy " + loud.energy());

        float[] silence = new float[AudioAnalyzer.NUM_BANDS];
        Arrays.fill(silence, FLOOR_DB);
        AudioFeatures f = loud;
        for (int k = 0; k < 120; k++) f = tick(silence);

        assertEquals(0.0, f.bass(), 0.02);
        assertEquals(0.0, f.mid(), 0.02);
        assertEquals(0.0, f.high(), 0.02);
        assertEquals(0.0, f.energy(), 0.02);
        assertEquals(0.0, f.kick(), 0.02);
        assertEquals(0.0, f.snare(), 0.02);
        assertEquals(0.0, f.tonal(), 0.05);
        assertEquals(0.0, f.kickHit(), 0.02);
        assertEquals(0.0, f.beatStrength(), 0.02);
    }

    @Test
    void staleFeaturesDecayInCurrentWhenFramesStop() {
        simulateKick(10.0);
        AudioFeatures before = analyzer.current();
        assertTrue(before.energy() > 0.05);

        clock.addAndGet(2_000_000_000L); // 2 s without spectrum frames (paused)
        AudioFeatures after = analyzer.current();

        assertTrue(after.energy() < before.energy() * 0.2,
                "energy " + before.energy() + " -> " + after.energy());
        assertEquals(before.beatCount(), after.beatCount());
    }

    @Test
    void currentDoesNotDecayWhileFramesAreFresh() {
        simulateKick(5.0);
        AudioFeatures a = analyzer.current();
        clock.addAndGet(TICK_NS);
        AudioFeatures b = analyzer.current();
        assertEquals(a, b);
    }

    @Test
    void resetClearsStateButBeatCountStaysMonotonic() {
        simulateKick(10.0);
        AudioFeatures before = analyzer.current();
        assertTrue(before.beatCount() > 0);
        assertTrue(before.energy() > 0);

        analyzer.reset();
        AudioFeatures after = analyzer.current();

        assertEquals(before.beatCount(), after.beatCount());
        assertEquals(0.0, after.bass());
        assertEquals(0.0, after.mid());
        assertEquals(0.0, after.high());
        assertEquals(0.0, after.energy());
        assertEquals(0.0, after.kick());
        assertEquals(0.0, after.tonal());
        assertEquals(0.0, after.kickHit());
        assertEquals(0.0, after.beatStrength());
    }

    @Test
    void beatsAreSuppressedDuringWarmupAfterReset() {
        // warm up the adaptive state, then reset and immediately feed hits
        simulateKick(5.0);
        analyzer.reset();
        long base = analyzer.current().beatCount();
        boolean hit = true;
        for (int k = 0; k < 12; k++) {
            tick(frame(hit, -30f));
            hit = !hit;
        }
        assertEquals(base, analyzer.current().beatCount(), "no beats in the 12-tick warmup");
    }

    @Test
    void beatCountNeverDecreases() {
        long last = 0;
        long prevSlot = -1;
        for (int k = 0; k < 600; k++) {
            long slot = (long) Math.floor(k * TICK_S / 0.31);
            boolean hit = slot != prevSlot;
            prevSlot = slot;
            if (k == 300) analyzer.reset();
            long now = tick(frame(hit, -30f)).beatCount();
            assertTrue(now >= last, "beatCount went backwards at tick " + k);
            last = now;
        }
    }

    @Test
    void shortSpectrumArraysNeverThrow() {
        float[] shortFrame = new float[32];
        Arrays.fill(shortFrame, -20f);
        for (int k = 0; k < 200; k++) {
            float[] f = shortFrame.clone();
            if (k % 12 == 0) f[0] = f[1] = f[2] = -3f;
            AudioFeatures out = assertDoesNotThrow(() -> tick(f));
            assertFinite(out);
        }
        assertDoesNotThrow(() -> tick(new float[0]));
        assertDoesNotThrow(() -> tick(new float[1]));
        assertDoesNotThrow(() -> tick(new float[3]));
    }

    @Test
    void oversizedAndOutOfRangeInputIsHandled() {
        float[] big = new float[1024];
        Arrays.fill(big, 40f); // above 0 dBFS: clamped
        AudioFeatures a = assertDoesNotThrow(() -> tick(big));
        assertFinite(a);

        float[] low = new float[AudioAnalyzer.NUM_BANDS];
        Arrays.fill(low, -500f);
        assertFinite(assertDoesNotThrow(() -> tick(low)));

        float[] nan = new float[AudioAnalyzer.NUM_BANDS];
        Arrays.fill(nan, Float.NaN);
        // NaN is not a valid dB value, but it must not poison later frames permanently
        assertDoesNotThrow(() -> tick(nan));
    }

    @Test
    void allLevelsStayInUnitRange() {
        long prevSlot = -1;
        for (int k = 0; k < 800; k++) {
            long slot = (long) Math.floor(k * TICK_S / 0.31);
            boolean hit = slot != prevSlot;
            prevSlot = slot;
            AudioFeatures f = tick(frame(hit, -30f));
            for (double v : new double[]{f.bass(), f.mid(), f.high(), f.energy(), f.kick(), f.snare(),
                    f.tonal(), f.kickHit(), f.snareHit(), f.beatStrength()}) {
                assertTrue(v >= 0.0 && v <= 1.0, "level out of range: " + v + " at tick " + k);
            }
        }
    }

    @Test
    void scaledClampsAndKeepsBeatCount() {
        AudioFeatures f = new AudioFeatures(0.5, 0.6, 0.7, 0.8, 7, 0.9, 0.3, 1.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        AudioFeatures doubled = f.scaled(4.0);
        assertEquals(1.0, doubled.bass());
        assertEquals(1.0, doubled.energy());
        assertEquals(7, doubled.beatCount());
        assertEquals(0.3, doubled.rawBass());
        assertEquals(0.0, f.scaled(-1).bass());
        assertEquals(0.25, f.scaled(0.5).bass(), 1e-9);
    }

    private static void assertFinite(AudioFeatures f) {
        for (double v : new double[]{f.bass(), f.mid(), f.high(), f.energy(), f.beatStrength(), f.kick(),
                f.snare(), f.tonal(), f.kickHit(), f.snareHit(), f.rawBass()}) {
            assertTrue(Double.isFinite(v), "non-finite value in " + f);
        }
    }
}
