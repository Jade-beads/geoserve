# GeoServer 初始化服务

这是一个兼容 JDK 8 的 Spring Boot 服务，用于通过 HTTP REST API 初始化 GeoServer 资源。

## 功能说明

- 创建缺失的工作区。
- 从 classpath 中的 SLD 文件创建缺失的工作区样式。
- 为 GaussDB/openGauss 部署创建 PostGIS 兼容的数据源。
- 创建缺失的普通表图层或 SQL View 图层。
- 为新建图层设置默认样式。
- 创建缺失的 GeoWebCache WMTS 图层配置，并支持正则参数过滤器。
- 不会删除或覆盖已有的 GeoServer 资源。

## 接口

- `GET /api/geoserver/status`
- `POST /api/geoserver/init`

`POST /api/geoserver/init` 会返回每个资源的执行结果，状态包括 `CREATED`、`SKIPPED` 或 `FAILED`。

## 运行

```bash
mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

服务会读取 `src/main/resources/application.yml`。大部分配置也可以通过环境变量覆盖。

## 配置真实环境参数

仓库中提交的 `application.yml` 已经过脱敏处理，只保留安全的本地默认值和空凭据。不要把真实的 GeoServer 地址、数据库主机、用户名或密码提交到仓库。

部署到真实环境时，按下面流程配置：

1. 在仓库外准备私有环境变量文件、CI 密钥配置或服务器启动脚本。
2. 填写下面列出的必需参数。
3. 使用这些环境变量启动服务。
4. 调用 `GET /api/geoserver/status` 检查连接状态。
5. 状态正常后，再调用 `POST /api/geoserver/init` 执行初始化。

必需运行参数：

| 参数 | 说明 | 示例 |
| --- | --- | --- |
| `GEOSERVER_BASE_URL` | GeoServer 根地址，需要以 `/geoserver` 结尾 | `http://<host>:<port>/geoserver` |
| `GEOSERVER_USERNAME` | GeoServer REST 用户名 | `<username>` |
| `GEOSERVER_PASSWORD` | GeoServer REST 密码 | `<password>` |
| `GEOSERVER_WORKSPACE` | 需要创建或使用的工作区 | `geo_init_demo` |
| `GAUSS_DB_HOST` | GeoServer 可访问的 GaussDB/openGauss 主机 | `<db-host>` |
| `GAUSS_DB_PORT` | GaussDB/openGauss 端口 | `5432` |
| `GAUSS_DB_NAME` | 数据库名称 | `<database>` |
| `GAUSS_DB_SCHEMA` | 数据库 schema | `public` |
| `GAUSS_DB_USERNAME` | 写入 GeoServer 数据源配置的数据库用户名 | `<db-user>` |
| `GAUSS_DB_PASSWORD` | 写入 GeoServer 数据源配置的数据库密码 | `<db-password>` |

可选参数：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `GEOSERVER_INIT_RUN_ON_STARTUP` | 是否在应用启动时自动执行初始化 | `false` |

启动命令示例：

```bash
export GEOSERVER_BASE_URL="http://<host>:<port>/geoserver"
export GEOSERVER_USERNAME="<username>"
export GEOSERVER_PASSWORD="<password>"
export GEOSERVER_WORKSPACE="geo_init_demo"
export GAUSS_DB_HOST="<db-host>"
export GAUSS_DB_PORT="5432"
export GAUSS_DB_NAME="<database>"
export GAUSS_DB_SCHEMA="public"
export GAUSS_DB_USERNAME="<db-user>"
export GAUSS_DB_PASSWORD="<db-password>"

mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 配置说明

- `geoserver.init.run-on-startup` 默认为 `false`；只有确实需要应用启动时自动初始化时，才把它设置为 `true`。
- SQL View 文件使用 GeoServer 的 `%param%` 占位符。不要在 Java 代码里拼接来自请求的输入作为 SQL。
- WMTS 参数化切片默认使用 `VIEWPARAMS`。请保持正则过滤器足够严格，避免缓存数量无限增长。
