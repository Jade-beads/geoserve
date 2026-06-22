package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Deploy;
import com.geoserve.init.model.GeoServerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本机托管 GeoServer 的生命周期组件。
 *
 * 启动链路：
 * 1. 从 resources/file 读取 GeoServer ZIP 包。
 * 2. 解压到 install-dir。
 * 3. 设置 data/cache/log 等持久目录环境变量。
 * 4. 启动 GeoServer 子进程并等待 REST 版本接口可用。
 * 5. 如开启 run-on-startup，再执行现有初始化服务。
 *
 * 停止链路只删除 install-dir，不删除 data-dir、cache-dir、log-dir。
 */
@Component
public class GeoServerDeploymentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GeoServerDeploymentLifecycle.class);

    private final GeoServerInitProperties properties;
    private final GeoServerRestClient restClient;
    private final GeoServerInitService initService;
    private final GeoServerProcessLauncher processLauncher;
    private final ResourceLoader resourceLoader;
    private final Sleeper sleeper;

    private Process process;
    private File installDirectory;
    private File geoserverHome;
    private GeoServerProcessCommand shutdownCommand;

    public GeoServerDeploymentLifecycle(GeoServerInitProperties properties,
                                        GeoServerRestClient restClient,
                                        GeoServerInitService initService,
                                        GeoServerProcessLauncher processLauncher,
                                        ResourceLoader resourceLoader) {
        this(properties, restClient, initService, processLauncher, resourceLoader, new ThreadSleeper());
    }

    GeoServerDeploymentLifecycle(GeoServerInitProperties properties,
                                 GeoServerRestClient restClient,
                                 GeoServerInitService initService,
                                 GeoServerProcessLauncher processLauncher,
                                 ResourceLoader resourceLoader,
                                 Sleeper sleeper) {
        this.properties = properties;
        this.restClient = restClient;
        this.initService = initService;
        this.processLauncher = processLauncher;
        this.resourceLoader = resourceLoader;
        this.sleeper = sleeper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        startManagedGeoServerAndInitializeIfNeeded();
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void onContextClosed() {
        shutdownManagedGeoServer();
    }

    public synchronized void startManagedGeoServerAndInitializeIfNeeded() {
        Deploy deploy = deploy();
        if (!deploy.isEnabled()) {
            log.info("GeoServer managed deployment disabled");
            return;
        }
        if (process != null) {
            log.info("GeoServer managed process already started node={}", nodeName(deploy));
            return;
        }

        try {
            PreparedDeployment prepared = prepareDeployment(deploy);
            this.installDirectory = prepared.installDirectory;
            this.geoserverHome = prepared.geoserverHome;
            this.shutdownCommand = prepared.shutdownCommand;
            this.process = processLauncher.start(prepared.startupCommand);
            log.info("GeoServer managed process started node={} home={} dataDir={} cacheDir={} logLocation={}",
                    nodeName(deploy),
                    prepared.geoserverHome.getAbsolutePath(),
                    prepared.dataDirectory.getAbsolutePath(),
                    prepared.cacheDirectory.getAbsolutePath(),
                    prepared.logLocation.getAbsolutePath());
            waitUntilReady(deploy);
            if (properties.getInit() != null && properties.getInit().isRunOnStartup()) {
                log.info("GeoServer is ready, run startup initialization node={}", nodeName(deploy));
                initService.initialize();
            }
        } catch (RuntimeException ex) {
            destroyManagedProcess(deploy);
            throw ex;
        } catch (IOException ex) {
            destroyManagedProcess(deploy);
            throw new IllegalStateException("GeoServer managed deployment failed: " + ex.getMessage(), ex);
        }
    }

    public synchronized void shutdownManagedGeoServer() {
        Deploy deploy = deploy();
        if (!deploy.isEnabled()) {
            return;
        }

        try {
            if (shutdownCommand != null) {
                int exitCode = processLauncher.runAndWait(shutdownCommand, deploy.getShutdownTimeoutSeconds());
                if (exitCode != 0) {
                    throw new IllegalStateException("GeoServer shutdown script returned exit code " + exitCode);
                }
                log.info("GeoServer shutdown script finished node={} exitCode={}", nodeName(deploy), exitCode);
            }
        } catch (Exception ex) {
            log.error("GeoServer shutdown script failed node={} message={}", nodeName(deploy), ex.getMessage(), ex);
        } finally {
            destroyManagedProcess(deploy);
            if (deploy.isDeleteInstallOnStop() && installDirectory != null) {
                try {
                    deleteRecursively(installDirectory);
                    log.info("GeoServer install directory deleted node={} path={}",
                            nodeName(deploy), installDirectory.getAbsolutePath());
                } catch (IOException ex) {
                    log.error("GeoServer install directory delete failed node={} path={} message={}",
                            nodeName(deploy), installDirectory.getAbsolutePath(), ex.getMessage(), ex);
                }
            }
            process = null;
            shutdownCommand = null;
            geoserverHome = null;
            installDirectory = null;
        }
    }

    private PreparedDeployment prepareDeployment(Deploy deploy) throws IOException {
        File workDir = mkdir(required(deploy.getWorkDir(), "geoserver.deploy.workDir"));
        File installDir = file(required(deploy.getInstallDir(), "geoserver.deploy.installDir"));
        File dataDir = file(required(deploy.getDataDir(), "geoserver.deploy.dataDir"));
        File cacheDir = file(required(deploy.getCacheDir(), "geoserver.deploy.cacheDir"));
        File logDir = file(required(deploy.getLogDir(), "geoserver.deploy.logDir"));
        validatePersistentDirectory("data-dir", dataDir, installDir, deploy);
        validatePersistentDirectory("cache-dir", cacheDir, installDir, deploy);
        validatePersistentDirectory("log-dir", logDir, installDir, deploy);
        File logLocation = logLocation(deploy, logDir);
        validatePersistentDirectory("log-location", logLocation, installDir, deploy);

        mkdir(workDir);
        if (installDir.exists()) {
            deleteRecursively(installDir);
        }
        mkdir(installDir);
        mkdir(dataDir);
        mkdir(cacheDir);
        mkdir(logDir);
        if (logLocation.getParentFile() != null) {
            mkdir(logLocation.getParentFile());
        }

        extractZip(deploy, installDir);
        File startupScript = findScript(installDir, deploy.getStartupScript());
        if (startupScript == null) {
            throw new IllegalStateException("GeoServer startup script not found under install-dir: "
                    + deploy.getStartupScript());
        }
        startupScript.setExecutable(true);
        File home = resolveHomeFromScript(startupScript, deploy.getStartupScript());
        File shutdownScript = new File(home, normalizeRelativePath(deploy.getShutdownScript()));
        if (shutdownScript.exists()) {
            shutdownScript.setExecutable(true);
        }

        Map<String, String> environment = environment(deploy, home, dataDir, cacheDir, logLocation);
        GeoServerProcessCommand startupCommand = command(deploy, startupScript, home, environment, logDir);
        GeoServerProcessCommand shutdownCommand = shutdownScript.exists()
                ? command(deploy, shutdownScript, home, environment, logDir) : null;

        PreparedDeployment prepared = new PreparedDeployment();
        prepared.installDirectory = installDir;
        prepared.geoserverHome = home;
        prepared.dataDirectory = dataDir;
        prepared.cacheDirectory = cacheDir;
        prepared.logLocation = logLocation;
        prepared.startupCommand = startupCommand;
        prepared.shutdownCommand = shutdownCommand;
        return prepared;
    }

    private void waitUntilReady(Deploy deploy) {
        long timeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(0, deploy.getStartupTimeoutSeconds()));
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String lastMessage = "";
        while (true) {
            GeoServerStatus status = restClient.checkStatus();
            if (status != null && status.isReachable()) {
                log.info("GeoServer REST is ready node={} version={}", nodeName(deploy), status.getVersion());
                return;
            }
            lastMessage = status == null ? "status is null" : status.getMessage();
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("GeoServer startup timed out after "
                        + deploy.getStartupTimeoutSeconds() + " seconds, last status: " + lastMessage);
            }
            sleeper.sleep(1000L);
        }
    }

    private void extractZip(Deploy deploy, File installDir) throws IOException {
        String archiveLocation = required(deploy.getArchiveLocation(), "geoserver.deploy.archiveLocation");
        if (!archiveLocation.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Only GeoServer ZIP archive is supported: " + archiveLocation);
        }
        Resource resource = resourceLoader.getResource(archiveLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("GeoServer archive not found: " + archiveLocation);
        }

        log.info("GeoServer archive extracting node={} archive={} target={}",
                nodeName(deploy), archiveLocation, installDir.getAbsolutePath());
        ZipInputStream zip = new ZipInputStream(resource.getInputStream());
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = safeZipTarget(installDir, entry);
                if (entry.isDirectory()) {
                    mkdir(target);
                } else {
                    mkdir(target.getParentFile());
                    Files.copy(zip, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }

    private File safeZipTarget(File installDir, ZipEntry entry) throws IOException {
        File target = new File(installDir, entry.getName()).getCanonicalFile();
        File canonicalInstallDir = installDir.getCanonicalFile();
        String installPath = canonicalInstallDir.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(installPath) && !targetPath.startsWith(installPath + File.separator)) {
            throw new IllegalArgumentException("Illegal GeoServer archive entry path: " + entry.getName());
        }
        return target;
    }

    private File findScript(File installDir, String relativeScript) {
        String normalizedScript = normalizeRelativePath(relativeScript);
        File direct = new File(installDir, normalizedScript);
        if (direct.exists()) {
            return direct;
        }
        File[] children = installDir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File candidate = new File(child, normalizedScript);
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private File resolveHomeFromScript(File script, String relativeScript) {
        int segments = normalizeRelativePath(relativeScript).split("/").length;
        File home = script;
        for (int i = 0; i < segments; i++) {
            home = home.getParentFile();
        }
        return home;
    }

    private GeoServerProcessCommand command(Deploy deploy,
                                            File script,
                                            File home,
                                            Map<String, String> environment,
                                            File logDir) {
        List<String> command = new ArrayList<String>();
        command.add("sh");
        command.add(script.getAbsolutePath());
        return new GeoServerProcessCommand(nodeName(deploy), command, home,
                new LinkedHashMap<String, String>(environment), logDir);
    }

    private Map<String, String> environment(Deploy deploy,
                                            File home,
                                            File dataDir,
                                            File cacheDir,
                                            File logLocation) {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("GEOSERVER_HOME", home.getAbsolutePath());
        environment.put("GEOSERVER_DATA_DIR", dataDir.getAbsolutePath());
        environment.put("GEOWEBCACHE_CACHE_DIR", cacheDir.getAbsolutePath());
        environment.put("GEOSERVER_LOG_LOCATION", logLocation.getAbsolutePath());
        environment.put("GEOSERVER_PORT", String.valueOf(deploy.getPort()));
        environment.put("GEOSERVER_CONTEXT_PATH", defaultString(deploy.getContextPath(), "/geoserver"));
        if (hasText(deploy.getJavaHome())) {
            environment.put("JAVA_HOME", deploy.getJavaHome());
        }
        if (hasText(deploy.getRequireFile())) {
            environment.put("GEOSERVER_REQUIRE_FILE", deploy.getRequireFile());
        }
        environment.put("JAVA_OPTS", javaOpts(deploy, dataDir, cacheDir, logLocation));
        return environment;
    }

    private String javaOpts(Deploy deploy, File dataDir, File cacheDir, File logLocation) {
        List<String> options = new ArrayList<String>();
        String existingJavaOpts = System.getenv("JAVA_OPTS");
        if (hasText(existingJavaOpts)) {
            options.add(existingJavaOpts);
        }
        if (deploy.getJvmArgs() != null) {
            options.addAll(deploy.getJvmArgs());
        }
        options.add("-DGEOSERVER_DATA_DIR=" + dataDir.getAbsolutePath());
        options.add("-DGEOWEBCACHE_CACHE_DIR=" + cacheDir.getAbsolutePath());
        options.add("-DGEOSERVER_LOG_LOCATION=" + logLocation.getAbsolutePath());
        options.add("-Djetty.port=" + deploy.getPort());
        options.add("-Djetty.http.port=" + deploy.getPort());
        return join(options, " ");
    }

    private void validatePersistentDirectory(String label, File persistentDirectory, File installDir, Deploy deploy)
            throws IOException {
        if (deploy.isDeleteInstallOnStop() && isSameOrChild(persistentDirectory, installDir)) {
            throw new IllegalArgumentException("geoserver.deploy." + label
                    + " must not be inside install-dir when delete-install-on-stop=true");
        }
    }

    private boolean isSameOrChild(File child, File parent) throws IOException {
        File canonicalChild = child.getCanonicalFile();
        File canonicalParent = parent.getCanonicalFile();
        String childPath = canonicalChild.getPath();
        String parentPath = canonicalParent.getPath();
        return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
    }

    private void destroyManagedProcess(Deploy deploy) {
        if (process == null) {
            return;
        }
        try {
            if (process.isAlive()) {
                process.destroy();
                boolean stopped = process.waitFor(deploy.getShutdownTimeoutSeconds(), TimeUnit.SECONDS);
                if (!stopped) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private File logLocation(Deploy deploy, File logDir) {
        if (hasText(deploy.getLogLocation())) {
            return file(deploy.getLogLocation());
        }
        return new File(logDir, "geoserver.log");
    }

    private File mkdir(String path) {
        return mkdir(file(path));
    }

    private File mkdir(File file) {
        if (file != null && !file.exists() && !file.mkdirs()) {
            throw new IllegalStateException("Cannot create directory: " + file.getAbsolutePath());
        }
        return file;
    }

    private File file(String path) {
        return new File(path);
    }

    private String normalizeRelativePath(String path) {
        return required(path, "script path").replace("\\", "/");
    }

    private void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Cannot delete " + file.getAbsolutePath());
        }
    }

    private Deploy deploy() {
        if (properties.getDeploy() == null) {
            properties.setDeploy(new Deploy());
        }
        return properties.getDeploy();
    }

    private String nodeName(Deploy deploy) {
        return defaultString(deploy.getNodeName(), "local");
    }

    private String required(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private String defaultString(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private String join(List<String> items, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(delimiter);
            }
            builder.append(items.get(i));
        }
        return builder.toString();
    }

    interface Sleeper {
        void sleep(long millis);
    }

    private static class ThreadSleeper implements Sleeper {
        @Override
        public void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting GeoServer startup", ex);
            }
        }
    }

    private static class PreparedDeployment {
        private File installDirectory;
        private File geoserverHome;
        private File dataDirectory;
        private File cacheDirectory;
        private File logLocation;
        private GeoServerProcessCommand startupCommand;
        private GeoServerProcessCommand shutdownCommand;
    }
}
