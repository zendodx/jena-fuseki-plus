package io.github.jenafuseki.plus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 主入口。
 *
 * <p>启动顺序（由 Spring 容器自动管理）：
 * <ol>
 *   <li>Spring 容器初始化，加载所有 Bean（FusekiProperties、FusekiLauncher、Controller 等）</li>
 *   <li>{@code FusekiLauncher}（实现 SmartLifecycle，phase=MIN_VALUE+100）自动启动 Fuseki
 *       — 在守护子线程中运行 FusekiCmd.main()，阻塞等待就绪</li>
 *   <li>{@code MyExtensionService}（@PostConstruct）初始化业务逻辑</li>
 *   <li>Spring Boot 完成启动，Tomcat 监听 8080，Fuseki Jetty 监听 3030</li>
 *   <li>关闭时：Spring 调用 SmartLifecycle.stop() → 优雅停止 Fuseki</li>
 * </ol>
 *
 * <p>端口分配：
 * <ul>
 *   <li>8080 — Spring Boot（自定义 REST API / Actuator 监控）</li>
 *   <li>3030 — Fuseki（SPARQL endpoint / 管理 API，保持原有接口不变）</li>
 * </ul>
 */
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
