package com.geoserve.init.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 GeoServer SQL View 资源和 YAML 参数配置。
 *
 * 这些测试不连接 GeoServer，只确保发布到 GeoServer 的 classpath SQL/YAML
 * 已经和热力图分区总表结构保持一致。
 */
class GeoServerSqlResourceTest {

    @Test
    void sqlTemplatesUseRealPartitionTotalTablesWithoutPlaceholders() throws Exception {
        String[] sqlResources = new String[] {
                "sql/basic_all.sql",
                "sql/basic.sql",
                "sql/scene.sql",
                "sql/finance_app.sql",
                "sql/land_val.sql",
                "sql/mode_result.sql"
        };

        for (String sqlResource : sqlResources) {
            assertThat(read(sqlResource)).doesNotContain("replace_with");
        }
    }

    @Test
    void basicAllReadsPermanentNumberTotalOnlyByBatchId() throws Exception {
        String sql = read("sql/basic_all.sql");

        assertThat(sql).contains("FROM tb_grid_permanent_num_total");
        assertThat(sql).contains("COALESCE(num, 0) AS total_num");
        assertThat(sql).contains("WHERE batch_id = CAST('%batchId%' AS BIGINT)");
        assertThat(sql).doesNotContain("population_type");
    }

    @Test
    void basicLayerAggregatesFilteredMultiSelectParametersByGrid() throws Exception {
        String sql = read("sql/basic.sql");

        assertThat(sql).contains("FROM tb_grid_filter_num_total");
        assertThat(sql).contains("SUM(COALESCE(num, 0)) AS total_num");
        assertThat(sql).contains("code_coun::text = '%county%'");
        assertThat(sql).contains("population_type = ANY (string_to_array('%ptype%', '|'))");
        assertThat(sql).contains("age_type = ANY (string_to_array('%age%', '|'))");
        assertThat(sql).contains("gende = ANY (string_to_array('%gender%', '|'))");
        assertThat(sql).contains("GROUP BY grid_id, geom_polygon");
    }

    @Test
    void dynamicColumnLayersReturnSelectedColumnAsTotalNum() throws Exception {
        assertDynamicColumnSql("sql/scene.sql", "tb_grid_personalized_portrait_total");
        assertDynamicColumnSql("sql/finance_app.sql", "tb_grid_finance_app_total");
        assertDynamicColumnSql("sql/land_val.sql", "tb_grid_land_value_total");
    }

    @Test
    void modeResultReadsScoreResultByBatchIdAndSelectedTypeColumnWithoutGrouping() throws Exception {
        String sql = read("sql/mode_result.sql");

        assertThat(sql).contains("FROM tb_grid_score_result");
        assertThat(sql).contains("COALESCE(%type%, 0) AS total_num");
        assertThat(sql).contains("WHERE batch_id = CAST('%batch_id%' AS BIGINT)");
        assertThat(sql).contains("grid_id");
        assertThat(sql).contains("geom_polygon");
        assertThat(sql).doesNotContain("GROUP BY");
    }

    @Test
    void applicationYamlUsesLayerSpecificPtypeDefaultsAndWhitelists() throws Exception {
        String yaml = read("application.yml");

        assertThat(yaml).contains("default-value: hieg_end_individual");
        assertThat(yaml).contains("hieg_end_individual|college_student|white_collar");
        assertThat(yaml).contains("\"3c\"");
        assertThat(yaml).contains("default-value: debit_card_bc");
        assertThat(yaml).contains("debit_card_bc|debit_card_abc|debit_card_icbc");
        assertThat(yaml).contains("default-value: average_rent");
        assertThat(yaml).contains("average_rent|average_house_price");
        assertThat(yaml).contains("name: mode_result");
        assertThat(yaml).contains("sql-location: classpath:sql/mode_result.sql");
        assertThat(yaml).contains("batch-id-parameter-name: batch_id");
        assertThat(yaml).contains("default-style: score_style");
        assertThat(yaml).contains("default-value: grid_indicator_score");
        assertThat(yaml).contains("grid_indicator_score|grid_pop_score|grid_peer_score");
    }

