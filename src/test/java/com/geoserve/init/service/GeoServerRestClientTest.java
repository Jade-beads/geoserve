package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Geometry;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.ParameterFilter;
import com.geoserve.init.config.GeoServerInitProperties.SqlParameter;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Wmts;
import com.geoserve.init.model.ResourceAction;
import com.geoserve.init.model.ResourceStatus;
import com.geoserve.init.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 不连接真实 GeoServer，测试 GeoServer HTTP 契约。
 *
 * MockRestServiceServer 会验证 GeoServerRestClient 在单工作区、单数据源模型下生成的
 * REST 路径、方法、请求头和关键 payload 片段。
 */
class GeoServerRestClientTest {

    private GeoServerRestClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // 测试凭据使用虚构值，避免仓库历史中出现真实密钥。
        RestTemplate restTemplate = new RestTemplate();
        GeoServerInitProperties properties = new GeoServerInitProperties();
        properties.setBaseUrl("http://geoserver.local/geoserver");
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setWorkspace("site_selection");
        properties.setDatastore(datastore());

        client = new GeoServerRestClient(restTemplate, properties, new DefaultResourceLoader());
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
    }

    @Test
    void ensureWorkspaceUsesSingleConfiguredWorkspaceAndSkipsWhenAlreadyExists() {
        // 已存在资源返回 200，因此不应继续发送 POST。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection.json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
                .andRespond(withSuccess("{\"workspace\":{\"name\":\"site_selection\"}}", MediaType.APPLICATION_JSON));

        ResourceAction action = client.ensureWorkspace();

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.SKIPPED);
        assertThat(action.getName()).isEqualTo("site_selection");
        server.verify();
    }

    @Test
    void ensureWorkspaceCreatesSingleConfiguredWorkspaceWhenMissing() {
        // 404 触发工作区的幂等创建路径。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"workspace\":{\"name\":\"site_selection\"}}"))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureWorkspace();

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureStyleUsesRootWorkspaceAndUploadsClasspathSldWhenStyleIsMissing() {
        Style style = new Style();
        style.setName("count_style");
        style.setSldLocation("classpath:styles/count-style.sld");

        // 样式缺失时，客户端会读取 classpath SLD 并上传到根工作区。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection/styles/count_style.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection/styles?name=count_style"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType("application/vnd.ogc.sld+xml"))
                .andExpect(content().string(containsString("<sld:StyledLayerDescriptor")))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureStyle(style);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        assertThat(action.getName()).isEqualTo("site_selection:count_style");
        server.verify();
    }

    @Test
    void ensureDatastoreUsesRootWorkspaceAndCreatesPostgisCompatibleGaussDbStoreWhenMissing() {
        Datastore datastore = datastore();

        // 数据源 payload 必须使用 GeoServer PostGIS entry 结构，以兼容 GaussDB/openGauss。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection/datastores/gauss_store.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection/datastores"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"dbtype\",\"$\":\"postgis\"")))
                .andExpect(content().string(containsString("\"host\",\"$\":\"db.example.local\"")))
                .andExpect(content().string(containsString("\"port\",\"$\":\"5432\"")))
                .andExpect(content().string(containsString("\"database\",\"$\":\"gisdb\"")))
                .andExpect(content().string(containsString("\"schema\",\"$\":\"public\"")))
                .andExpect(content().string(containsString("\"user\",\"$\":\"gauss\"")))
                .andExpect(content().string(containsString("\"namespace\",\"$\":\"site_selection\"")))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureDatastore(datastore);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        assertThat(action.getName()).isEqualTo("site_selection:gauss_store");
        server.verify();
    }

    @Test
    void ensureSqlViewLayerUsesRootWorkspaceDatastoreBatchDefaultAndSetsDefaultStyle() {
        Layer layer = sqlViewLayer("basic", false);
        layer.setDefaultStyle("count_style");
        layer.setSqlLocation("classpath:sql/basic.sql");

        // 图层创建会发送 JDBC_VIRTUAL_TABLE 元数据，之后再用第二个 PUT 绑定默认样式。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/layers/site_selection:basic.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/site_selection/datastores/gauss_store/featuretypes?recalculate=nativebbox,latlonbbox"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("JDBC_VIRTUAL_TABLE")))
                .andExpect(content().string(containsString("FROM replace_with_basic_source")))
                .andExpect(content().string(containsString("\"geometry\":{\"name\":\"geom_polygon\",\"type\":\"Polygon\",\"srid\":4326}")))
                .andExpect(content().string(containsString("\"name\":\"batchId\",\"defaultValue\":\"1001\",\"regexpValidator\":\"^[0-9]+$\"")))
                .andExpect(content().string(containsString("\"name\":\"county\",\"defaultValue\":\"-1\",\"regexpValidator\":\"^(-1|[0-9]+)$\"")))
                .andExpect(content().string(containsString("\"name\":\"ptype\",\"defaultValue\":\"all\",\"regexpValidator\":\"^(all|[A-Za-z0-9_-]+(\\\\|[A-Za-z0-9_-]+)*)$\"")))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/layers/site_selection:basic"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("\"defaultStyle\":{\"name\":\"site_selection:count_style\"}")))
                .andRespond(withSuccess());

        ResourceAction action = client.ensureFeatureType(layer);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureGwcLayerCreatesEpsg3857PngRegexBatchIdFilterWhenBasicAllIsMissing() {
        Layer layer = sqlViewLayer("basic_all", true);
        layer.setSqlLocation("classpath:sql/basic_all.sql");

        // GWC 图层 XML 必须包含 VIEWPARAMS，让 batchId 参数化 WMTS 请求生成独立缓存 key。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/gwc/rest/layers/site_selection:basic_all.xml"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/gwc/rest/layers/site_selection:basic_all.xml"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<name>site_selection:basic_all</name>")))
                .andExpect(content().string(containsString("<gridSetName>EPSG:3857</gridSetName>")))
                .andExpect(content().string(containsString("<string>image/png</string>")))
                .andExpect(content().string(containsString("<regexParameterFilter>")))
                .andExpect(content().string(containsString("<key>VIEWPARAMS</key>")))
                .andExpect(content().string(containsString("<defaultValue>batchId:1001</defaultValue>")))
                .andExpect(content().string(containsString("<regex>^batchId:[0-9]+$</regex>")))
                .andRespond(withSuccess());

        ResourceAction action = client.ensureGwcLayer(layer);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureGwcLayerSkipsWmsOnlyLayerWithoutCallingGwcRest() {
        Layer layer = sqlViewLayer("scene", false);

        server.expect(never(), requestTo("http://geoserver.local/geoserver/gwc/rest/layers/site_selection:scene.xml"));

        ResourceAction action = client.ensureGwcLayer(layer);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.SKIPPED);
        assertThat(action.getMessage()).contains("WMTS disabled");
        server.verify();
    }

    private Datastore datastore() {
        // 测试数据源模拟脱敏后的 GaussDB/openGauss PostGIS 兼容配置。
        Datastore datastore = new Datastore();
        datastore.setName("gauss_store");
        datastore.setDescription("gauss_store");
        datastore.setHost("db.example.local");
        datastore.setPort(5432);
        datastore.setDatabase("gisdb");
        datastore.setSchema("public");
        datastore.setUsername("gauss");
        datastore.setPassword("test-db-password");
        datastore.setDbtype("postgis");
        return datastore;
    }

    private Layer sqlViewLayer(String name, boolean wmtsEnabled) {
        // 测试图层模拟生产 SQL View 链路：geometry + parameters + WMTS filter。
        Geometry geometry = new Geometry();
        geometry.setName("geom_polygon");
        geometry.setType("Polygon");
        geometry.setSrid(4326);

        Layer layer = new Layer();
        layer.setName(name);
        layer.setTitle(name);
        layer.setSourceType(SourceType.SQL_VIEW);
        layer.setSqlLocation("classpath:sql/" + name + ".sql");
        layer.setBatchIdDefault("1001");
        layer.setSrs("EPSG:4326");
        layer.setGeometry(geometry);
        layer.setDefaultStyle("count_style");
        layer.setSqlParameters(asList(county(), ptype(), age(), gender()));
        layer.setWmts(wmts(wmtsEnabled));
        return layer;
    }

    private SqlParameter county() {
        SqlParameter parameter = new SqlParameter();
        parameter.setName("county");
        parameter.setDefaultValue("-1");
        parameter.setRegexpValidator("^(-1|[0-9]+)$");
        return parameter;
    }

    private SqlParameter ptype() {
        return stringPipeParameter("ptype");
    }

    private SqlParameter age() {
        return stringPipeParameter("age");
    }

    private SqlParameter gender() {
        return stringPipeParameter("gender");
    }

    private SqlParameter stringPipeParameter(String name) {
        SqlParameter parameter = new SqlParameter();
        parameter.setName(name);
        parameter.setDefaultValue("all");
        parameter.setRegexpValidator("^(all|[A-Za-z0-9_-]+(\\|[A-Za-z0-9_-]+)*)$");
        return parameter;
    }

    private Wmts wmts(boolean enabled) {
        ParameterFilter viewparams = new ParameterFilter();
        viewparams.setType("regex");
        viewparams.setKey("VIEWPARAMS");
        viewparams.setDefaultValue("batchId:1001");
        viewparams.setRegex("^batchId:[0-9]+$");

        Wmts wmts = new Wmts();
        wmts.setEnabled(enabled);
        wmts.setGridsets(Collections.singletonList("EPSG:3857"));
        wmts.setFormats(Collections.singletonList("image/png"));
        wmts.setParameterFilters(Collections.singletonList(viewparams));
        return wmts;
    }

    private String basicAuth() {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString("test-user:test-password".getBytes(StandardCharsets.UTF_8));
    }

    @SafeVarargs
    private final <T> java.util.List<T> asList(T... values) {
        java.util.List<T> list = new java.util.ArrayList<T>();
        Collections.addAll(list, values);
        return list;
    }
}
