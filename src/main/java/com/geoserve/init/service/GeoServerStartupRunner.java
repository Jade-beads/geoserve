package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可选的启动初始化入口，适合希望应用启动时自动初始化的环境。
 *
 * 这里刻意复用 POST /api/geoserver/init 使用的同一个 GeoServerInitService，
 * 确保手动初始化和自动初始化走完全相同的资源链路与结果逻辑。
 */
@Component
public class GeoServerStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoServerStartupRunner.class);

    private final GeoServerInitProperties properties;
    private final GeoServerInitService initService;

    public GeoServerStartupRunner(GeoServerInitProperties properties, GeoServerInitService initService) {
        this.properties = properties;
        this.initService = initService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // application.yml 默认 false，避免本地开发启动时意外写入 GeoServer。
        if (properties.getInit() != null && properties.getInit().isRunOnStartup()) {
            if (properties.getDeploy() != null && properties.getDeploy().isEnabled()) {
                log.info("Skip ApplicationRunner GeoServer init because managed deployment will initialize after GeoServer is ready");
                return;
            }
            initService.initialize();
        }
    }
}
