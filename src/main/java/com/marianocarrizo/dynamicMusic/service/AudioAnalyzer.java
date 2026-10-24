package com.marianocarrizo.dynamicMusic.service;

/**
 * Turns raw spectrum bands (dBFS) into stable, audio-reactive features:
 * three bands with adaptive normalization, attack/release envelopes and a
 * bass onset (beat) detector based on spectral flux against an adaptive threshold.
 *
 * process() is called from the media thread (one call per spectrum interval),
 * current() may be called from any thread (typically the FX thread each frame).
 */
public final class AudioAnalyzer {

    // Band split (indices into the 32 spectrum bands, end exclusive)
    private static final int BASS_END = 4;
    private static final int MID_END  = 13;

    // Linear magnitude below this is treated as digital silence (-60 dB reads as 0.001)
    private static final double NOISE_GATE = 0.0012;
    // Lowest value the rolling peak may decay to, so quiet noise is not blown up to 1.0
    private static final double PEAK_FLOOR = 0.006;
    // Per-tick (50 ms) peak decay: half-life of roughly 4 seconds
    private static final double PEAK_DECAY = 0.992;
    // Shapes the normalized value to widen the dynamic range
    private static final double SHAPE_EXPONENT = 1.6;

    // Envelope smoothing per tick
    private static final double ATTACK        = 0.6;
    private static final double RELEASE       = 0.08;
    private static final double RELEASE_STALE = 0.18;

    // Beat detection
    private static final int    HISTORY_SIZE   = 20;      // ~1 s of flux values
    private static final double THRESHOLD_K    = 1.4;     // mean + K * stddev
    private static final double MIN_FLUX       = 0.07;    // absolute minimum jump to count as a beat
    private static final double MIN_BASS_LEVEL = 0.3;
    private static final long   REFRACTORY_NS  = 150_000_000L;
    private static final double BEAT_DECAY     = 0.82;    // per tick
    private static final int    WARMUP_TICKS   = 6;       // ignore beats right after a reset

    private static final long TICK_NS  = 50_000_000L;
    private static final long STALE_NS = 120_000_000L;

    private final double[] peak = new double[3];
    private final double[] env  = new double[3];
    private final double[] fluxHistory = new double[HISTORY_SIZE];
    private int fluxCount;
    private int fluxIndex;
    private double prevBass;
    private long lastBeatNs;
    private long lastTickNs;
    private long beatCount;
    private double beatStrength;
    private int warmup;

    private volatile AudioFeatures snapshot = AudioFeatures.SILENT;

    public AudioAnalyzer() {
        reset();
    }

    /** Clears all adaptive state (call on track change). The beat counter stays monotonic. */
    public synchronized void reset() {
        for (int i = 0; i < 3; i++) { peak[i] = PEAK_FLOOR; env[i] = 0; }
        fluxCount = 0;
        fluxIndex = 0;
        prevBass = 0;
        beatStrength = 0;
        warmup = WARMUP_TICKS;
        lastBeatNs = 0;
        lastTickNs = System.nanoTime();
        publish();
    }

    /** Feeds one spectrum frame (dBFS per band). Returns the new snapshot. */
    public synchronized AudioFeatures process(float[] magnitudes) {
        long now = System.nanoTime();
        lastTickNs = now;

        double[] raw = new double[3];
        int[] counts = new int[3];
        int n = Math.min(magnitudes.length, 32);
        for (int i = 0; i < n; i++) {
            double db = Math.max(-60.0, Math.min(0.0, magnitudes[i]));
            double lin = Math.max(0.0, Math.pow(10.0, db / 20.0) - NOISE_GATE);
            int band = i < BASS_END ? 0 : (i < MID_END ? 1 : 2);
            raw[band] += lin;
            counts[band]++;
        }

        double[] shaped = new double[3];
        for (int b = 0; b < 3; b++) {
            double v = counts[b] > 0 ? raw[b] / counts[b] : 0.0;
            peak[b] = Math.max(PEAK_FLOOR, Math.max(v, peak[b] * PEAK_DECAY));
            shaped[b] = Math.pow(Math.min(1.0, v / peak[b]), SHAPE_EXPONENT);
            double k = shaped[b] > env[b] ? ATTACK : RELEASE;
            env[b] += (shaped[b] - env[b]) * k;
        }

        detectBeat(shaped[0], now);
        return publish();
    }

    /**
     * Latest snapshot. If no spectrum frames arrived recently (paused, stopped),
     * levels are decayed here so the visuals fade out instead of freezing.
     */
    public synchronized AudioFeatures current() {
        long now = System.nanoTime();
        if (now - lastTickNs > STALE_NS) {
            long ticks = Math.min(40, (now - lastTickNs) / TICK_NS);
            for (long t = 0; t < ticks; t++) {
                for (int b = 0; b < 3; b++) env[b] *= (1.0 - RELEASE_STALE);
                beatStrength *= BEAT_DECAY;
            }
            lastTickNs += ticks * TICK_NS;
            publish();
        }
        return snapshot;
    }

    private void detectBeat(double bassShaped, long now) {
        double flux = Math.max(0.0, bassShaped - prevBass);
        prevBass = bassShaped;

        double mean = 0, var = 0;
        for (int i = 0; i < fluxCount; i++) mean += fluxHistory[i];
        if (fluxCount > 0) mean /= fluxCount;
        for (int i = 0; i < fluxCount; i++) {
            double d = fluxHistory[i] - mean;
            var += d * d;
        }
        double std = fluxCount > 0 ? Math.sqrt(var / fluxCount) : 0.0;
        double threshold = Math.max(MIN_FLUX, mean + THRESHOLD_K * std);

        boolean beat = warmup <= 0
                && flux > threshold
                && bassShaped > MIN_BASS_LEVEL
                && now - lastBeatNs > REFRACTORY_NS;
        if (beat) {
            lastBeatNs = now;
            beatCount++;
            beatStrength = Math.min(1.0, 0.45 + (flux - threshold) * 2.5 + bassShaped * 0.3);
        } else {
            beatStrength *= BEAT_DECAY;
        }
        if (warmup > 0) warmup--;

        fluxHistory[fluxIndex] = flux;
        fluxIndex = (fluxIndex + 1) % HISTORY_SIZE;
        if (fluxCount < HISTORY_SIZE) fluxCount++;
    }

    private AudioFeatures publish() {
        double energy = 0.5 * env[0] + 0.3 * env[1] + 0.2 * env[2];
        AudioFeatures f = new AudioFeatures(env[0], env[1], env[2], energy, beatCount, beatStrength);
        snapshot = f;
        return f;
    }
}
