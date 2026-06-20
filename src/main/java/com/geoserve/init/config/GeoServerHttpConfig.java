package com.geoserve.init.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * GeoServer REST/GWC REST 调用共用的 HTTP 客户端配置。
 *
 * 初始化过程会创建工作区、上传 SLD、发布 FeatureType、配置 GWC 图层。
 * 其中部分 GeoServer 操作会计算 bbox 或访问数据库元数据，所以读取超时时间比连接超时时间更长。
 */
@Configuration
public class GeoServerHttpConfig {

    /**
     * 注入到 {@code GeoServerRestClient} 的共享 RestTemplate。
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
