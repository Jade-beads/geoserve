package com.geoserve.init.model;

/**
 * 图层数据来源模式。
 */
public enum SourceType {
    /** 通过 GeoServer FeatureType REST API 发布数据库物理表。 */
    TABLE,
    /** 根据 classpath SQL 发布 GeoServer SQL View/JDBC virtual table。 */
    SQL_VIEW
}
