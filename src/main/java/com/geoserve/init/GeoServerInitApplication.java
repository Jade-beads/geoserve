package com.geoserve.init;

import com.geoserve.init.config.GeoServerInitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GeoServerInitProperties.class)
public class GeoServerInitApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoServerInitApplication.class, args);
    }
}
