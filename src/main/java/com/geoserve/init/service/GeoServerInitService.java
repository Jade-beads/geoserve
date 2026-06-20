package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.model.ResourceAction;
import org.springframework.stereotype.Service;

/**
 * 编排完整的 GeoServer 初始化流程。
 *
 * 执行顺序很重要：
 * 1. 根 workspace 为后续所有资源创建 namespace。
 * 2. Styles 需要先存在，图层才能引用默认样式。
 * 3. 根 datastore 需要先存在，FeatureType/Layer 才能发布。
 * 4. Layers 发布完成后，才创建可选的 GWC/WMTS 配置。
 *
 * 每个资源都通过 {@link #run(String, String, ActionCallback)} 包装，保证单个资源失败时返回 FAILED，
 * 同时不掩盖前后资源的执行状态。
 */
@Service
public class GeoServerInitService {

    private final GeoServerRestClient client;
    private final GeoServerInitProperties properties;

    public GeoServerInitService(GeoServerRestClient client, GeoServerInitProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 执行一次幂等初始化。
     *
     * 具体 GeoServer HTTP 调用都在 GeoServerRestClient 中。这里只负责业务顺序和 API 返回明细汇总。
     */
    public InitResult initialize() {
        InitResult result = new InitResult();

        // 步骤 1：单 workspace。后续 style/store/layer 的 URL 都会带这个根 workspace。
        result.addAction(run("workspace", properties.getWorkspace(), new ActionCallback() {
            @Override
            public ResourceAction run() {
                return client.ensureWorkspace();
            }
        }));

        // 步骤 2：先把 SLD 样式上传到工作区，后续图层才能引用。
        for (Style style : properties.getStyles()) {
            result.addAction(run("style", qualified(style.getName()), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureStyle(style);
                }
            }));
        }

        // 步骤 3：创建数据库数据源，使用 GaussDB/openGauss 的 PostGIS 兼容配置。
        final Datastore datastore = properties.getDatastore();
        result.addAction(run("datastore", qualified(datastore.getName()), new ActionCallback() {
            @Override
            public ResourceAction run() {
                return client.ensureDatastore(datastore);
            }
        }));

        // 步骤 4：先发布全部 FeatureType/Layer，保证所有业务图层先完整进入 GeoServer。
        for (Layer layer : properties.getLayers()) {
            result.addAction(run("layer", qualified(layer.getName()), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureFeatureType(layer);
                }
            }));
        }

        // 步骤 5：只为启用 WMTS 的图层添加 GWC 缓存配置。
        for (Layer layer : properties.getLayers()) {
            if (layer.getWmts() != null && layer.getWmts().isEnabled()) {
                result.addAction(run("gwc-layer", qualified(layer.getName()), new ActionCallback() {
                    @Override
                    public ResourceAction run() {
                        return client.ensureGwcLayer(layer);
                    }
                }));
            }
        }

        return result;
    }

    /**
     * 将运行时异常转换成 API 可见的 FAILED 结果。
     *
     * 该服务刻意不做事务回滚：GeoServer 远端资源可能已经创建成功，返回完整 action 明细
     * 比尝试回滚更安全。
     */
    private ResourceAction run(String type, String name, ActionCallback callback) {
        try {
            ResourceAction action = callback.run();
            if (action == null) {
                return ResourceAction.failed(type, name, "No action returned");
            }
            return action;
        } catch (RuntimeException ex) {
            return ResourceAction.failed(type, name, ex.getMessage());
        }
    }

    private String qualified(String name) {
        return properties.getWorkspace() + ":" + name;
    }

    /** JDK 8 兼容的小回调接口，用来避免把异常捕获逻辑散落在每个步骤里。 */
    private interface ActionCallback {
        ResourceAction run();
    }
}
