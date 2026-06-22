package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Deploy;
import com.geoserve.init.config.GeoServerInitProperties.Init;
import com.geoserve.init.model.GeoServerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试项目托管 GeoServer 子进程的监听器生命周期。
 *
 * 测试使用临时 ZIP 内的 shell 脚本，不启动真实 GeoServer。
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
    void listenerDirectlyImplementsApplicationListenerWithoutExtraLauncherInterface() {
        assertThat(ApplicationListener.class).isAssignableFrom(GeoServerAutoConfigurationListener.class);
        assertThat(Files.exists(java.nio.file.Paths.get(
                "src/main/java/com/geoserve/init/service/GeoServerProcessLauncher.java"))).isFalse();
        assertThat(Files.exists(java.nio.file.Paths.get(
                "src/main/java/com/geoserve/init/service/GeoServerProcessCommand.java"))).isFalse();
        assertThat(Files.exists(java.nio.file.Paths.get(
                "src/main/java/com/geoserve/init/service/DefaultGeoServerProcessLauncher.java"))).isFalse();
    }

    @Test
    void readyEventExtractsArchiveStartsGeoServerWithCacheDirectoryAndRunsInitAfterReady() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        properties.getInit().setRunOnStartup(true);
        properties.getDeploy().setNodeName("A");
        properties.getDeploy().setJavaHome("/opt/jdk8");
        properties.getDeploy().setJvmArgs(Collections.singletonList("-Xmx512m"));

        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        when(restClient.checkStatus()).thenReturn(
                new GeoServerStatus(false, null, "connection refused"),
                new GeoServerStatus(true, "2.28.1", "ready"));

        GeoServerAutoConfigurationListener listener = listener(properties, restClient, initService);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        Path home = tempDir.resolve("install/geoserver-2.28.1");
        assertThat(Files.exists(home.resolve("bin/startup.sh"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("data"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("cache"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("logs"))).isTrue();
        assertThat(readWithRetry(home.resolve("startup.env")))
                .contains("GEOWEBCACHE_CACHE_DIR=" + tempDir.resolve("cache"))
                .contains("GEOSERVER_DATA_DIR=" + tempDir.resolve("data"))
                .contains("GEOSERVER_LOG_LOCATION=" + tempDir.resolve("logs/geoserver.log"))
                .contains("JAVA_HOME=/opt/jdk8")
                .contains("-Djetty.http.port=18080")
                .contains("-Xmx512m");
        verify(initService).initialize();

        listener.onApplicationEvent(new ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class)));
    }

    @Test
    void readyEventStopsRunningProcessDeletesInstallDirectoryAndStartsAgain() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        properties.getInit().setRunOnStartup(true);
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        when(restClient.checkStatus()).thenReturn(
                new GeoServerStatus(false, null, "not running"),
                new GeoServerStatus(true, "2.28.1", "ready"),
                new GeoServerStatus(true, "2.28.1", "ready"));
        GeoServerAutoConfigurationListener listener = listener(properties, restClient, initService);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
        Path marker = tempDir.resolve("install/old-marker.txt");
        Files.write(marker, "old".getBytes(StandardCharsets.UTF_8));

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertThat(Files.exists(marker)).isFalse();
        assertThat(Files.exists(tempDir.resolve("install/geoserver-2.28.1/bin/startup.sh"))).isTrue();
        verify(initService, times(2)).initialize();

        listener.onApplicationEvent(new ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class)));
    }

    @Test
    void closeEventStopsProcessDeletesOnlyInstallDirectoryAndKeepsCacheDataAndLogs() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        when(restClient.checkStatus()).thenReturn(
                new GeoServerStatus(false, null, "not running"),
                new GeoServerStatus(true, "2.28.1", "ready"));
        GeoServerAutoConfigurationListener listener = listener(properties, restClient, initService);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
        Files.write(tempDir.resolve("data/keep.txt"), "data".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("cache/keep.txt"), "cache".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("logs/keep.txt"), "logs".getBytes(StandardCharsets.UTF_8));

        listener.onApplicationEvent(new ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class)));

        assertThat(Files.exists(tempDir.resolve("install"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("data/keep.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("cache/keep.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("logs/keep.txt"))).isTrue();
    }

    @Test
    void readyEventFailsWhenRemoteGeoServerIsReachableButNoLocalShutdownScriptExists() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        when(restClient.checkStatus()).thenReturn(new GeoServerStatus(true, "2.28.1", "ready"));
        GeoServerAutoConfigurationListener listener = listener(properties, restClient, initService);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutdown script not found");
        verify(initService, never()).initialize();
    }

    @Test
    void readyEventFailsWhenCacheDirectoryWouldBeDeletedWithInstallDirectory() throws Exception {
        Path archive = fakeGeoServerArchive();
        GeoServerInitProperties properties = properties(archive);
        properties.getDeploy().setCacheDir(tempDir.resolve("install/cache").toString());
        GeoServerRestClient restClient = mock(GeoServerRestClient.class);
        GeoServerInitService initService = mock(GeoServerInitService.class);
        when(restClient.checkStatus()).thenReturn(new GeoServerStatus(false, null, "not running"));
        GeoServerAutoConfigurationListener listener = listener(properties, restClient, initService);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cache-dir");
        verify(initService, never()).initialize();
    }

    private GeoServerAutoConfigurationListener listener(GeoServerInitProperties properties,
                                                        GeoServerRestClient restClient,
                                                        GeoServerInitService initService) {
        GeoServerAutoConfigurationListener listener = new GeoServerAutoConfigurationListener();
        ReflectionTestUtils.setField(listener, "properties", properties);
        ReflectionTestUtils.setField(listener, "restClient", restClient);
        ReflectionTestUtils.setField(listener, "initService", initService);
        ReflectionTestUtils.setField(listener, "resourceLoader", new DefaultResourceLoader());
        ReflectionTestUtils.setField(listener, "sleepMillis", 5L);
        return listener;
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
        deploy.setStartupTimeoutSeconds(2);
        deploy.setShutdownTimeoutSeconds(2);

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
            addZipEntry(zip, "geoserver-2.28.1/bin/startup.sh",
                    "#!/bin/sh\n"
                            + "env | grep -E 'GEOWEBCACHE_CACHE_DIR|GEOSERVER_DATA_DIR|GEOSERVER_LOG_LOCATION|JAVA_HOME|JAVA_OPTS' > startup.env\n"
                            + "while true; do sleep 1; done\n");
            addZipEntry(zip, "geoserver-2.28.1/bin/shutdown.sh",
                    "#!/bin/sh\n"
                            + "echo stop > shutdown.env\n");
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

    private String readWithRetry(Path path) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
            Thread.sleep(10L);
        }
        return "";
    }
}
