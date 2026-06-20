package com.geoserve.init.model;

/**
 * GET /api/geoserver/status 的响应体。
 *
 * 这个对象只表示连接和认证状态，不表示 workspace、datastore、layer 或 WMTS 资源已经创建。
 */
public class GeoServerStatus {

    /** GeoServer 版本接口成功响应时为 true。 */
    private boolean reachable;
    /** 从 REST 响应中解析出的 GeoServer 版本。 */
    private String version;
    /** 便于部署诊断的成功或失败说明。 */
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
