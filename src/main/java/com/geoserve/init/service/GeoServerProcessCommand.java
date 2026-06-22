package com.geoserve.init.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动或停止 GeoServer 子进程时使用的命令快照。
 *
 * 该对象只保存已经解析完成的目录、命令和环境变量，便于日志和单元测试追踪。
 */
public class GeoServerProcessCommand {

    private final String nodeName;
    private final List<String> command;
    private final File workingDirectory;
    private final Map<String, String> environment;
    private final File logDirectory;

    public GeoServerProcessCommand(String nodeName,
                                   List<String> command,
                                   File workingDirectory,
                                   Map<String, String> environment,
                                   File logDirectory) {
        this.nodeName = nodeName;
        this.command = Collections.unmodifiableList(new ArrayList<String>(command));
        this.workingDirectory = workingDirectory;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<String, String>(environment));
        this.logDirectory = logDirectory;
    }

    public String getNodeName() {
        return nodeName;
    }

    public List<String> getCommand() {
        return command;
    }

    public File getWorkingDirectory() {
        return workingDirectory;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public File getLogDirectory() {
        return logDirectory;
    }
}
