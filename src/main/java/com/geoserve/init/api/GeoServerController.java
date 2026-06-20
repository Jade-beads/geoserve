package com.geoserve.init.api;

import com.geoserve.init.model.GeoServerStatus;
import com.geoserve.init.model.InitResult;
import com.geoserve.init.service.GeoServerInitService;
import com.geoserve.init.service.GeoServerRestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 HTTP 入口，供运维人员、部署脚本或业务系统触发。
 *
 * 请求链路：
 * - {@code GET /api/geoserver/status} 只校验 GeoServer 连接和认证。
 * - {@code POST /api/geoserver/init} 启动幂等初始化流程。
 */
@RestController
@RequestMapping("/api/geoserver")
public class GeoServerController {

    private final GeoServerInitService initService;
    private final GeoServerRestClient restClient;

    public GeoServerController(GeoServerInitService initService, GeoServerRestClient restClient) {
        this.initService = initService;
        this.restClient = restClient;
    }

    /**
     * 执行 {@code geoserver.*} 下配置的完整初始化步骤。
     *
     * 返回每个资源的明细结果，调用方可以看到 workspace/style/store/layer 等资源是创建、
     * 跳过还是失败。
     */
    @PostMapping("/init")
    public InitResult init() {
        return initService.initialize();
    }

    /**
     * 在初始化前检查 GeoServer REST 是否可访问。
     *
     * 该接口只读：只调用 GeoServer 的版本接口，不创建任何资源。
     */
    @GetMapping("/status")
    public GeoServerStatus status() {
        return restClient.checkStatus();
    }
}
