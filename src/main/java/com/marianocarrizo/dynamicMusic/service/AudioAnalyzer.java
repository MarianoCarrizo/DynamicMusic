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

    /*
     * Band split over the JavaFX spectrum, which uses equal-width LINEAR bands from 0 Hz to
     * Nyquist. With 32 bands each one was ~690 Hz wide, so "bass" (bands 0-3) covered
     * 0-2.7 kHz: the kick was only ~15% of it and the level barely moved. With 256 bands
     * (~86 Hz each at 44.1 kHz) the split below follows real frequency ranges.
     */
    public static final int NUM_BANDS = 256;
    private static final int BASS_END = 3;    // ~0-260 Hz: kick and bass guitar
    private static final int MID_END  = 24;   // ~260 Hz-2 kHz: body of the mix
    private static final int HIGH_END = 94;   // ~2-8 kHz: snare crack, cymbals; above is mostly air

    /*
     * Percussive/tonal split (causal HPSS, after FitzGerald 2010). Band index = freq / 86.13 Hz,
     * assuming a 44.1 kHz source (Nyquist 22050 Hz over 256 bands), same as the constants above.
     */
    private static final int KICK_END    = BASS_END;  // bands 0-2: ~0-260 Hz
    private static final int SNARE_START = 17;        // ~1.5 kHz
    private static final int SNARE_END   = 59;        // ~5 kHz (exclusive)
    private static final int TONAL_START = BASS_END;  // ~260 Hz
    private static final int TONAL_END   = MID_END;   // ~2 kHz (exclusive)
    private static final int HPSS_FRAMES = 9;         // harmonic median over time: ~225 ms
    private static final int HPSS_HALF_WIDTH = 8;     // percussive median over frequency: 17 bands
    private static final double HPSS_EPS = 1e-12;
    // Per-feature rolling-peak floors (kick, snare, tonal); percussive parts are quiet
    private static final double[] PF_FLOOR = {0.004, 0.0006, 0.002};
    private static final double TONAL_ATTACK  = 1 - Math.exp(-0.025 / 0.3);  // ~0.3 s
    private static final double TONAL_RELEASE = 1 - Math.exp(-0.025 / 0.8);  // ~0.8 s
    // Hit envelopes: jump to the onset strength, then decay with this time constant
    private static final double KICK_HIT_DECAY  = Math.exp(-0.025 / 0.150);
    private static final double SNARE_HIT_DECAY = Math.exp(-0.025 / 0.120);
    private static final long   SNARE_REFRACTORY_NS = 100_000_000L;
    private static final double MIN_PERC_LEVEL = 0.20;  // shaped level an onset must reach

    // Linear magnitude below this is treated as silence (-60 dB reads as 0.001)
    private static final double NOISE_GATE = 0.0004;  // ~-68 dB; spectrum threshold is -75 dB
    // Lowest value the rolling peak may decay to, so quiet noise is not blown up to 1.0
    // Per band (bass, mid, high): the high band is naturally ~20 dB quieter, and a shared floor
    // of 0.006 kept it pinned near 0 (measured p50 0.02), so embers never flickered
    private static final double[] PEAK_FLOOR = {0.006, 0.003, 0.0008};
    // Per-tick (25 ms) peak decay: half-life of roughly 2.3 seconds, so quiet passages after
    // a loud hit are normalized up again quickly instead of staying flat for ~4 s
    private static final double PEAK_DECAY = 0.9925;
    // Shapes the normalized value; lower = mid-level music still moves visibly
    private static final double SHAPE_EXPONENT = 1.25;

    // Envelope smoothing per tick. Kept light: the renderer smooths again, and stacking two
    // slow envelopes made the visuals lag the music
    private static final double ATTACK        = 0.85;
    private static final double RELEASE       = 0.30;  // ~70 ms: bass must dip between kicks at ~190 bpm
    private static final double RELEASE_STALE = 0.10;

    // Beat detection
    private static final int    HISTORY_SIZE   = 40;      // ~1 s of flux values
    private static final double THRESHOLD_K    = 1.25;    // mean + K * stddev
    private static final double MIN_FLUX       = 0.06;    // absolute minimum jump to count as a beat
    private static final long   REFRACTORY_NS  = 140_000_000L;
    private static final double BEAT_DECAY     = 0.905;   // per tick (0.82 per 50 ms)
    private static final int    WARMUP_TICKS   = 12;      // ignore beats right after a reset

    // Must match MusicService's spectrum interval
    private static final long TICK_NS  = 25_000_000L;
    private static final long STALE_NS = 120_000_000L;

    private final double[] peak = new double[3];
    private final double[] env  = new double[3];
    private final Onset kickOnset = new Onset(REFRACTORY_NS);
    private final Onset snareOnset = new Onset(SNARE_REFRACTORY_NS);
    private long lastTickNs;
    // HPSS state: ring of the last frames (linear magnitudes) and scratch arrays, all preallocated
    private final double[][] frames = new double[HPSS_FRAMES][HIGH_END];
    private int frameHead;
    private int frameFill;
    private final double[] cur = new double[HIGH_END];
    private final double[] perc = new double[HIGH_END];
    private final double[] medBuf = new double[Math.max(HPSS_FRAMES, 2 * HPSS_HALF_WIDTH + 1)];
    // kick, snare, tonal: rolling peak and smoothed level
    private final double[] pfPeak = new double[3];
    private final double[] pfEnv = new double[3];
    private double kickHit, snareHit;
    private long beatCount;
    private double beatStrength;
    private int warmup;

    // Diagnostics only: last shaped (pre-envelope) bass and spectrum timestamp
    private double lastRawBass = 0;
    private double lastMediaTime = 0;

    private volatile AudioFeatures snapshot = AudioFeatures.SILENT;

    public AudioAnalyzer() {
        reset();
    }

    /** Clears all adaptive state (call on track change). The beat counter stays monotonic. */
    public synchronized void reset() {
        for (int i = 0; i < 3; i++) { peak[i] = PEAK_FLOOR[i]; env[i] = 0; }
        kickOnset.reset();
        snareOnset.reset();
        for (int i = 0; i < 3; i++) { pfPeak[i] = PF_FLOOR[i]; pfEnv[i] = 0; }
        frameFill = 0;
        frameHead = 0;
        kickHit = 0;
        snareHit = 0;
        beatStrength = 0;
        warmup = WARMUP_TICKS;
        lastTickNs = System.nanoTime();
        publish();
    }

    /** Feeds one spectrum frame (dBFS per band). Returns the new snapshot. */
    public synchronized AudioFeatures process(float[] magnitudes) {
        return process(magnitudes, lastMediaTime);
    }

    /** Feeds one spectrum frame with its media timestamp (seconds), kept for diagnostics. */
    public synchronized AudioFeatures process(float[] magnitudes, double mediaTime) {
        lastMediaTime = mediaTime;
        long now = System.nanoTime();
        lastTickNs = now;

        double[] raw = new double[3];
        int[] counts = new int[3];
        int n = Math.min(magnitudes.length, HIGH_END);
        java.util.Arrays.fill(cur, 0.0);
        for (int i = 0; i < n; i++) {
            double db = Math.max(-75.0, Math.min(0.0, magnitudes[i]));
            double lin = Math.max(0.0, Math.pow(10.0, db / 20.0) - NOISE_GATE);
            cur[i] = lin;
            int band = i < BASS_END ? 0 : (i < MID_END ? 1 : 2);
            raw[band] += lin;
            counts[band]++;
        }

        double[] shaped = new double[3];
        for (int b = 0; b < 3; b++) {
            double v = counts[b] > 0 ? raw[b] / counts[b] : 0.0;
            peak[b] = Math.max(PEAK_FLOOR[b], Math.max(v, peak[b] * PEAK_DECAY));
            shaped[b] = Math.pow(Math.min(1.0, v / peak[b]), SHAPE_EXPONENT);
            double k = shaped[b] > env[b] ? ATTACK : RELEASE;
            env[b] += (shaped[b] - env[b]) * k;
        }

        lastRawBass = shaped[0];
        analyzePercussion(now);
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
                for (int b = 0; b < 3; b++) {
                    env[b] *= (1.0 - RELEASE_STALE);
                    pfEnv[b] *= (1.0 - RELEASE_STALE);
                }
                beatStrength *= BEAT_DECAY;
                kickHit *= KICK_HIT_DECAY;
                snareHit *= SNARE_HIT_DECAY;
            }
            lastTickNs += ticks * TICK_NS;
            publish();
        }
        return snapshot;
    }

    /**
     * Causal HPSS on the current frame: harmonic = median over the last frames per band,
     * percussive = median over neighbouring bands of this frame; soft mask P^2/(H^2+P^2).
     * Then derives the kick/snare/tonal features and their onsets.
     */
    private void analyzePercussion(long now) {
        System.arraycopy(cur, 0, frames[frameHead], 0, HIGH_END);
        frameHead = (frameHead + 1) % HPSS_FRAMES;
        if (frameFill < HPSS_FRAMES) frameFill++;

        double kickSum = 0, snareSum = 0, tonalSum = 0;
        for (int b = 0; b < HIGH_END; b++) {
            double m = cur[b];
            double e = 0;
            if (m > 0) {
                for (int t = 0; t < frameFill; t++) medBuf[t] = frames[t][b];
                double hMed = median(medBuf, frameFill);
                int lo = Math.max(0, b - HPSS_HALF_WIDTH), hi = Math.min(HIGH_END - 1, b + HPSS_HALF_WIDTH);
                int cnt = 0;
                for (int k = lo; k <= hi; k++) medBuf[cnt++] = cur[k];
                double pMed = median(medBuf, cnt);
                e = m * (pMed * pMed) / (hMed * hMed + pMed * pMed + HPSS_EPS);
            }
            perc[b] = e;
            if (b < KICK_END) kickSum += e;
            if (b >= SNARE_START && b < SNARE_END) snareSum += e;
            if (b >= TONAL_START && b < TONAL_END) tonalSum += m - e;
        }

        double kickS = normalizePf(0, kickSum / KICK_END);
        double snareS = normalizePf(1, snareSum / (SNARE_END - SNARE_START));
        double tonalS = normalizePf(2, tonalSum / (TONAL_END - TONAL_START));
        pfEnv[0] += (kickS - pfEnv[0]) * (kickS > pfEnv[0] ? ATTACK : RELEASE);
        pfEnv[1] += (snareS - pfEnv[1]) * (snareS > pfEnv[1] ? ATTACK : RELEASE);
        pfEnv[2] += (tonalS - pfEnv[2]) * (tonalS > pfEnv[2] ? TONAL_ATTACK : TONAL_RELEASE);

        double kickStrength = kickOnset.check(kickS, now, warmup <= 0);
        if (kickStrength > 0) {
            beatCount++;
            beatStrength = kickStrength;
            kickHit = Math.max(kickHit * KICK_HIT_DECAY, kickStrength);
        } else {
            beatStrength *= BEAT_DECAY;
            kickHit *= KICK_HIT_DECAY;
        }
        double snareStrength = snareOnset.check(snareS, now, warmup <= 0);
        snareHit = snareStrength > 0 ? Math.max(snareHit * SNARE_HIT_DECAY, snareStrength)
                : snareHit * SNARE_HIT_DECAY;
        if (warmup > 0) warmup--;
    }

    private double normalizePf(int i, double v) {
        pfPeak[i] = Math.max(PF_FLOOR[i], Math.max(v, pfPeak[i] * PEAK_DECAY));
        return Math.pow(Math.min(1.0, v / pfPeak[i]), SHAPE_EXPONENT);
    }

    /** Median of the first n values (insertion sort, in place; n is at most 17). */
    private static double median(double[] a, int n) {
        for (int i = 1; i < n; i++) {
            double x = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > x) { a[j + 1] = a[j]; j--; }
            a[j + 1] = x;
        }
        return (n & 1) == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    /** Spectral-flux onset detector on a 0..1 level: adaptive threshold mean + K*std. */
    private static final class Onset {
        private final double[] history = new double[HISTORY_SIZE];
        private final long refractoryNs;
        private int count, index;
        private double prev;
        private long lastNs;

        Onset(long refractoryNs) { this.refractoryNs = refractoryNs; }

        void reset() { count = 0; index = 0; prev = 0; lastNs = 0; }

        /** Returns the onset strength (0.45..1) or 0 when there is no onset. */
        double check(double level, long now, boolean armed) {
            double flux = Math.max(0.0, level - prev);
            prev = level;
            double mean = 0, var = 0;
            for (int i = 0; i < count; i++) mean += history[i];
            if (count > 0) mean /= count;
            for (int i = 0; i < count; i++) {
                double d = history[i] - mean;
                var += d * d;
            }
            double std = count > 0 ? Math.sqrt(var / count) : 0.0;
            double threshold = Math.max(MIN_FLUX, mean + THRESHOLD_K * std);
            double strength = 0;
            if (armed && flux > threshold && level > MIN_PERC_LEVEL && now - lastNs > refractoryNs) {
                lastNs = now;
                strength = Math.min(1.0, 0.45 + (flux - threshold) * 2.5 + level * 0.3);
            }
            history[index] = flux;
            index = (index + 1) % HISTORY_SIZE;
            if (count < HISTORY_SIZE) count++;
            return strength;
        }
    }

    private AudioFeatures publish() {
        double energy = 0.5 * env[0] + 0.3 * env[1] + 0.2 * env[2];
        AudioFeatures f = new AudioFeatures(env[0], env[1], env[2], energy, beatCount, beatStrength,
                lastRawBass, lastMediaTime,
                pfEnv[0], pfEnv[1], pfEnv[2], kickHit, snareHit);
        snapshot = f;
        return f;
    }
}
