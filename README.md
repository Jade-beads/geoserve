# GeoServer 初始化服务

这是一个兼容 JDK 8 的 Spring Boot 服务，用于通过 HTTP REST API 初始化 GeoServer 资源。当前版本按“单工作区 + 单高斯数据库数据源”设计，默认工作区为 `site_selection`，默认数据源为 `gauss_store`。

## 功能说明

- 创建缺失的 `site_selection` 工作区。
- 创建缺失的工作区样式：`count_style`、`price_style`。
- 创建缺失的 GaussDB/openGauss PostGIS 兼容数据源：`gauss_store`。
- 创建缺失的 SQL View 图层：`basic_all`、`basic`、`scene`、`finance_app`、`land_val`。
- 为图层设置默认样式。
- 只为 `basic_all` 创建 GeoWebCache WMTS 配置，使用 `EPSG:3857` 和 `image/png`。
- 每一步都会先判断资源是否存在；存在则返回 `SKIPPED` 并继续执行下一步，不删除、不覆盖、不重建已有资源。

## 接口

- `GET /api/geoserver/status`：检查 GeoServer 地址、认证和版本。
- `POST /api/geoserver/init`：执行初始化流程。

`POST /api/geoserver/init` 会返回每个资源的执行结果，状态包括 `CREATED`、`SKIPPED` 或 `FAILED`。

## 初始化链路

初始化主链路如下：

1. `GeoServerController` 接收 `/api/geoserver/status` 和 `/api/geoserver/init` 请求。
2. `GeoServerInitService` 按 `workspace -> styles -> datastore -> layers -> GWC/WMTS` 顺序编排初始化。
3. `GeoServerRestClient` 执行真实的 GeoServer REST/GWC REST 请求。
4. 每个 `ensure*` 方法都会先 `GET` 判断资源是否存在，不存在时再 `POST` 或 `PUT` 创建。
5. `GeoServerInitProperties` 绑定 `application.yml` 和环境变量，作为资源名称、数据库参数、SQL View 参数、WMTS 参数过滤器的配置来源。
6. `src/main/resources/sql/*.sql` 保存 SQL View 模板，`src/main/resources/styles/*.sld` 保存样式文件。

如果需要从接口调用反查代码，可以按这个路径阅读：

`Controller -> InitService -> RestClient.ensure* -> RestClient payload builder -> application.yml/sql/sld`

## 运行

```bash
mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

服务会读取 `src/main/resources/application.yml`。真实环境参数建议通过环境变量、CI 密钥或服务器启动脚本注入，不要提交到仓库。

## 配置真实环境参数

仓库中的 `application.yml` 已脱敏，只保留本地默认值和空凭据。部署到真实环境时，按下面流程配置：

1. 在仓库外准备私有环境变量文件、CI 密钥配置或服务器启动脚本。
2. 填写 GeoServer 地址、账号密码和 GaussDB/openGauss 连接参数。
3. 如需调整默认批次，填写各图层的 `*_BATCH_ID_DEFAULT`。
4. 使用这些环境变量启动服务。
5. 调用 `GET /api/geoserver/status` 检查连接状态。
6. 状态正常后，调用 `POST /api/geoserver/init` 执行初始化。

必需运行参数：

| 参数 | 说明 | 示例 |
| --- | --- | --- |
| `GEOSERVER_BASE_URL` | GeoServer 根地址，需要以 `/geoserver` 结尾 | `http://<host>:<port>/geoserver` |
| `GEOSERVER_USERNAME` | GeoServer REST 用户名 | `<username>` |
| `GEOSERVER_PASSWORD` | GeoServer REST 密码 | `<password>` |
| `GAUSS_DB_HOST` | GeoServer 可访问的 GaussDB/openGauss 主机 | `<db-host>` |
| `GAUSS_DB_PORT` | GaussDB/openGauss 端口 | `5432` |
| `GAUSS_DB_NAME` | 数据库名称 | `<database>` |
| `GAUSS_DB_SCHEMA` | 数据库 schema | `public` |
| `GAUSS_DB_USERNAME` | 写入 GeoServer 数据源配置的数据库用户名 | `<db-user>` |
| `GAUSS_DB_PASSWORD` | 写入 GeoServer 数据源配置的数据库密码 | `<db-password>` |

可选参数：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `GEOSERVER_WORKSPACE` | 统一工作区名称；一般保持默认即可 | `site_selection` |
| `GEOSERVER_INIT_RUN_ON_STARTUP` | 是否在应用启动时自动执行初始化 | `false` |
| `BASIC_ALL_BATCH_ID_DEFAULT` | `basic_all` 的 `batchId` 默认值 | `1001` |
| `BASIC_BATCH_ID_DEFAULT` | `basic` 的 `batchId` 默认值 | `1001` |
| `SCENE_BATCH_ID_DEFAULT` | `scene` 的 `batchId` 默认值 | `1001` |
| `FINANCE_APP_BATCH_ID_DEFAULT` | `finance_app` 的 `batchId` 默认值 | `1001` |
| `LAND_VAL_BATCH_ID_DEFAULT` | `land_val` 的 `batchId` 默认值 | `1001` |

