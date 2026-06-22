package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Deploy;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Wmts;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.model.ResourceAction;
import com.geoserve.init.model.ResourceStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只测试初始化编排层。
 *
 * 这里 mock GeoServerRestClient，让测试聚焦在单工作区、单数据源下的资源链路顺序、
 * 结果聚合和失败处理上。
 */
class GeoServerInitServiceTest {

    @Test
    void initializeRunsSingleWorkspaceDatastoreAndFiveLayersInOrder() {
        GeoServerRestClient client = mock(GeoServerRestClient.class);
        GeoServerInitProperties properties = properties();

        when(client.ensureWorkspace())
                .thenReturn(ResourceAction.created("workspace", "site_selection", "created"));
        when(client.ensureStyle(properties.getStyles().get(0)))
                .thenReturn(ResourceAction.created("style", "site_selection:count_style", "created"));
        when(client.ensureStyle(properties.getStyles().get(1)))
                .thenReturn(ResourceAction.skipped("style", "site_selection:price_style", "exists"));
        when(client.ensureDatastore(properties.getDatastore()))
                .thenReturn(ResourceAction.created("datastore", "site_selection:gauss_store", "created"));
        for (Layer layer : properties.getLayers()) {
            when(client.ensureFeatureType(layer))
                    .thenReturn(ResourceAction.created("layer", "site_selection:" + layer.getName(), "created"));
        }
        when(client.ensureGwcLayer(properties.getLayers().get(0)))
                .thenReturn(ResourceAction.created("gwc-layer", "site_selection:basic_all", "created"));

        InitResult result = new GeoServerInitService(client, properties).initialize();

        // 顺序与生产链路一致：workspace -> styles -> datastore -> 5 layers -> 1 GWC。
        assertThat(result.getActions()).hasSize(10);
        assertThat(result.getActions())
                .extracting(ResourceAction::getStatus)
                .containsExactly(ResourceStatus.CREATED, ResourceStatus.CREATED, ResourceStatus.SKIPPED,
                        ResourceStatus.CREATED, ResourceStatus.CREATED, ResourceStatus.CREATED,
                        ResourceStatus.CREATED, ResourceStatus.CREATED, ResourceStatus.CREATED,
                        ResourceStatus.CREATED);
        verify(client).ensureWorkspace();
        verify(client).ensureDatastore(properties.getDatastore());
        verify(client).ensureGwcLayer(properties.getLayers().get(0));
        verify(client, never()).ensureGwcLayer(properties.getLayers().get(1));
        verify(client, never()).ensureGwcLayer(properties.getLayers().get(2));
        verify(client, never()).ensureGwcLayer(properties.getLayers().get(3));
        verify(client, never()).ensureGwcLayer(properties.getLayers().get(4));

        InOrder inOrder = inOrder(client);
        inOrder.verify(client).ensureWorkspace();
        inOrder.verify(client).ensureStyle(properties.getStyles().get(0));
        inOrder.verify(client).ensureStyle(properties.getStyles().get(1));
        inOrder.verify(client).ensureDatastore(properties.getDatastore());
        for (Layer layer : properties.getLayers()) {
            inOrder.verify(client).ensureFeatureType(layer);
        }
        inOrder.verify(client).ensureGwcLayer(properties.getLayers().get(0));
    }

    @Test
    void initializeRecordsFailureAndContinuesWithNextConfiguredResource() {
        GeoServerRestClient client = mock(GeoServerRestClient.class);
        GeoServerInitProperties properties = properties();

        when(client.ensureWorkspace())
                .thenThrow(new IllegalStateException("GeoServer rejected workspace"));
        when(client.ensureStyle(properties.getStyles().get(0)))
                .thenReturn(ResourceAction.created("style", "site_selection:count_style", "created"));

        InitResult result = new GeoServerInitService(client, properties).initialize();

        // 失败步骤会被记录，但服务仍会继续处理后续配置项。
        assertThat(result.getActions()).hasSize(10);
        assertThat(result.getActions().get(0).getStatus()).isEqualTo(ResourceStatus.FAILED);
        assertThat(result.getActions().get(0).getMessage()).contains("GeoServer rejected workspace");
        assertThat(result.getActions().get(1).getStatus()).isEqualTo(ResourceStatus.CREATED);
    }

    @Test
    void startupRunnerSkipsInitWhenManagedDeploymentWillInitializeAfterGeoServerReady() throws Exception {
        GeoServerInitProperties properties = properties();
        properties.getInit().setRunOnStartup(true);
        Deploy deploy = new Deploy();
        deploy.setEnabled(true);
        properties.setDeploy(deploy);
        GeoServerInitService initService = mock(GeoServerInitService.class);

        new GeoServerStartupRunner(properties, initService).run(mock(org.springframework.boot.ApplicationArguments.class));

        verify(initService, never()).initialize();
    }

    @Test
    void startupRunnerKeepsOriginalRunOnStartupBehaviorWhenManagedDeploymentDisabled() throws Exception {
        GeoServerInitProperties properties = properties();
        properties.getInit().setRunOnStartup(true);
        GeoServerInitService initService = mock(GeoServerInitService.class);

        new GeoServerStartupRunner(properties, initService).run(mock(org.springframework.boot.ApplicationArguments.class));

        verify(initService).initialize();
    }

    private GeoServerInitProperties properties() {
        // 最小化内存配置，等价于 YAML 中 site_selection 单工作区、单数据源、5 图层。
        Datastore datastore = new Datastore();
        datastore.setName("gauss_store");

        Style countStyle = new Style();
        countStyle.setName("count_style");
        countStyle.setSldLocation("classpath:styles/count-style.sld");

        Style priceStyle = new Style();
        priceStyle.setName("price_style");
        priceStyle.setSldLocation("classpath:styles/price-style.sld");

        GeoServerInitProperties properties = new GeoServerInitProperties();
        properties.setWorkspace("site_selection");
        properties.setDatastore(datastore);
        properties.setStyles(Arrays.asList(countStyle, priceStyle));
        properties.setLayers(Arrays.asList(
                layer("basic_all", true),
                layer("basic", false),
                layer("scene", false),
                layer("finance_app", false),
                layer("land_val", false)));
        return properties;
    }

    private Layer layer(String name, boolean wmtsEnabled) {
        Layer layer = new Layer();
        layer.setName(name);
        layer.setBatchIdDefault("1001");
        Wmts wmts = new Wmts();
        wmts.setEnabled(wmtsEnabled);
        wmts.setGridsets(Collections.singletonList("EPSG:3857"));
        wmts.setFormats(Collections.singletonList("image/png"));
        layer.setWmts(wmts);
        return layer;
    }
}
