package com.marianocarrizo.dynamicMusic.config;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot migration from the pre-scenes session.json shape to a single "Default" scene.
 * Idempotent: does nothing when scenes already exist. Never throws.
 */
final class SceneMigration {

    private SceneMigration() {}

    /**
     * Ensures {@code cfg} has at least one scene.
     *
     * @param sessionFile the on-disk session file (backed up to .bak before migrating, if it exists)
     * @return true if the config was changed and should be saved
     */
    static boolean migrate(SessionConfig cfg, Path sessionFile) {
        try {
            if (cfg.hasScenes()) {
                // Already in the new shape: just make sure the active id and auras are valid.
                cfg.getActiveScene().ensureAuras();
                return false;
            }
            backup(sessionFile);

            Scene scene = cfg.buildSceneFromLegacy("Default");
            for (TrackType t : TrackType.values()) {
                AuraConfig aura = presetFor(cfg.getLegacyMoodEffectName(t)).defaults();
                aura.setReactivity(cfg.getMoodSensitivity(t) / 4.0);
                scene.setAura(AuraSlot.of(t), aura);
            }
            scene.ensureAuras(); // ON_DEMAND = NEUTRAL
            cfg.installMigratedScene(scene);
            return true;
        } catch (RuntimeException e) {
            System.err.println("Scene migration failed: " + e.getMessage());
            return false;
        }
    }

    /** Maps a legacy particle effect name (the old EffectType enum) to an aura preset. */
    private static AuraPreset presetFor(String legacyEffect) {
        if (legacyEffect == null) return AuraPreset.NEUTRAL;
        return switch (legacyEffect) {
            case "FIRE", "BLOOD_RAIN"                -> AuraPreset.FIRE;
            case "ELECTRIC", "PLASMA", "SHOCKWAVE"   -> AuraPreset.ELECTRIC;
            case "VOID", "MAELSTROM"                 -> AuraPreset.SHADOW;
            case "AURORA"                            -> AuraPreset.HOLY;
            default                                  -> AuraPreset.NEUTRAL;
        };
    }

    /** Copies session.json to session.json.bak, never overwriting an existing backup. */
    private static void backup(Path sessionFile) {
        try {
            if (sessionFile == null || !Files.exists(sessionFile)) return;
            Path bak = sessionFile.resolveSibling(sessionFile.getFileName() + ".bak");
            if (!Files.exists(bak)) Files.copy(sessionFile, bak);
        } catch (IOException e) {
            System.err.println("Could not back up session before migration: " + e.getMessage());
        }
    }
}
