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
                "sql/land_val.sql"
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
        assertThat(sql).contains("code_coun = CAST('%county%' AS INTEGER)");
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
    void applicationYamlUsesLayerSpecificPtypeDefaultsAndWhitelists() throws Exception {
        String yaml = read("application.yml");

        assertThat(yaml).contains("default-value: hieg_end_individual");
        assertThat(yaml).contains("hieg_end_individual|college_student|white_collar");
        assertThat(yaml).contains("\"3c\"");
        assertThat(yaml).contains("default-value: debit_card_bc");
        assertThat(yaml).contains("debit_card_bc|debit_card_abc|debit_card_icbc");
        assertThat(yaml).contains("default-value: average_rent");
        assertThat(yaml).contains("average_rent|average_house_price");
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
        assertThat(example).doesNotContain("192.168." + "100.116");
        assertThat(example).doesNotContain("postgres:" + "postgres");
        assertThat(example).doesNotContain("geoserver");
    }

    private void assertDynamicColumnSql(String resource, String tableName) throws Exception {
        String sql = read(resource);

        assertThat(sql).contains("FROM " + tableName);
        assertThat(sql).contains("COALESCE(%ptype%, 0) AS total_num");
        assertThat(sql).contains("code_coun = CAST('%county%' AS INTEGER)");
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
