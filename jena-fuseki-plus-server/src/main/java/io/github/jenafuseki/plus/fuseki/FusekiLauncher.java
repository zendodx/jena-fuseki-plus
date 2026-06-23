package io.github.jenafuseki.plus.fuseki;

import io.github.jenafuseki.plus.App;
import org.apache.jena.fuseki.cmd.FusekiCmd;
import org.apache.jena.fuseki.cmd.JettyFusekiWebapp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fuseki 嵌入式启动器（Spring Bean）。
 *
 * <p>实现 {@link SmartLifecycle}，由 Spring 容器统一管理生命周期：
 * <ul>
 *   <li>Spring 启动完成后自动调用 {@link #start()}，在守护子线程中运行 Fuseki</li>
 *   <li>Spring 关闭时自动调用 {@link #stop()}，优雅停止 Fuseki 的 Jetty 服务</li>
 * </ul>
 *
 * <p>Fuseki 的 Jetty 运行在独立端口（默认 3030），
 * Spring Boot 的 Tomcat 运行在另一个端口（默认 8080），两者互不干扰。
 */
@Component
public class FusekiLauncher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FusekiLauncher.class);

    private final FusekiProperties props;

    private Thread fusekiThread;
    private volatile boolean running = false;

    public FusekiLauncher(FusekiProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ //
    //  SmartLifecycle
    // ------------------------------------------------------------------ //

    /**
     * Spring 容器启动完成后调用。
     * 在守护子线程中启动 Fuseki，并等待就绪。
     */
    @Override
    public void start() {
        if (running) {
            log.warn("[Fuseki] 已在运行，跳过重复启动");
            return;
        }

        String fusekiHome = resolveHome();
        String fusekiBase = resolveBase();
        String[] args = buildArgs();

        log.info("[Fuseki] FUSEKI_HOME = {}", fusekiHome);
        log.info("[Fuseki] FUSEKI_BASE = {}", fusekiBase);
        log.info("[Fuseki] 启动参数    = {}", Arrays.toString(args));

        // 设置 Fuseki 依赖的系统属性（等价于启动脚本中的环境变量）
        System.setProperty("FUSEKI_HOME", fusekiHome);
        System.setProperty("FUSEKI_BASE", fusekiBase);

        fusekiThread = new Thread(() -> {
            try {
                FusekiCmd.main(args);
            } catch (Exception e) {
                log.error("[Fuseki] 启动异常: {}", e.getMessage(), e);
            }
        }, "fuseki-main-thread");

        fusekiThread.setDaemon(true);
        fusekiThread.start();
        running = true;

        // 等待 Fuseki 就绪
        boolean ready = waitForReady(props.getStartTimeoutSeconds());
        if (ready) {
            log.info("[Fuseki] 就绪 ✓  SPARQL endpoint → http://localhost:{}", props.getPort());
        } else {
            log.warn("[Fuseki] {}s 内未就绪，Spring 上下文继续启动", props.getStartTimeoutSeconds());
        }
    }

    /**
     * Spring 容器关闭时调用，优雅停止 Fuseki。
     */
    @Override
    public void stop() {
        if (!running) return;
        log.info("[Fuseki] 正在停止...");
        try {
            JettyFusekiWebapp instance = JettyFusekiWebapp.instance;
            if (instance != null) {
                instance.stop();
            }
            if (fusekiThread != null) {
                fusekiThread.join(5000);
            }
        } catch (Exception e) {
            log.warn("[Fuseki] 停止时异常: {}", e.getMessage());
        } finally {
            running = false;
            log.info("[Fuseki] 已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running && fusekiThread != null && fusekiThread.isAlive();
    }

    /**
     * 启动阶段：越大越晚启动。
     * 设为较低值，让 Fuseki 在 Spring MVC 等组件之前就绪。
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    // ------------------------------------------------------------------ //
    //  Fuseki 就绪状态判断
    // ------------------------------------------------------------------ //

    /**
     * 轮询 JettyFusekiWebapp.instance 是否非 null，每 500ms 检查一次
     */
    private boolean waitForReady(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (JettyFusekiWebapp.instance != null) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    //  路径推断 & 参数构建
    // ------------------------------------------------------------------ //

    private String resolveHome() {
        String home = props.getHome();
        if (home != null && !home.isBlank()) return home;
        return projectRoot()
                .resolve("libs")
                .resolve("apache-jena-fuseki-4.10.0")
                .toString();
    }

    private String resolveBase() {
        String base = props.getBase();
        if (base != null && !base.isBlank()) return base;
        return projectRoot().resolve("run").toString();
    }

    /**
     * 定位项目根目录（包含 libs/ 子目录的那一层）。
     *
     * <p>策略：从 App.class 编译产物的位置出发，向上逐级查找包含 libs/ 的目录，
     * 不依赖启动时的工作目录（CWD），也不使用写死的绝对路径。
     *
     * <ul>
     *   <li>开发期（mvn spring-boot:run）：classes 在 target/classes/，
     *       向上 2 级即可找到 pom.xml 所在目录（即 jena-fuseki-plus-server/）</li>
     *   <li>生产（fat jar）：jar 文件所在目录视为项目根目录</li>
     * </ul>
     */
    private Path projectRoot() {
        try {
            java.net.URL location = App.class
                    .getProtectionDomain().getCodeSource().getLocation();
            Path base = Paths.get(location.toURI()).toAbsolutePath();

            // fat jar 场景：base 本身是个 .jar 文件，取其父目录
            if (Files.isRegularFile(base)) {
                base = base.getParent();
            }

            // 开发期：base = .../target/classes，向上逐级找到含 libs/ 的目录
            Path candidate = base;
            for (int i = 0; i < 6; i++) {
                if (candidate == null) break;
                if (Files.isDirectory(candidate.resolve("libs"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
            // 未找到 libs/ 时回退到 CWD（兜底）
            log.warn("[Fuseki] 未找到 libs/ 目录，回退到工作目录: {}", Paths.get("").toAbsolutePath());
        } catch (Exception e) {
            log.warn("[Fuseki] 项目根目录推断失败，回退到工作目录: {}", e.getMessage());
        }
        return Paths.get("").toAbsolutePath();
    }

    private String[] buildArgs() {
        List<String> args = new ArrayList<>();
        args.add("--port=" + props.getPort());

        if (props.isVerbose()) {
            args.add("--verbose");
        }

        String configFile = props.getConfigFile();
        if (configFile != null && !configFile.isBlank()) {
            // TTL 配置文件优先（最灵活，可描述多数据集）
            args.add("--config=" + configFile);
        } else if (props.isMemDataset()) {
            // 内存数据集（开发/测试）
            args.add("--mem");
            if (props.isAllowUpdate()) args.add("--update");
            args.add(props.getDatasetPath());
        }
        // 若都未配置，Fuseki 自动扫描 FUSEKI_BASE/configuration/ 目录

        return args.toArray(new String[0]);
    }
}

