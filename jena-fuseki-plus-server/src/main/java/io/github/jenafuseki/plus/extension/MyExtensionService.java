package io.github.jenafuseki.plus.extension;

import io.github.jenafuseki.plus.fuseki.FusekiLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 示例：业务扩展服务（Spring Bean）。
 *
 * <p>通过 {@code @PostConstruct} 在 Spring 容器启动完成、Fuseki 就绪后自动执行初始化，
 * 通过 {@code @PreDestroy} 在 Spring 关闭时自动释放资源。
 *
 * <p>在此处放置你自己的业务逻辑：
 * <ul>
 *   <li>订阅消息队列（Kafka、RocketMQ 等），消费后写入 Fuseki</li>
 *   <li>启动定时任务（配合 {@code @Scheduled}）</li>
 *   <li>注册 Fuseki 数据变更监听</li>
 *   <li>调用外部系统初始化数据</li>
 * </ul>
 */
@Service
public class MyExtensionService {

    private static final Logger log = LoggerFactory.getLogger(MyExtensionService.class);

    /** 注入 FusekiLauncher，可随时获取 Fuseki 运行状态 */
    private final FusekiLauncher fuseki;

    public MyExtensionService(FusekiLauncher fuseki) {
        this.fuseki = fuseki;
    }

    /**
     * Spring 容器启动完成、所有 Bean 就绪后执行。
     * 此时 FusekiLauncher 已通过 SmartLifecycle 完成了 Fuseki 的启动等待，
     * 可以安全地访问 SPARQL endpoint。
     */
    @PostConstruct
    public void init() {
        log.info("[MyExtensionService] 初始化，Fuseki 运行状态: {}", fuseki.isRunning());

        // ----------------------------------------------------------------
        // TODO: 在此处添加业务初始化逻辑
        //
        // 示例1：向 Fuseki 写入初始数据（Fuseki 已就绪，可直接调用）
        //   RDFConnection conn = RDFConnectionFactory.connect("http://localhost:3030/ds");
        //   conn.load(initialDataModel);
        //
        // 示例2：启动后台数据同步线程
        //   Executors.newSingleThreadScheduledExecutor()
        //       .scheduleAtFixedRate(this::syncData, 0, 5, TimeUnit.MINUTES);
        //
        // 示例3：订阅消息队列
        //   kafkaConsumer.subscribe(List.of("rdf-events"));
        // ----------------------------------------------------------------
    }

    /**
     * Spring 容器关闭时自动调用，释放资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("[MyExtensionService] 销毁，释放资源");
        // TODO: 关闭线程池、消息连接等
    }
}

