package com.marianocarrizo.dynamicMusic.config;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionConfigTest {

    @Test
    void emptyConfigCreatesDefaultSceneWithAllAuras() {
        SessionConfig cfg = new SessionConfig();
        assertTrue(cfg.getScenes().isEmpty());

        Scene scene = cfg.getActiveScene();

        assertEquals("Default", scene.getName());
        assertEquals(1, cfg.getScenes().size());
        assertEquals(scene.getId(), cfg.getActiveSceneId());
    }

    @Test
    void addSceneAppendsWithoutChangingActiveScene() {
        SessionConfig cfg = new SessionConfig();
        Scene first = cfg.getActiveScene();

        Scene second = cfg.addScene("Boss");

        assertEquals(2, cfg.getScenes().size());
        assertNotEquals(first.getId(), second.getId());
        assertSame(first, cfg.getActiveScene());
    }

    @Test
    void duplicateSceneDeepCopiesUnderNewIdAndName() {
        SessionConfig cfg = new SessionConfig();
        Scene original = cfg.getActiveScene();
        original.setAssignment(TrackType.RAGED, "C:/music/raged.mp3");
        original.getBackground().setPath("C:/bg.png");
        original.getAura(AuraSlot.RAGED).setIntensity(0.9);

        Scene copy = cfg.duplicateScene(original.getId(), "Copy");

        assertNotNull(copy);
        assertNotEquals(original.getId(), copy.getId());
        assertEquals("Copy", copy.getName());
        assertEquals(2, cfg.getScenes().size());
        assertSame(copy, cfg.getScenes().get(1), "inserted right after the source");
        assertEquals("C:/music/raged.mp3", copy.getAssignments().get(TrackType.RAGED));
        assertEquals(0.9, copy.getAura(AuraSlot.RAGED).getIntensity(), 1e-9);

        // mutating the copy must not leak into the original
        copy.getBackground().setPath("C:/other.png");
        copy.getAura(AuraSlot.RAGED).setIntensity(0.1);
        copy.setAssignment(TrackType.RAGED, "C:/music/other.mp3");
        assertEquals("C:/bg.png", original.getBackground().getPath());
        assertEquals(0.9, original.getAura(AuraSlot.RAGED).getIntensity(), 1e-9);
        assertEquals("C:/music/raged.mp3", original.getAssignments().get(TrackType.RAGED));
    }

    @Test
    void duplicateUnknownSceneReturnsNull() {
        SessionConfig cfg = new SessionConfig();
        cfg.getActiveScene();
        assertNull(cfg.duplicateScene("nope", "x"));
        assertNull(cfg.duplicateScene(null, "x"));
    }

    @Test
    void renameSceneIgnoresBlankAndUnknown() {
        SessionConfig cfg = new SessionConfig();
        Scene s = cfg.getActiveScene();

        cfg.renameScene(s.getId(), "Renamed");
        assertEquals("Renamed", s.getName());

        cfg.renameScene(s.getId(), "   ");
        cfg.renameScene(s.getId(), null);
        cfg.renameScene("unknown", "Other");
        assertEquals("Renamed", s.getName());
    }

    @Test
    void deleteSceneRefusesTheLastOne() {
        SessionConfig cfg = new SessionConfig();
        Scene only = cfg.getActiveScene();

        assertFalse(cfg.deleteScene(only.getId()));
        assertEquals(1, cfg.getScenes().size());
    }

    @Test
    void deleteActiveSceneActivatesAnotherOne() {
        SessionConfig cfg = new SessionConfig();
        Scene first = cfg.getActiveScene();
        Scene second = cfg.addScene("Second");
        cfg.setActiveScene(second.getId());

        assertTrue(cfg.deleteScene(second.getId()));

        assertEquals(1, cfg.getScenes().size());
        assertEquals(first.getId(), cfg.getActiveSceneId());
        assertSame(first, cfg.getActiveScene());
    }

    @Test
    void deleteInactiveSceneKeepsActiveOne() {
        SessionConfig cfg = new SessionConfig();
        Scene first = cfg.getActiveScene();
        Scene second = cfg.addScene("Second");

        assertTrue(cfg.deleteScene(second.getId()));

        assertSame(first, cfg.getActiveScene());
        assertFalse(cfg.deleteScene("unknown"));
    }

    @Test
    void getActiveSceneFallsBackToFirstWhenIdIsInvalid() {
        SessionConfig cfg = new SessionConfig();
        Scene first = cfg.getActiveScene();
        cfg.addScene("Second");

        cfg.setActiveScene("does-not-exist");

        assertSame(first, cfg.getActiveScene());
        assertEquals(first.getId(), cfg.getActiveSceneId());
    }

    @Test
    void getScenesIsUnmodifiable() {
        SessionConfig cfg = new SessionConfig();
        cfg.getActiveScene();
        assertThrows(UnsupportedOperationException.class, () -> cfg.getScenes().clear());
    }

    @Test
    void adoptScenesKeepsScenesAndActiveId() {
        SessionConfig loaded = new SessionConfig();
        loaded.getActiveScene();
        Scene second = loaded.addScene("Second");
        loaded.setActiveScene(second.getId());

        SessionConfig target = new SessionConfig();
        target.getActiveScene(); // fresh "Default" that must be replaced
        target.adoptScenesFrom(loaded);

        assertEquals(2, target.getScenes().size());
        assertEquals(second.getId(), target.getActiveSceneId());
        assertSame(second, target.getActiveScene());
    }

    @Test
    void adoptScenesFromNullOrEmptyChangesNothing() {
        SessionConfig target = new SessionConfig();
        Scene mine = target.getActiveScene();

        target.adoptScenesFrom(null);
        target.adoptScenesFrom(new SessionConfig());

        assertSame(mine, target.getActiveScene());
        assertEquals(1, target.getScenes().size());
    }

    @Test
    void facadeWritesIntoTheActiveScene() {
        SessionConfig cfg = new SessionConfig();
        Scene a = cfg.getActiveScene();
        Scene b = cfg.addScene("B");

        cfg.setBackgroundImagePath("C:/a.png");
        cfg.setVideoPlaybackRate(2.0);
        cfg.setActiveScene(b.getId());
        cfg.setBackgroundImagePath("C:/b.png");

        assertEquals("C:/a.png", a.getBackground().getPath());
        assertEquals("C:/b.png", cfg.getBackgroundImagePath());
        assertEquals(1.0, cfg.getVideoPlaybackRate(), "defaults to 1.0 on the other scene");
        assertEquals("FILL", cfg.getBackgroundFitMode());
    }

    @Test
    void defaultsForMissingValues() {
        SessionConfig cfg = new SessionConfig();
        assertEquals(2.0, cfg.getMoodSensitivity(TrackType.RAGED));
        assertEquals(2.0, cfg.getMoodSensitivity(null));
        assertEquals(1.0, cfg.getMasterVolume());
        assertEquals("MOOD", cfg.getActiveMode());
        assertTrue(cfg.getPlaylists().isEmpty());
        assertEquals(AuraPreset.SHADOW, cfg.getActiveScene().getAura(AuraSlot.PAUSED).getPreset());
    }
}
