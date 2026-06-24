# GeoServer WMTS 缓存目录与样式上传改造说明

## 背景

本次改造解决两个问题：

1. GeoServer 样式上传走 `RestTemplate` 时，业务工程中的 JSON-only 请求封装会拦截 SLD XML，报 `request object not json type`。
2. 托管启动 GeoServer 后，GWC 切片仍写入 `install` 下默认 `data_dir/gwc`，没有进入指定挂载盘目录。

同时根据当前 GeoServer 环境，把 WMTS gridset 名称从 `EPSG:3857` 调整为实际存在的 `WebMercatorQuad`。

## 修改文件

| 文件 | 说明 |
| --- | --- |
| `pom.xml` | 新增 Apache HttpClient 依赖，用于原始 SLD XML 上传。 |
| `src/main/java/com/geoserve/init/service/GeoServerStyleHttpClient.java` | 新增独立样式上传客户端，不走 `RestTemplate`。 |
| `src/main/java/com/geoserve/init/service/GeoServerRestClient.java` | `ensureStyle` 改为调用 `GeoServerStyleHttpClient`。 |
| `src/main/java/com/geoserve/init/service/GeoServerAutoConfigurationListener.java` | GeoServer 启动前写入 `webapps/geoserver/WEB-INF/web.xml` 的 `GEOWEBCACHE_CACHE_DIR`。 |
| `src/main/resources/application.yml` | `basic_all` 的 WMTS gridset 改为 `WebMercatorQuad`。 |
| `README.md` | 同步说明 GWC 缓存目录写入方式和 WMTS 请求示例。 |
| `src/test/java/...` | 增加或调整对应单元测试。 |

## 样式上传改造

### 依赖

`pom.xml` 增加：

```xml
<!-- GeoServer SLD 上传使用原生 Apache HttpClient，避开业务侧 JSON-only RestTemplate 封装。 -->
<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient</artifactId>
    <exclusions>
        <exclusion>
            <groupId>commons-codec</groupId>
            <artifactId>commons-codec</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>commons-codec</groupId>
    <artifactId>commons-codec</artifactId>
    <version>1.11</version>
</dependency>
```

`commons-codec` 固定到 `1.11` 是为了匹配当前本地仓库已有依赖，避免离线环境解析 `1.15` 失败。

### 独立上传类

新增文件：

```text
src/main/java/com/geoserve/init/service/GeoServerStyleHttpClient.java
```

核心代码：

```java
@Component
public class GeoServerStyleHttpClient {

    private static final String SLD_CONTENT_TYPE = "application/vnd.ogc.sld+xml";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    public void postSld(String targetUrl, String username, String password, String sld) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(READ_TIMEOUT_MS)
                .build();

        HttpPost post = new HttpPost(targetUrl);
        post.setConfig(requestConfig);
        post.setHeader(HttpHeaders.AUTHORIZATION, basicAuthHeader(username, password));
        post.setHeader(HttpHeaders.CONTENT_TYPE, SLD_CONTENT_TYPE);
        post.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", "
                + MediaType.TEXT_PLAIN_VALUE + ", */*");
        post.setEntity(new StringEntity(sld, StandardCharsets.UTF_8));

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getStatusLine().getStatusCode();
            org.apache.http.HttpEntity responseEntity = response.getEntity();
            String responseBody = responseEntity == null
                    ? "" : EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new RestClientException("GeoServer style upload failed url=" + targetUrl
                        + " status=" + status
                        + " reason=" + response.getStatusLine().getReasonPhrase()
                        + " body=" + responseBody);
            }
        } catch (IOException ex) {
            throw new RestClientException("GeoServer style upload failed url=" + targetUrl
                    + " error=" + ex.getMessage(), ex);
        }
    }
}
```

错误处理要求：

- 非 `2xx` 直接抛异常。
- 异常信息包含 `url`、`status`、`reason`、`body`。
- 不吞 GeoServer 返回体。

### `GeoServerRestClient` 调整

构造器增加 `GeoServerStyleHttpClient`：

```java
private final GeoServerStyleHttpClient styleHttpClient;

public GeoServerRestClient(RestTemplate restTemplate,
                           GeoServerInitProperties properties,
                           ResourceLoader resourceLoader,
                           GeoServerStyleHttpClient styleHttpClient) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resourceLoader = resourceLoader;
    this.styleHttpClient = styleHttpClient;
}
```

