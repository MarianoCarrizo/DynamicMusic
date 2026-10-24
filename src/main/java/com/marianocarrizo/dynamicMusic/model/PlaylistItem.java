package com.marianocarrizo.dynamicMusic.model;

import java.io.File;

public class PlaylistItem {

    private String path;
    private String youtubeUrl;
    private String title;

    public PlaylistItem() {}

    public PlaylistItem(String path) {
        this.path = path;
    }

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

    public String displayName() {
        return (title != null && !title.isBlank()) ? title : new File(path).getName();
    }
}
