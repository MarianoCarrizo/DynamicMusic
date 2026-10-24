package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;

import java.io.File;

/**
 * What the scene editor needs from the main window. The editor mutates the active scene
 * through {@link #session()} and calls back here so the main window and the ambiance
 * follow every edit. Every method must be called on the FX thread.
 */
public interface SceneHost {

    /** Live session; its active scene is the one being edited. */
    SessionConfig session();

    /** Persists the session now. */
    void saveSession();

    /** The active scene was renamed (scene selector needs a refresh). */
    void onSceneRenamed();

    /** Deletes the active scene and activates another. Returns false if it is the last one. */
    boolean deleteActiveScene();

    /** An aura of the active scene changed; applies it live and schedules a save. */
    void onAuraEdited(AuraSlot slot);

    /** Assigns an audio file to a mood of the active scene (pinning runs off the FX thread). */
    void assignTrack(TrackType type, File file);

    /** Removes the track assigned to a mood of the active scene. */
    void clearTrack(TrackType type);

    /** Re-applies the active scene's background (path, segment, rate, boomerang) to the ambiance. */
    void applyBackground();

    /** Re-applies the active scene's background fit mode to the ambiance. */
    void applyFitMode();

    boolean isFfmpegAvailable();
}
