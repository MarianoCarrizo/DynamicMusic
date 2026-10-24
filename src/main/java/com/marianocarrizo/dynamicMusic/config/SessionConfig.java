package com.marianocarrizo.dynamicMusic.config;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.BackgroundConfig;
import com.marianocarrizo.dynamicMusic.model.Playlist;
import com.marianocarrizo.dynamicMusic.model.Scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POJO representing the saved user session.
 * Stores the original absolute path of each track assigned by TrackType,
 * a single background image path shared across all moods,
 * and per-mood effect type preferences.
 *
 * Gson serializes all maps as Map<String,String> to avoid EnumMap
 * deserialization issues.
 */
public class SessionConfig {

    // Scenes: each one packages assignments + background + auras. activeSceneId selects the current one.
    private List<Scene> scenes;
    private String activeSceneId;

    // --- LEGACY, migration-only fields (pre-scenes session.json) ---
    // Kept ONLY so old files deserialize. SceneMigration moves them into the "Default"
    // scene and nulls them, so they are never written again. Do not read them elsewhere.
    private Map<String, String> assignments;
    private String backgroundImagePath;
    private String backgroundFitMode;
    private Long videoSegmentStartMs;
    private Long videoSegmentEndMs;
    private Double videoPlaybackRate;
    private Boolean boomerangEnabled;
    // --- end legacy ---

    // Último directorio usado en el FileChooser de imagen de fondo (independiente de lastFolderPath)
    private String lastImageFolderPath;

    // Índice (0-based) de la pantalla elegida para el stage de ambiance; -1 = auto
    private int ambianceScreenIndex = -1;

    // LEGACY (pre-scenes) per-mood effect name: TrackType.name() → old effect name; read only by SceneMigration
    private Map<String, String> moodEffects;

    // Last folder opened with "Open Folder" — null if never used
    private String lastFolderPath;

    // Lista de playlists del usuario (nueva — aditiva; null en sesiones antiguas)
    private List<Playlist> playlists;

    // Modo de reproducción activo: "MOOD" | "PLAYLIST"; null equivale a "MOOD"
    private String activeMode;

    // Nombre de la playlist activa al cerrar la sesión; null si ninguna
    private String activePlaylistName;

    // Sensibilidad por mood: TrackType.name() -> Double; null en sesiones antiguas (defecto 2.0)
    private Map<String, Double> moodSensitivities;

    // Volumen maestro [0.0-1.0]; boxed Double para que Gson deserialice null (no 0.0) en sesiones antiguas
    private Double masterVolume;

    public SessionConfig() {}

    // --- Scenes ---

    /** Unmodifiable view of the scenes (never null). */
    public List<Scene> getScenes() {
        return (scenes != null) ? Collections.unmodifiableList(scenes) : List.of();
    }

    public String getActiveSceneId() { return activeSceneId; }

    /**
     * Takes over the scene list and active scene of a loaded config, so the scene
     * facade below writes into the persisted scenes instead of a fresh "Default".
     */
    public void adoptScenesFrom(SessionConfig other) {
        if (other == null || other.scenes == null || other.scenes.isEmpty()) return;
        this.scenes = new ArrayList<>(other.scenes);
        this.activeSceneId = other.activeSceneId;
    }

    /**
     * Returns the active scene, never null. Falls back to the first scene when the id is
     * missing or invalid, and creates a "Default" scene when there are none.
     */
    public Scene getActiveScene() {
        if (scenes == null) scenes = new ArrayList<>();
        scenes.removeIf(s -> s == null);
        if (scenes.isEmpty()) {
            Scene def = new Scene("Default");
            def.ensureAuras();
            scenes.add(def);
        }
        Scene found = findScene(activeSceneId);
        if (found == null) {
            found = scenes.get(0);
            activeSceneId = found.getId();
        }
        return found;
    }

    /** Activates the scene with the given id; ignored if no such scene exists. */
    public void setActiveScene(String id) {
        Scene s = findScene(id);
        if (s != null) activeSceneId = s.getId();
    }

    public Scene addScene(String name) {
        if (scenes == null) scenes = new ArrayList<>();
        Scene s = new Scene(name);
        s.ensureAuras();
        scenes.add(s);
        return s;
    }

