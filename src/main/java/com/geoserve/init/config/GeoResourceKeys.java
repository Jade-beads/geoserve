package com.geoserve.init.config;

/**
 * 业务代码需要稳定逻辑标识时可使用的资源 key。
 *
 * 真实 GeoServer 资源名称仍以 application.yml 为准。这个枚举只避免未来代码里散落临时字符串，
 * 同时保留部署时改名的能力。
 */
public enum GeoResourceKeys {
    /** 默认工作区逻辑 key。 */
    DEFAULT_WORKSPACE("workspace.default"),
    /** 默认样式逻辑 key。 */
    DEFAULT_STYLE("style.default"),
    /** 默认数据源逻辑 key。 */
    DEFAULT_DATASTORE("datastore.default"),
    /** 默认图层逻辑 key。 */
    DEFAULT_LAYER("layer.default");

    private final String key;

    GeoResourceKeys(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