启动命令示例：

```bash
export GEOSERVER_BASE_URL="http://<host>:<port>/geoserver"
export GEOSERVER_USERNAME="<username>"
export GEOSERVER_PASSWORD="<password>"
export GEOSERVER_WORKSPACE="site_selection"
export GAUSS_DB_HOST="<db-host>"
export GAUSS_DB_PORT="5432"
export GAUSS_DB_NAME="<database>"
export GAUSS_DB_SCHEMA="public"
export GAUSS_DB_USERNAME="<db-user>"
export GAUSS_DB_PASSWORD="<db-password>"
export BASIC_ALL_BATCH_ID_DEFAULT="1001"

mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 本地 PostgreSQL 联调

本地联调用 `local-postgres` profile，不影响默认业务高斯配置。该模式只发布 `basic` 一个 WMS SQL View 图层，工作区默认 `site_selection_local`，数据源默认 `local_postgres_store`。

前提：

- 本地 PostgreSQL 已安装 PostGIS。
- 本地库已有 `public.tb_grid` 和 `public.tb_grid_filter_num`。
- `tb_grid.geom_polygon` 是 GeoServer 可识别的 geometry 字段。

先生成本地分区总表和一个测试批次数据：

```bash
PGPASSWORD=<db-password> psql -h <db-host> -p 5432 -U <db-user> -d <database> \
  -f docs/sql/local-postgres-basic-partition.sql
```

脚本默认使用 `batch_id=202511310100`、`biz_date=20251131`、`city_code=100`。它会创建 `tb_grid_filter_num_total`，按 `batch_id` 创建 LIST 分区，并从 `tb_grid`、`tb_grid_filter_num` 写入当前批次数据。重复执行同一个 `batch_id` 时，会先清理该批次再重新插入。换批次时可以继续通过 `-v batch_id=... -v biz_date=... -v city_code=...` 覆盖默认值。

配置本地 GeoServer 和 PostgreSQL：

```bash
source .env.local-postgres

mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

`.env.local-postgres` 是本地私有文件，已加入 `.gitignore`；仓库只保留 `.env.local-postgres.example` 作为脱敏模板。

启动后先检查 GeoServer 连接，再执行初始化：

```bash
curl -s http://localhost:8081/api/geoserver/status
curl -s -X POST http://localhost:8081/api/geoserver/init
```

本地 WMS 验证示例：

```text
GET /geoserver/site_selection_local/wms?
  service=WMS&
  version=1.1.0&
  request=GetMap&
  layers=site_selection_local:basic&
  styles=&
  bbox=<minx>,<miny>,<maxx>,<maxy>&
  width=768&
  height=512&
  srs=EPSG:4326&
  format=image/png&
  transparent=true&
  viewparams=batchId:202511310100;county:-1;ptype:home%7Cwork;age:all;gender:all
```

## 本机托管 GeoServer 部署

当前服务支持“本机管理”模式：A/B 两台服务器各启动一套本 Java 服务，每套服务只负责解压、启动、停止本机 GeoServer。外部轮循由 Nginx、SLB 或其他负载均衡组件完成。

GeoServer 有 JMS Cluster 方案，但它主要用于配置同步，不负责业务数据和切片数据分发。当前项目不启用 JMS 集群，按两套独立 GeoServer 部署处理。GeoWebCache 切片缓存通过 `GEOWEBCACHE_CACHE_DIR` 独立配置，可指向 A/B 都能访问的共享盘目录；严格生产多实例缓存方案仍建议评估 standalone GeoWebCache。

### 资源包放置

把 GeoServer 官方 ZIP 包放到：

```text
src/main/resources/geoserver/geoserver-bin.zip
```

该目录保留了 `.gitkeep`，真实 ZIP 包已在 `.gitignore` 中忽略，不会提交到 GitHub。当前实现只解压 ZIP 包；压缩包内需要包含 `bin/startup.sh` 和 `bin/shutdown.sh`。

### 启动流程

开启托管部署后，应用启动顺序如下：

1. 创建 `work-dir`、`install-dir`、`data-dir`、`cache-dir`、`log-dir`。
2. 删除旧的 `install-dir`，重新解压 GeoServer ZIP。
3. 设置 `GEOSERVER_DATA_DIR`、`GEOWEBCACHE_CACHE_DIR`、`GEOSERVER_LOG_LOCATION`、`JAVA_HOME`、`JAVA_OPTS`。
4. 执行 GeoServer `bin/startup.sh`。
5. 轮询 `GET /rest/about/version`，等待 GeoServer 可用。
6. 如果 `GEOSERVER_INIT_RUN_ON_STARTUP=true`，再执行 workspace、style、datastore、layer、GWC 初始化。

