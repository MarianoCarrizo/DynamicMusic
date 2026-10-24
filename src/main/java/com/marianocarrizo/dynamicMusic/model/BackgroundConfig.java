package com.marianocarrizo.dynamicMusic.model;

/**
 * Background settings of a scene. Same semantics as the former global session fields:
 * null segment = full video loop, null rate = 1.0, null boomerang = false.
 */
public class BackgroundConfig {

    private String path;
    private String fitMode;           // "FILL" | "FIT" | "STRETCH" | "CENTER"; null = FILL
    private Long segmentStartMs;
    private Long segmentEndMs;
    private Double playbackRate;
    private Boolean boomerangEnabled;

    /** No-arg constructor required by Gson. */
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
