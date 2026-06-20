package com.geoserve.init.model;

public class GeoServerStatus {

    private boolean reachable;
    private String version;
    private String message;

    public GeoServerStatus() {
    }

    public GeoServerStatus(boolean reachable, String version, String message) {
        this.reachable = reachable;
        this.version = version;
        this.message = message;
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
