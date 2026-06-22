# AutoConfigurationListener 图片代码转写

说明：以下内容按图片可见代码转写。

```java
package com.bocsoft.csar.csar.application.config;

import cn.hutool.core.collection.CollUtil;
import com.bocsoft.csar.csar.application.utils.FileUtil;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.*;
import java.util.List;

/**
 * <Description> <br>
 * 监听应用启动完成后，执行进程，启动csar.jar
 */
@Configuration("paramSdkAutoConfiguration")
public class AutoConfigurationListener implements ApplicationListener {
    private static final Logger log = LoggerFactory.getLogger(AutoConfigurationListener.class);

    public AutoConfigurationListener() { log.info("load-csar-shell-jar:[csar-shell-jar]"); }

    Process process = null;

    @Autowired
    ResourceLoader resourceLoader;

    @Autowired
    private CasrChkConfig casrChkConfig;

    private void startSecondJarSh() {
        try {
            //先从项目包里拷贝resources/csar_chk/csar_rpt、csar_src目录文件资源到服务器csar用户根目录下，创建rpt、src目录
            //csar_rpt存放inst_jar.sh和csar.jar
            //csar_src存放lib和sh脚本
            // 使用 ProcessBuilder 启动第二个 JAR 包
            Resource csar_rpt_jar = resourceLoader.getResource("classpath:csar_chk/csar_rpt/csar.jar");
            Resource csar_rpt_sh = resourceLoader.getResource("classpath:csar_chk/csar_rpt/inst_jar.sh");
            InputStream inputStreamJar = csar_rpt_jar.getInputStream();
            InputStream inputStreamSh = csar_rpt_sh.getInputStream();
            //
            log.info("开始拷贝文件到服务器目录。。。");
            long startTime = System.currentTimeMillis();
            String app_home = casrChkConfig.getApp_home();
            String java_home = casrChkConfig.getJava_home();
            List<String> csar_src_files = casrChkConfig.getCsar_src_files();
            //拷贝rpt目录所需文件
            String rptTargetFilePath = app_home.concat("/rpt");
            String rptJarTargetFilePath = rptTargetFilePath.concat("/csar.jar");
            String rptShTargetFilePath = rptTargetFilePath.concat("/inst_jar.sh");
            //目录是否存在，不存在创建
            FileUtil.mkdir(rptTargetFilePath);
            //先删除该目录下所有文件，重新拷贝复制
            FileUtil.delAllFile(rptTargetFilePath);
            //拷贝文件
            log.info("开始拷贝---：{}", rptJarTargetFilePath);
            FileUtils.copyInputStreamToFile(inputStreamJar, new File(rptJarTargetFilePath));
            log.info("拷贝完成---：{}", rptJarTargetFilePath);
            log.info("开始拷贝---：{}", rptShTargetFilePath);
            FileUtils.copyInputStreamToFile(inputStreamSh, new File(rptShTargetFilePath));
            log.info("拷贝完成---：{}", rptShTargetFilePath);
            String csarSrcPath = "classpath:csar_chk/csar_src";
            //拷贝src目录所需文件
            String srcTargetFilePath = app_home.concat("/src");
            if (CollUtil.isNotEmpty(csar_src_files)) {
                for (String src : csar_src_files) {
                    //逐个文件拷贝
                    String concat = csarSrcPath.concat(src);
                    Resource csar_src = resourceLoader.getResource(concat);
                    InputStream inputStream = csar_src.getInputStream();
                    String targetPath = srcTargetFilePath.concat(src);
                    //目录是否存在，不存在创建
                    FileUtil.mkdir(targetPath);
                    //先删除文件，重新拷贝复制
                    FileUtil.deleteFile(targetPath);
                    //拷贝文件
                    log.info("开始拷贝---：{}", concat);
                    FileUtils.copyInputStreamToFile(inputStream, new File(targetPath));
                    log.info("拷贝完成---：{}", targetPath);
                }
            }

            //拷贝jar_install/cfg_jar目录所需文件
            String csarJarInstallPath = "classpath:csar_chk/jar_install";
            String jarInstallTargetFilePath = app_home.concat("/jar_install");
            List<String> jar_install = casrChkConfig.getJar_install();
            if (CollUtil.isNotEmpty(jar_install)) {
                for (String install : jar_install) {
                    //逐个文件拷贝
                    String concat = csarJarInstallPath.concat(install);
                    Resource csar_src = resourceLoader.getResource(concat);
                    InputStream inputStream = csar_src.getInputStream();
                    String targetPath = jarInstallTargetFilePath.concat(install);
                    //目录是否存在，不存在创建
                    FileUtil.mkdir(targetPath);
                    //先删除文件，重新拷贝复制
                    FileUtil.deleteFile(targetPath);
                    //拷贝文件
                    log.info("开始拷贝---：{}", concat);
                    FileUtils.copyInputStreamToFile(inputStream, new File(targetPath));
                    log.info("拷贝完成---：{}", targetPath);
                }
            }
            long endTime = System.currentTimeMillis();
            log.info("拷贝所有文件到服务器目录完成：{}。。。", (endTime - startTime) / 1000 + "/s");
            // 使用 ProcessBuilder 启动
            String csarChkShPath = rptTargetFilePath.concat("/inst_jar.sh");
            log.info("startSecondJarSh:{}", csarChkShPath);
            //赋予执行权限,并执行
            log.info("开始赋予执行权限,并执行csar脚本");
            //ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", csarChkShPath);
            ProcessBuilder processBuilderChmod = new ProcessBuilder("/bin/chmod", "755", csarChkShPath);
            //传入脚本参数
            processBuilderChmod.environment().put("APP_HOME", app_home);
            processBuilderChmod.environment().put("CSAR_HOME", app_home);
            processBuilderChmod.environment().put("JAVA_HOME", java_home);
            processBuilderChmod.redirectErrorStream(true); // 合并错误流
            Process processChmod = processBuilderChmod.start();
            int waitFor = processChmod.waitFor();
            log.info("赋予755执行权限-----------------------------:{}", waitFor);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (this) {
                    if (null != process) {
                        process.destroy();
                        log.info("--------------------------------Child process stopped due to main process exit.");
                    }
                }
            }));
            processBuilderChmod.inheritIO();
            new Thread(() -> {
                InputStream is1 = null;
                try {
                    //ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", csarChkShPath);
                    //process = processBuilder.start();
                    //执行脚本
                    String[] cmdArr = {"sh", "-c", csarChkShPath};
                    process = Runtime.getRuntime().exec(cmdArr);
                    log.info("--------------------------------" + process.isAlive());
                    //把缓冲区读取出来
                    is1 = process.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is1));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("读取流--------------------------------Child process:{}", line);
                    }
                    //等待shell脚本结果
                    int exitCode = process.waitFor();
                    log.info("--------------------------------Child process exited with code: " + exitCode);
                    log.info("执行csar脚本end");
                    log.info("执行csar脚本结果：{}", exitCode);
                    if (exitCode != 0) {
                        // 如果子进程结束，终止主进程
                        System.exit(exitCode); // 使用子进程的退出码结束主进程
                    }
                } catch (IOException | InterruptedException e) {
                    log.error("启动csar进程异常：", e);
                    log.error("启动csar子进程异常，关闭主进程");
                    if (process != null) {
                        process.destroy(); // 确保子进程被销毁
                    }
                    System.exit(0); // 结束主进程
                } finally {
                    if (is1 != null) {
                        try {
                            is1.close();
                        } catch (IOException e) {
                            log.error("启动csar子进程异常，关闭主进程：", e);
                            if (process != null) {
                                process.destroy(); // 确保子进程被销毁
                            }
                            System.exit(0); // 结束主进程
                        }
                    }
                }
            }).start();
        } catch (Exception e) {
            log.error("启动csar进程异常：", e);
            synchronized (this) {
                if (process != null) {
                    process.destroy(); // 确保子进程被销毁
                }
                System.exit(0); // 结束主进程
            }
        }
    }

    private void stopSecondJarSh() {
        try {
            // 使用 ProcessBuilder 停止第二个 JAR 包
            String app_home = casrChkConfig.getApp_home();
            String csarChkShPath = app_home.concat("/src/killDeamon.sh");
            log.info("stopSecondJarSh:{}", csarChkShPath);
            //赋予执行权限,并执行
            log.info("主进程关闭，开始赋予执行权限,并执行csar停止脚本");
            ProcessBuilder processBuilderChmod = new ProcessBuilder("/bin/chmod", "755", csarChkShPath);
            //传入脚本参数
            processBuilderChmod.environment().put("APP_HOME", app_home);
            processBuilderChmod.environment().put("CSAR_HOME", app_home);
            processBuilderChmod.redirectErrorStream(true); // 合并错误流
            Process processChmod = processBuilderChmod.start();
            int waitFor = processChmod.waitFor();
            log.info("赋予755执行权限--------------------------------:{}", waitFor);
            processBuilderChmod.inheritIO();
            new Thread(() -> {
                InputStream is1 = null;
                try {
                    //执行脚本
                    String[] cmdArr = {"sh", "-c", csarChkShPath};
                    process = Runtime.getRuntime().exec(cmdArr);
                    log.info("--------------------------------" + process.isAlive());
                    //把缓冲区读取出来
                    is1 = process.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is1));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("读取流--------------------------------Child process:{}", line);
                    }
                    //等待shell脚本结果
                    int exitCode = process.waitFor();
                    log.info("--------------------------------Child process exited with code: " + exitCode);
                    log.info("执行csar停止脚本end");
                    log.info("执行csar停止脚本结果：{}", exitCode);
                    if (exitCode != 0) {
                        // 如果子进程结束，终止主进程
                        System.exit(exitCode); // 使用子进程的退出码结束主进程
                    }
                } catch (IOException | InterruptedException e) {
                    log.error("停止csar进程异常：", e);
                    if (process != null) {
                        process.destroy(); // 确保子进程被销毁
                    }
                } finally {
                    if (is1 != null) {
                        try {
                            is1.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();
        } catch (Exception e) {
            log.error("停止csar进程异常：", e);
            synchronized (this) {
                if (process != null) {
                    process.destroy(); // 确保子进程被销毁
                }
            }
        }
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        //销毁
        if (casrChkConfig.getOpenCsarProcess()) {
            if (event instanceof ContextClosedEvent) {
                synchronized (this) {
                    if (null != process) {
                        log.error("主进程关闭，销毁子进程，process.destroy()...");
                        process.destroy();
                        process = null;
                        stopSecondJarSh();
                    }
                }
            }

            //启动
            if (event instanceof ApplicationReadyEvent) {
                synchronized (this) {
                    startSecondJarSh();
                }
            }
        }
    }
}
```
