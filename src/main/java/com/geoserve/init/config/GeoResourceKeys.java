package com.geoserve.init.config;

public enum GeoResourceKeys {
    DEFAULT_WORKSPACE("workspace.default"),
    DEFAULT_STYLE("style.default"),
    DEFAULT_DATASTORE("datastore.default"),
    DEFAULT_LAYER("layer.default");

    private final String key;

    GeoResourceKeys(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
