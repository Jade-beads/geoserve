package com.geoserve.init.model;

public class ResourceAction {

    private String type;
    private String name;
    private ResourceStatus status;
    private String message;

    public ResourceAction() {
    }

    public ResourceAction(String type, String name, ResourceStatus status, String message) {
        this.type = type;
        this.name = name;
        this.status = status;
        this.message = message;
    }

    public static ResourceAction created(String type, String name, String message) {
        return new ResourceAction(type, name, ResourceStatus.CREATED, message);
    }

    public static ResourceAction skipped(String type, String name, String message) {
        return new ResourceAction(type, name, ResourceStatus.SKIPPED, message);
    }

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
