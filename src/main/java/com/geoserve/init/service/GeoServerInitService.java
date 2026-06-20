package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Workspace;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.model.ResourceAction;
import org.springframework.stereotype.Service;

@Service
public class GeoServerInitService {

    private final GeoServerRestClient client;
    private final GeoServerInitProperties properties;

    public GeoServerInitService(GeoServerRestClient client, GeoServerInitProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public InitResult initialize() {
        InitResult result = new InitResult();

        for (Workspace workspace : properties.getWorkspaces()) {
            result.addAction(run("workspace", workspace.getName(), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureWorkspace(workspace);
                }
            }));
        }

        for (Style style : properties.getStyles()) {
            result.addAction(run("style", qualified(style.getWorkspace(), style.getName()), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureStyle(style);
                }
            }));
        }

        for (Datastore datastore : properties.getDatastores()) {
            result.addAction(run("datastore", qualified(datastore.getWorkspace(), datastore.getName()), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureDatastore(datastore);
                }
            }));
        }

        for (Layer layer : properties.getLayers()) {
            result.addAction(run("layer", qualified(layer.getWorkspace(), layer.getName()), new ActionCallback() {
                @Override
                public ResourceAction run() {
                    return client.ensureFeatureType(layer);
                }
            }));
            if (layer.getWmts() != null && layer.getWmts().isEnabled()) {
                result.addAction(run("gwc-layer", qualified(layer.getWorkspace(), layer.getName()), new ActionCallback() {
                    @Override
                    public ResourceAction run() {
                        return client.ensureGwcLayer(layer);
                    }
                }));
            }
        }

        return result;
    }

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

    private String qualified(String workspace, String name) {
        return workspace + ":" + name;
    }

    private interface ActionCallback {
        ResourceAction run();
    }
}
