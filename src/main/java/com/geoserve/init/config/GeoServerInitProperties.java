package com.geoserve.init.config;

import com.geoserve.init.model.SourceType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code geoserver.*} 的根配置模型。
 *
 * 配置链路：
 * application.yml/环境变量 -> 当前 properties 对象 ->
 * GeoServerInitService 编排 -> GeoServerRestClient REST payload。
 *
 * 当前版本固定为“单工作区 + 单数据源”模型：style、layer 不再单独配置 workspace/datastore，
 * 统一使用根配置中的 workspace 和 datastore。
 */
@ConfigurationProperties(prefix = "geoserver")
public class GeoServerInitProperties {

    /** GeoServer 根地址，例如 {@code http://host:8080/geoserver}。 */
    private String baseUrl;
    /** GeoServer REST Basic 认证用户名。 */
    private String username;
    /** GeoServer REST Basic 认证密码。 */
    private String password;
    /** 所有资源统一使用的工作区。 */
    private String workspace = "site_selection";
    /** 启动行为开关。 */
    private Init init = new Init();
    /** 单个高斯数据库数据源配置。 */
    private Datastore datastore = new Datastore();
    /** 需要上传到根工作区的 SLD 样式。 */
    private List<Style> styles = new ArrayList<Style>();
    /** 根数据源存在后需要发布的 Feature 图层。 */
    private List<Layer> layers = new ArrayList<Layer>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Init getInit() {
        return init;
    }

    public void setInit(Init init) {
        this.init = init;
    }

    public Datastore getDatastore() {
        return datastore;
    }

    public void setDatastore(Datastore datastore) {
        this.datastore = datastore;
    }

    public List<Style> getStyles() {
        return styles;
    }

    public void setStyles(List<Style> styles) {
        this.styles = styles;
    }

    public List<Layer> getLayers() {
        return layers;
    }

    public void setLayers(List<Layer> layers) {
        this.layers = layers;
    }

    public static class Init {
        /** 为 true 时，ApplicationRunner 会调用与 POST /init 相同的初始化流程。 */
        private boolean runOnStartup;

        public boolean isRunOnStartup() {
            return runOnStartup;
        }

        public void setRunOnStartup(boolean runOnStartup) {
            this.runOnStartup = runOnStartup;
        }
    }

    public static class Style {
        /** 根工作区内的样式名称。 */
        private String name;
        /** SLD 资源位置，通常为 classpath:styles/*.sld。 */
        private String sldLocation;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSldLocation() {
            return sldLocation;
        }

        public void setSldLocation(String sldLocation) {
            this.sldLocation = sldLocation;
        }
    }

    public static class Datastore {
        /** GeoServer 数据源名称。 */
        private String name;
        /** GeoServer 页面中展示的数据源描述。 */
        private String description;
        /** GeoServer 进程可访问的数据库主机，不一定是当前初始化服务可访问的主机。 */
        private String host;
        /** 数据库端口；GaussDB/openGauss 的 PostGIS 兼容部署通常使用 5432。 */
        private int port = 5432;
        /** 写入 GeoServer 数据源连接参数的数据库名称。 */
        private String database;
        /** 数据源暴露的数据库 schema。 */
        private String schema = "public";
        /** 写入 GeoServer 数据源配置的数据库用户名。 */
        private String username;
        /** 写入 GeoServer 数据源配置的数据库密码。 */
        private String password;
        /** GeoServer store dbtype。GaussDB/openGauss 走 PostgreSQL 兼容时保持 postgis。 */
        private String dbtype = "postgis";
        /** 数据源创建后是否在 GeoServer 中启用。 */
        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDbtype() {
            return dbtype;
        }

