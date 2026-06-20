package com.geoserve.init.model;

import java.util.ArrayList;
import java.util.List;

public class InitResult {

    private boolean success = true;
    private List<ResourceAction> actions = new ArrayList<ResourceAction>();

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<ResourceAction> getActions() {
        return actions;
    }

    public void setActions(List<ResourceAction> actions) {
        this.actions = actions;
        this.success = true;
        if (actions != null) {
            for (ResourceAction action : actions) {
                if (action.getStatus() == ResourceStatus.FAILED) {
                    this.success = false;
                    break;
                }
            }
        }
    }

    public void addAction(ResourceAction action) {
        actions.add(action);
        if (action.getStatus() == ResourceStatus.FAILED) {
            success = false;
        }
    }
}
