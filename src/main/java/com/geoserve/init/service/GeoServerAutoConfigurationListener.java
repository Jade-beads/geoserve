package com.geoserve.init.service;

import com.geoserve.init.config.GeoServerInitProperties;
import com.geoserve.init.config.GeoServerInitProperties.Deploy;
import com.geoserve.init.model.GeoServerStatus;
import com.geoserve.init.util.IpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 监听 Spring Boot 启停事件，在本机解压、启动、停止 GeoServer。
 *
 * 该类按 AutoConfigurationListener 示例保留单类监听器形态：
 * 通过 {@link #onApplicationEvent(ApplicationEvent)} 分发启动和关闭事件。
 */
@Configuration("geoServerAutoConfigurationListener")
public class GeoServerAutoConfigurationListener implements ApplicationListener<ApplicationEvent> {

    private static final Logger log = LoggerFactory.getLogger(GeoServerAutoConfigurationListener.class);

    Process process = null;

    @Autowired
    ResourceLoader resourceLoader;

    @Autowired
    private GeoServerInitProperties properties;

    @Autowired
    private GeoServerRestClient restClient;

    @Autowired
    private GeoServerInitService initService;

    private File installDirectory;
    private File geoserverHome;
    private File shutdownScript;
    private long sleepMillis = 1000L;

    public GeoServerAutoConfigurationListener() {
        log.info("load-geoserver-managed-deploy:[geoserver-managed-deploy]");
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        Deploy deploy = deploy();
        if (!deploy.isEnabled()) {
            return;
        }

        if (event instanceof ContextClosedEvent) {
            synchronized (this) {
                stopGeoServerSh(false);
            }
        }

        if (event instanceof ApplicationReadyEvent) {
            synchronized (this) {
                startGeoServerSh();
            }
        }
    }

    private void startGeoServerSh() {
        Deploy deploy = deploy();
        try {
            if (isGeoServerRunning()) {
                log.warn("GeoServer is already running, stop it before managed restart node={}", nodeName(deploy));
                stopGeoServerSh(true);
            }

            PreparedDeployment prepared = prepareDeployment(deploy);
            this.installDirectory = prepared.installDirectory;
            this.geoserverHome = prepared.geoserverHome;
            this.shutdownScript = prepared.shutdownScript;

            chmodExecutable(prepared.startupScript, prepared.environment, prepared.geoserverHome);
            String startupScriptPath = prepared.startupScript.getAbsolutePath();
            String[] cmdArr = {"sh", "-c", startupScriptPath};
            process = Runtime.getRuntime().exec(cmdArr, envArray(prepared.environment), prepared.geoserverHome);
            log.info("GeoServer startup script executed node={} script={} dataDir={} cacheDir={} logLocation={}",
                    nodeName(deploy),
                    startupScriptPath,
                    prepared.dataDirectory.getAbsolutePath(),
                    prepared.cacheDirectory.getAbsolutePath(),
                    prepared.logLocation.getAbsolutePath());
            startOutputLogger(process.getInputStream(), "GeoServer child output");
            startOutputLogger(process.getErrorStream(), "GeoServer child error");
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (GeoServerAutoConfigurationListener.this) {
                        if (process != null && process.isAlive()) {
                            process.destroy();
                            log.info("GeoServer child process stopped due to main process exit");
                        }
                    }
                }
            }));

            waitUntilReady(deploy);
            if (properties.getInit() != null && properties.getInit().isRunOnStartup()) {
                log.info("GeoServer is ready, run startup initialization node={}", nodeName(deploy));
                initService.initialize();
            }
        } catch (RuntimeException ex) {
            destroyProcess(deploy);
            throw ex;
        } catch (Exception ex) {
            destroyProcess(deploy);
            throw new IllegalStateException("GeoServer managed startup failed: " + ex.getMessage(), ex);
        }
    }

    private void stopGeoServerSh(boolean failOnError) {
        Deploy deploy = deploy();
        RuntimeException failure = null;
        try {
            File script = resolveShutdownScript(deploy);
            if (script != null) {
                File home = resolveHomeFromScript(script, deploy.getShutdownScript());
                Map<String, String> environment = environment(deploy, home,
                        file(required(deploy.getDataDir(), "geoserver.deploy.dataDir")),
                        cacheDirectory(deploy),
                        logLocation(deploy, file(required(deploy.getLogDir(), "geoserver.deploy.logDir"))));
                chmodExecutable(script, environment, home);
                String[] cmdArr = {"sh", "-c", script.getAbsolutePath()};
                Process stopProcess = Runtime.getRuntime().exec(cmdArr, envArray(environment), home);
                startOutputLogger(stopProcess.getInputStream(), "GeoServer shutdown output");
                startOutputLogger(stopProcess.getErrorStream(), "GeoServer shutdown error");
                boolean finished = stopProcess.waitFor(deploy.getShutdownTimeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    stopProcess.destroyForcibly();
                    throw new IllegalStateException("GeoServer shutdown script timed out: " + script.getAbsolutePath());
                }
                int exitCode = stopProcess.exitValue();
                if (exitCode != 0) {
                    throw new IllegalStateException("GeoServer shutdown script returned exit code " + exitCode);
                }
                log.info("GeoServer shutdown script finished node={} script={}", nodeName(deploy), script.getAbsolutePath());
            } else if (failOnError) {
                throw new IllegalStateException("GeoServer shutdown script not found under install-dir: "
                        + deploy.getShutdownScript());
            }
        } catch (RuntimeException ex) {
            failure = ex;
            log.error("GeoServer shutdown failed node={} message={}", nodeName(deploy), ex.getMessage(), ex);
        } catch (Exception ex) {
            failure = new IllegalStateException("GeoServer shutdown failed: " + ex.getMessage(), ex);
            log.error("GeoServer shutdown failed node={} message={}", nodeName(deploy), ex.getMessage(), ex);
        } finally {
            destroyProcess(deploy);
            if (deploy.isDeleteInstallOnStop()) {
                deleteInstallDirectory(deploy);
            }
            process = null;
            shutdownScript = null;
            geoserverHome = null;
            installDirectory = null;
        }

        if (failOnError && failure != null) {
            throw failure;
        }
    }

    private PreparedDeployment prepareDeployment(Deploy deploy) throws IOException {
        File workDir = mkdir(required(deploy.getWorkDir(), "geoserver.deploy.workDir"));
        File installDir = file(required(deploy.getInstallDir(), "geoserver.deploy.installDir"));
        File dataDir = file(required(deploy.getDataDir(), "geoserver.deploy.dataDir"));
        File cacheDir = cacheDirectory(deploy);
        File logDir = file(required(deploy.getLogDir(), "geoserver.deploy.logDir"));
        validatePersistentDirectory("data-dir", dataDir, installDir, deploy);
        validatePersistentDirectory("cache-dir", cacheDir, installDir, deploy);
        validatePersistentDirectory("log-dir", logDir, installDir, deploy);
        File logLocation = logLocation(deploy, logDir);
        validatePersistentDirectory("log-location", logLocation, installDir, deploy);

        mkdir(workDir);
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
        File home = resolveHomeFromScript(startupScript, deploy.getStartupScript());
        File stopScript = new File(home, normalizeRelativePath(deploy.getShutdownScript()));
        Map<String, String> environment = environment(deploy, home, dataDir, cacheDir, logLocation);

        PreparedDeployment prepared = new PreparedDeployment();
        prepared.installDirectory = installDir;
        prepared.geoserverHome = home;
        prepared.dataDirectory = dataDir;
        prepared.cacheDirectory = cacheDir;
        prepared.logLocation = logLocation;
        prepared.startupScript = startupScript;
        prepared.shutdownScript = stopScript.exists() ? stopScript : null;
        prepared.environment = environment;
        return prepared;
    }

    private boolean isGeoServerRunning() {
        if (process != null && process.isAlive()) {
            return true;
        }
        GeoServerStatus status = restClient.checkStatus();
        return status != null && status.isReachable();
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
            sleep();
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
                } else if (target.exists()) {
                    log.debug("GeoServer archive entry skipped because target exists node={} path={}",
                            nodeName(deploy), target.getAbsolutePath());
                } else {
                    mkdir(target.getParentFile());
                    Files.copy(zip, target.toPath());
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

    private File resolveShutdownScript(Deploy deploy) {
        if (shutdownScript != null && shutdownScript.exists()) {
            return shutdownScript;
        }
        File installDir = file(required(deploy.getInstallDir(), "geoserver.deploy.installDir"));
        return findScript(installDir, deploy.getShutdownScript());
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

    private Map<String, String> environment(Deploy deploy,
                                            File home,
                                            File dataDir,
                                            File cacheDir,
                                            File logLocation) {
        Map<String, String> environment = new LinkedHashMap<String, String>(System.getenv());
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

    private File cacheDirectory(Deploy deploy) {
        File cacheRoot = file(required(deploy.getCacheDir(), "geoserver.deploy.cacheDir"));
        if (!deploy.isCacheDirPerHostEnabled()) {
            return cacheRoot;
        }
        String hostAddress;
        try {
            InetAddress hostIp = resolveHostIp();
            hostAddress = hostIp == null ? "" : hostIp.getHostAddress();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Cannot resolve host IP for GeoWebCache cache directory", ex);
        } catch (SocketException ex) {
            throw new IllegalStateException("Cannot resolve host IP for GeoWebCache cache directory", ex);
        }
        if (!hasText(hostAddress)) {
            throw new IllegalStateException("Cannot resolve host IP for GeoWebCache cache directory");
        }
        return new File(cacheRoot, hostAddress.replace('.', '_') + "_gwc");
    }

    protected InetAddress resolveHostIp() throws SocketException {
        return IpUtil.getHostIp();
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
        if (!hasXmxOption(existingJavaOpts, deploy.getJvmArgs()) && hasText(deploy.getJvmMaxHeap())) {
            options.add(xmxOption(deploy.getJvmMaxHeap()));
        }
        options.add("-DGEOSERVER_DATA_DIR=" + dataDir.getAbsolutePath());
        options.add("-DGEOWEBCACHE_CACHE_DIR=" + cacheDir.getAbsolutePath());
        options.add("-DGEOSERVER_LOG_LOCATION=" + logLocation.getAbsolutePath());
        options.add("-Djetty.port=" + deploy.getPort());
        options.add("-Djetty.http.port=" + deploy.getPort());
        return join(options, " ");
    }

    private boolean hasXmxOption(String existingJavaOpts, List<String> jvmArgs) {
        if (containsXmxOption(existingJavaOpts)) {
            return true;
        }
        if (jvmArgs == null) {
            return false;
        }
        for (String arg : jvmArgs) {
            if (containsXmxOption(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsXmxOption(String value) {
        if (!hasText(value)) {
            return false;
        }
        String[] tokens = value.trim().split("\\s+");
        for (String token : tokens) {
            if (token.startsWith("-Xmx")) {
                return true;
            }
        }
        return false;
    }

    private String xmxOption(String jvmMaxHeap) {
        String value = jvmMaxHeap.trim();
        return value.startsWith("-Xmx") ? value : "-Xmx" + value;
    }

    private void chmodExecutable(File target, Map<String, String> environment, File directory)
            throws IOException, InterruptedException {
        ProcessBuilder chmodBuilder = new ProcessBuilder("/bin/chmod", "755", target.getAbsolutePath());
        chmodBuilder.directory(directory);
        chmodBuilder.redirectErrorStream(true);
        chmodBuilder.environment().putAll(environment);
        Process chmodProcess = chmodBuilder.start();
        startOutputLogger(chmodProcess.getInputStream(), "GeoServer chmod output");
        boolean finished = chmodProcess.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            chmodProcess.destroyForcibly();
            throw new IllegalStateException("chmod timed out: " + target.getAbsolutePath());
        }
        if (chmodProcess.exitValue() != 0) {
            throw new IllegalStateException("chmod failed exitCode=" + chmodProcess.exitValue()
                    + " file=" + target.getAbsolutePath());
        }
    }

    private void startOutputLogger(final InputStream inputStream, final String prefix) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("{}: {}", prefix, line);
                    }
                } catch (IOException ex) {
                    log.warn("{} logger stopped message={}", prefix, ex.getMessage());
                } finally {
                    try {
                        reader.close();
                    } catch (IOException ex) {
                        log.debug("{} stream close failed", prefix, ex);
                    }
                }
            }
        }, prefix.replace(' ', '-').toLowerCase());
        thread.setDaemon(true);
        thread.start();
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

    private void destroyProcess(Deploy deploy) {
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

    private void deleteInstallDirectory(Deploy deploy) {
        File installDir = installDirectory != null
                ? installDirectory : file(required(deploy.getInstallDir(), "geoserver.deploy.installDir"));
        try {
            deleteRecursively(installDir);
            log.info("GeoServer install directory deleted node={} path={}",
                    nodeName(deploy), installDir.getAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("GeoServer install directory delete failed: " + ex.getMessage(), ex);
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

    private String[] envArray(Map<String, String> environment) {
        String[] envp = new String[environment.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            envp[index] = entry.getKey() + "=" + entry.getValue();
            index++;
        }
        return envp;
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

    private void sleep() {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting GeoServer startup", ex);
        }
    }

    private static class PreparedDeployment {
        private File installDirectory;
        private File geoserverHome;
        private File dataDirectory;
        private File cacheDirectory;
        private File logLocation;
        private File startupScript;
        private File shutdownScript;
        private Map<String, String> environment;
    }
}
