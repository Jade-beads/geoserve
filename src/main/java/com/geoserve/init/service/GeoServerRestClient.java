package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Datastore;
import com.geoserve.init.config.GeoServerInitProperties.Geometry;
import com.geoserve.init.config.GeoServerInitProperties.Layer;
import com.geoserve.init.config.GeoServerInitProperties.ParameterFilter;
import com.geoserve.init.config.GeoServerInitProperties.SqlParameter;
import com.geoserve.init.config.GeoServerInitProperties.Style;
import com.geoserve.init.config.GeoServerInitProperties.Wmts;
import com.geoserve.init.model.GeoServerStatus;
import com.geoserve.init.model.ResourceAction;
import com.geoserve.init.model.SourceType;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 底层 GeoServer REST/GWC REST 客户端。
 *
 * 这个类负责维护与 GeoServer 的 HTTP 契约：
 * - 先通过 GET 判断资源是否已经存在。
 * - 只有资源缺失时才执行 POST/PUT。
 * - payload 构造逻辑集中在这里，方便从 YAML 配置一路追到发送给 GeoServer 的 JSON/XML。
 */
@Component
public class GeoServerRestClient {

    private static final Logger log = LoggerFactory.getLogger(GeoServerRestClient.class);

    private final RestTemplate restTemplate;
    private final GeoServerInitProperties properties;
    private final ResourceLoader resourceLoader;

