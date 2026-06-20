package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Workspace;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.model.ResourceAction;
import com.geoserve.init.model.ResourceStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeoServerInitServiceTest {

    @Test
    void initializeRunsResourcesInWorkspaceStyleDatastoreLayerGwcOrder() {
        GeoServerRestClient client = mock(GeoServerRestClient.class);
        GeoServerInitProperties properties = properties();

        when(client.ensureWorkspace(properties.getWorkspaces().get(0)))
                .thenReturn(ResourceAction.created("workspace", "demo", "created"));
        when(client.ensureStyle(properties.getStyles().get(0)))
                .thenReturn(ResourceAction.skipped("style", "demo:grid_polygon", "exists"));
        when(client.ensureDatastore(properties.getDatastores().get(0)))
                .thenReturn(ResourceAction.created("datastore", "demo:gauss_store", "created"));
        when(client.ensureFeatureType(properties.getLayers().get(0)))
                .thenReturn(ResourceAction.created("layer", "demo:grid_finance", "created"));
        when(client.ensureGwcLayer(properties.getLayers().get(0)))
                .thenReturn(ResourceAction.created("gwc-layer", "demo:grid_finance", "created"));

        InitResult result = new GeoServerInitService(client, properties).initialize();

        assertThat(result.getActions())
                .extracting(ResourceAction::getStatus)
                .containsExactly(ResourceStatus.CREATED, ResourceStatus.SKIPPED,
                        ResourceStatus.CREATED, ResourceStatus.CREATED, ResourceStatus.CREATED);
        verify(client).ensureWorkspace(properties.getWorkspaces().get(0));
        verify(client).ensureStyle(properties.getStyles().get(0));
        verify(client).ensureDatastore(properties.getDatastores().get(0));
        verify(client).ensureFeatureType(properties.getLayers().get(0));
        verify(client).ensureGwcLayer(properties.getLayers().get(0));
    }

    @Test
    void initializeRecordsFailureAndContinuesWithNextConfiguredResource() {
        GeoServerRestClient client = mock(GeoServerRestClient.class);
        GeoServerInitProperties properties = properties();

        when(client.ensureWorkspace(properties.getWorkspaces().get(0)))
                .thenThrow(new IllegalStateException("GeoServer rejected workspace"));
        when(client.ensureStyle(properties.getStyles().get(0)))
                .thenReturn(ResourceAction.created("style", "demo:grid_polygon", "created"));

        InitResult result = new GeoServerInitService(client, properties).initialize();

        assertThat(result.getActions()).hasSize(5);
        assertThat(result.getActions().get(0).getStatus()).isEqualTo(ResourceStatus.FAILED);
        assertThat(result.getActions().get(0).getMessage()).contains("GeoServer rejected workspace");
        assertThat(result.getActions().get(1).getStatus()).isEqualTo(ResourceStatus.CREATED);
    }

    private GeoServerInitProperties properties() {
        Workspace workspace = new Workspace();
        workspace.setName("demo");

        Style style = new Style();
        style.setWorkspace("demo");
        style.setName("grid_polygon");
        style.setSldLocation("classpath:styles/test-polygon.sld");

        Datastore datastore = new Datastore();
        datastore.setWorkspace("demo");
        datastore.setName("gauss_store");

        Layer layer = new Layer();
        layer.setWorkspace("demo");
        layer.setDatastore("gauss_store");
        layer.setName("grid_finance");
        GeoServerInitProperties.Wmts wmts = new GeoServerInitProperties.Wmts();
        wmts.setEnabled(true);
        layer.setWmts(wmts);

        GeoServerInitProperties properties = new GeoServerInitProperties();
        properties.setWorkspaces(Collections.singletonList(workspace));
        properties.setStyles(Collections.singletonList(style));
        properties.setDatastores(Collections.singletonList(datastore));
        properties.setLayers(Collections.singletonList(layer));
        return properties;
    }
}
