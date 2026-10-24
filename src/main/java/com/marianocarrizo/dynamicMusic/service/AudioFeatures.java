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
 */
public record AudioFeatures(double bass, double mid, double high, double energy,
                            long beatCount, double beatStrength) {

    public static final AudioFeatures SILENT = new AudioFeatures(0, 0, 0, 0, 0, 0);

    /** Returns a copy with levels multiplied by gain and clamped to 0..1. beatCount is untouched. */
    public AudioFeatures scaled(double gain) {
        return new AudioFeatures(clamp(bass * gain), clamp(mid * gain), clamp(high * gain),
                clamp(energy * gain), beatCount, clamp(beatStrength * gain));
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1.0, v);
    }
}
