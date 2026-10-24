package com.marianocarrizo.dynamicMusic.model;

public class BackgroundConfig {

    private String path;
    private String fitMode;
    private Long segmentStartMs;
    private Long segmentEndMs;
    private Double playbackRate;
    private Boolean boomerangEnabled;

    public BackgroundConfig() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getFitMode() { return fitMode; }
    public void setFitMode(String fitMode) { this.fitMode = fitMode; }

    public Long getSegmentStartMs() { return segmentStartMs; }
    public void setSegmentStartMs(Long ms) { this.segmentStartMs = ms; }

    public Long getSegmentEndMs() { return segmentEndMs; }
    public void setSegmentEndMs(Long ms) { this.segmentEndMs = ms; }

    public Double getPlaybackRate() { return playbackRate; }
    public void setPlaybackRate(Double rate) { this.playbackRate = rate; }

    public Boolean getBoomerangEnabled() { return boomerangEnabled; }
    public void setBoomerangEnabled(Boolean v) { this.boomerangEnabled = v; }

    public BackgroundConfig copy() {
        BackgroundConfig c = new BackgroundConfig();
        c.path = path;
        c.fitMode = fitMode;
        c.segmentStartMs = segmentStartMs;
        c.segmentEndMs = segmentEndMs;
        c.playbackRate = playbackRate;
        c.boomerangEnabled = boomerangEnabled;
        return c;
    }
}
