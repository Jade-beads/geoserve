package com.geoserve.init.api;

import com.geoserve.init.model.GeoServerStatus;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.service.GeoServerInitService;
import com.geoserve.init.service.GeoServerRestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geoserver")
public class GeoServerController {

    private final GeoServerInitService initService;
    private final GeoServerRestClient restClient;

    public GeoServerController(GeoServerInitService initService, GeoServerRestClient restClient) {
        this.initService = initService;
        this.restClient = restClient;
    }

    @PostMapping("/init")
    public InitResult init() {
        return initService.initialize();
    }

    @GetMapping("/status")
    public GeoServerStatus status() {
        return restClient.checkStatus();
    }
}