停止项目时，应用会优先执行 `bin/shutdown.sh`，再销毁子进程。默认只删除 `install-dir`，不会删除 `data-dir`、`cache-dir`、`log-dir`。

### 关键配置

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `GEOSERVER_DEPLOY_ENABLED` | 是否启用本机托管 GeoServer | `false` |
| `GEOSERVER_DEPLOY_NODE_NAME` | 当前节点名，用于日志区分 | `local` |
| `GEOSERVER_DEPLOY_ARCHIVE_LOCATION` | GeoServer ZIP 包位置 | `classpath:geoserver/geoserver-bin.zip` |
| `GEOSERVER_DEPLOY_WORK_DIR` | 托管部署根目录 | `runtime/geoserver` |
| `GEOSERVER_DEPLOY_INSTALL_DIR` | GeoServer 解压运行目录，停止时可删除 | `runtime/geoserver/install` |
| `GEOSERVER_DEPLOY_DATA_DIR` | GeoServer 数据目录，建议 A/B 各自独立 | `runtime/geoserver/data` |
| `GEOSERVER_DEPLOY_CACHE_DIR` | GWC 切片缓存目录，可配置共享盘 | `runtime/geoserver/gwc-cache` |
| `GEOSERVER_DEPLOY_LOG_DIR` | GeoServer 日志目录 | `logs/geoserver` |
| `GEOSERVER_DEPLOY_LOG_LOCATION` | GeoServer 自身日志文件 | `logs/geoserver/geoserver.log` |
| `GEOSERVER_DEPLOY_JAVA_HOME` | 启动 GeoServer 使用的 JDK 路径 | 空，继承当前环境 |
| `GEOSERVER_DEPLOY_PORT` | GeoServer 端口 | `8080` |
| `GEOSERVER_DEPLOY_CONTEXT_PATH` | GeoServer 上下文路径 | `/geoserver` |
| `GEOSERVER_DEPLOY_STARTUP_TIMEOUT_SECONDS` | 等待 GeoServer 可用的超时时间 | `120` |
| `GEOSERVER_DEPLOY_SHUTDOWN_TIMEOUT_SECONDS` | 停止脚本和进程销毁超时时间 | `30` |
| `APP_LOG_FILE` | 当前初始化服务的输出日志文件 | `logs/geoserve-init.log` |

`GEOSERVER_BASE_URL` 必须和托管 GeoServer 的真实端口、上下文路径一致。默认是 `http://localhost:8080/geoserver`；如果调整 `GEOSERVER_DEPLOY_PORT` 或 `GEOSERVER_DEPLOY_CONTEXT_PATH`，需要同步调整 `GEOSERVER_BASE_URL`。

`data-dir`、`cache-dir`、`log-dir`、`log-location` 不能放在 `install-dir` 下面。因为 `install-dir` 会在停止时删除，这些目录如果配置在里面会导致可复用数据被误删，应用会直接启动失败并输出明确错误。

### A/B 节点示例

A 节点：

```bash
export GEOSERVER_DEPLOY_ENABLED=true
export GEOSERVER_DEPLOY_NODE_NAME=A
export GEOSERVER_DEPLOY_INSTALL_DIR=/opt/geoserve/runtime/a/install
export GEOSERVER_DEPLOY_DATA_DIR=/opt/geoserve/runtime/a/data
export GEOSERVER_DEPLOY_LOG_DIR=/opt/geoserve/logs/a
export GEOSERVER_DEPLOY_LOG_LOCATION=/opt/geoserve/logs/a/geoserver.log
export GEOSERVER_DEPLOY_CACHE_DIR=/share/geowebcache/site-selection
export GEOSERVER_BASE_URL=http://localhost:8080/geoserver
export GEOSERVER_INIT_RUN_ON_STARTUP=true
```

B 节点只需要把 `NODE_NAME`、`INSTALL_DIR`、`DATA_DIR`、`LOG_DIR` 改成 B 节点自己的目录，`GEOSERVER_DEPLOY_CACHE_DIR` 可以继续指向同一个共享盘目录。

## 资源清单

样式：

| 样式名 | 文件 | 用途 |
| --- | --- | --- |
| `count_style` | `classpath:styles/count-style.sld` | 人口数量语义配色，按 `total_num` 渲染 |
| `price_style` | `classpath:styles/price-style.sld` | 价格语义配色，按 `total_num` 渲染 |

图层：

