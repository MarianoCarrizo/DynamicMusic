package com.marianocarrizo.dynamicMusic.model;

public enum AuraPreset {
    FIRE("#FF5A1F", "#7A1100"),
    SHADOW("#8B6CFF", "#0B0614"),
    ELECTRIC("#4FC3FF", "#0A2A4A"),
    HOLY("#FFD27A", "#FFF4D6"),
    NEUTRAL("#9AA4B2", "#1A1D22");

    private final String primary;
    private final String secondary;

    AuraPreset(String primary, String secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    public String defaultPrimary()   { return primary; }
    public String defaultSecondary() { return secondary; }

    public AuraConfig defaults() {
        AuraConfig c = new AuraConfig();
        c.setPreset(this);
        c.setPrimaryColor(primary);
        c.setSecondaryColor(secondary);
        if (this == NEUTRAL) {
            c.setIntensity(0.3);
            c.setEmbers(false);
        }
        return c;
    }
}
