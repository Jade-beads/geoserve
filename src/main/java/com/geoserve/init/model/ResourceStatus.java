package com.geoserve.init.model;

/**
 * 单个初始化步骤可能返回的结果状态。
 */
public enum ResourceStatus {
    /** 资源原本不存在，并由本次执行创建。 */
    CREATED,
    /** 资源已存在，或相关功能未开启，因此没有执行写操作。 */
    SKIPPED,
    /** 当前步骤抛出异常或返回了非法结果。 */
    FAILED
}
