package com.marianocarrizo.dynamicMusic.model;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;

/** Aura slots of a scene: one per mood plus a neutral "on demand" aura. */
public enum AuraSlot {
    PAUSED,
    NORMAL,
    RAGED,
    ADVANTAGE,
    ON_DEMAND;

    /** Maps a mood track to its aura slot. */
    public static AuraSlot of(TrackType type) {
        if (type == null) return ON_DEMAND;
        return switch (type) {
            case PAUSED    -> PAUSED;
            case NORMAL    -> NORMAL;
            case RAGED     -> RAGED;
            case ADVANTAGE -> ADVANTAGE;
        };
    }
}
