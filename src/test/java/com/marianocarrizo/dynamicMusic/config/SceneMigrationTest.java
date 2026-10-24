package com.marianocarrizo.dynamicMusic.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneMigrationTest {

    private static final Gson GSON = new Gson();

    private static final String LEGACY_JSON = """
            {
              "assignments": {
                "RAGED": "C:/music/raged.mp3",
                "NORMAL": "C:/music/normal.mp3",
                "BOGUS": "C:/music/bogus.mp3",
                "PAUSED": null
              },
              "backgroundImagePath": "C:/bg/video.mp4",
              "backgroundFitMode": "STRETCH",
              "videoSegmentStartMs": 1500,
              "videoSegmentEndMs": 9000,
              "videoPlaybackRate": 1.5,
              "boomerangEnabled": true,
              "moodEffects": {
                "RAGED": "BLOOD_RAIN",
                "NORMAL": "AURORA",
                "ADVANTAGE": "SHOCKWAVE",
                "PAUSED": "SOMETHING_UNKNOWN"
              },
              "moodSensitivities": { "RAGED": 4.0, "NORMAL": 1.0, "ADVANTAGE": 8.0 },
              "lastFolderPath": "C:/music",
              "masterVolume": 0.4
            }
            """;

    private static SessionConfig legacy() {
        return GSON.fromJson(LEGACY_JSON, SessionConfig.class);
    }

    @Test
    void legacySessionBecomesExactlyOneDefaultScene(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        assertFalse(cfg.hasScenes());

        assertTrue(SceneMigration.migrate(cfg, dir.resolve("session.json")));

        assertEquals(1, cfg.getScenes().size());
        Scene scene = cfg.getScenes().get(0);
        assertEquals("Default", scene.getName());
        assertEquals(scene.getId(), cfg.getActiveSceneId());
    }

    @Test
    void migrationCopiesAssignmentsAndBackgroundValues(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        SceneMigration.migrate(cfg, dir.resolve("session.json"));

        Scene scene = cfg.getActiveScene();
        Map<TrackType, String> assignments = scene.getAssignments();
        assertEquals(2, assignments.size(), "unknown keys and null values are dropped");
        assertEquals("C:/music/raged.mp3", assignments.get(TrackType.RAGED));
        assertEquals("C:/music/normal.mp3", assignments.get(TrackType.NORMAL));

        assertEquals("C:/bg/video.mp4", scene.getBackground().getPath());
        assertEquals("STRETCH", scene.getBackground().getFitMode());
        assertEquals(1500L, scene.getBackground().getSegmentStartMs());
        assertEquals(9000L, scene.getBackground().getSegmentEndMs());
        assertEquals(1.5, scene.getBackground().getPlaybackRate());
        assertEquals(Boolean.TRUE, scene.getBackground().getBoomerangEnabled());

        assertEquals("C:/bg/video.mp4", cfg.getBackgroundImagePath());
        assertEquals("STRETCH", cfg.getBackgroundFitMode());
        assertEquals(1.5, cfg.getVideoPlaybackRate());
    }

    @Test
    void aurasAreSeededFromLegacyEffectNames(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        SceneMigration.migrate(cfg, dir.resolve("session.json"));
        Scene scene = cfg.getActiveScene();

        assertEquals(AuraPreset.FIRE, scene.getAura(AuraSlot.RAGED).getPreset());
        assertEquals(AuraPreset.HOLY, scene.getAura(AuraSlot.NORMAL).getPreset());
        assertEquals(AuraPreset.ELECTRIC, scene.getAura(AuraSlot.ADVANTAGE).getPreset());
        assertEquals(AuraPreset.NEUTRAL, scene.getAura(AuraSlot.PAUSED).getPreset());
        assertEquals(AuraPreset.NEUTRAL, scene.getAura(AuraSlot.ON_DEMAND).getPreset());
    }

    @Test
    void everyDocumentedEffectNameMapsToItsPreset(@TempDir Path dir) {
        Map<String, AuraPreset> expected = Map.of(
                "FIRE", AuraPreset.FIRE,
                "BLOOD_RAIN", AuraPreset.FIRE,
                "ELECTRIC", AuraPreset.ELECTRIC,
                "PLASMA", AuraPreset.ELECTRIC,
                "SHOCKWAVE", AuraPreset.ELECTRIC,
                "VOID", AuraPreset.SHADOW,
                "MAELSTROM", AuraPreset.SHADOW,
                "AURORA", AuraPreset.HOLY,
                "NOPE", AuraPreset.NEUTRAL);
        expected.forEach((effect, preset) -> {
            SessionConfig cfg = GSON.fromJson(
                    "{\"moodEffects\":{\"RAGED\":\"" + effect + "\"}}", SessionConfig.class);
            SceneMigration.migrate(cfg, dir.resolve("session.json"));
            assertEquals(preset, cfg.getActiveScene().getAura(AuraSlot.RAGED).getPreset(), effect);
        });
    }

    @Test
    void reactivityIsSensitivityDividedByFourAndClamped(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        SceneMigration.migrate(cfg, dir.resolve("session.json"));
        Scene scene = cfg.getActiveScene();

        assertEquals(1.0, scene.getAura(AuraSlot.RAGED).getReactivity(), 1e-9);
        assertEquals(0.25, scene.getAura(AuraSlot.NORMAL).getReactivity(), 1e-9);
        assertEquals(1.0, scene.getAura(AuraSlot.ADVANTAGE).getReactivity(), 1e-9);
        assertEquals(0.5, scene.getAura(AuraSlot.PAUSED).getReactivity(), 1e-9);
    }

    @Test
    void migrationIsIdempotent(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        Path file = dir.resolve("session.json");
        assertTrue(SceneMigration.migrate(cfg, file));
        String firstId = cfg.getActiveSceneId();
        String firstJson = GSON.toJson(cfg);

        assertFalse(SceneMigration.migrate(cfg, file));

        assertEquals(1, cfg.getScenes().size());
        assertEquals(firstId, cfg.getActiveSceneId());
        assertEquals(firstJson, GSON.toJson(cfg));
    }

    @Test
    void backupIsWrittenOnceAndNeverOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        Files.writeString(file, "original");

        SceneMigration.migrate(legacy(), file);
        Path bak = dir.resolve("session.json.bak");
        assertEquals("original", Files.readString(bak));

        Files.writeString(file, "changed later");
        SceneMigration.migrate(legacy(), file);
        assertEquals("original", Files.readString(bak));
    }

    @Test
    void missingSessionFileIsFineAndWritesNoBackup(@TempDir Path dir) {
        assertTrue(SceneMigration.migrate(legacy(), dir.resolve("absent.json")));
        assertFalse(Files.exists(dir.resolve("absent.json.bak")));
        assertDoesNotThrow(() -> SceneMigration.migrate(legacy(), null));
    }

    @Test
    void legacyFieldsAreNotSerializedAfterMigration(@TempDir Path dir) {
        SessionConfig cfg = legacy();
        SceneMigration.migrate(cfg, dir.resolve("session.json"));

        JsonObject root = GSON.toJsonTree(cfg).getAsJsonObject();
        for (String legacyKey : List.of("assignments", "backgroundImagePath", "backgroundFitMode",
                "videoSegmentStartMs", "videoSegmentEndMs", "videoPlaybackRate", "boomerangEnabled",
                "moodEffects")) {
            assertFalse(root.has(legacyKey), "legacy field written back: " + legacyKey);
        }
        assertTrue(root.has("scenes"));
        assertEquals("C:/music", root.get("lastFolderPath").getAsString());
    }

    @Test
    void partialAndOddJsonNeverThrows(@TempDir Path dir) {
        List<String> inputs = List.of(
                "{}",
                "{\"assignments\":null,\"moodEffects\":null,\"moodSensitivities\":null}",
                "{\"assignments\":{\"RAGED\":null,\"\":\"x\"},\"moodEffects\":{\"RAGED\":null}}",
                "{\"scenes\":[]}",
                "{\"scenes\":[null]}",
                "{\"scenes\":[{}]}",
                "{\"scenes\":[{\"id\":null,\"name\":null,\"assignments\":null,\"background\":null,\"auras\":null}]}",
                "{\"scenes\":[{\"auras\":{\"RAGED\":null,\"NORMAL\":{}}}],\"activeSceneId\":\"missing\"}",
                "{\"moodSensitivities\":{\"RAGED\":-3.0,\"NORMAL\":1e308}}");
        for (String json : inputs) {
            SessionConfig cfg = GSON.fromJson(json, SessionConfig.class);
            assertDoesNotThrow(() -> SceneMigration.migrate(cfg, dir.resolve("session.json")), json);
            assertNotNull(cfg.getActiveScene(), json);
            for (AuraSlot slot : AuraSlot.values()) {
                AuraConfig aura = cfg.getActiveScene().getAura(slot);
                assertNotNull(aura, json);
                assertTrue(aura.getReactivity() >= 0 && aura.getReactivity() <= 1, json);
            }
        }
    }

    @Test
    void existingScenesAreLeftUntouched(@TempDir Path dir) {
        SessionConfig cfg = new SessionConfig();
        Scene scene = cfg.addScene("Boss");
        cfg.setActiveScene(scene.getId());

        assertFalse(SceneMigration.migrate(cfg, dir.resolve("session.json")));
        assertEquals(1, cfg.getScenes().size());
        assertEquals("Boss", cfg.getActiveScene().getName());
        assertFalse(Files.exists(dir.resolve("session.json.bak")));
    }
}