    /** Deep-copies a scene under a new id and name. Returns null if the source id is unknown. */
    public Scene duplicateScene(String id, String newName) {
        Scene src = findScene(id);
        if (src == null) return null;
        Scene copy = src.copy();
        copy.setId(UUID.randomUUID().toString());
        copy.setName(newName);
        scenes.add(scenes.indexOf(src) + 1, copy);
        return copy;
    }

    public void renameScene(String id, String name) {
        Scene s = findScene(id);
        if (s != null && name != null && !name.isBlank()) s.setName(name);
    }

    /** Deletes a scene. Refuses (returns false) to delete the last one. */
    public boolean deleteScene(String id) {
        String activeId = getActiveScene().getId(); // also strips null entries
        Scene s = findScene(id);
        if (s == null || scenes.size() <= 1) return false;
        boolean wasActive = s.getId().equals(activeId);
        scenes.remove(s);
        if (wasActive) activeSceneId = scenes.get(0).getId();
        return true;
    }

    private Scene findScene(String id) {
        if (id == null || scenes == null) return null;
        for (Scene s : scenes) {
            if (s != null && id.equals(s.getId())) return s;
        }
        return null;
    }

    // --- Legacy migration hooks (used by SceneMigration only) ---

    boolean hasScenes() { return scenes != null && !scenes.isEmpty(); }

    /** Builds a scene from the legacy scene-scoped fields. Does not modify them. */
    Scene buildSceneFromLegacy(String name) {
        Scene s = new Scene(name);
        if (assignments != null) {
            assignments.forEach((key, value) -> {
                if (key == null || value == null) return;
                try {
                    s.setAssignment(TrackType.valueOf(key), value);
                } catch (IllegalArgumentException ignored) {
                    // Unknown enum key: skipped
                }
            });
        }
        BackgroundConfig bg = s.getBackground();
        bg.setPath(backgroundImagePath);
        bg.setFitMode(backgroundFitMode);
        bg.setSegmentStartMs(videoSegmentStartMs);
        bg.setSegmentEndMs(videoSegmentEndMs);
        bg.setPlaybackRate(videoPlaybackRate);
        bg.setBoomerangEnabled(boomerangEnabled);
        return s;
    }

    /** Installs the migrated scene as the only/active one and nulls the legacy fields. */
    void installMigratedScene(Scene s) {
        scenes = new ArrayList<>();
        scenes.add(s);
        activeSceneId = s.getId();
        assignments = null;
        backgroundImagePath = null;
        backgroundFitMode = null;
        videoSegmentStartMs = null;
        videoSegmentEndMs = null;
        videoPlaybackRate = null;
        boomerangEnabled = null;
        moodEffects = null;
    }

    // --- Scene-scoped facade (reads/writes the ACTIVE scene) ---

    /**
     * Returns the active scene's assignments as a type-safe EnumMap.
     * Entries with unknown enum keys are silently skipped.
     */
    public Map<TrackType, String> getAssignments() {
        return getActiveScene().getAssignments();
    }

    /**
     * Adds or replaces the track assignment for the given mood in the active scene.
     * YouTube-cache paths are pinned to the library by Scene.setAssignment.
     */
    public void put(TrackType type, String absolutePath) {
        getActiveScene().setAssignment(type, absolutePath);
    }

    // --- Background image path ---

    public String getBackgroundImagePath() {
        return getActiveScene().getBackground().getPath();
    }

    public void setBackgroundImagePath(String path) {
        getActiveScene().getBackground().setPath(path);
    }

    // --- Last image folder ---

    public String getLastImageFolderPath() {
        return lastImageFolderPath;
    }

    public void setLastImageFolderPath(String path) {
        this.lastImageFolderPath = path;
    }

    // --- Ambiance screen index ---

    public int getAmbianceScreenIndex() {
        return ambianceScreenIndex;
    }

    public void setAmbianceScreenIndex(int index) {
        this.ambianceScreenIndex = index;
    }

    // --- Mood effects ---

    /**
     * LEGACY: the name of the pre-scenes particle effect stored for the given mood (e.g. "FIRE",
     * "AURORA"), or the old built-in default when absent. Only used to migrate old session files.
     */
    public String getLegacyMoodEffectName(TrackType type) {
        if (moodEffects != null && type != null) {
            String stored = moodEffects.get(type.name());
            if (stored != null) return stored;
        }
        return defaultLegacyEffectName(type);
    }

