package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Geometry;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.ParameterFilter;
import com.geoserve.init.config.GeoServerInitProperties.SqlParameter;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Wmts;
import com.geoserve.init.config.GeoServerInitProperties.Workspace;
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
 * MockRestServiceServer 会验证 GeoServerRestClient 生成的 REST 路径、方法、请求头和关键 payload 片段。
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

        client = new GeoServerRestClient(restTemplate, properties, new DefaultResourceLoader());
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
    }

    @Test
    void ensureWorkspaceSkipsWhenWorkspaceAlreadyExists() {
        Workspace workspace = new Workspace();
        workspace.setName("demo");

        // 已存在资源返回 200，因此不应继续发送 POST。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo.json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
                .andRespond(withSuccess("{\"workspace\":{\"name\":\"demo\"}}", MediaType.APPLICATION_JSON));

        ResourceAction action = client.ensureWorkspace(workspace);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.SKIPPED);
        assertThat(action.getName()).isEqualTo("demo");
        server.verify();
    }

    @Test
    void ensureWorkspaceCreatesWhenWorkspaceIsMissing() {
        Workspace workspace = new Workspace();
        workspace.setName("demo");

        // 404 触发工作区的幂等创建路径。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"workspace\":{\"name\":\"demo\"}}"))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureWorkspace(workspace);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureStyleUploadsClasspathSldWhenStyleIsMissing() {
        Style style = new Style();
        style.setWorkspace("demo");
        style.setName("grid_polygon");
        style.setSldLocation("classpath:styles/test-polygon.sld");

        // 样式缺失时，客户端会读取 classpath SLD 并上传到工作区。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo/styles/grid_polygon.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo/styles?name=grid_polygon"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType("application/vnd.ogc.sld+xml"))
                .andExpect(content().string(containsString("<sld:StyledLayerDescriptor")))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureStyle(style);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureDatastoreCreatesPostgisCompatibleGaussDbStoreWhenMissing() {
        Datastore datastore = datastore();

        // 数据源 payload 必须使用 GeoServer PostGIS entry 结构，以兼容 GaussDB/openGauss。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo/datastores/gauss_store.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo/datastores"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"dbtype\",\"$\":\"postgis\"")))
                .andExpect(content().string(containsString("\"host\",\"$\":\"db.example.local\"")))
                .andExpect(content().string(containsString("\"port\",\"$\":\"5432\"")))
                .andExpect(content().string(containsString("\"database\",\"$\":\"gisdb\"")))
                .andExpect(content().string(containsString("\"schema\",\"$\":\"public\"")))
                .andExpect(content().string(containsString("\"user\",\"$\":\"gauss\"")))
                .andRespond(withStatus(HttpStatus.CREATED));

        ResourceAction action = client.ensureDatastore(datastore);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureSqlViewLayerCreatesVirtualTableAndSetsDefaultStyleWhenLayerIsMissing() {
        Layer layer = sqlViewLayer();

        // 图层创建会发送 JDBC_VIRTUAL_TABLE 元数据，之后再用第二个 PUT 绑定默认样式。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/layers/demo:grid_finance.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/workspaces/demo/datastores/gauss_store/featuretypes?recalculate=nativebbox,latlonbbox"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("JDBC_VIRTUAL_TABLE")))
                .andExpect(content().string(containsString("FROM v_grid_finance_app")))
                .andExpect(content().string(containsString("\"geometry\":{\"name\":\"geom_polygon\",\"type\":\"Polygon\",\"srid\":4326}")))
                .andExpect(content().string(containsString("\"name\":\"city\",\"defaultValue\":\"-1\",\"regexpValidator\":\"^[-|\\\\w]*$\"")))
                .andExpect(content().string(containsString("\"name\":\"type\",\"defaultValue\":\"all\",\"regexpValidator\":\"^[\\\\w|]*$\"")))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/rest/layers/demo:grid_finance"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("\"defaultStyle\":{\"name\":\"demo:grid_polygon\"}")))
                .andRespond(withSuccess());

        ResourceAction action = client.ensureFeatureType(layer);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    @Test
    void ensureGwcLayerCreatesRegexViewparamsFilterWhenTileLayerIsMissing() {
        Layer layer = sqlViewLayer();

        // GWC 图层 XML 必须包含 VIEWPARAMS，让参数化 WMTS 请求生成独立缓存 key。
        server.expect(once(), requestTo("http://geoserver.local/geoserver/gwc/rest/layers/demo:grid_finance.xml"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("http://geoserver.local/geoserver/gwc/rest/layers/demo:grid_finance.xml"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<name>demo:grid_finance</name>")))
                .andExpect(content().string(containsString("<gridSetName>EPSG:4326</gridSetName>")))
                .andExpect(content().string(containsString("<string>image/png</string>")))
                .andExpect(content().string(containsString("<regexParameterFilter>")))
                .andExpect(content().string(containsString("<key>VIEWPARAMS</key>")))
                .andExpect(content().string(containsString("<defaultValue>city:-1;county:-1;type:all</defaultValue>")))
                .andRespond(withSuccess());

        ResourceAction action = client.ensureGwcLayer(layer);

        assertThat(action.getStatus()).isEqualTo(ResourceStatus.CREATED);
        server.verify();
    }

    private Datastore datastore() {
        // 测试数据源模拟脱敏后的 GaussDB/openGauss PostGIS 兼容配置。
        Datastore datastore = new Datastore();
        datastore.setWorkspace("demo");
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

    private Layer sqlViewLayer() {
        // 测试图层模拟生产 SQL View 链路：geometry + parameters + WMTS filter。
        Geometry geometry = new Geometry();
        geometry.setName("geom_polygon");
        geometry.setType("Polygon");
        geometry.setSrid(4326);

        SqlParameter city = new SqlParameter();
        city.setName("city");
        city.setDefaultValue("-1");
        city.setRegexpValidator("^[-|\\w]*$");

        SqlParameter county = new SqlParameter();
        county.setName("county");
        county.setDefaultValue("-1");
        county.setRegexpValidator("^[-|\\w]*$");

        SqlParameter type = new SqlParameter();
        type.setName("type");
        type.setDefaultValue("all");
        type.setRegexpValidator("^[\\w|]*$");

        ParameterFilter viewparams = new ParameterFilter();
        viewparams.setType("regex");
        viewparams.setKey("VIEWPARAMS");
        viewparams.setDefaultValue("city:-1;county:-1;type:all");
        viewparams.setRegex("^city:[0-9-]+;county:[0-9-]+;type:[\\w|]+$");

        Wmts wmts = new Wmts();
        wmts.setEnabled(true);
        wmts.setGridsets(Collections.singletonList("EPSG:4326"));
        wmts.setFormats(Collections.singletonList("image/png"));
        wmts.setParameterFilters(Collections.singletonList(viewparams));

        Layer layer = new Layer();
        layer.setWorkspace("demo");
        layer.setDatastore("gauss_store");
        layer.setName("grid_finance");
        layer.setTitle("Grid Finance");
        layer.setSourceType(SourceType.SQL_VIEW);
        layer.setSqlLocation("classpath:sql/grid_finance.sql");
        layer.setSrs("EPSG:4326");
        layer.setGeometry(geometry);
        layer.setDefaultStyle("grid_polygon");
        layer.setSqlParameters(asList(city, county, type));
        layer.setWmts(wmts);
        return layer;
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
