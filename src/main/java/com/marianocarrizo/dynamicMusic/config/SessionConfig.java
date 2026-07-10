package com.marianocarrizo.dynamicMusic.config;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.ambiance.EffectType;
import com.marianocarrizo.dynamicMusic.model.Playlist;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Key = enum name (e.g. "PAUSED"), value = absolute file path
    private Map<String, String> assignments;

    // Ruta global de imagen/video de fondo (reemplaza backgroundAsset; Gson ignora la clave antigua)
    private String backgroundImagePath;

    // Último directorio usado en el FileChooser de imagen de fondo (independiente de lastFolderPath)
    private String lastImageFolderPath;

    // Índice (0-based) de la pantalla elegida para el stage de ambiance; -1 = auto
    private int ambianceScreenIndex = -1;

    // Per-mood effect type: TrackType.name() → EffectType.name()
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

    public SessionConfig() {
        this.assignments = new LinkedHashMap<>();
    }

    /**
     * Returns assignments converted to a type-safe EnumMap.
     * Entries with unknown enum keys are silently skipped.
     */
    public Map<TrackType, String> getAssignments() {
        Map<TrackType, String> result = new EnumMap<>(TrackType.class);
        if (assignments == null) return result;
        assignments.forEach((key, value) -> {
            try {
                result.put(TrackType.valueOf(key), value);
            } catch (IllegalArgumentException ignored) {
                // Unknown enum key: silently skipped for forward compatibility
            }
        });
        return result;
    }

    /**
     * Adds or replaces the track assignment for the given mood.
     */
    public void put(TrackType type, String absolutePath) {
        if (assignments == null) {
            assignments = new LinkedHashMap<>();
        }
        assignments.put(type.name(), absolutePath);
    }

    // --- Background image path ---

    public String getBackgroundImagePath() {
        return backgroundImagePath;
    }

    public void setBackgroundImagePath(String path) {
        this.backgroundImagePath = path;
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
     * Returns the EffectType for the given mood, or a built-in default if
     * the stored value is absent or refers to an unknown EffectType.
     */
    public EffectType getMoodEffect(TrackType type) {
        if (moodEffects == null || type == null) return defaultEffect(type);
        String stored = moodEffects.get(type.name());
        if (stored == null) return defaultEffect(type);
        try {
            return EffectType.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return defaultEffect(type);
        }
    }

    /**
     * Saves the effect preference for the given mood.
     */
    public void setMoodEffect(TrackType type, EffectType effect) {
        if (type == null || effect == null) return;
        if (moodEffects == null) moodEffects = new LinkedHashMap<>();
        moodEffects.put(type.name(), effect.name());
    }

    private static EffectType defaultEffect(TrackType type) {
        if (type == null) return EffectType.AURORA;
        return switch (type) {
            case RAGED     -> EffectType.FIRE;
            case NORMAL    -> EffectType.AURORA;
            case ADVANTAGE -> EffectType.ELECTRIC;
            case PAUSED    -> EffectType.VOID;
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
}
