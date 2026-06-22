package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Deploy;
import com.geoserve.init.config.GeoServerInitProperties.Init;
import com.geoserve.init.model.GeoServerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试项目托管 GeoServer 子进程的生命周期。
 *
 * 测试不启动真实 GeoServer，只验证解压、命令、环境变量、健康检查和清理边界。
 */
class GeoServerDeploymentLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void deployDefaultsKeepManagedGeoServerDisabledAndPersistentFoldersOutsideInstallDir() {
        Deploy deploy = new GeoServerInitProperties().getDeploy();

        assertThat(deploy.isEnabled()).isFalse();
        assertThat(deploy.getArchiveLocation()).isEqualTo("classpath:geoserver/geoserver-bin.zip");
        assertThat(deploy.getWorkDir()).isEqualTo("runtime/geoserver");
        assertThat(deploy.getInstallDir()).isEqualTo("runtime/geoserver/install");
        assertThat(deploy.getDataDir()).isEqualTo("runtime/geoserver/data");
        assertThat(deploy.getCacheDir()).isEqualTo("runtime/geoserver/gwc-cache");
        assertThat(deploy.getLogDir()).isEqualTo("logs/geoserver");
        assertThat(deploy.isDeleteInstallOnStop()).isTrue();
    }

    @Test
    void startExtractsArchiveLaunchesGeoServerWithPersistentDirectoriesAndRunsInitAfterReady() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        properties.getInit().setRunOnStartup(true);
        properties.getDeploy().setNodeName("A");
        properties.getDeploy().setJavaHome("/opt/jdk8");
        properties.getDeploy().setJvmArgs(Collections.singletonList("-Xmx512m"));

        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        RecordingProcessLauncher launcher = new RecordingProcessLauncher();
        when(restClient.checkStatus()).thenReturn(new GeoServerStatus(true, "2.28.1", "ready"));

        GeoServerDeploymentLifecycle lifecycle = lifecycle(properties, restClient, initService, launcher);

        lifecycle.startManagedGeoServerAndInitializeIfNeeded();

        assertThat(Files.exists(tempDir.resolve("install/geoserver-2.28.1/bin/startup.sh"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("data"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("cache"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("logs"))).isTrue();

        GeoServerProcessCommand command = launcher.startedCommands.get(0);
        assertThat(command.getNodeName()).isEqualTo("A");
        assertThat(command.getCommand()).contains("sh");
        assertThat(command.getCommand().get(command.getCommand().size() - 1)).endsWith("bin/startup.sh");
        assertThat(command.getWorkingDirectory()).isEqualTo(tempDir.resolve("install/geoserver-2.28.1").toFile());
        assertThat(command.getEnvironment()).containsEntry("GEOSERVER_DATA_DIR", tempDir.resolve("data").toString());
        assertThat(command.getEnvironment()).containsEntry("GEOWEBCACHE_CACHE_DIR", tempDir.resolve("cache").toString());
        assertThat(command.getEnvironment()).containsEntry("GEOSERVER_LOG_LOCATION", tempDir.resolve("logs/geoserver.log").toString());
        assertThat(command.getEnvironment()).containsEntry("JAVA_HOME", "/opt/jdk8");
        assertThat(command.getEnvironment().get("JAVA_OPTS"))
                .contains("-DGEOSERVER_DATA_DIR=" + tempDir.resolve("data"))
                .contains("-DGEOWEBCACHE_CACHE_DIR=" + tempDir.resolve("cache"))
                .contains("-DGEOSERVER_LOG_LOCATION=" + tempDir.resolve("logs/geoserver.log"))
                .contains("-Djetty.http.port=18080")
                .contains("-Xmx512m");
        verify(initService).initialize();
    }

    @Test
    void shutdownRunsShutdownScriptDestroysProcessAndDeletesOnlyInstallDirectory() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        RecordingProcessLauncher launcher = new RecordingProcessLauncher();
        when(restClient.checkStatus()).thenReturn(new GeoServerStatus(true, "2.28.1", "ready"));

        GeoServerDeploymentLifecycle lifecycle = lifecycle(properties, restClient, initService, launcher);
        lifecycle.startManagedGeoServerAndInitializeIfNeeded();

        Files.write(tempDir.resolve("data/keep.txt"), "data".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("cache/keep.txt"), "cache".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("logs/keep.txt"), "logs".getBytes(StandardCharsets.UTF_8));

        lifecycle.shutdownManagedGeoServer();

        assertThat(launcher.shutdownCommands).hasSize(1);
        assertThat(launcher.shutdownCommands.get(0).getCommand().get(1)).endsWith("bin/shutdown.sh");
        assertThat(launcher.process.destroyed).isTrue();
        assertThat(Files.exists(tempDir.resolve("install"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("data/keep.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("cache/keep.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("logs/keep.txt"))).isTrue();
    }

    @Test
    void startupTimeoutFailsClearlyAndDoesNotRunInit() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        properties.getDeploy().setStartupTimeoutSeconds(0);
        properties.getInit().setRunOnStartup(true);
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        RecordingProcessLauncher launcher = new RecordingProcessLauncher();
        when(restClient.checkStatus()).thenReturn(new GeoServerStatus(false, null, "connection refused"));

        GeoServerDeploymentLifecycle lifecycle = lifecycle(properties, restClient, initService, launcher);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                lifecycle.startManagedGeoServerAndInitializeIfNeeded();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GeoServer startup timed out");
        verify(initService, never()).initialize();
    }

    private GeoServerDeploymentLifecycle lifecycle(GeoServerInitProperties properties,
                                                   GeoServerRestClient restClient,
                                                   GeoServerInitService initService,
                                                   GeoServerProcessLauncher launcher) {
        return new GeoServerDeploymentLifecycle(
                properties,
                restClient,
                initService,
                launcher,
                new DefaultResourceLoader(),
                new GeoServerDeploymentLifecycle.Sleeper() {
                    @Override
                    public void sleep(long millis) {
                        // 单元测试不真实等待。
                    }
                });
    }

    private GeoServerInitProperties properties(Path archive) {
        Deploy deploy = new Deploy();
        deploy.setEnabled(true);
        deploy.setArchiveLocation(archive.toUri().toString());
        deploy.setWorkDir(tempDir.resolve("work").toString());
        deploy.setInstallDir(tempDir.resolve("install").toString());
        deploy.setDataDir(tempDir.resolve("data").toString());
        deploy.setCacheDir(tempDir.resolve("cache").toString());
        deploy.setLogDir(tempDir.resolve("logs").toString());
        deploy.setPort(18080);
        deploy.setStartupTimeoutSeconds(1);
        deploy.setShutdownTimeoutSeconds(1);

        GeoServerInitProperties properties = new GeoServerInitProperties();
        properties.setBaseUrl("http://localhost:18080/geoserver");
        properties.setUsername("admin");
        properties.setPassword("test-password");
        properties.setDeploy(deploy);
        properties.setInit(new Init());
        return properties;
    }

    private Path fakeGeoServerArchive() throws IOException {
        Path archive = tempDir.resolve("geoserver-bin.zip");
        ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
        try {
            addZipEntry(zip, "geoserver-2.28.1/bin/startup.sh", "#!/bin/sh\necho start\n");
            addZipEntry(zip, "geoserver-2.28.1/bin/shutdown.sh", "#!/bin/sh\necho stop\n");
        } finally {
            zip.close();
        }
        return archive;
    }

    private void addZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        ByteArrayInputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[128];
        int length;
        while ((length = input.read(buffer)) != -1) {
            zip.write(buffer, 0, length);
        }
        zip.closeEntry();
    }

    private static class RecordingProcessLauncher implements GeoServerProcessLauncher {
        private final RecordingProcess process = new RecordingProcess();
        private final java.util.List<GeoServerProcessCommand> startedCommands = new java.util.ArrayList<GeoServerProcessCommand>();
        private final java.util.List<GeoServerProcessCommand> shutdownCommands = new java.util.ArrayList<GeoServerProcessCommand>();

        @Override
        public Process start(GeoServerProcessCommand command) {
            startedCommands.add(command);
            return process;
        }

        @Override
        public int runAndWait(GeoServerProcessCommand command, int timeoutSeconds) {
            shutdownCommands.add(command);
            return 0;
        }
    }

    private static class RecordingProcess extends Process {
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }
    }
}