| 图层名 | 服务 | SQL 模板 | 数据表 | 参数 | 默认样式 |
| --- | --- | --- | --- | --- | --- |
| `basic_all` | WMTS | `classpath:sql/basic_all.sql` | `tb_grid_permanent_num_total` | `batchId` | `count_style` |
| `basic` | WMS | `classpath:sql/basic.sql` | `tb_grid_filter_num_total` | `batchId county ptype age gender` | `count_style` |
| `scene` | WMS | `classpath:sql/scene.sql` | `tb_grid_personalized_portrait_total` | `batchId county ptype` | `count_style` |
| `finance_app` | WMS | `classpath:sql/finance_app.sql` | `tb_grid_finance_app_total` | `batchId county ptype` | `count_style` |
| `land_val` | WMS | `classpath:sql/land_val.sql` | `tb_grid_land_value_total` | `batchId county ptype` | `price_style` |

所有 SQL View 模板统一返回：

- `grid_id`
- `geom_polygon`
- `total_num`

几何字段统一为 `geom_polygon`，SRID 为 `4326`。SQL 已对应热力图分区任务写入的 5 张总表。

## 参数规则

| 参数 | 适用图层 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `batchId` | 全部图层 | 每个图层单独配置 | 只能是数字 |
| `county` | WMS 图层 | `-1` | `-1` 表示全部县区；其他值映射 `code_coun` |
| `ptype` | `basic` | `all` | 多选值过滤，映射 `population_type`，允许 `all/home/work/home\|work/work\|home` |
| `age` | `basic` | `all` | 多选值过滤，映射 `age_type` |
| `gender` | `basic` | `all` | 多选值过滤，映射 `gende` |
| `ptype` | `scene` | `hieg_end_individual` | 列名参数，只允许场景标签白名单列 |
| `ptype` | `finance_app` | `debit_card_bc` | 列名参数，只允许金融 app 白名单列 |
| `ptype` | `land_val` | `average_rent` | 列名参数，只允许 `average_rent/average_house_price` |

SQL View 文件使用 GeoServer 的 `%param%` 占位符。`basic` 的 `ptype/age/gender` 支持用 `|` 多选过滤，并在过滤后按 `grid_id/geom_polygon` 汇总 `total_num`。`scene`、`finance_app`、`land_val` 的 `ptype` 会直接替换到 SQL 列名位置，因此必须保持 `application.yml` 中的白名单正则足够严格。

## 请求示例

WMS 示例：

```text
GET /geoserver/site_selection/wms?
  service=WMS&
  version=1.1.0&
  request=GetMap&
  layers=site_selection:basic&
  styles=&
  bbox=<minx>,<miny>,<maxx>,<maxy>&
  width=768&
  height=512&
  srs=EPSG:4326&
  format=image/png&
  transparent=true&
  viewparams=batchId:1001;county:-1;ptype:all;age:all;gender:all
```

基础标签多选时建议把 `|` 做 URL 编码：

```text
viewparams=batchId:1001;county:-1;ptype:home%7Cwork;age:all;gender:all
```

动态列 WMS 示例：

```text
GET /geoserver/site_selection/wms?
  service=WMS&
  version=1.1.0&
  request=GetMap&
  layers=site_selection:scene&
  styles=&
  bbox=<minx>,<miny>,<maxx>,<maxy>&
  width=768&
  height=512&
  srs=EPSG:4326&
  format=image/png&
  transparent=true&
  viewparams=batchId:1001;county:-1;ptype:hieg_end_individual
```

如果要请求场景标签里的 `"3c"` 列，双引号需要 URL 编码：

```text
viewparams=batchId:1001;county:-1;ptype:%223c%22
```

WMTS 参数化瓦片示例：

```text
GET /geoserver/gwc/service/wmts?
  SERVICE=WMTS&
  REQUEST=GetTile&
  VERSION=1.0.0&
  LAYER=site_selection:basic_all&
  STYLE=&
  TILEMATRIXSET=EPSG:3857&
  TILEMATRIX=EPSG:3857:<z>&
  TILEROW=<row>&
  TILECOL=<col>&
  FORMAT=image/png&
  VIEWPARAMS=batchId:1001
```

`basic_all` 的 GWC 参数过滤器只允许 `VIEWPARAMS` 匹配 `^batchId:[0-9]+$`。不同 `batchId` 会进入不同缓存 key。

## 注意事项

- GeoServer 全局 WMS/WFS/WCS 能力不在本服务中关闭；本服务只负责发布资源和 `basic_all` 的 WMTS 缓存配置。
- 已存在资源按幂等策略跳过，不会自动更新已有样式、数据源、图层或 GWC 配置。
- 如果需要让已有资源跟随配置变更，请先在 GeoServer 中人工处理旧资源，或单独扩展“更新模式”。
- 请保持 WMTS 参数正则足够严格，避免缓存数量失控。
