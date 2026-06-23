package io.github.jenafuseki.plus.extension;

import io.github.jenafuseki.plus.fuseki.FusekiLauncher;
import io.github.jenafuseki.plus.fuseki.FusekiProperties;
import org.apache.jena.fuseki.cmd.JettyFusekiWebapp;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例：SPARQL 审计/代理 Controller（Spring REST Controller）。
 *
 * <p>GET /api/fuseki/datasets       — 列出所有已挂载的数据集及其状态
 * <p>GET /api/fuseki/query?dataset=/ds&query=SELECT...  — 审计日志 + 透传查询
 *
 * <p>注意：这是新增端点，不修改原有的 Fuseki /sparql /query 等标准端点。
 */
@RestController
@RequestMapping("/api/fuseki")
public class SparqlProxyServlet {

    private static final Logger log = LoggerFactory.getLogger(SparqlProxyServlet.class);

    private final FusekiLauncher fuseki;
    private final FusekiProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public SparqlProxyServlet(FusekiLauncher fuseki, FusekiProperties props) {
        this.fuseki = fuseki;
        this.props = props;
    }

    /**
     * 列出 Fuseki 中所有已挂载的数据集及其状态。
     * GET /api/fuseki/datasets
     */
    @GetMapping("/datasets")
    public ResponseEntity<Map<String, Object>> datasets() {
        DataAccessPointRegistry registry = getRegistry();
        if (registry == null) {
            Map<String, Object> err = Map.of("error", "Fuseki not ready");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
        }

        List<Map<String, Object>> datasetList = new ArrayList<>();
        registry.accessPoints().forEach(dap -> {
            Map<String, Object> item = new LinkedHashMap<>();
            // getName() 返回带前导斜杠的路径，如 "/travel_kg_v4"，去掉前导斜杠方便前端使用
            String name = dap.getName();
            if (name.startsWith("/")) name = name.substring(1);
            item.put("name", name);
            item.put("path", dap.getName()); // 保留完整路径，方便构造 SPARQL endpoint
            item.put("accepting", dap.getDataService().isAcceptingRequests());
            datasetList.add(item);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasets", datasetList);
        body.put("count", datasetList.size());
        return ResponseEntity.ok(body);
    }

    /**
     * SPARQL 查询代理：将前端请求转发给 Fuseki，避免前端直接跨域访问 3030 端口。
     *
     * <p>POST /api/fuseki/sparql
     * <pre>
     * {
     *   "dataset": "/travel_kg_v4",   // 数据集路径（含前导斜杠）
     *   "query":   "SELECT ..."        // SPARQL 语句
     * }
     * </pre>
     */
    @PostMapping("/sparql")
    public ResponseEntity<Object> sparqlProxy(@RequestBody Map<String, String> req) {
        String dataset = req.get("dataset");
        String query   = req.get("query");
        if (dataset == null || query == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dataset and query are required"));
        }
        // 确保 dataset 以 / 开头
        if (!dataset.startsWith("/")) dataset = "/" + dataset;

        String endpoint = String.format("http://localhost:%d%s/sparql", props.getPort(), dataset);
        log.info("[SparqlProxy] dataset={} query={}", dataset,
                query.substring(0, Math.min(120, query.length())).replace("\n", " "));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("query", query);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
            ResponseEntity<Object> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, request, Object.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            log.warn("[SparqlProxy] 转发失败 endpoint={} error={}", endpoint, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki 不可用: " + e.getMessage()));
        }
    }

    /** 从 Fuseki 运行时获取数据集注册表 */
    private DataAccessPointRegistry getRegistry() {
        JettyFusekiWebapp instance = JettyFusekiWebapp.instance;
        if (instance == null || !fuseki.isRunning()) return null;
        try {
            return instance.getDataAccessPointRegistry();
        } catch (Exception e) {
            log.warn("[SparqlProxy] 获取注册表失败: {}", e.getMessage());
            return null;
        }
    }
}

