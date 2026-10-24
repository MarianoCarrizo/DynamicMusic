package com.marianocarrizo.dynamicMusic.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionConfigManagerTest {

    @Test
    void missingFileYieldsFreshConfigWithOneScene(@TempDir Path dir) {
        SessionConfig cfg = new SessionConfigManager(dir.resolve("session.json")).load();
        assertEquals(1, cfg.getScenes().size());
    }

    @Test
    void malformedJsonYieldsFreshConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        Files.writeString(file, "{ this is not json");
        SessionConfig cfg = new SessionConfigManager(file).load();
        assertEquals(1, cfg.getScenes().size());
    }

    @Test
    void saveAndLoadRoundTripsScenes(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("session.json");
        SessionConfigManager mgr = new SessionConfigManager(file);
        SessionConfig cfg = new SessionConfig();
        cfg.getActiveScene().setAssignment(TrackType.NORMAL, "C:/music/n.mp3");
        Scene second = cfg.addScene("Second");
        cfg.setActiveScene(second.getId());
        cfg.getActiveScene().getAura(AuraSlot.RAGED).setIntensity(0.77);
        cfg.setMasterVolume(0.3);

        mgr.save(cfg);
        SessionConfig loaded = mgr.load();

        assertEquals(2, loaded.getScenes().size());
        assertEquals(second.getId(), loaded.getActiveSceneId());
        assertEquals(0.77, loaded.getActiveScene().getAura(AuraSlot.RAGED).getIntensity(), 1e-9);
        assertEquals("C:/music/n.mp3", loaded.getScenes().get(0).getAssignments().get(TrackType.NORMAL));
        assertEquals(0.3, loaded.getMasterVolume(), 1e-9);
        assertFalse(Files.exists(file.resolveSibling("session.json.tmp")));
    }

    @Test
    void loadMigratesLegacyFileSavesItAndKeepsOneBackup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        String legacy = """
                {"assignments":{"RAGED":"C:/music/raged.mp3"},
                 "backgroundImagePath":"C:/bg.png",
                 "moodEffects":{"RAGED":"PLASMA"},
                 "moodSensitivities":{"RAGED":2.0}}
                """;
        Files.writeString(file, legacy);
        SessionConfigManager mgr = new SessionConfigManager(file);

        SessionConfig first = mgr.load();

        assertEquals(1, first.getScenes().size());
        assertEquals("C:/music/raged.mp3", first.getAssignments().get(TrackType.RAGED));
        assertEquals("C:/bg.png", first.getBackgroundImagePath());
        assertEquals(legacy, Files.readString(dir.resolve("session.json.bak")));

        JsonObject onDisk = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(onDisk.has("scenes"));
        assertFalse(onDisk.has("assignments"));
        assertFalse(onDisk.has("backgroundImagePath"));
        assertFalse(onDisk.has("moodEffects"));

        SessionConfig second = mgr.load();
        assertEquals(1, second.getScenes().size());
        assertEquals(first.getActiveSceneId(), second.getActiveSceneId());
        assertEquals(legacy, Files.readString(dir.resolve("session.json.bak")));
    }
}
