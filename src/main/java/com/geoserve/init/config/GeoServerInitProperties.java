package com.geoserve.init.config;

import com.geoserve.init.model.SourceType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "geoserver")
public class GeoServerInitProperties {

    private String baseUrl;
    private String username;
    private String password;
    private Init init = new Init();
    private List<Workspace> workspaces = new ArrayList<Workspace>();
    private List<Style> styles = new ArrayList<Style>();
    private List<Datastore> datastores = new ArrayList<Datastore>();
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

    public Init getInit() {
        return init;
    }

    public void setInit(Init init) {
        this.init = init;
    }

    public List<Workspace> getWorkspaces() {
        return workspaces;
    }

    public void setWorkspaces(List<Workspace> workspaces) {
        this.workspaces = workspaces;
    }

    public List<Style> getStyles() {
        return styles;
    }

    public void setStyles(List<Style> styles) {
        this.styles = styles;
    }

    public List<Datastore> getDatastores() {
        return datastores;
    }

    public void setDatastores(List<Datastore> datastores) {
        this.datastores = datastores;
    }

    public List<Layer> getLayers() {
        return layers;
    }

    public void setLayers(List<Layer> layers) {
        this.layers = layers;
    }

    public static class Init {
        private boolean runOnStartup;

        public boolean isRunOnStartup() {
            return runOnStartup;
        }

        public void setRunOnStartup(boolean runOnStartup) {
            this.runOnStartup = runOnStartup;
        }
    }

    public static class Workspace {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Style {
        private String workspace;
        private String name;
        private String sldLocation;

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

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
        private String workspace;
        private String name;
        private String description;
        private String host;
        private int port = 5432;
        private String database;
        private String schema = "public";
        private String username;
        private String password;
        private String dbtype = "postgis";
        private boolean enabled = true;

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

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
        private String workspace;
        private String datastore;
        private String name;
        private String title;
        private SourceType sourceType = SourceType.TABLE;
        private String table;
        private String sqlLocation;
        private String srs = "EPSG:4326";
        private Geometry geometry;
        private String defaultStyle;
        private boolean enabled = true;
        private List<SqlParameter> sqlParameters = new ArrayList<SqlParameter>();
        private Wmts wmts = new Wmts();

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public String getDatastore() {
            return datastore;
        }

        public void setDatastore(String datastore) {
            this.datastore = datastore;
        }

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
        private String name;
        private String type;
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
        private String name;
        private String defaultValue;
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
        private boolean enabled;
        private List<String> gridsets = new ArrayList<String>();
        private List<String> formats = new ArrayList<String>();
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
        private String type = "regex";
        private String key;
        private String defaultValue;
        private String regex;
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
