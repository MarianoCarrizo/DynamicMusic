package com.marianocarrizo.dynamicMusic.model;

import java.util.regex.Pattern;

/**
 * Visual aura settings for one slot of a scene.
 * Getters are null-safe so partial or hand-edited JSON never yields NPEs.
 */
public class AuraConfig {

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    private AuraPreset preset;
    private String primaryColor;    // "#RRGGBB"
    private String secondaryColor;  // "#RRGGBB"
    private double intensity = 0.5;   // 0..1
    private double reactivity = 0.5;  // 0..1
    private boolean vignette = true;
    private boolean edgeGlow = true;
    private boolean embers = true;
    private boolean backgroundPulse = true;

    /** No-arg constructor required by Gson. */
    public AuraConfig() {}

    public AuraPreset getPreset() { return preset != null ? preset : AuraPreset.NEUTRAL; }
    public void setPreset(AuraPreset preset) { this.preset = preset; }

    public String getPrimaryColor() {
        return isHex(primaryColor) ? primaryColor : getPreset().defaultPrimary();
    }
    public void setPrimaryColor(String c) { this.primaryColor = isHex(c) ? c : null; }

    public String getSecondaryColor() {
        return isHex(secondaryColor) ? secondaryColor : getPreset().defaultSecondary();
    }
    public void setSecondaryColor(String c) { this.secondaryColor = isHex(c) ? c : null; }

    public double getIntensity() { return clamp01(intensity); }
    public void setIntensity(double v) { this.intensity = clamp01(v); }

    public double getReactivity() { return clamp01(reactivity); }
    public void setReactivity(double v) { this.reactivity = clamp01(v); }

    public boolean isVignette() { return vignette; }
    public void setVignette(boolean v) { this.vignette = v; }

    public boolean isEdgeGlow() { return edgeGlow; }
    public void setEdgeGlow(boolean v) { this.edgeGlow = v; }

    public boolean isEmbers() { return embers; }
    public void setEmbers(boolean v) { this.embers = v; }

    public boolean isBackgroundPulse() { return backgroundPulse; }
    public void setBackgroundPulse(boolean v) { this.backgroundPulse = v; }

    public AuraConfig copy() {
        AuraConfig c = new AuraConfig();
        c.preset = preset;
        c.primaryColor = primaryColor;
        c.secondaryColor = secondaryColor;
        c.intensity = intensity;
        c.reactivity = reactivity;
        c.vignette = vignette;
        c.edgeGlow = edgeGlow;
        c.embers = embers;
        c.backgroundPulse = backgroundPulse;
        return c;
    }

    private static boolean isHex(String s) { return s != null && HEX.matcher(s).matches(); }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
