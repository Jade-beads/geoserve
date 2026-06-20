package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class GeoServerStartupRunner implements ApplicationRunner {

    private final GeoServerInitProperties properties;
    private final GeoServerInitService initService;

    public GeoServerStartupRunner(GeoServerInitProperties properties, GeoServerInitService initService) {
        this.properties = properties;
        this.initService = initService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getInit() != null && properties.getInit().isRunOnStartup()) {
            initService.initialize();
        }
    }
}
