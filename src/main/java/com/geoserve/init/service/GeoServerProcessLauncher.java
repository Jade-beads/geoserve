package com.geoserve.init.service;

import java.io.IOException;

/**
 * GeoServer 子进程启动器。
 *
 * 生命周期类负责准备命令和环境变量；启动器只负责真正创建进程或执行停止脚本。
 */
public interface GeoServerProcessLauncher {

    Process start(GeoServerProcessCommand command) throws IOException;

    int runAndWait(GeoServerProcessCommand command, int timeoutSeconds) throws IOException, InterruptedException;
}
