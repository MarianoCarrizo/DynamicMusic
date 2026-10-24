package com.marianocarrizo.dynamicMusic.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraConfigTest {

    @Test
    void intensityAndReactivityAreClampedOnSet() {
        AuraConfig c = new AuraConfig();
        c.setIntensity(5);
        c.setReactivity(-2);
        assertEquals(1.0, c.getIntensity());
        assertEquals(0.0, c.getReactivity());

        c.setIntensity(0.25);
        c.setReactivity(0.75);
        assertEquals(0.25, c.getIntensity());
        assertEquals(0.75, c.getReactivity());
    }

    @Test
    void nanFallsBackToHalf() {
        AuraConfig c = new AuraConfig();
        c.setIntensity(Double.NaN);
        c.setReactivity(Double.NaN);
        assertEquals(0.5, c.getIntensity());
        assertEquals(0.5, c.getReactivity());
    }

    @Test
    void infinitiesAreClamped() {
        AuraConfig c = new AuraConfig();
        c.setIntensity(Double.POSITIVE_INFINITY);
        c.setReactivity(Double.NEGATIVE_INFINITY);
        assertEquals(1.0, c.getIntensity());
        assertEquals(0.0, c.getReactivity());
    }

    @Test
    void invalidColorsFallBackToPresetColors() {
        AuraConfig c = AuraPreset.FIRE.defaults();
        c.setPrimaryColor("red");
        c.setSecondaryColor("#12345");
        assertEquals(AuraPreset.FIRE.defaultPrimary(), c.getPrimaryColor());
        assertEquals(AuraPreset.FIRE.defaultSecondary(), c.getSecondaryColor());

        c.setPrimaryColor(null);
        assertEquals(AuraPreset.FIRE.defaultPrimary(), c.getPrimaryColor());
    }

    @Test
    void validColorsAreKept() {
        AuraConfig c = new AuraConfig();
        c.setPrimaryColor("#00ff7F");
        c.setSecondaryColor("#ABCDEF");
        assertEquals("#00ff7F", c.getPrimaryColor());
        assertEquals("#ABCDEF", c.getSecondaryColor());
    }

    @Test
    void invalidColorResetsPreviouslySetColor() {
        AuraConfig c = AuraPreset.HOLY.defaults();
        c.setPrimaryColor("#000000");
        c.setPrimaryColor("nope");
        assertEquals(AuraPreset.HOLY.defaultPrimary(), c.getPrimaryColor());
    }

    @Test
    void missingPresetIsNeutral() {
        AuraConfig c = new AuraConfig();
        assertEquals(AuraPreset.NEUTRAL, c.getPreset());
        assertEquals(AuraPreset.NEUTRAL.defaultPrimary(), c.getPrimaryColor());
    }

    @Test
    void colorsFollowThePresetUntilOverridden() {
        AuraConfig c = new AuraConfig();
        c.setPreset(AuraPreset.ELECTRIC);
        assertEquals(AuraPreset.ELECTRIC.defaultPrimary(), c.getPrimaryColor());
        c.setPreset(AuraPreset.SHADOW);
        assertEquals(AuraPreset.SHADOW.defaultSecondary(), c.getSecondaryColor());
    }

    @Test
    void presetDefaultsSeedColorsAndNeutralIsCalmer() {
        for (AuraPreset p : AuraPreset.values()) {
            AuraConfig c = p.defaults();
            assertEquals(p, c.getPreset());
            assertEquals(p.defaultPrimary(), c.getPrimaryColor());
            assertEquals(p.defaultSecondary(), c.getSecondaryColor());
            assertTrue(p.defaultPrimary().matches("#[0-9A-Fa-f]{6}"));
            assertTrue(p.defaultSecondary().matches("#[0-9A-Fa-f]{6}"));
        }
        AuraConfig neutral = AuraPreset.NEUTRAL.defaults();
        assertEquals(0.3, neutral.getIntensity());
        assertFalse(neutral.isEmbers());
        assertTrue(AuraPreset.FIRE.defaults().isEmbers());
        assertEquals(0.5, AuraPreset.FIRE.defaults().getIntensity());
    }

    @Test
    void copyIsDeepAndIndependent() {
        AuraConfig a = AuraPreset.FIRE.defaults();
        a.setIntensity(0.9);
        a.setReactivity(0.1);
        a.setPrimaryColor("#112233");
        a.setVignette(false);
        a.setEdgeGlow(false);
        a.setEmbers(false);
        a.setBackgroundPulse(false);

        AuraConfig b = a.copy();

        assertNotSame(a, b);
        assertEquals(a.getPreset(), b.getPreset());
        assertEquals("#112233", b.getPrimaryColor());
        assertEquals(a.getSecondaryColor(), b.getSecondaryColor());
        assertEquals(0.9, b.getIntensity());
        assertEquals(0.1, b.getReactivity());
        assertFalse(b.isVignette());
        assertFalse(b.isEdgeGlow());
        assertFalse(b.isEmbers());
        assertFalse(b.isBackgroundPulse());

        b.setIntensity(0.2);
        b.setPreset(AuraPreset.HOLY);
        assertEquals(0.9, a.getIntensity());
        assertEquals(AuraPreset.FIRE, a.getPreset());
    }
}