`ensureStyle` 中上传样式改为：

```java
String sld = readResource(required(style.getSldLocation(), "style.sldLocation"));
styleHttpClient.postSld(url("/rest/workspaces/" + workspace + "/styles?name=" + name),
        properties.getUsername(), properties.getPassword(), sld);
return ResourceAction.created("style", qualifiedName, "Style created");
```

## GWC 缓存目录改造

### 问题原因

GeoServer 内置 GeoWebCache 默认把切片写到：

```text
GEOSERVER_DATA_DIR/gwc
```

只设置环境变量或 `JAVA_OPTS -DGEOWEBCACHE_CACHE_DIR=...` 在当前环境中没有生效。GeoServer 官方文档推荐在 GeoServer WebApp 的 `WEB-INF/web.xml` 中配置：

```xml
<context-param>
   <param-name>GEOWEBCACHE_CACHE_DIR</param-name>
   <param-value>/path/to/cache</param-value>
</context-param>
```

### 启动前写入 `web.xml`

`GeoServerAutoConfigurationListener.prepareDeployment` 中，在解压、替换用户密码、替换 JDBC 驱动后，启动 GeoServer 前执行：

```java
configureGwcCacheDirectory(deploy, home, cacheDir);
```

核心方法：

```java
private void configureGwcCacheDirectory(Deploy deploy, File home, File cacheDir) throws IOException {
    File webXml = safeHomeChild(home, "webapps/geoserver/WEB-INF/web.xml", "GeoServer web.xml");
    if (!webXml.isFile()) {
        throw new IllegalStateException("GeoServer web.xml not found: " + webXml.getAbsolutePath());
    }
    try {
        Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(webXml);
        Element contextParam = findContextParam(document, "GEOWEBCACHE_CACHE_DIR");
        if (contextParam == null) {
            contextParam = document.createElement("context-param");
            Element paramName = document.createElement("param-name");
            paramName.setTextContent("GEOWEBCACHE_CACHE_DIR");
            contextParam.appendChild(paramName);
            contextParam.appendChild(document.createElement("param-value"));
            document.getDocumentElement().appendChild(contextParam);
        }
        Element paramValue = firstChildElement(contextParam, "param-value");
        if (paramValue == null) {
            paramValue = document.createElement("param-value");
            contextParam.appendChild(paramValue);
        }
        paramValue.setTextContent(cacheDir.getAbsolutePath());
        writeXml(document, webXml);
        log.info("GeoServer GWC cache directory configured node={} webXml={} cacheDir={}",
                nodeName(deploy), webXml.getAbsolutePath(), cacheDir.getAbsolutePath());
    } catch (IllegalStateException ex) {
        throw ex;
    } catch (Exception ex) {
        throw new IllegalStateException("GeoServer web.xml GWC cache directory update failed: "
                + webXml.getAbsolutePath() + " message=" + ex.getMessage(), ex);
    }
}
```

生效结果：

- 启动前更新 `install/geoserver-*/webapps/geoserver/WEB-INF/web.xml`。
- 写入的路径是实际派生后的缓存目录。
- 如果开启按 IP 分目录，示例为：

```text
/geoserver/192_168_0_1_gwc
```

仍然保留环境变量和 `JAVA_OPTS`：

```text
GEOWEBCACHE_CACHE_DIR=/geoserver/192_168_0_1_gwc
JAVA_OPTS=... -DGEOWEBCACHE_CACHE_DIR=/geoserver/192_168_0_1_gwc
```

## WMTS GridSet 改造

当前 GeoServer 中 Web Mercator 切片矩阵集名称是：

```text
WebMercatorQuad
```

因此 `application.yml` 中 `basic_all` 改为：

```yaml
wmts:
  enabled: true
  gridsets:
    - WebMercatorQuad
  formats:
    - image/png
  parameter-filters:
    - type: regex
      key: VIEWPARAMS
      default-value: "batchId:${BASIC_ALL_BATCH_ID_DEFAULT:1001}"
      regex: "^batchId:[0-9]+$"
```

WMTS 请求示例同步改为：

