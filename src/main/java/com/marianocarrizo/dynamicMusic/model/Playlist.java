package com.marianocarrizo.dynamicMusic.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {

    private String name;

    private List<String> paths;

    private List<PlaylistItem> items;

    public Playlist() {
        this.items = new ArrayList<>();
    }

    public Playlist(String name) {
        this.name = name;
        this.items = new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<PlaylistItem> getItems() {
        if (items == null) items = new ArrayList<>();
        if (paths != null && !paths.isEmpty()) {
            for (String p : paths) items.add(new PlaylistItem(p));
            paths = null;
        }
        return items;
    }

    public List<String> getPaths() {
        List<String> out = new ArrayList<>();
        for (PlaylistItem it : getItems()) out.add(it.getPath());
        return out;
    }

    public void add(String path) {
        getItems().add(new PlaylistItem(path));
    }

    public void addItem(PlaylistItem item) {
        getItems().add(item);
    }

    public void removeAt(int index) {
        List<PlaylistItem> l = getItems();
        if (index >= 0 && index < l.size()) l.remove(index);
    }

    public void reorder(int from, int to) {
        List<PlaylistItem> l = getItems();
        if (from < 0 || from >= l.size() || to < 0 || to >= l.size()) return;
        l.add(to, l.remove(from));
    }

    public int size() {
        return getItems().size();
    }
}
