package com.marianocarrizo.dynamicMusic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * POJO representing a user playlist.
 * Gson serialises private fields by reflection; no TypeAdapter needed.
 *
 * Schema migration: old sessions carry a bare {@code paths} String array.
 * New sessions use the {@code items} array of {@link PlaylistItem}.
 * {@link #getItems()} migrates lazily on first access so no data is lost
 * and no custom deserialiser is required.
 */
public class Playlist {

    private String name;

    /** LEGACY: read-only migration source. New saves leave this null. */
    private List<String> paths;

    /** Primary storage. Written on every save after first migration. */
    private List<PlaylistItem> items;

    /** No-arg constructor required by Gson. */
    public Playlist() {
        this.items = new ArrayList<>();
    }

    /** Creates a playlist with the given name and an empty item list. */
    public Playlist(String name) {
        this.name = name;
        this.items = new ArrayList<>();
    }

    // --- Name ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // --- Item access with lazy migration ---

    /**
     * Returns the live item list, migrating from the legacy {@code paths} field on first call
     * when the current session file pre-dates this schema change.
     */
    public List<PlaylistItem> getItems() {
        if (items == null) items = new ArrayList<>();
        if (paths != null && !paths.isEmpty()) {
            for (String p : paths) items.add(new PlaylistItem(p));
            paths = null; // consumed; next save writes items only
        }
        return items;
    }

    /**
     * Returns a fresh derived {@code List<String>} of absolute paths.
     * Callers that only need paths keep working without changes.
     * This is a copy — mutations on the returned list have no effect.
     */
    public List<String> getPaths() {
        List<String> out = new ArrayList<>();
        for (PlaylistItem it : getItems()) out.add(it.getPath());
        return out;
    }

    // --- Mutation helpers ---

    /** Appends a local-file item using only its path. */
    public void add(String path) {
        getItems().add(new PlaylistItem(path));
    }

    /** Appends a fully-constructed item (e.g. a YouTube-sourced track). */
    public void addItem(PlaylistItem item) {
        getItems().add(item);
    }

    /** Removes the item at the given index. No-op if the index is out of range. */
    public void removeAt(int index) {
        List<PlaylistItem> l = getItems();
        if (index >= 0 && index < l.size()) l.remove(index);
    }

    /**
     * Moves the item at position {@code from} to position {@code to}.
     * No-op if either index is out of range.
     */
    public void reorder(int from, int to) {
        List<PlaylistItem> l = getItems();
        if (from < 0 || from >= l.size() || to < 0 || to >= l.size()) return;
        l.add(to, l.remove(from));
    }

    /** Returns the number of items; 0 if the list has never been populated. */
    public int size() {
        return getItems().size();
    }
}
