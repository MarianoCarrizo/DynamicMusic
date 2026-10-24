package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;

import java.io.File;

public interface SceneHost {

    SessionConfig session();

    void saveSession();

    void onSceneRenamed();

    boolean deleteActiveScene();

    void onAuraEdited(AuraSlot slot);

    void assignTrack(TrackType type, File file);

    void clearTrack(TrackType type);

    void applyBackground();

    void applyFitMode();

    boolean isFfmpegAvailable();
}
