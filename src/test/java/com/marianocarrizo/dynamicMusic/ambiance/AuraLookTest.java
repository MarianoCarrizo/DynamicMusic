package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraLookTest {

    @Test
    void resolveIsFiniteForEveryPreset() {
        for (AuraPreset p : AuraPreset.values()) {
            for (boolean calm : new boolean[]{false, true}) {
                double[] v = AuraLook.resolve(p.defaults(), calm);
                assertEquals(AuraLook.SIZE, v.length);
                for (int i = 0; i < v.length; i++) {
                    assertTrue(Double.isFinite(v[i]), p + " calm=" + calm + " slot " + i);
                    assertTrue(v[i] >= 0, p + " calm=" + calm + " slot " + i + " is negative");
                }
            }
        }
    }

    @Test
    void colorsAndConfigValuesAreCopiedIntoTheArray() {
        AuraConfig c = AuraPreset.FIRE.defaults();
        c.setPrimaryColor("#FF0000");
        c.setSecondaryColor("#0000FF");
        c.setIntensity(0.8);
        c.setReactivity(0.2);
        c.setVignette(false);
        c.setEmbers(false);

        double[] v = AuraLook.resolve(c);

        assertEquals(1.0, v[AuraLook.PR], 1e-6);
        assertEquals(0.0, v[AuraLook.PG], 1e-6);
        assertEquals(0.0, v[AuraLook.PB], 1e-6);
        assertEquals(1.0, v[AuraLook.SB], 1e-6);
        assertEquals(0.8, v[AuraLook.INTENSITY], 1e-9);
        assertEquals(0.2, v[AuraLook.REACTIVITY], 1e-9);
        assertEquals(0.0, v[AuraLook.W_VIGNETTE]);
        assertEquals(1.0, v[AuraLook.W_GLOW]);
        assertEquals(0.0, v[AuraLook.W_EMBERS]);
        assertEquals(1.0, v[AuraLook.W_PULSE]);
    }

    @Test
    void nullConfigResolvesLikeNeutral() {
        assertArrayEquals(AuraLook.resolve(AuraPreset.NEUTRAL.defaults()), AuraLook.resolve(null));
    }

    @Test
    void normalResolveHasUnitMotionAndPerc() {
        for (AuraPreset p : AuraPreset.values()) {
            double[] v = AuraLook.resolve(p.defaults());
            assertEquals(1.0, v[AuraLook.MOTION]);
            assertEquals(1.0, v[AuraLook.PERC]);
        }
    }

    @Test
    void calmDampsMotionPercAndBeatAccents() {
        for (AuraPreset p : AuraPreset.values()) {
            double[] normal = AuraLook.resolve(p.defaults(), false);
            double[] calm = AuraLook.resolve(p.defaults(), true);

            assertTrue(calm[AuraLook.MOTION] < normal[AuraLook.MOTION], p + " MOTION");
            assertTrue(calm[AuraLook.PERC] < normal[AuraLook.PERC], p + " PERC");
            assertTrue(calm[AuraLook.BEAT_FLASH] < normal[AuraLook.BEAT_FLASH], p + " BEAT_FLASH");
            assertTrue(calm[AuraLook.E_SPEED] < normal[AuraLook.E_SPEED], p + " E_SPEED");
            assertTrue(calm[AuraLook.G_ENERGY] < normal[AuraLook.G_ENERGY], p + " G_ENERGY");
            assertTrue(calm[AuraLook.EDGE_LINE] <= normal[AuraLook.EDGE_LINE], p + " EDGE_LINE");
            assertEquals(0.0, calm[AuraLook.EDGE_LINE], p + " edge line is off when calm");

            // colors and layer weights are not affected by calm
            assertEquals(normal[AuraLook.PR], calm[AuraLook.PR]);
            assertEquals(normal[AuraLook.W_GLOW], calm[AuraLook.W_GLOW]);
        }
    }

    @Test
    void calmFalseMatchesPlainResolve() {
        AuraConfig c = AuraPreset.ELECTRIC.defaults();
        assertArrayEquals(AuraLook.resolve(c), AuraLook.resolve(c, false));
    }
}
