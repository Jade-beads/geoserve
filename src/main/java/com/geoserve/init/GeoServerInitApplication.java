package com.geoserve.init;

import com.geoserve.init.config.GeoServerInitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 应用启动入口。
 *
 * 初始化链路：
 * 1. Spring Boot 从这个类启动。
 * 2. {@link GeoServerInitProperties} 绑定 application.yml 和环境变量中的 geoserver.* 配置。
 * 3. HTTP 请求进入 {@code GeoServerController}；如果开启 run-on-startup，则启动时进入
 *    {@code GeoServerStartupRunner}。
 */
@SpringBootApplication
@EnableConfigurationProperties(GeoServerInitProperties.class)
public class GeoServerInitApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoServerInitApplication.class, args);
    }
}
