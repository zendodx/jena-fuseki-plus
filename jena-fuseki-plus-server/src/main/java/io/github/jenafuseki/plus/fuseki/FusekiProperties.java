package io.github.jenafuseki.plus.fuseki;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fuseki 嵌入式服务配置属性，绑定 application.yml 中 fuseki.* 前缀的配置项。
 *
 * <pre>
 * fuseki:
 *   port: 3030
 *   home: ""             # 留空则自动推断
 *   base: ""             # 留空则自动推断
 *   start-timeout-seconds: 60
 *   allow-update: true
 *   config-file: ""      # TTL 配置文件路径（优先级最高）
 *   mem-dataset: false   # 仅开发/测试
 *   dataset-path: /ds
 *   verbose: false
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "fuseki")
public class FusekiProperties {

    /** Fuseki 服务监听端口，默认 3030 */
    private int port = 3030;

    /**
     * FUSEKI_HOME 目录（即 apache-jena-fuseki-4.10.0 目录）。
     * 留空则自动推断为 {工作目录}/libs/apache-jena-fuseki-4.10.0
     */
    private String home = "";

    /**
     * FUSEKI_BASE 目录（运行目录，存放数据集/配置/日志）。
     * 留空则自动推断为 {工作目录}/run
     */
    private String base = "";

    /** 等待 Fuseki 就绪的超时秒数，默认 60 */
    private int startTimeoutSeconds = 60;

    /** 是否允许 SPARQL Update 写操作，默认 true */
    private boolean allowUpdate = true;

    /**
     * TTL 数据集配置文件路径（优先级最高）。
     * 与 memDataset/datasetPath 互斥，留空则自动扫描 FUSEKI_BASE/configuration/
     */
    private String configFile = "";

    /** 使用内存数据集快速启动（仅开发/测试），默认 false */
    private boolean memDataset = false;

    /** 内存数据集挂载路径，memDataset=true 时生效，默认 /ds */
    private String datasetPath = "/ds";

    /** 是否开启 Fuseki 详细日志，默认 false */
    private boolean verbose = false;

    // ------------------- Getters & Setters ------------------- //

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getHome() { return home; }
    public void setHome(String home) { this.home = home; }

    public String getBase() { return base; }
    public void setBase(String base) { this.base = base; }

    public int getStartTimeoutSeconds() { return startTimeoutSeconds; }
    public void setStartTimeoutSeconds(int startTimeoutSeconds) { this.startTimeoutSeconds = startTimeoutSeconds; }

    public boolean isAllowUpdate() { return allowUpdate; }
    public void setAllowUpdate(boolean allowUpdate) { this.allowUpdate = allowUpdate; }

    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }

    public boolean isMemDataset() { return memDataset; }
    public void setMemDataset(boolean memDataset) { this.memDataset = memDataset; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }
}