    public GeoServerRestClient(RestTemplate restTemplate,
                               GeoServerInitProperties properties,
                               ResourceLoader resourceLoader) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 读取 GeoServer 版本信息，用于证明 base URL 和 Basic 认证可用。
     */
    public GeoServerStatus checkStatus() {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url("/rest/about/version"),
                    HttpMethod.GET,
                    new HttpEntity<Void>(jsonHeaders()),
                    Map.class);
            return new GeoServerStatus(true, findVersion(response.getBody()), "GeoServer is reachable");
        } catch (RestClientException ex) {
            return new GeoServerStatus(false, null, ex.getMessage());
        }
    }

    /**
     * 确保工作区存在。
     *
     * REST 链路：
     * 1. GET /rest/workspaces/{workspace}.json
     * 2. GET 返回 404 时 POST /rest/workspaces
     */
    public ResourceAction ensureWorkspace() {
        String name = workspace();
        if (exists("/rest/workspaces/" + name + ".json")) {
            return ResourceAction.skipped("workspace", name, "Workspace already exists");
        }

        Map<String, Object> workspaceBody = object("workspace", object("name", name));
        restTemplate.exchange(url("/rest/workspaces"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(workspaceBody, jsonHeaders()), Void.class);
        return ResourceAction.created("workspace", name, "Workspace created");
    }

    /**
     * 通过上传 SLD 文档确保工作区样式存在。
     *
     * REST 链路：
     * 1. GET /rest/workspaces/{workspace}/styles/{style}.json
     * 2. 样式缺失时 POST /rest/workspaces/{workspace}/styles?name={style}，
     *    Content-Type 为 application/vnd.ogc.sld+xml。
     */
    public ResourceAction ensureStyle(Style style) {
        String workspace = workspace();
        String name = required(style.getName(), "style.name");
        String qualifiedName = workspace + ":" + name;
        if (exists("/rest/workspaces/" + workspace + "/styles/" + name + ".json")) {
            return ResourceAction.skipped("style", qualifiedName, "Style already exists");
        }

        String sld = readResource(required(style.getSldLocation(), "style.sldLocation"));
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.ogc.sld+xml"));
        restTemplate.exchange(url("/rest/workspaces/" + workspace + "/styles?name=" + name),
                HttpMethod.POST, new HttpEntity<String>(sld, headers), Void.class);
        return ResourceAction.created("style", qualifiedName, "Style created");
    }

    /**
     * 确保 GaussDB/openGauss 使用的 PostGIS 兼容数据源存在。
     *
     * 数据源连接参数在 {@link #datastorePayload(Datastore)} 中组装，结构遵循 GeoServer 需要的
     * {@code connectionParameters.entry}。
     */
    public ResourceAction ensureDatastore(Datastore datastore) {
        String workspace = workspace();
        String name = required(datastore.getName(), "datastore.name");
        String qualifiedName = workspace + ":" + name;
        if (exists("/rest/workspaces/" + workspace + "/datastores/" + name + ".json")) {
            return ResourceAction.skipped("datastore", qualifiedName, "Datastore already exists");
        }

        restTemplate.exchange(url("/rest/workspaces/" + workspace + "/datastores"),
                HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(datastorePayload(datastore), jsonHeaders()),
                Void.class);
        return ResourceAction.created("datastore", qualifiedName, "Datastore created");
    }

    /**
     * 确保 FeatureType 已发布成 GeoServer 图层。
     *
     * TABLE 图层直接映射物理表。SQL_VIEW 图层会带上 {@code JDBC_VIRTUAL_TABLE} 元数据，
     * 元数据由 classpath SQL 和参数校验器生成。GeoServer 创建 FeatureType 后会自动生成 Layer。
     */
    public ResourceAction ensureFeatureType(Layer layer) {
        String workspace = workspace();
        String name = required(layer.getName(), "layer.name");
        String qualifiedName = workspace + ":" + name;
        if (exists("/rest/layers/" + qualifiedName + ".json")) {
            return ResourceAction.skipped("layer", qualifiedName, "Layer already exists");
        }

        String datastore = datastoreName();
        String createPath = "/rest/workspaces/" + workspace + "/datastores/" + datastore
                + "/featuretypes?recalculate=nativebbox,latlonbbox";
        Map<String, Object> payload = featureTypePayload(layer);
        log.info("GeoServer feature type create start layer={} datastore={} url={} sourceType={} sqlLocation={} geometry={} params={}",
                qualifiedName,
                workspace + ":" + datastore,
                createPath,
                layer.getSourceType(),
                defaultString(layer.getSqlLocation(), ""),
                geometryForLog(layer),
                paramsForLog(layer));
        // recalculate 要求 GeoServer 在发布时计算 native bbox 和 lat/lon bbox。
        try {
            restTemplate.exchange(url(createPath),
                    HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(payload, jsonHeaders()),
                    Void.class);
        } catch (RestClientException ex) {
            log.error("GeoServer feature type create failed layer={} datastore={} url={} sqlLocation={} geometry={} params={} sql={} response={}",
                    qualifiedName,
                    workspace + ":" + datastore,
                    createPath,
                    defaultString(layer.getSqlLocation(), ""),
                    geometryForLog(layer),
                    paramsForLog(layer),
                    compactSql(sqlForLog(layer)),
                    responseForLog(ex),
                    ex);
            throw ex;
        }

        if (hasText(layer.getDefaultStyle())) {
            // FeatureType 创建后 Layer 已存在；这里第二次调用用于绑定默认样式。
            restTemplate.exchange(url("/rest/layers/" + qualifiedName),
                    HttpMethod.PUT,
                    new HttpEntity<Map<String, Object>>(layerStylePayload(layer), jsonHeaders()),
                    Void.class);
        }

        return ResourceAction.created("layer", qualifiedName, "Layer created");
    }

    private String sqlForLog(Layer layer) {
        if (layer.getSourceType() != SourceType.SQL_VIEW || !hasText(layer.getSqlLocation())) {
            return "";
        }
        return readResource(layer.getSqlLocation());
    }

    private String compactSql(String sql) {
        if (sql == null) {
            return "";
        }
        String compact = sql.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= 1000 ? compact : compact.substring(0, 1000) + "...";
    }

    private String geometryForLog(Layer layer) {
        Geometry geometry = layer.getGeometry();
        if (geometry == null) {
            return "";
        }
        return defaultString(geometry.getName(), "") + "/"
                + defaultString(geometry.getType(), "") + "/"
                + geometry.getSrid();
    }

    private String paramsForLog(Layer layer) {
        List<Map<String, Object>> params = sqlParameters(layer);
        List<String> items = new ArrayList<String>();
        for (Map<String, Object> param : params) {
            items.add(String.valueOf(param.get("name")) + "="
                    + String.valueOf(param.get("defaultValue"))
                    + " regex=" + String.valueOf(param.get("regexpValidator")));
        }
        return join(items, ", ");
    }

    private String responseForLog(RestClientException ex) {
        if (ex instanceof HttpStatusCodeException) {
            HttpStatusCodeException httpEx = (HttpStatusCodeException) ex;
            return "status=" + httpEx.getStatusCode()
                    + " body=" + defaultString(httpEx.getResponseBodyAsString(), "");
        }
        return defaultString(ex.getMessage(), ex.getClass().getName());
    }

    /**
     * 确保用于 WMTS 瓦片服务的 GeoWebCache 图层存在。
     *
     * GWC 需要在 GeoServer Layer 已存在后配置。参数过滤器对动态 SQL View 瓦片很关键，
     * 因为它会成为缓存 key 的一部分。
     */
    public ResourceAction ensureGwcLayer(Layer layer) {
        String workspace = workspace();
        String name = required(layer.getName(), "layer.name");
        String qualifiedName = workspace + ":" + name;
        if (layer.getWmts() == null || !layer.getWmts().isEnabled()) {
            return ResourceAction.skipped("gwc-layer", qualifiedName, "WMTS disabled");
        }
        if (exists("/gwc/rest/layers/" + qualifiedName + ".xml")) {
            return ResourceAction.skipped("gwc-layer", qualifiedName, "GWC layer already exists");
        }

        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        restTemplate.exchange(url("/gwc/rest/layers/" + qualifiedName + ".xml"),
                HttpMethod.PUT,
                new HttpEntity<String>(gwcLayerXml(layer), headers),
                Void.class);
        return ResourceAction.created("gwc-layer", qualifiedName, "GWC WMTS layer created");
    }

    /**
     * 所有 ensure* 方法共享的幂等检查。
     *
     * 只有 404 表示资源缺失；其他 HTTP 错误都按真实失败继续抛出。
     */
    private boolean exists(String path) {
        try {
            restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<Void>(jsonHeaders()), String.class);
            return true;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 构造 GeoServer datastore REST API 接受的 JSON body。
     */
    private Map<String, Object> datastorePayload(Datastore datastore) {
        Map<String, Object> dataStore = new LinkedHashMap<String, Object>();
        dataStore.put("name", required(datastore.getName(), "datastore.name"));
        dataStore.put("description", defaultString(datastore.getDescription(), datastore.getName()));
        dataStore.put("type", "PostGIS");
        dataStore.put("enabled", datastore.isEnabled());
        dataStore.put("connectionParameters", object("entry", datastoreEntries(datastore)));
        return object("dataStore", dataStore);
    }

    /**
     * 将 GaussDB/openGauss 配置转换成 GeoServer PostGIS 连接参数 entry。
     */
    private List<Map<String, Object>> datastoreEntries(Datastore datastore) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        entries.add(entry("dbtype", defaultString(datastore.getDbtype(), "postgis")));
        entries.add(entry("host", required(datastore.getHost(), "datastore.host")));
        entries.add(entry("port", String.valueOf(datastore.getPort())));
        entries.add(entry("database", required(datastore.getDatabase(), "datastore.database")));
        entries.add(entry("schema", defaultString(datastore.getSchema(), "public")));
        entries.add(entry("user", required(datastore.getUsername(), "datastore.username")));
        entries.add(entry("passwd", required(datastore.getPassword(), "datastore.password")));
        entries.add(entry("namespace", workspace()));
        // 下面这些参数用于优化 bbox 计算和连接池健康度。
        entries.add(entry("Loose bbox", "true"));
        entries.add(entry("Estimated extends", "true"));
        entries.add(entry("validate connections", "true"));
        entries.add(entry("fetch size", "1000"));
        entries.add(entry("min connections", "1"));
        entries.add(entry("max connections", "10"));
        entries.add(entry("Connection timeout", "20"));
        entries.add(entry("Expose primary keys", "false"));
        return entries;
    }

    /**
     * 为物理表或 SQL View 构造 FeatureType payload。
     */
    private Map<String, Object> featureTypePayload(Layer layer) {
        Map<String, Object> featureType = new LinkedHashMap<String, Object>();
        featureType.put("name", required(layer.getName(), "layer.name"));
        featureType.put("nativeName", nativeName(layer));
        featureType.put("title", defaultString(layer.getTitle(), layer.getName()));
        featureType.put("enabled", layer.isEnabled());
        featureType.put("srs", defaultString(layer.getSrs(), "EPSG:4326"));
        featureType.put("projectionPolicy", "FORCE_DECLARED");
        featureType.put("store", object("name", workspace() + ":" + datastoreName()));

        if (layer.getSourceType() == SourceType.SQL_VIEW) {
            // GeoServer 会把 JDBC virtual table 定义放在 FeatureType metadata 中。
            featureType.put("metadata", object("entry", jdbcVirtualTableEntry(layer)));
        }

        return object("featureType", featureType);
    }

    /**
     * GeoServer 识别 SQL View/JDBC virtual table 所需的 metadata entry。
     */
    private Map<String, Object> jdbcVirtualTableEntry(Layer layer) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("@key", "JDBC_VIRTUAL_TABLE");
        entry.put("virtualTable", virtualTable(layer));
        return entry;
    }

    /**
     * 构造 SQL View 定义。
     *
     * SQL 文本从资源文件读取，并使用 GeoServer 的 %param% 占位符。输入校验交给 GeoServer，
     * 由 YAML 中配置的 regexpValidator 控制。
     */
    private Map<String, Object> virtualTable(Layer layer) {
        Geometry geometry = layer.getGeometry();
        if (geometry == null) {
            throw new IllegalArgumentException("layer.geometry is required for SQL_VIEW layer " + layer.getName());
        }

        Map<String, Object> virtualTable = new LinkedHashMap<String, Object>();
        virtualTable.put("name", layer.getName());
        virtualTable.put("sql", readResource(required(layer.getSqlLocation(), "layer.sqlLocation")));
        virtualTable.put("escapeSql", false);
        virtualTable.put("geometry", geometryPayload(geometry));
        virtualTable.put("parameter", sqlParameters(layer));
        return virtualTable;
    }

    /**
     * 将 SQL View 参数转换成 GeoServer virtual table 参数定义。
     */
    private List<Map<String, Object>> sqlParameters(Layer layer) {
        List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        parameters.add(sqlParameter("batchId",
                required(layer.getBatchIdDefault(), "layer.batchIdDefault"),
                "^[0-9]+$"));
        if (layer.getSqlParameters() == null) {
            return parameters;
        }
        for (SqlParameter parameter : layer.getSqlParameters()) {
            if ("batchId".equals(parameter.getName())) {
                continue;
            }
            parameters.add(sqlParameter(
                    required(parameter.getName(), "sqlParameter.name"),
                    required(parameter.getDefaultValue(), "sqlParameter.defaultValue"),
                    required(parameter.getRegexpValidator(), "sqlParameter.regexpValidator")));
        }
        return parameters;
    }

    private Map<String, Object> sqlParameter(String name, String defaultValue, String regexpValidator) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("defaultValue", defaultValue);
        payload.put("regexpValidator", regexpValidator);
        return payload;
    }

    /**
     * 声明 SQL View/table 返回的几何字段，供 GeoServer 发布图层。
     */
    private Map<String, Object> geometryPayload(Geometry geometry) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", required(geometry.getName(), "geometry.name"));
        payload.put("type", required(geometry.getType(), "geometry.type"));
        payload.put("srid", geometry.getSrid());
        return payload;
    }

    /**
     * 构造后续更新 Layer 默认样式的 payload。
     */
    private Map<String, Object> layerStylePayload(Layer layer) {
        String styleName = layer.getDefaultStyle();
        if (!styleName.contains(":")) {
            styleName = workspace() + ":" + styleName;
        }
        Map<String, Object> layerPayload = new LinkedHashMap<String, Object>();
        layerPayload.put("defaultStyle", object("name", styleName));
        layerPayload.put("enabled", layer.isEnabled());
        return object("layer", layerPayload);
    }

    /**
     * 构造 GeoWebCache GeoServerLayer XML。
     *
     * WMTS parameter filters 在这里序列化。对于 SQL View 图层，VIEWPARAMS 应配置为
     * regexParameterFilter，让不同请求参数生成不同缓存 key，同时仍受正则约束。
     */
    private String gwcLayerXml(Layer layer) {
        Wmts wmts = layer.getWmts();
        List<String> gridsets = wmts.getGridsets() == null || wmts.getGridsets().isEmpty()
                ? defaultList("EPSG:4326") : wmts.getGridsets();
        List<String> formats = wmts.getFormats() == null || wmts.getFormats().isEmpty()
                ? defaultList("image/png") : wmts.getFormats();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<GeoServerLayer>");
        xml.append("<enabled>true</enabled>");
        xml.append("<inMemoryCached>true</inMemoryCached>");
        xml.append("<name>").append(xml(workspace() + ":" + layer.getName())).append("</name>");
        xml.append("<mimeFormats>");
        for (String format : formats) {
            xml.append("<string>").append(xml(format)).append("</string>");
        }
        xml.append("</mimeFormats>");
        xml.append("<gridSubsets>");
        for (String gridset : gridsets) {
            xml.append("<gridSubset><gridSetName>").append(xml(gridset)).append("</gridSetName></gridSubset>");
        }
        xml.append("</gridSubsets>");
        xml.append("<metaWidthHeight><int>4</int><int>4</int></metaWidthHeight>");
        xml.append("<expireCache>0</expireCache>");
        xml.append("<expireClients>0</expireClients>");
        xml.append("<parameterFilters>");
        // 即使图层使用默认样式，也保留 STYLES 标准参数过滤器。
        xml.append("<styleParameterFilter><key>STYLES</key><defaultValue></defaultValue></styleParameterFilter>");
        appendParameterFilters(xml, wmts.getParameterFilters());
        xml.append("</parameterFilters>");
        xml.append("<gutter>0</gutter>");
        xml.append("</GeoServerLayer>");
        return xml.toString();
    }

    /**
     * 序列化配置中的 GWC 参数过滤器。
     *
     * type=string 生成白名单过滤器；其他值默认按 regex 处理，这是 VIEWPARAMS 的常用模式。
     */
    private void appendParameterFilters(StringBuilder xml, List<ParameterFilter> filters) {
        if (filters == null) {
            return;
        }
        for (ParameterFilter filter : filters) {
            if ("string".equalsIgnoreCase(filter.getType())) {
                xml.append("<stringParameterFilter>");
                appendFilterCommon(xml, filter);
                xml.append("<values>");
                if (filter.getValues() != null) {
                    for (String value : filter.getValues()) {
                        xml.append("<string>").append(xml(value)).append("</string>");
                    }
                }
                xml.append("</values>");
                xml.append("</stringParameterFilter>");
            } else {
                xml.append("<regexParameterFilter>");
                appendFilterCommon(xml, filter);
                xml.append("<regex>").append(xml(required(filter.getRegex(), "wmts.parameterFilter.regex"))).append("</regex>");
                xml.append("</regexParameterFilter>");
            }
        }
    }

    /**
     * 写入 stringParameterFilter 和 regexParameterFilter 共有的字段。
     */
    private void appendFilterCommon(StringBuilder xml, ParameterFilter filter) {
        xml.append("<key>").append(xml(required(filter.getKey(), "wmts.parameterFilter.key"))).append("</key>");
        xml.append("<defaultValue>").append(xml(required(filter.getDefaultValue(), "wmts.parameterFilter.defaultValue")))
                .append("</defaultValue>");
    }

    /**
     * 计算 GeoServer 底层 native name。
     *
     * SQL View 使用 virtual table 名称；TABLE 图层优先使用 table 配置，未配置时使用 name。
     */
    private String nativeName(Layer layer) {
        if (layer.getSourceType() == SourceType.SQL_VIEW) {
            return layer.getName();
        }
        return hasText(layer.getTable()) ? layer.getTable() : layer.getName();
    }

    /**
     * 从 classpath 或其他 Spring ResourceLoader 支持的位置读取 SQL/SLD 资源。
     */
    private String readResource(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Resource not found: " + location);
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read resource " + location, ex);
        }
    }

    /**
     * 从 /rest/about/version 响应 JSON 中提取 GeoServer 版本。
     */
    private String findVersion(Map body) {
        if (body == null) {
            return null;
        }
        Object about = body.get("about");
        if (!(about instanceof Map)) {
            return null;
        }
        Object resources = ((Map) about).get("resource");
        if (resources instanceof List) {
            for (Object item : (List) resources) {
                if (item instanceof Map && "GeoServer".equals(((Map) item).get("@name"))) {
                    Object version = ((Map) item).get("Version");
                    return version == null ? null : String.valueOf(version);
                }
            }
        }
        return null;
    }

    /**
     * GeoServer REST 端点使用的 JSON 请求头。
     */
    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(defaultList(MediaType.APPLICATION_JSON));
        return headers;
    }

    /**
     * 根据脱敏后的运行时配置构造 Basic 认证请求头。
     */
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String userPass = defaultString(properties.getUsername(), "") + ":" + defaultString(properties.getPassword(), "");
        String encoded = Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        return headers;
    }

    /**
     * 拼接配置中的 base URL 和 GeoServer REST path，避免出现重复斜杠。
     */
    private String url(String path) {
        String baseUrl = required(properties.getBaseUrl(), "geoserver.baseUrl");
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    private String workspace() {
        return required(properties.getWorkspace(), "geoserver.workspace");
    }

    private String datastoreName() {
        Datastore datastore = properties.getDatastore();
        if (datastore == null) {
            throw new IllegalArgumentException("geoserver.datastore is required");
        }
        return required(datastore.getName(), "geoserver.datastore.name");
    }

    private Map<String, Object> entry(String key, String value) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("@key", key);
        entry.put("$", value);
        return entry;
    }

    private Map<String, Object> object(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private <T> List<T> defaultList(T value) {
        List<T> list = new ArrayList<T>();
        list.add(value);
        return list;
    }

    private String required(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private String defaultString(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private String join(List<String> items, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(delimiter);
            }
            builder.append(items.get(i));
        }
        return builder.toString();
    }

    private String xml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
