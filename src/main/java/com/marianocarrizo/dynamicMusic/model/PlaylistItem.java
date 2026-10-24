package com.marianocarrizo.dynamicMusic.model;

import java.io.File;

public class PlaylistItem {

    private String path;
    private String youtubeUrl;
    private String title;

    /** No-arg constructor required by Gson. */
    public PlaylistItem() {}

    /** Convenience constructor for local-file items. */
    public PlaylistItem(String path) {
        this.path = path;
    }

    /** Full constructor for YouTube-sourced items. */
    public PlaylistItem(String path, String youtubeUrl, String title) {
        this.path = path;
        this.youtubeUrl = youtubeUrl;
        this.title = title;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getYoutubeUrl() { return youtubeUrl; }
    public void setYoutubeUrl(String youtubeUrl) { this.youtubeUrl = youtubeUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    /** Returns title if non-blank, otherwise falls back to the filename basename. */
    public String displayName() {
        return (title != null && !title.isBlank()) ? title : new File(path).getName();
    }
}
