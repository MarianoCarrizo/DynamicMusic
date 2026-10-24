package com.marianocarrizo.dynamicMusic.model;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.service.TrackLibrary;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A full package for one enemy/situation: 4 mood track assignments,
 * a background and 5 auras (one per mood plus an on-demand one).
 * Getters are null-safe so partial JSON never yields NPEs.
 */
public class Scene {

    private String id;
    private String name;

    // Key = TrackType name, value = absolute file path
    private Map<String, String> assignments;

    private BackgroundConfig background;

    // Key = AuraSlot name
    private Map<String, AuraConfig> auras;

    /** No-arg constructor required by Gson. */
    public Scene() {
        this.id = UUID.randomUUID().toString();
        this.assignments = new LinkedHashMap<>();
        this.background = new BackgroundConfig();
        this.auras = new LinkedHashMap<>();
    }

    public Scene(String name) {
        this();
        this.name = name;
    }

    public String getId() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        return id;
    }
    public void setId(String id) { this.id = id; }

    public String getName() { return (name != null && !name.isBlank()) ? name : "Scene"; }
    public void setName(String name) { this.name = name; }

    // --- Assignments ---

    /** Returns assignments as a type-safe EnumMap copy; unknown keys and null values are skipped. */
    public Map<TrackType, String> getAssignments() {
        Map<TrackType, String> result = new EnumMap<>(TrackType.class);
        if (assignments == null) return result;
        assignments.forEach((key, value) -> {
            if (value == null) return;
            try {
                result.put(TrackType.valueOf(key), value);
            } catch (IllegalArgumentException ignored) {
                // Unknown enum key: skipped for forward compatibility
            }
        });
        return result;
    }

    /**
     * Assigns a track to a mood. This is the single choke point for YouTube pinning:
     * paths inside the yt-cache are copied to the permanent library so cache eviction
     * never breaks a scene. A null path clears the assignment.
     */
    public void setAssignment(TrackType type, String path) {
        if (type == null) return;
        if (assignments == null) assignments = new LinkedHashMap<>();
        if (path == null) {
            assignments.remove(type.name());
        } else {
            assignments.put(type.name(), TrackLibrary.pin(path));
        }
    }

    // --- Background ---

    public BackgroundConfig getBackground() {
        if (background == null) background = new BackgroundConfig();
        return background;
    }
    public void setBackground(BackgroundConfig b) { this.background = b; }

    // --- Auras ---

    /** Returns the aura for the slot, lazily creating it with defaults if missing. */
    public AuraConfig getAura(AuraSlot slot) {
        if (auras == null) auras = new LinkedHashMap<>();
        AuraConfig a = auras.get(slot.name());
        if (a == null) {
            a = defaultPreset(slot).defaults();
            auras.put(slot.name(), a);
        }
        return a;
    }

    public void setAura(AuraSlot slot, AuraConfig aura) {
        if (slot == null || aura == null) return;
        if (auras == null) auras = new LinkedHashMap<>();
        auras.put(slot.name(), aura);
    }

    /** Fills any missing aura slot with defaults. */
    public void ensureAuras() {
        for (AuraSlot s : AuraSlot.values()) getAura(s);
    }

    public static AuraPreset defaultPreset(AuraSlot slot) {
        return switch (slot) {
            case PAUSED    -> AuraPreset.SHADOW;
            case NORMAL    -> AuraPreset.HOLY;
            case RAGED     -> AuraPreset.FIRE;
            case ADVANTAGE -> AuraPreset.ELECTRIC;
            case ON_DEMAND -> AuraPreset.NEUTRAL;
        };
    }

    /** Deep copy (same id). Callers duplicating a scene must assign a new id and name. */
    public Scene copy() {
        Scene s = new Scene();
        s.id = id;
        s.name = name;
        s.assignments = assignments != null ? new LinkedHashMap<>(assignments) : new LinkedHashMap<>();
        s.background = background != null ? background.copy() : new BackgroundConfig();
        s.auras = new LinkedHashMap<>();
        if (auras != null) {
            auras.forEach((k, v) -> { if (v != null) s.auras.put(k, v.copy()); });
        }
        return s;
    }
}
