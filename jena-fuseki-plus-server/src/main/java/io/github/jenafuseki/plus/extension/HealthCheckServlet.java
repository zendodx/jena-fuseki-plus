package io.github.jenafuseki.plus.extension;

import io.github.jenafuseki.plus.fuseki.FusekiLauncher;
import io.github.jenafuseki.plus.fuseki.FusekiProperties;
import org.apache.jena.fuseki.cmd.JettyFusekiWebapp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义健康检查端点（Spring REST Controller）。
 *
 * <p>GET /api/fuseki/health    — Fuseki 运行状态
 * <p>GET /api/fuseki/admin-url — 返回 Fuseki 官方管理台 URL（供前端跳转）
 */
@RestController
@RequestMapping("/api/fuseki")
public class HealthCheckServlet {

    private final FusekiLauncher fuseki;
    private final FusekiProperties fusekiProperties;

    public HealthCheckServlet(FusekiLauncher fuseki, FusekiProperties fusekiProperties) {
        this.fuseki = fuseki;
        this.fusekiProperties = fusekiProperties;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean fusekiReady = JettyFusekiWebapp.instance != null && fuseki.isRunning();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", fusekiReady ? "UP" : "DOWN");
        body.put("fuseki", "4.10.0");
        body.put("fusekiRunning", fusekiReady);
        body.put("timestamp", System.currentTimeMillis());

        HttpStatus status = fusekiReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 返回 Fuseki 官方管理台地址。
     *
     * <p>使用请求中的 Host（域名/IP），替换端口为 Fuseki 端口，
     * 保证在任何网络环境（localhost / 局域网 IP / 域名代理）下都能正确跳转。
     */
    @GetMapping("/admin-url")
    public ResponseEntity<Map<String, Object>> adminUrl(HttpServletRequest request) {
        // 取请求来源的 host（不含端口），原样拼接 Fuseki 端口
        String scheme = request.getScheme();          // http / https
        String host   = request.getServerName();      // 域名或 IP
        int    fusekiPort = fusekiProperties.getPort(); // 默认 3030

        String url = scheme + "://" + host + ":" + fusekiPort;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("fusekiPort", fusekiPort);
        body.put("running", JettyFusekiWebapp.instance != null && fuseki.isRunning());

        return ResponseEntity.ok(body);
    }
}

