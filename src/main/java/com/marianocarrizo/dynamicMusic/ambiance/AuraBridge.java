package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;

/**
 * TEMPORARY bridge between scene auras and the legacy particle renderer.
 * AmbianceStage still draws {@link EffectType} particles with a single sensitivity;
 * this maps each mood's {@link AuraConfig} onto those two knobs.
 * Delete this class when the aura engine replaces the legacy particles.
 */
public final class AuraBridge {

    private AuraBridge() {}

    /** Legacy effect used to approximate an aura preset. */
    public static EffectType effectFor(AuraPreset preset) {
        if (preset == null) return EffectType.AURORA;
        return switch (preset) {
            case FIRE     -> EffectType.FIRE;
            case SHADOW   -> EffectType.VOID;
            case ELECTRIC -> EffectType.ELECTRIC;
            case HOLY, NEUTRAL -> EffectType.AURORA;
        };
    }

    /** Legacy sensitivity multiplier derived from the aura reactivity (0..1 maps to 0.1..4). */
    public static double sensitivityFor(AuraConfig aura) {
        double reactivity = aura != null ? aura.getReactivity() : 0.5;
        return Math.max(0.1, reactivity * 4.0);
    }

    /**
     * Pushes one mood's aura to the stage. The sensitivity is a single global value on the
     * stage, so it is only applied when {@code isCurrentMood} (switching moods re-applies it).
     */
    public static void applyMood(AmbianceStage stage, Scene scene, TrackType mood, boolean isCurrentMood) {
        if (stage == null || scene == null || mood == null) return;
        AuraConfig aura = scene.getAura(AuraSlot.of(mood));
        stage.setMoodEffect(mood, effectFor(aura.getPreset()));
        if (isCurrentMood) stage.setSensitivity(sensitivityFor(aura));
    }

    /** Pushes the auras of all four moods; sensitivity follows {@code currentMood}. */
    public static void applyAll(AmbianceStage stage, Scene scene, TrackType currentMood) {
        for (TrackType t : TrackType.values()) {
            applyMood(stage, scene, t, t == currentMood);
        }
    }
}