    @Test
    void sldDisplayTextUsesChineseNames() throws Exception {
        String countSld = read("styles/count-style.sld");
        String priceSld = read("styles/price-style.sld");
        String scoreSld = read("styles/score-style.sld");

        assertThat(countSld).contains("<sld:Name>人口数量样式</sld:Name>");
        assertThat(priceSld).contains("<sld:Name>价格样式</sld:Name>");
        assertThat(scoreSld).contains("<sld:Name>评分样式</sld:Name>");
        assertThat(scoreSld).contains("<ogc:PropertyName>total_num</ogc:PropertyName>");
        assertThat(countSld).contains("<sld:Name>高值</sld:Name>");
        assertThat(countSld).contains("<sld:Name>中值</sld:Name>");
        assertThat(countSld).contains("<sld:Name>低值</sld:Name>");
        assertThat(priceSld).contains("<sld:Name>高值</sld:Name>");
        assertThat(priceSld).contains("<sld:Name>中值</sld:Name>");
        assertThat(priceSld).contains("<sld:Name>低值</sld:Name>");
        assertThat(scoreSld).contains("<sld:Name>高值</sld:Name>");
        assertThat(scoreSld).contains("<sld:Name>中值</sld:Name>");
        assertThat(scoreSld).contains("<sld:Name>低值</sld:Name>");
        assertThat(countSld).doesNotContain("<sld:Name>high</sld:Name>");
        assertThat(countSld).doesNotContain("<sld:Name>medium</sld:Name>");
        assertThat(countSld).doesNotContain("<sld:Name>low</sld:Name>");
        assertThat(priceSld).doesNotContain("<sld:Name>high</sld:Name>");
        assertThat(priceSld).doesNotContain("<sld:Name>medium</sld:Name>");
        assertThat(priceSld).doesNotContain("<sld:Name>low</sld:Name>");
    }

    @Test
    void localPostgresProfilePublishesOnlyBasicLayerWithLocalEnvironmentVariables() throws Exception {
        String yaml = read("application-local-postgres.yml");

        assertThat(yaml).contains("base-url: ${LOCAL_GEOSERVER_BASE_URL:http://localhost:8080/geoserver}");
        assertThat(yaml).contains("username: ${LOCAL_GEOSERVER_USERNAME:}");
        assertThat(yaml).contains("password: ${LOCAL_GEOSERVER_PASSWORD:}");
        assertThat(yaml).contains("workspace: ${LOCAL_GEOSERVER_WORKSPACE:site_selection_local}");
        assertThat(yaml).contains("name: local_postgres_store");
        assertThat(yaml).contains("host: ${LOCAL_PG_HOST:localhost}");
        assertThat(yaml).contains("database: ${LOCAL_PG_DATABASE:gisdb}");
        assertThat(yaml).contains("sql-location: classpath:sql/basic.sql");
        assertThat(yaml).contains("batch-id-default: ${LOCAL_BASIC_BATCH_ID_DEFAULT:202511310100}");
        assertThat(yaml).contains("default-style: count_style");
        assertThat(yaml).doesNotContain("basic_all");
        assertThat(yaml).doesNotContain("scene");
        assertThat(yaml).doesNotContain("finance_app");
        assertThat(yaml).doesNotContain("land_val");
        assertThat(yaml).doesNotContain("mode_result");
    }

    @Test
    void localPostgresPartitionScriptBuildsBasicTotalPartitionFromSourceTables() throws Exception {
        String sql = readFile("docs/sql/local-postgres-basic-partition.sql");

        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis");
        assertThat(sql).contains("\\set batch_id 202511310100");
        assertThat(sql).contains("\\set biz_date 20251131");
        assertThat(sql).contains("\\set city_code 100");
        assertThat(sql).contains("tb_grid_filter_num_total");
        assertThat(sql).contains("PARTITION BY LIST (batch_id)");
        assertThat(sql).contains("JOIN public.tb_grid_filter_num");
        assertThat(sql).contains("FROM public.tb_grid g");
        assertThat(sql).contains("PARTITION OF public.tb_grid_filter_num_total");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS");
        assertThat(sql).contains("DELETE FROM public.tb_grid_filter_num_total");
        assertThat(sql).contains("INSERT INTO public.tb_grid_filter_num_total");
        assertThat(sql).contains("population_type, age_type, gende, num");
    }

    @Test
    void localPostgresPrivateEnvironmentFileIsIgnoredAndExampleIsSanitized() throws Exception {
        String gitignore = readFile(".gitignore");
        String example = readFile(".env.local-postgres.example");

        assertThat(gitignore).contains(".env.local-postgres");
        assertThat(example).contains("LOCAL_GEOSERVER_BASE_URL=");
        assertThat(example).contains("LOCAL_BASIC_BATCH_ID_DEFAULT=\"202511310100\"");
        String sensitiveIp = String.join(".", "192", "168", "100", "116");
        assertThat(example).doesNotContain(sensitiveIp);
        assertThat(example).doesNotContain("postgres:" + "postgres");
        assertThat(example).doesNotContain("geoserver");
    }