```text
GET /geoserver/gwc/service/wmts?
  SERVICE=WMTS&
  REQUEST=GetTile&
  VERSION=1.0.0&
  LAYER=site_selection:basic_all&
  STYLE=&
  TILEMATRIXSET=WebMercatorQuad&
  TILEMATRIX=WebMercatorQuad:<z>&
  TILEROW=<row>&
  TILECOL=<col>&
  FORMAT=image/png&
  VIEWPARAMS=batchId:1001
```

## 参数化切片缓存说明

GWC 支持参数化缓存。当前使用：

```xml
<regexParameterFilter>
  <key>VIEWPARAMS</key>
  <defaultValue>batchId:1001</defaultValue>
  <regex>^batchId:[0-9]+$</regex>
</regexParameterFilter>
```

含义：

- `VIEWPARAMS=batchId:1001` 与 `VIEWPARAMS=batchId:1002` 会进入不同缓存 key。
- 它不是给每个参数创建一个独立根目录。
- 它会在 GWC 缓存根目录内部按图层、gridset、format、参数组合区分缓存。
- 如果 `VIEWPARAMS` 不匹配正则，请求不会按该参数进入有效缓存。

## 部署配置重点

业务侧主要配置：

```bash
export GEOSERVER_DEPLOY_LOCAL_ROOT=/opt/geoserve/geoserver
export GEOSERVER_DEPLOY_TILE_ROOT=/geoserver
export GEOSERVER_DEPLOY_CACHE_DIR_PER_HOST_ENABLED=true
```

派生结果示例：

```text
/opt/geoserve/geoserver/install
/opt/geoserve/geoserver/data
/opt/geoserve/geoserver/logs/geoserver.log
/geoserver/192_168_0_1_gwc
```

如果 `GEOSERVER_DEPLOY_CACHE_DIR_PER_HOST_ENABLED=false`，则最终 GWC 目录就是 `GEOSERVER_DEPLOY_TILE_ROOT` 或 `GEOSERVER_DEPLOY_CACHE_DIR` 本身。

## 验证方式

### 代码测试

```bash
mvn -q -Dmaven.repo.local=.m2/repository test
git diff --check
```

### 启动后检查 `web.xml`

```bash
grep -n "GEOWEBCACHE_CACHE_DIR" -A2 \
  /opt/geoserve/geoserver/install/geoserver-*/webapps/geoserver/WEB-INF/web.xml
```

应看到：

```xml
<param-name>GEOWEBCACHE_CACHE_DIR</param-name>
<param-value>/geoserver/本机IP_gwc</param-value>
```

### 检查 GWC 图层配置

```bash
curl -u admin:密码 \
  "http://localhost:8003/geoserver/gwc/rest/layers/site_selection:basic_all.xml"
```

应包含：

```xml
<gridSetName>WebMercatorQuad</gridSetName>
<key>VIEWPARAMS</key>
<defaultValue>batchId:1001</defaultValue>
<regex>^batchId:[0-9]+$</regex>
```

### 检查实际切片目录

发起一次 WMTS 请求后，检查挂载盘目录是否出现切片文件：

```bash
find /geoserver -maxdepth 5 -type f | head
```

如果仍然写入 `install/data_dir/gwc`，优先检查：

1. 启动前生成的 `web.xml` 是否包含 `GEOWEBCACHE_CACHE_DIR`。
2. GeoServer 是否确实是由当前 Java 服务启动，而不是手动启动了旧目录中的 GeoServer。
3. 当前运行的 GeoServer `GEOSERVER_DATA_DIR` 是否指向预期目录。
4. GeoServer 进程是否重启过；`web.xml` 修改需要重启 WebApp/GeoServer 才会生效。

## 注意事项

- `WebMercatorQuad` 是当前 GeoServer 环境中的 gridset 名称；如果换环境，需要以 GeoServer Tile Caching 页面实际名称为准。
- 图层源数据 SRS 仍然可以是 `EPSG:4326`，这和 WMTS 使用 `WebMercatorQuad` 不冲突。
- GWC 参数过滤器允许根据 `VIEWPARAMS` 区分缓存 key，但不会自动为每个参数值创建独立根目录。
- 当前初始化逻辑仍按“存在就跳过”，如果已有 GWC layer 中不是 `WebMercatorQuad`，需要删除旧 GWC layer 后重新初始化，或手动改 GeoServer Tile Caching 配置。