    private static String defaultLegacyEffectName(TrackType type) {
        if (type == null) return "AURORA";
        return switch (type) {
            case RAGED     -> "FIRE";
            case NORMAL    -> "AURORA";
            case ADVANTAGE -> "ELECTRIC";
            case PAUSED    -> "VOID";
        };
    }

    // --- Last folder ---

    public String getLastFolderPath() { return lastFolderPath; }
    public void setLastFolderPath(String path) { this.lastFolderPath = path; }

    // --- Playlists ---

    /**
     * Retorna la lista de playlists.
     * Si Gson dejó el campo null (sesión antigua), retorna una lista vacía mutable.
     * No muta el campo en el camino de lectura para evitar efectos colaterales.
     */
    public List<Playlist> getPlaylists() {
        return (playlists != null) ? playlists : new ArrayList<>();
    }

    public void setPlaylists(List<Playlist> p) {
        this.playlists = p;
    }

    // --- Active mode ---

    /**
     * Retorna el modo activo guardado en sesión.
     * Si es null (sesión antigua) retorna "MOOD" como valor por defecto.
     */
    public String getActiveMode() {
        return (activeMode != null) ? activeMode : "MOOD";
    }

    public void setActiveMode(String mode) {
        this.activeMode = mode;
    }

    // --- Active playlist name ---

    public String getActivePlaylistName() { return activePlaylistName; }
    public void setActivePlaylistName(String name) { this.activePlaylistName = name; }

    // --- Mood sensitivities ---

    /**
     * Returns the sensitivity value for the given mood.
     * Returns the default value 2.0 when the map is absent (first run or old session)
     * or the mood key is not yet stored.
     */
    public double getMoodSensitivity(TrackType t) {
        if (t == null || moodSensitivities == null) return 2.0;
        Double stored = moodSensitivities.get(t.name());
        return (stored != null) ? stored : 2.0;
    }

    /**
     * Persists the sensitivity value for the given mood.
     * Lazy-initialises the map on first write.
     */
    public void setMoodSensitivity(TrackType t, double value) {
        if (t == null) return;
        if (moodSensitivities == null) moodSensitivities = new LinkedHashMap<>();
        moodSensitivities.put(t.name(), value);
    }

    // --- Background fit mode ---

    /**
     * Retorna el modo de ajuste del fondo guardado en sesión.
     * Si es null (sesión antigua), retorna "FILL" como valor por defecto.
     */
    public String getBackgroundFitMode() {
        String mode = getActiveScene().getBackground().getFitMode();
        return (mode != null) ? mode : "FILL";
    }

    public void setBackgroundFitMode(String mode) {
        getActiveScene().getBackground().setFitMode(mode);
    }

    // --- Master volume ---

    /**
     * Returns the persisted master volume.
     * Defaults to 1.0 when the field is absent in old session files (Gson leaves it null).
     */
    public double getMasterVolume() {
        return (masterVolume != null) ? masterVolume : 1.0;
    }

    public void setMasterVolume(double v) {
        this.masterVolume = v;
    }

    // --- Video segment loop ---

    public Long getVideoSegmentStartMs() { return getActiveScene().getBackground().getSegmentStartMs(); }
    public void setVideoSegmentStartMs(Long ms) { getActiveScene().getBackground().setSegmentStartMs(ms); }

    public Long getVideoSegmentEndMs() { return getActiveScene().getBackground().getSegmentEndMs(); }
    public void setVideoSegmentEndMs(Long ms) { getActiveScene().getBackground().setSegmentEndMs(ms); }

    // --- Video playback rate ---

    /**
     * Returns the persisted video playback rate.
     * Defaults to 1.0 when the field is absent in old session files (Gson leaves it null).
     */
    public double getVideoPlaybackRate() {
        Double rate = getActiveScene().getBackground().getPlaybackRate();
        return (rate != null) ? rate : 1.0;
    }

    public void setVideoPlaybackRate(Double rate) {
        getActiveScene().getBackground().setPlaybackRate(rate);
    }

    // --- Boomerang ---

    /** Puede retornar null en sesiones antiguas; usar Boolean.TRUE.equals() para null-safety. */
    public Boolean getBoomerangEnabled() {
        return getActiveScene().getBackground().getBoomerangEnabled();
    }

    public void setBoomerangEnabled(Boolean v) {
        getActiveScene().getBackground().setBoomerangEnabled(v);
    }
}
