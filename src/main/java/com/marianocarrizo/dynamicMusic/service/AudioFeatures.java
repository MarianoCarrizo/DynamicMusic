package com.marianocarrizo.dynamicMusic.service;

/**
 * Immutable snapshot of the analyzed audio. All levels are normalized to 0..1.
 *
 * @param bass         smoothed, adaptively normalized low band level
 * @param mid          smoothed, adaptively normalized mid band level
 * @param high         smoothed, adaptively normalized high band level
 * @param energy       weighted overall level
 * @param beatCount    monotonically increasing; a change means a new beat happened
 * @param beatStrength strength of the most recent beat, decaying towards 0
 * @param rawBass      normalized bass before envelope smoothing (diagnostics)
 * @param mediaTime    media timestamp of the spectrum frame in seconds (diagnostics)
 * @param kick         percussive energy of ~0-260 Hz (kick drum), fast envelope
 * @param snare        percussive energy of ~1.5-5 kHz (snare, hats), fast envelope
 * @param tonal        tonal (non-percussive) energy of ~260 Hz-2 kHz, slow envelope ("breathing")
 * @param kickHit      kick onset punch: jumps on a hit, decays in ~150 ms
 * @param snareHit     snare onset punch: jumps on a hit, decays in ~120 ms
 */
public record AudioFeatures(double bass, double mid, double high, double energy,
                            long beatCount, double beatStrength,
                            double rawBass, double mediaTime,
                            double kick, double snare, double tonal,
                            double kickHit, double snareHit) {

    public static final AudioFeatures SILENT = new AudioFeatures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Returns a copy with levels multiplied by gain and clamped to 0..1. beatCount is untouched. */
    public AudioFeatures scaled(double gain) {
        return new AudioFeatures(clamp(bass * gain), clamp(mid * gain), clamp(high * gain),
                clamp(energy * gain), beatCount, clamp(beatStrength * gain), rawBass, mediaTime,
                clamp(kick * gain), clamp(snare * gain), clamp(tonal * gain),
                clamp(kickHit * gain), clamp(snareHit * gain));
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1.0, v);
    }
}