    @Test
    void applicationYamlDefinesManagedDeploymentAndProjectFileLogging() throws Exception {
        String yaml = read("application.yml");

        assertThat(yaml).contains("logging:");
        assertThat(yaml).contains("name: ${APP_LOG_FILE:logs/geoserve-init.log}");
        assertThat(yaml).contains("deploy:");
        assertThat(yaml).contains("enabled: ${GEOSERVER_DEPLOY_ENABLED:false}");
        assertThat(yaml).contains("archive-location: ${GEOSERVER_DEPLOY_ARCHIVE_LOCATION:classpath:geoserver/geoserver-bin.zip}");
        assertThat(yaml).contains("local-root: ${GEOSERVER_DEPLOY_LOCAL_ROOT:runtime/geoserver}");
        assertThat(yaml).contains("tile-root: ${GEOSERVER_DEPLOY_TILE_ROOT:runtime/geoserver/gwc-cache}");
        assertThat(yaml).contains("install-dir: ${GEOSERVER_DEPLOY_INSTALL_DIR:${geoserver.deploy.local-root}/install}");
        assertThat(yaml).contains("data-dir: ${GEOSERVER_DEPLOY_DATA_DIR:${geoserver.deploy.local-root}/data}");
        assertThat(yaml).contains("cache-dir: ${GEOSERVER_DEPLOY_CACHE_DIR:${geoserver.deploy.tile-root}}");
        assertThat(yaml).contains("cache-dir-per-host-enabled: ${GEOSERVER_DEPLOY_CACHE_DIR_PER_HOST_ENABLED:true}");
        assertThat(yaml).contains("log-location: ${GEOSERVER_DEPLOY_LOG_LOCATION:${geoserver.deploy.local-root}/logs/geoserver.log}");
        assertThat(yaml).contains("jvm-max-heap: ${GEOSERVER_DEPLOY_JVM_MAX_HEAP:4g}");
        assertThat(yaml).contains("jdbc-driver-location: ${GEOSERVER_DEPLOY_JDBC_DRIVER_LOCATION:classpath:geoserver/gsjdbc4.jar}");
        assertThat(yaml).contains("jdbc-driver-target-lib-dir: ${GEOSERVER_DEPLOY_JDBC_DRIVER_TARGET_LIB_DIR:webapps/geoserver/WEB-INF/lib}");
        assertThat(yaml).contains("admin-user-name: ${GEOSERVER_DEPLOY_ADMIN_USER_NAME:admin}");
        assertThat(yaml).contains("admin-password-encoded: ${GEOSERVER_DEPLOY_ADMIN_PASSWORD_ENCODED:}");
        assertThat(yaml).contains("users-xml-path: ${GEOSERVER_DEPLOY_USERS_XML_PATH:security/usergroup/default/users.xml}");
        assertThat(yaml).contains("startup-timeout-seconds: ${GEOSERVER_DEPLOY_STARTUP_TIMEOUT_SECONDS:120}");
    }

    @Test
    void geoserverArchiveDirectoryKeepsPlaceholderButIgnoresLargeRuntimePackages() throws Exception {
        String gitignore = readFile(".gitignore");

        assertThat(gitignore).contains("src/main/resources/geoserver/*.zip");
        assertThat(gitignore).contains("src/main/resources/geoserver/*.jar");
        assertThat(readFile("src/main/resources/geoserver/.gitkeep"))
                .contains("本目录用于本地放置 GeoServer ZIP 包和 GaussDB JDBC 驱动包");
    }

    @Test
    void readmeExplainsSharedTileCacheConfigurationBeforeStartup() throws Exception {
        String readme = readFile("README.md");

        assertThat(readme).contains("score_style");
        assertThat(readme).contains("GEOSERVER_DEPLOY_LOCAL_ROOT=/opt/geoserve/geoserver");
        assertThat(readme).contains("GEOSERVER_DEPLOY_TILE_ROOT=/geoserver");
        assertThat(readme).contains("业务项目只需要改两个路径和账号密码");
        assertThat(readme).contains("默认会给 GeoServer 子进程追加 `-Xmx4g`");
        assertThat(readme).contains("gsjdbc4.jar");
        assertThat(readme).contains("postgresql*.jar");
        assertThat(readme).contains("GEOSERVER_DEPLOY_JDBC_DRIVER_LOCATION");
        assertThat(readme).contains("GEOSERVER_DEPLOY_ADMIN_PASSWORD_ENCODED");
        assertThat(readme).contains("security/usergroup/default/users.xml");
        assertThat(readme).contains("启动前替换");
        assertThat(readme).contains("/geoserver/192_168_0_1_gwc");
        assertThat(readme).contains("启动脚本执行前注入到 `GEOWEBCACHE_CACHE_DIR`");
        assertThat(readme).contains("需要在项目启动前配置");
        assertThat(readme).contains("按本机 IP 自动派生");
    }

    private void assertDynamicColumnSql(String resource, String tableName) throws Exception {
        String sql = read(resource);

        assertThat(sql).contains("FROM " + tableName);
        assertThat(sql).contains("COALESCE(%ptype%, 0) AS total_num");
        assertThat(sql).contains("code_coun::text = '%county%'");
    }

    private String read(String resource) throws Exception {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        assertThat(inputStream).as("classpath resource " + resource).isNotNull();
        try {
            byte[] bytes = new byte[inputStream.available()];
            int read = inputStream.read(bytes);
            assertThat(read).isEqualTo(bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            inputStream.close();
        }
    }

    private String readFile(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
