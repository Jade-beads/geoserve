package com.geoserve.init.model;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/geoserver/init 的响应体。
 *
 * success 是从 action 列表汇总出的总状态：只要 workspace/style/datastore/layer/GWC 中
 * 有任一步骤返回 FAILED，它就会变成 false。
 */
public class InitResult {

    /** 初始化总状态。false 表示至少一个 ResourceAction 失败。 */
    private boolean success = true;
    /** 按初始化链路顺序记录的执行明细。 */
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
        // 反序列化或测试直接替换 action 列表时，重新计算总状态。
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
        // 每追加一个步骤，就同步更新总状态。
        if (action.getStatus() == ResourceStatus.FAILED) {
            success = false;
        }
    }
}