        public void setDbtype(String dbtype) {
            this.dbtype = dbtype;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Layer {
        /** 发布后的图层名称。SQL View 图层中它也是 virtual table 名称。 */
        private String name;
        /** GeoServer 中展示的标题；为空时使用 name。 */
        private String title;
        /** TABLE 发布物理表；SQL_VIEW 发布 JDBC virtual table。 */
        private SourceType sourceType = SourceType.TABLE;
        /** TABLE 图层使用的物理表名；为空时使用图层 name。 */
        private String table;
        /** SQL_VIEW 图层使用的 SQL 资源位置，通常为 classpath:sql/*.sql。 */
        private String sqlLocation;
        /** 每个图层单独配置的 batchId 默认值。 */
        private String batchIdDefault;
        /** GeoServer 发布 FeatureType 时声明的 SRS。 */
        private String srs = "EPSG:4326";
        /** GeoServer JDBC virtual table 必需的几何字段元数据。 */
        private Geometry geometry;
        /** 默认样式名称；不带工作区前缀时会自动归属到根工作区。 */
        private String defaultStyle;
        /** 发布后的 GeoServer 图层是否启用。 */
        private boolean enabled = true;
        /** 除 batchId 外，通过 GeoServer viewparams 暴露的 SQL View 参数。 */
        private List<SqlParameter> sqlParameters = new ArrayList<SqlParameter>();
        /** 当前图层可选的 GeoWebCache/WMTS 配置。 */
        private Wmts wmts = new Wmts();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public SourceType getSourceType() {
            return sourceType;
        }

        public void setSourceType(SourceType sourceType) {
            this.sourceType = sourceType;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public String getSqlLocation() {
            return sqlLocation;
        }

        public void setSqlLocation(String sqlLocation) {
            this.sqlLocation = sqlLocation;
        }

        public String getBatchIdDefault() {
            return batchIdDefault;
        }

        public void setBatchIdDefault(String batchIdDefault) {
            this.batchIdDefault = batchIdDefault;
        }

        public String getSrs() {
            return srs;
        }

        public void setSrs(String srs) {
            this.srs = srs;
        }

        public Geometry getGeometry() {
            return geometry;
        }

        public void setGeometry(Geometry geometry) {
            this.geometry = geometry;
        }

        public String getDefaultStyle() {
            return defaultStyle;
        }

        public void setDefaultStyle(String defaultStyle) {
            this.defaultStyle = defaultStyle;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<SqlParameter> getSqlParameters() {
            return sqlParameters;
        }

        public void setSqlParameters(List<SqlParameter> sqlParameters) {
            this.sqlParameters = sqlParameters;
        }

        public Wmts getWmts() {
            return wmts;
        }

        public void setWmts(Wmts wmts) {
            this.wmts = wmts;
        }
    }

    public static class Geometry {
        /** 表或 SQL View 返回的几何字段名。 */
        private String name;
        /** GeoServer 几何类型，例如 Polygon、MultiPolygon、Point。 */
        private String type;
        /** JDBC virtual table 元数据使用的几何 SRID。 */
        private int srid = 4326;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getSrid() {
            return srid;
        }

        public void setSrid(int srid) {
            this.srid = srid;
        }
    }

    public static class SqlParameter {
        /** 参数名，在 SQL 中写作 %name%，请求时写作 viewparams=name:value。 */
        private String name;
        /** 请求未传该 SQL View 参数时 GeoServer 使用的安全默认值。 */
        private String defaultValue;
        /** GeoServer 侧正则校验器。应保持严格，避免不安全 SQL View 输入。 */
        private String regexpValidator;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String getRegexpValidator() {
            return regexpValidator;
        }

        public void setRegexpValidator(String regexpValidator) {
            this.regexpValidator = regexpValidator;
        }
    }

    public static class Wmts {
        /** GeoServer 图层发布完成后，是否继续创建 GWC 图层配置。 */
        private boolean enabled;
        /** GWC gridsets，例如 EPSG:3857。 */
        private List<String> gridsets = new ArrayList<String>();
        /** WMTS 暴露的瓦片 MIME 格式。 */
        private List<String> formats = new ArrayList<String>();
        /** 参与缓存 key 生成的参数过滤器。 */
        private List<ParameterFilter> parameterFilters = new ArrayList<ParameterFilter>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getGridsets() {
            return gridsets;
        }

        public void setGridsets(List<String> gridsets) {
            this.gridsets = gridsets;
        }

        public List<String> getFormats() {
            return formats;
        }

        public void setFormats(List<String> formats) {
            this.formats = formats;
        }

        public List<ParameterFilter> getParameterFilters() {
            return parameterFilters;
        }

        public void setParameterFilters(List<ParameterFilter> parameterFilters) {
            this.parameterFilters = parameterFilters;
        }
    }

    public static class ParameterFilter {
        /** 过滤器类型。动态 VIEWPARAMS 用 regex；固定枚举值用 string。 */
        private String type = "regex";
        /** 请求参数名，例如 VIEWPARAMS。 */
        private String key;
        /** 请求未传该参数时使用的默认缓存 key 值。 */
        private String defaultValue;
        /** regex 过滤器中 GWC 接受的正则表达式。 */
        private String regex;
        /** string 过滤器允许的固定值列表。 */
        private List<String> values = new ArrayList<String>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String getRegex() {
            return regex;
        }

        public void setRegex(String regex) {
            this.regex = regex;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }
    }
}
