package com.marianocarrizo.dynamicMusic.service;

public record AudioFeatures(double bass, double mid, double high, double energy,
                            long beatCount, double beatStrength,
                            double rawBass, double mediaTime,
                            double kick, double snare, double tonal,
                            double kickHit, double snareHit) {

    public static final AudioFeatures SILENT = new AudioFeatures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

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
