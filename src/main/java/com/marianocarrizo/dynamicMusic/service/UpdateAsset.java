package com.marianocarrizo.dynamicMusic.service;

enum UpdateAsset {
    INSTALLER(".exe"),
    CHECKSUM(".exe.sha256"),
    SIGNATURE(".exe.sha256.sig");

    private static final String FILE_PREFIX = "DynamicMusic-Setup-";

    private final String suffix;

    UpdateAsset(String suffix) {
        this.suffix = suffix;
    }

    String fileName(String version) {
        return FILE_PREFIX + version + suffix;
    }
}
