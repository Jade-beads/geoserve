package com.geoserve.init.service;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * GeoServer 样式上传专用 HTTP 客户端。
 *
 * 该类只负责原始 SLD XML 上传，不依赖 RestTemplate，避免业务工程里的 JSON-only 请求封装拦截非 JSON body。
 */
@Component
public class GeoServerStyleHttpClient {

    private static final String SLD_CONTENT_TYPE = "application/vnd.ogc.sld+xml";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    /**
     * 上传 SLD XML 到 GeoServer style REST 端点。
     */
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

    private String basicAuthHeader(String username, String password) {
        String userPass = defaultString(username) + ":" + defaultString(password);
        String encoded = Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
