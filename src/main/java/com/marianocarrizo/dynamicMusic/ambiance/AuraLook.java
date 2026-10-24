package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import javafx.scene.paint.Color;

import java.util.EnumMap;
import java.util.Map;

final class AuraLook {

    static final int PR = 0, PG = 1, PB = 2;
    static final int SR = 3, SG = 4, SB = 5;
    static final int INTENSITY = 6;
    static final int REACTIVITY = 7;
    static final int W_VIGNETTE = 8;
    static final int W_GLOW = 9;
    static final int W_EMBERS = 10;
    static final int W_PULSE = 11;
    static final int V_BASE = 12;
    static final int V_BREATH = 13;
    static final int V_RIM_DARKEN = 14;
    static final int G_BASE = 15;
    static final int G_ENERGY = 16;
    static final int BEAT_FLASH = 17;
    static final int BEAT_DECAY = 18;
    static final int EDGE_LINE = 19;
    static final int E_SPEED = 20;
    static final int E_FLICKER = 21;
    static final int E_SIZE = 22;
    static final int E_SWAY = 23;
    static final int E_SECONDARY = 24;
    static final int E_ALPHA = 25;
    static final int MOTION = 26;
    static final int PERC = 27;
    static final int SIZE = 28;

    private static final double CALM_MOTION = 0.7;
    private static final double CALM_PERC = 0.10;
    private static final double CALM_BEAT_FLASH = 0.5;
    private static final double CALM_EDGE_LINE = 0.0;
    private static final double CALM_EMBER_SPEED = 0.55;
    private static final double CALM_EMBER_FLICKER = 0.4;
    private static final double CALM_GLOW_ENERGY = 0.8;
    private static final double CALM_VIGNETTE_BREATH = 0.8;

    private record Flavor(double vBase, double vBreath, double rimDarken,
                          double gBase, double gEnergy, double beatFlash, double beatDecay, double edgeLine,
                          double eSpeed, double eFlicker, double eSize, double eSway,
                          double eSecondary, double eAlpha) {}

    private static final Map<AuraPreset, Flavor> FLAVORS = new EnumMap<>(AuraPreset.class);
    static {
        FLAVORS.put(AuraPreset.FIRE,     new Flavor(0.34, 0.26, 0.70, 0.30, 0.45, 0.22, 0.22, 0.15, 1.50, 0.70, 1.00, 1.00, 0.55, 0.90));
        FLAVORS.put(AuraPreset.SHADOW,   new Flavor(0.55, 0.22, 0.85, 0.12, 0.35, 0.10, 0.30, 0.00, 0.55, 0.25, 1.20, 1.30, 0.70, 0.65));
        FLAVORS.put(AuraPreset.ELECTRIC, new Flavor(0.30, 0.24, 0.70, 0.22, 0.45, 0.55, 0.10, 0.90, 1.10, 0.85, 0.80, 0.40, 0.40, 0.90));
        FLAVORS.put(AuraPreset.HOLY,     new Flavor(0.18, 0.18, 0.35, 0.30, 0.45, 0.12, 0.35, 0.10, 0.70, 0.20, 1.10, 1.00, 0.50, 0.85));
        FLAVORS.put(AuraPreset.NEUTRAL,  new Flavor(0.28, 0.20, 0.80, 0.12, 0.35, 0.08, 0.30, 0.00, 0.60, 0.15, 1.00, 0.70, 0.30, 0.80));
    }

    private AuraLook() {}

    static double[] resolve(AuraConfig aura, boolean calm) {
        double[] v = resolve(aura);
        if (calm) {
            v[MOTION]    *= CALM_MOTION;
            v[PERC]      *= CALM_PERC;
            v[BEAT_FLASH] *= CALM_BEAT_FLASH;
            v[EDGE_LINE] *= CALM_EDGE_LINE;
            v[E_SPEED]   *= CALM_EMBER_SPEED;
            v[E_FLICKER] *= CALM_EMBER_FLICKER;
            v[G_ENERGY]  *= CALM_GLOW_ENERGY;
            v[V_BREATH]  *= CALM_VIGNETTE_BREATH;
        }
        return v;
    }

    static double[] resolve(AuraConfig aura) {
        double[] v = new double[SIZE];
        v[MOTION] = 1.0;
        v[PERC] = 1.0;
        if (aura == null) aura = AuraPreset.NEUTRAL.defaults();
        Flavor f = FLAVORS.get(aura.getPreset());

        Color p = Color.web(aura.getPrimaryColor());
        Color s = Color.web(aura.getSecondaryColor());
        v[PR] = p.getRed();  v[PG] = p.getGreen();  v[PB] = p.getBlue();
        v[SR] = s.getRed();  v[SG] = s.getGreen();  v[SB] = s.getBlue();
        v[INTENSITY]  = aura.getIntensity();
        v[REACTIVITY] = aura.getReactivity();
        v[W_VIGNETTE] = aura.isVignette() ? 1 : 0;
        v[W_GLOW]     = aura.isEdgeGlow() ? 1 : 0;
        v[W_EMBERS]   = aura.isEmbers() ? 1 : 0;
        v[W_PULSE]    = aura.isBackgroundPulse() ? 1 : 0;

        v[V_BASE] = f.vBase;           v[V_BREATH] = f.vBreath;  v[V_RIM_DARKEN] = f.rimDarken;
        v[G_BASE] = f.gBase;           v[G_ENERGY] = f.gEnergy;
        v[BEAT_FLASH] = f.beatFlash;   v[BEAT_DECAY] = f.beatDecay;  v[EDGE_LINE] = f.edgeLine;
        v[E_SPEED] = f.eSpeed;         v[E_FLICKER] = f.eFlicker;    v[E_SIZE] = f.eSize;
        v[E_SWAY] = f.eSway;           v[E_SECONDARY] = f.eSecondary; v[E_ALPHA] = f.eAlpha;
        return v;
    }
}
