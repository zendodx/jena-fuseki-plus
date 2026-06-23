package io.github.jenafuseki.plus.extension;

import io.github.jenafuseki.plus.fuseki.FusekiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 图谱可视化数据接口。
 *
 * <p>通过向 Fuseki SPARQL 端点发送查询，将结果转换为
 * 前端图渲染所需的 {nodes, edges} 格式。
 *
 * <ul>
 *   <li>GET /api/graph/neighbors  — 查询实体邻居（展开图谱）</li>
 *   <li>GET /api/graph/node       — 查询单个节点的所有属性</li>
 *   <li>GET /api/graph/search     — 按标签/URI 关键词搜索节点</li>
 *   <li>POST /api/graph/sparql    — 执行任意 SPARQL SELECT，返回图结构</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final FusekiProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public GraphController(FusekiProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ //
    //  1. 邻居查询 - 图谱展开核心接口
    // ------------------------------------------------------------------ //

    /**
     * 查询指定实体的邻居节点（支持深度控制）。
     *
     * <p>GET /api/graph/neighbors?dataset=/travel_kg_v4&uri=xxx&depth=1&limit=50
     *
     * @param dataset 数据集路径，如 /travel_kg_v4
     * @param uri     起始节点 URI
     * @param depth   展开深度（1~3，默认 1，过大会超时）
     * @param limit   最大返回三元组数（默认 100）
     */
    @GetMapping("/neighbors")
    public ResponseEntity<Map<String, Object>> neighbors(
            @RequestParam String dataset,
            @RequestParam String uri,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "100") int limit) {

        depth = Math.min(depth, 3); // 最大深度限制为 3
        limit = Math.min(limit, 500);

        // 构造 SPARQL 查询，depth=1 查直接邻居
        String sparql = buildNeighborSparql(uri, depth, limit);
        log.info("[Graph] neighbors uri={} depth={} limit={}", uri, depth, limit);

        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready or query failed"));
        }

        return ResponseEntity.ok(buildGraphResult(bindings, uri));
    }

    // ------------------------------------------------------------------ //
    //  2. 节点详情
    // ------------------------------------------------------------------ //

    /**
     * 查询单个节点的所有属性（谓词-宾语对）。
     *
     * <p>GET /api/graph/node?dataset=/travel_kg_v4&uri=xxx
     */
    @GetMapping("/node")
    public ResponseEntity<Map<String, Object>> nodeDetail(
            @RequestParam String dataset,
            @RequestParam String uri) {

        String sparql = String.format(
                "SELECT ?p ?o WHERE { <%s> ?p ?o } LIMIT 200", uri);

        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready or query failed"));
        }

        List<Map<String, String>> props = new ArrayList<>();
        for (Map<String, String> row : bindings) {
            Map<String, String> prop = new LinkedHashMap<>();
            prop.put("predicate", row.getOrDefault("p", ""));
            prop.put("object", row.getOrDefault("o", ""));
            props.add(prop);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", uri);
        body.put("label", extractLabel(uri));
        body.put("properties", props);
        body.put("count", props.size());
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ //
    //  3. 节点搜索
    // ------------------------------------------------------------------ //

    /**
     * 按 URI 片段或 rdfs:label 关键词搜索节点。
     *
     * <p>GET /api/graph/search?dataset=/travel_kg_v4&keyword=北京&limit=20
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String dataset,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {

        limit = Math.min(limit, 100);

        // 先查 rdfs:label 匹配，再查 URI 包含匹配
        String sparql = String.format(
                "SELECT DISTINCT ?s ?label WHERE {\n" +
                "  { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label .\n" +
                "    FILTER(CONTAINS(LCASE(STR(?label)), LCASE(\"%s\"))) }\n" +
                "  UNION\n" +
                "  { ?s ?p ?o .\n" +
                "    FILTER(isURI(?s) && CONTAINS(LCASE(STR(?s)), LCASE(\"%s\"))) .\n" +
                "    OPTIONAL { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label } }\n" +
                "} LIMIT %d",
                keyword, keyword, limit);

        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready or query failed"));
        }

        List<Map<String, String>> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : bindings) {
            String s = row.getOrDefault("s", "");
            if (s.isEmpty() || seen.contains(s)) continue;
            seen.add(s);
            Map<String, String> node = new LinkedHashMap<>();
            node.put("uri", s);
            node.put("label", row.getOrDefault("label", extractLabel(s)));
            nodes.add(node);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        body.put("count", nodes.size());
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ //
    //  3b. 按指定属性值搜索节点（属性过滤搜索）
    // ------------------------------------------------------------------ //

    /**
     * 按指定谓词 + 属性值关键词搜索实体节点。
     *
     * <p>GET /api/graph/search-by-prop
     *   ?dataset=/travel_kg_v4
     *   &predicate=http://schema.org/name   （可选，留空则搜所有属性）
     *   &value=北京                          （属性值关键词，必填）
     *   &limit=20
     *
     * <p>predicate 也支持简写片段（如 "name"、"label"），后端会模糊匹配 URI 中含该片段的谓词。
     */
    @GetMapping("/search-by-prop")
    public ResponseEntity<Map<String, Object>> searchByProp(
            @RequestParam String dataset,
            @RequestParam(defaultValue = "") String predicate,
            @RequestParam String value,
            @RequestParam(defaultValue = "20") int limit) {

        if (value == null || value.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
        }
        limit = Math.min(limit, 100);

        String sparql;
        if (predicate == null || predicate.trim().isEmpty()) {
            // 没有指定谓词：搜索所有字面量属性值包含 value 的主语
            sparql = String.format(
                    "SELECT DISTINCT ?s ?label ?matchProp ?matchVal WHERE {\n" +
                    "  ?s ?matchProp ?matchVal .\n" +
                    "  FILTER(isLiteral(?matchVal) && CONTAINS(LCASE(STR(?matchVal)), LCASE(\"%s\")))\n" +
                    "  OPTIONAL { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label }\n" +
                    "} LIMIT %d",
                    escapeForSparql(value), limit);
        } else if (predicate.startsWith("http://") || predicate.startsWith("https://")) {
            // 完整 URI 谓词精确匹配
            sparql = String.format(
                    "SELECT DISTINCT ?s ?label ?matchVal WHERE {\n" +
                    "  ?s <%s> ?matchVal .\n" +
                    "  FILTER(CONTAINS(LCASE(STR(?matchVal)), LCASE(\"%s\")))\n" +
                    "  OPTIONAL { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label }\n" +
                    "} LIMIT %d",
                    predicate, escapeForSparql(value), limit);
        } else {
            // 简写片段：模糊匹配谓词 URI 中含该片段的情况
            sparql = String.format(
                    "SELECT DISTINCT ?s ?label ?matchProp ?matchVal WHERE {\n" +
                    "  ?s ?matchProp ?matchVal .\n" +
                    "  FILTER(CONTAINS(LCASE(STR(?matchProp)), LCASE(\"%s\")))\n" +
                    "  FILTER(CONTAINS(LCASE(STR(?matchVal)), LCASE(\"%s\")))\n" +
                    "  OPTIONAL { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label }\n" +
                    "} LIMIT %d",
                    escapeForSparql(predicate), escapeForSparql(value), limit);
        }

        log.info("[Graph] searchByProp dataset={} predicate={} value={}", dataset, predicate, value);
        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready or query failed"));
        }

        List<Map<String, String>> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : bindings) {
            String s = row.getOrDefault("s", "");
            if (s.isEmpty() || seen.contains(s)) continue;
            seen.add(s);
            Map<String, String> node = new LinkedHashMap<>();
            node.put("uri", s);
            node.put("label", row.getOrDefault("label", extractLabel(s)));
            // 额外返回匹配到的属性名和属性值，方便前端展示
            node.put("matchProp", extractLabel(row.getOrDefault("matchProp", predicate)));
            node.put("matchVal", row.getOrDefault("matchVal", ""));
            nodes.add(node);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        body.put("count", nodes.size());
        body.put("predicate", predicate);
        body.put("value", value);
        return ResponseEntity.ok(body);
    }

    /** 对用户输入进行基本的 SPARQL 字符串转义，防止注入 */
    private String escapeForSparql(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------ //
    //  4. 自定义 SPARQL → 图结构
    // ------------------------------------------------------------------ //

    /**
     * 执行自定义 SPARQL SELECT（结果必须包含 ?s ?p ?o 三列），
     * 返回图渲染所需的 {nodes, edges} 格式。
     *
     * <p>POST /api/graph/sparql
     * <pre>
     * {
     *   "dataset": "/travel_kg_v4",
     *   "sparql": "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 100"
     * }
     * </pre>
     */
    @PostMapping("/sparql")
    public ResponseEntity<Map<String, Object>> sparqlToGraph(
            @RequestBody Map<String, String> req) {

        String dataset = req.get("dataset");
        String sparql = req.get("sparql");
        if (dataset == null || sparql == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dataset and sparql are required"));
        }

        log.info("[Graph] sparql dataset={} query={}", dataset,
                sparql.substring(0, Math.min(100, sparql.length())).replace("\n", " "));

        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready or query failed"));
        }

        return ResponseEntity.ok(buildGraphResult(bindings, null));
    }

    // ------------------------------------------------------------------ //
    //  5. 概览统计（首页使用）
    // ------------------------------------------------------------------ //

    /**
     * 查询数据集的三元组总数及类型统计。
     *
     * <p>GET /api/graph/overview?dataset=/travel_kg_v4
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam String dataset) {

        // 总三元组数
        String countSparql = "SELECT (COUNT(*) AS ?total) WHERE { ?s ?p ?o }";
        List<Map<String, String>> countResult = executeSparql(dataset, countSparql);

        // 节点类型分布（取 rdf:type）
        String typeSparql =
                "SELECT ?type (COUNT(?s) AS ?count) WHERE {\n" +
                "  ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?type\n" +
                "} GROUP BY ?type ORDER BY DESC(?count) LIMIT 20";
        List<Map<String, String>> typeResult = executeSparql(dataset, typeSparql);

        long total = 0;
        if (countResult != null && !countResult.isEmpty()) {
            try { total = Long.parseLong(countResult.get(0).getOrDefault("total", "0")); }
            catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> types = new ArrayList<>();
        if (typeResult != null) {
            for (Map<String, String> row : typeResult) {
                Map<String, Object> t = new LinkedHashMap<>();
                String typeUri = row.getOrDefault("type", "");
                t.put("type", typeUri);
                t.put("label", extractLabel(typeUri));
                try { t.put("count", Long.parseLong(row.getOrDefault("count", "0"))); }
                catch (NumberFormatException e) { t.put("count", 0); }
                types.add(t);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", dataset);
        body.put("totalTriples", total);
        body.put("typeDistribution", types);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ //
    //  6. 谓词列表（SPARQL 编辑器自动补全）
    // ------------------------------------------------------------------ //

    /**
     * 查询数据集中所有 distinct 谓词，供前端编辑器自动补全。
     *
     * <p>GET /api/graph/predicates?dataset=/travel_kg_v4&limit=200
     */
    @GetMapping("/predicates")
    public ResponseEntity<Map<String, Object>> predicates(
            @RequestParam String dataset,
            @RequestParam(defaultValue = "200") int limit) {

        String sparql = String.format(
                "SELECT DISTINCT ?p WHERE { ?s ?p ?o . FILTER(isURI(?p)) } LIMIT %d", limit);

        List<Map<String, String>> bindings = executeSparql(dataset, sparql);
        if (bindings == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Fuseki not ready"));
        }

        List<String> predicates = new ArrayList<>();
        for (Map<String, String> row : bindings) {
            String p = row.get("p");
            if (p != null && !p.isBlank()) predicates.add(p);
        }

        return ResponseEntity.ok(Map.of("predicates", predicates));
    }

    // ------------------------------------------------------------------ //
    //  内部工具方法
    // ------------------------------------------------------------------ //

    /** 构造邻居查询 SPARQL */
    private String buildNeighborSparql(String uri, int depth, int limit) {
        if (depth == 1) {
            return String.format(
                    "SELECT ?s ?p ?o WHERE {\n" +
                    "  { <%s> ?p ?o . BIND(<%s> AS ?s) FILTER(isURI(?o) || isLiteral(?o)) }\n" +
                    "  UNION\n" +
                    "  { ?s ?p <%s> . BIND(<%s> AS ?o) FILTER(isURI(?s)) }\n" +
                    "} LIMIT %d",
                    uri, uri, uri, uri, limit);
        }
        // depth >= 2：用 path 表达式查询两跳
        return String.format(
                "SELECT DISTINCT ?s ?p ?o WHERE {\n" +
                "  <%s> ?p1 ?mid .\n" +
                "  ?s ?p ?o .\n" +
                "  FILTER(?s = <%s> || ?s = ?mid || ?o = <%s> || ?o = ?mid)\n" +
                "  FILTER(isURI(?s) && (isURI(?o) || isLiteral(?o)))\n" +
                "} LIMIT %d",
                uri, uri, uri, limit);
    }

    /**
     * 将 SPARQL 绑定结果转换为图结构 {nodes, edges}。
     * 期望结果变量：?s ?p ?o（或包含这三列的任意 SELECT）
     */
    private Map<String, Object> buildGraphResult(List<Map<String, String>> bindings, String centerUri) {
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        if (bindings.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of(), "nodeCount", 0, "edgeCount", 0);
        }

        // 检测结果列名
        Set<String> cols = bindings.get(0).keySet();
        boolean hasSpO = cols.contains("s") && cols.contains("p") && cols.contains("o");

        if (hasSpO) {
            // ── 标准 ?s ?p ?o 模式 ──────────────────────────────────────
            for (Map<String, String> row : bindings) {
                String s = row.get("s");
                String p = row.get("p");
                String o = row.get("o");
                if (s == null || p == null || o == null) continue;

                if (!nodeMap.containsKey(s)) {
                    nodeMap.put(s, buildNode(s, s.equals(centerUri)));
                }

                if (isUri(o)) {
                    if (!nodeMap.containsKey(o)) {
                        nodeMap.put(o, buildNode(o, false));
                    }
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("source", s);
                    edge.put("target", o);
                    edge.put("predicate", p);
                    edge.put("label", extractLabel(p));
                    edges.add(edge);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> node = nodeMap.get(s);
                    @SuppressWarnings("unchecked")
                    Map<String, String> attrs = (Map<String, String>) node.computeIfAbsent("attributes",
                            k -> new LinkedHashMap<String, String>());
                    attrs.put(extractLabel(p), o);
                    String pLabel = extractLabel(p).toLowerCase();
                    if (pLabel.contains("label") || pLabel.contains("name") || pLabel.contains("名")) {
                        node.put("label", o);
                    }
                }
            }
        } else {
            // ── 自动推断模式：将 URI 列视为节点，字面量列视为属性 ──────────
            // 扫描第一行，判断每列的角色
            List<String> uriCols = new ArrayList<>();   // URI 值的列 → 节点
            List<String> litCols = new ArrayList<>();   // 字面量列 → 属性

            Map<String, String> sample = bindings.get(0);
            for (String col : cols) {
                String val = sample.get(col);
                if (val != null && isUri(val)) {
                    uriCols.add(col);
                } else {
                    litCols.add(col);
                }
            }

            for (Map<String, String> row : bindings) {
                // 每个 URI 列的值都注册为节点，同时将字面量列作为属性挂在第一个 URI 节点上
                String primaryUri = null;
                for (String ucol : uriCols) {
                    String uri = row.get(ucol);
                    if (uri == null || uri.isBlank()) continue;
                    if (!nodeMap.containsKey(uri)) {
                        nodeMap.put(uri, buildNode(uri, uri.equals(centerUri)));
                    }
                    if (primaryUri == null) primaryUri = uri;
                }

                // URI 列之间两两建边（用列名作为谓词标签）
                if (uriCols.size() >= 2) {
                    for (int i = 0; i < uriCols.size() - 1; i++) {
                        String src = row.get(uriCols.get(i));
                        String tgt = row.get(uriCols.get(i + 1));
                        if (src == null || tgt == null || src.isBlank() || tgt.isBlank()) continue;
                        String predLabel = uriCols.get(i) + "→" + uriCols.get(i + 1);
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("source", src);
                        edge.put("target", tgt);
                        edge.put("predicate", predLabel);
                        edge.put("label", predLabel);
                        edges.add(edge);
                    }
                }

                // 字面量列作为主节点属性
                if (primaryUri != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> node = nodeMap.get(primaryUri);
                    @SuppressWarnings("unchecked")
                    Map<String, String> attrs = (Map<String, String>) node.computeIfAbsent("attributes",
                            k -> new LinkedHashMap<String, String>());
                    for (String lcol : litCols) {
                        String val = row.get(lcol);
                        if (val != null && !val.isBlank()) {
                            attrs.put(lcol, val);
                            String lcolLower = lcol.toLowerCase();
                            if (lcolLower.contains("label") || lcolLower.contains("name") || lcolLower.contains("名")) {
                                node.put("label", val);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>(nodeMap.values()));
        result.put("edges", edges);
        result.put("nodeCount", nodeMap.size());
        result.put("edgeCount", edges.size());
        return result;
    }

    private Map<String, Object> buildNode(String uri, boolean isCenter) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", uri);
        node.put("uri", uri);
        node.put("label", extractLabel(uri));
        node.put("isCenter", isCenter);
        node.put("attributes", new LinkedHashMap<String, String>());
        return node;
    }

    /** 从 URI 中提取人类可读标签（取 # 或 / 后的最后一段） */
    private String extractLabel(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        // 字面量直接返回（去掉类型标注 "xxx"^^<type>）
        if (!uri.startsWith("http") && !uri.startsWith("<")) {
            int typeIdx = uri.indexOf("^^");
            return typeIdx > 0 ? uri.substring(0, typeIdx) : uri;
        }
        int hash = uri.lastIndexOf('#');
        if (hash >= 0 && hash < uri.length() - 1) return uri.substring(hash + 1);
        int slash = uri.lastIndexOf('/');
        if (slash >= 0 && slash < uri.length() - 1) return uri.substring(slash + 1);
        return uri;
    }

    private boolean isUri(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    /**
     * 通过 HTTP POST 向 Fuseki SPARQL 端点发送查询，返回 bindings 列表。
     * 每个 binding 是 varName → value 的 Map（已去掉 JSON-LD type 包装）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, String>> executeSparql(String dataset, String sparql) {
        String endpoint = String.format("http://localhost:%d%s/sparql",
                props.getPort(), dataset);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("query", sparql);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            Map<String, Object> body = response.getBody();
            Map<String, Object> results = (Map<String, Object>) body.get("results");
            if (results == null) return Collections.emptyList();

            List<Map<String, Object>> rawBindings = (List<Map<String, Object>>) results.get("bindings");
            if (rawBindings == null) return Collections.emptyList();

            // 把 {"s": {"type":"uri","value":"xxx"}} 展平为 {"s":"xxx"}
            List<Map<String, String>> flat = new ArrayList<>();
            for (Map<String, Object> rb : rawBindings) {
                Map<String, String> row = new LinkedHashMap<>();
                rb.forEach((k, v) -> {
                    if (v instanceof Map) {
                        row.put(k, String.valueOf(((Map<?, ?>) v).get("value")));
                    }
                });
                flat.add(row);
            }
            return flat;

        } catch (Exception e) {
            log.warn("[Graph] SPARQL 执行失败 endpoint={} error={}", endpoint, e.getMessage());
            return null;
        }
    }
}

