package com.geoserve.init.model;

/**
 * 初始化接口返回的单个资源执行结果。
 *
 * name 示例：
 * - workspace: {@code site_selection}
 * - style/datastore/layer/GWC: {@code site_selection:basic_all}
 */
public class ResourceAction {

    /** 资源类型，例如 workspace、style、datastore、layer 或 gwc-layer。 */
    private String type;
    /** 资源名称；工作区内资源使用 workspace:name 格式。 */
    private String name;
    /** 当前步骤的 CREATED、SKIPPED 或 FAILED 状态。 */
    private ResourceStatus status;
    /** 给 API 调用方和日志阅读的说明信息。 */
    private String message;

    public ResourceAction() {
    }

    public ResourceAction(String type, String name, ResourceStatus status, String message) {
        this.type = type;
        this.name = name;
        this.status = status;
        this.message = message;
    }

    /** 本次执行中新创建资源的工厂方法。 */
    public static ResourceAction created(String type, String name, String message) {
        return new ResourceAction(type, name, ResourceStatus.CREATED, message);
    }

    /** 资源已存在且按幂等策略保持不变的工厂方法。 */
    public static ResourceAction skipped(String type, String name, String message) {
        return new ResourceAction(type, name, ResourceStatus.SKIPPED, message);
    }

    /** 检查或创建失败时的工厂方法。 */
    public static ResourceAction failed(String type, String name, String message) {
        return new ResourceAction(type, name, ResourceStatus.FAILED, message);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ResourceStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
