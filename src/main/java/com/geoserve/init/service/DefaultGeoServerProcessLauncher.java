package com.geoserve.init.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link ProcessBuilder} 的 GeoServer 子进程启动器。
 */
@Component
public class DefaultGeoServerProcessLauncher implements GeoServerProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(DefaultGeoServerProcessLauncher.class);

    @Override
    public Process start(GeoServerProcessCommand command) throws IOException {
        ProcessBuilder builder = processBuilder(command);
        Process process = builder.start();
        startOutputLogger(command, process.getInputStream());
        return process;
    }

    @Override
    public int runAndWait(GeoServerProcessCommand command, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder builder = processBuilder(command);
        Process process = builder.start();
        startOutputLogger(command, process.getInputStream());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("GeoServer shutdown command timed out after "
                    + timeoutSeconds + " seconds: " + command.getCommand());
        }
        return process.exitValue();
    }

    private ProcessBuilder processBuilder(GeoServerProcessCommand command) {
        ProcessBuilder builder = new ProcessBuilder(command.getCommand());
        builder.directory(command.getWorkingDirectory());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.putAll(command.getEnvironment());
        return builder;
    }

    private void startOutputLogger(final GeoServerProcessCommand command, final InputStream inputStream) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("GeoServer child output node={} line={}", command.getNodeName(), line);
                    }
                } catch (IOException ex) {
                    log.warn("GeoServer child output logger stopped node={} message={}",
                            command.getNodeName(), ex.getMessage());
                } finally {
                    try {
                        reader.close();
                    } catch (IOException ex) {
                        log.debug("GeoServer child output stream close failed node={}", command.getNodeName(), ex);
                    }
                }
            }
        }, "geoserver-child-output-" + command.getNodeName());
        thread.setDaemon(true);
        thread.start();
    }
}
