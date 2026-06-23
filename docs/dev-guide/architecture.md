# Jena Fuseki Plus — 整体架构文档

## 1. 系统全貌

本项目是基于 **Apache Jena Fuseki 4.10.0** 的二次开发平台，将 Fuseki 嵌入 Spring Boot，并在此之上构建了一套前端可视化管理台。整体分为三层：

```
┌─────────────────────────────────────────────────────────┐
│  Browser（Vue 3 SPA）         :5173 (dev) / :3040 (生产)  │
│  ┌──────────┐ ┌────────────┐ ┌───────────────────────┐  │
│  │ 图谱可视化 │ │ SPARQL 查询 │ │ 数据集管理 + 官方管理台 │  │
│  └──────────┘ └────────────┘ └───────────────────────┘  │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP / axios  /api/*
┌───────────────────────▼─────────────────────────────────┐
│  Spring Boot 2.7（Tomcat）               :3040            │
│  ┌──────────────────┐ ┌─────────────────────────────┐    │
│  │  GraphController │ │  SparqlProxyServlet          │   │
│  │  /api/graph/*    │ │  /api/fuseki/*               │   │
│  └────────┬─────────┘ └─────────────┬───────────────┘    │
│           │ RestTemplate             │ RestTemplate        │
└───────────┼──────────────────────────┼────────────────────┘
            │                          │ SPARQL HTTP Protocol
┌───────────▼──────────────────────────▼────────────────────┐
│  Apache Jena Fuseki 4.10.0（Jetty）     :3030              │
│  /{dataset}/sparql  /{dataset}/update  /{dataset}/data     │
│  /$/ping  /$/server  /$/datasets  /$/metrics               │
└────────────────────────────────────────────────────────────┘
```

**同一 JVM 进程**内运行两个 HTTP 服务器，互相独立、互不干扰：

- **Tomcat :3040** — Spring Boot 自定义 REST API，前端唯一交互入口
- **Jetty :3030** — Fuseki 原生 SPARQL 服务，保持原有接口完整不变

---

## 2. 进程内双服务器架构

### 启动顺序

```
SpringApplication.run()
    │
    ├─► FusekiLauncher.start()           [SmartLifecycle, phase=MIN_VALUE+100]
    │       ├─ 设置 FUSEKI_HOME / FUSEKI_BASE 系统属性
    │       ├─ 守护子线程: FusekiCmd.main(args)  → Jetty:3030
    │       └─ 轮询等待 JettyFusekiWebapp.instance != null（最多 60s）
    │
    ├─► Spring MVC / Actuator 初始化
    │
    ├─► MyExtensionService.init()        [@PostConstruct，Fuseki 已就绪]
    │       └─ 业务初始化（消息队列订阅、定时任务等）
    │
    └─► Tomcat:3040 就绪
```

### 关闭顺序

```
SIGTERM / Ctrl+C
    │
    ├─► MyExtensionService.destroy()     [@PreDestroy]
    └─► FusekiLauncher.stop()            [SmartLifecycle]
            ├─ JettyFusekiWebapp.instance.stop()
            └─ fusekiThread.join(5000ms)
```

---

## 3. 目录结构

### 3.1 后端（`jena-fuseki-plus-server/`）

```
jena-fuseki-plus-server/
├── libs/
│   └── apache-jena-fuseki-4.10.0/
│       ├── fuseki-server.jar            ← Fuseki fat jar（含 Jetty/Shiro/Jena/log4j-core）
│       └── webapp/WEB-INF/web.xml       ← Fuseki 原生 Servlet 配置
│
├── run/                                 ← FUSEKI_BASE（运行时数据目录）
│   ├── configuration/                   ← 数据集 TTL 配置文件（Fuseki 自动扫描加载）
│   ├── databases/                       ← TDB2 持久化数据文件
│   ├── backups/                         ← 备份目录
│   ├── logs/                            ← Fuseki 日志
│   ├── system/                          ← Fuseki 系统元数据
│   └── templates/                       ← 数据集模板
│
└── src/main/java/io/github/jenafuseki/plus
    ├── App.java                         ← @SpringBootApplication 入口
    ├── fuseki/
    │   ├── FusekiLauncher.java          ← @Component + SmartLifecycle，嵌入式启动 Fuseki
    │   └── FusekiProperties.java        ← @ConfigurationProperties(prefix="fuseki")
    └── extension/
        ├── MyExtensionService.java      ← @Service，业务扩展入口
        ├── HealthCheckServlet.java      ← /api/fuseki/health + /api/fuseki/admin-url
        ├── SparqlProxyServlet.java      ← /api/fuseki/datasets + /api/fuseki/sparql 代理
        └── GraphController.java        ← /api/graph/*，图谱数据接口核心
```

### 3.2 前端（`jena-fuseki-plus-ui/`）

```
jena-fuseki-plus-ui/
├── src/
│   ├── main.js                          ← Vue 应用入口，挂载 App
│   ├── App.vue                          ← 根组件，侧边栏导航 + <keep-alive> 页面容器
│   ├── router/
│   │   └── index.js                     ← Vue Router（Hash 模式），三个路由页面
│   ├── stores/
│   │   └── graphStore.js                ← Pinia Store，跨页面传递 SPARQL→图谱任务
│   ├── api/
│   │   └── index.js                     ← axios 封装，graphApi / fusekiApi / executeSparql
│   └── views/
│       ├── GraphView.vue                ← 知识图谱可视化页面
│       ├── SparqlView.vue               ← SPARQL 查询编辑器页面
│       └── DatasetsView.vue             ← 数据集管理页面
└── vite.config.js                       ← Vite 配置，代理 /api → localhost:3040
```

---

## 4. 前端架构

### 4.1 技术栈

| 层次     | 技术                                        |
|--------|-------------------------------------------|
| 框架     | Vue 3 + Composition API（`<script setup>`） |
| 构建     | Vite 5                                    |
| UI 组件库 | Element Plus                              |
| 图表     | ECharts 5（类型分布饼图）                         |
| 编辑器    | CodeMirror 6（SPARQL 语法高亮 + 自动补全）          |
| 图谱渲染   | Canvas 2D API（自研 Force-Directed 布局）       |
| 状态管理   | Pinia                                     |
| 路由     | Vue Router 4（Hash 模式）                     |
| HTTP   | axios（统一拦截器，错误自动 ElMessage 弹出）            |

### 4.2 路由页面

| 路由          | 组件                 | 功能                         |
|-------------|--------------------|----------------------------|
| `/graph`    | `GraphView.vue`    | 知识图谱可视化与交互                 |
| `/sparql`   | `SparqlView.vue`   | SPARQL 查询编辑与执行             |
| `/datasets` | `DatasetsView.vue` | 数据集管理（CRUD + 导入导出）         |
| （外链）        | ——                 | 官方管理台（新标签页打开 Fuseki :3030） |

### 4.3 图谱可视化（`GraphView.vue`）

图谱引擎完全基于 Canvas 2D 自实现，主要模块：

```
GraphView
├── 左侧控制面板
│   ├── 数据集选择（el-select）
│   ├── 概览统计（ECharts 饼图：类型分布）
│   ├── 节点搜索（关键词 / URI，定位或加载）
│   ├── 属性筛选（谓词 + 属性值组合搜索）
│   ├── 展开深度（Slider，0~3；0=仅属性查看）
│   ├── 操作按钮（重置 / 适配）
│   ├── 画布背景（浅灰 / 深色）
│   └── 节点类型图例（rdf:type 着色图例）
│
├── Canvas 画布
│   ├── Force-Directed 布局模拟（斥力+弹力+阻尼）
│   ├── 节点绘制（rdf:type 分类着色，高亮光晕）
│   ├── 边绘制（箭头 + 谓词标签）
│   ├── 鼠标交互（拖拽节点 / 平移画布 / 滚轮缩放）
│   ├── 左下角缩放工具栏（+/- / 比例显示 / 重置 / 适配）
│   └── 右键上下文菜单（移除节点 / 展开邻居）
│
└── 右侧节点详情面板（滑入动画）
    ├── 节点 URI / Label
    ├── 属性列表（谓词-值对）
    └── 展开邻居按钮
```

**节点着色机制：**

- 从每次 `mergeGraph` 的边集中扫描 `rdf:type` 谓词，建立 `nodeTypeMap: nodeId → typeUri`
- 按类型 URI 分配颜色（15 色池循环），无类型节点使用默认灰色 `#aab0c0`
- 图例面板动态渲染当前画布中出现的所有类型

**搜索定位机制：**

- 搜索结果若节点已在画布，显示"已有"标记，点击执行平滑居中动画 + 2 秒高亮光晕
- 若节点不在画布，点击执行加载（调 `/api/graph/neighbors`）

### 4.4 SPARQL 查询（`SparqlView.vue`）

| 功能     | 实现                                                                |
|--------|-------------------------------------------------------------------|
| 语法高亮   | CodeMirror 6 + SPARQL 语言扩展                                        |
| 自动补全   | 从 `/api/graph/predicates` 加载数据集谓词列表，注入 CodeMirror completion      |
| 格式化    | 自定义缩进格式化器（关键词大写 + 缩进对齐）                                           |
| 复制为单行  | 去除换行/多余空格，写入系统剪贴板                                                 |
| 查询历史   | `localStorage`（key: `sparql_query_history`，最多 20 条，自动去重），下拉菜单快速恢复 |
| 结果展示   | el-table（SELECT）/ el-result（ASK）                                  |
| 导出 CSV | 纯前端 Blob + anchor 下载                                              |
| 在图谱中查看 | 通过 Pinia `graphStore` 传递任务，路由跳转到 `/graph` 后自动执行渲染                 |

### 4.5 跨页面通信（Pinia `graphStore`）

`SparqlView` → `GraphView` 的 SPARQL 图谱查看功能通过 Pinia Store 实现：

```
SparqlView.viewAsGraph()
    └─ graphStore.setPendingSparql({ dataset, sparql })
    └─ router.push('/graph')

GraphView.onActivated()   ← keep-alive 激活钩子
    └─ graphStore.consumePendingSparql()   ← 消费一次性任务
    └─ graphApi.sparqlToGraph(dataset, sparql)
    └─ mergeGraph(result)
```

### 4.6 API 代理配置（Vite Dev Server）

开发模式下，Vite 将 `/api/*` 请求代理到 `http://localhost:3040`，解决跨域问题：

```
Browser :5173
    /api/* → proxy → Spring Boot :3040
```

生产模式下，前端静态资源由 Spring Boot 直接托管（Nginx 或内嵌静态资源服务），前端访问与后端同域。

---

## 5. 后端架构

### 5.1 关键类说明

#### `FusekiLauncher`

**包路径：** `fuseki.io.github.jenafuseki.plus.FusekiLauncher`

实现 `SmartLifecycle`，完全托管 Fuseki 生命周期：

| 方法            | 触发时机          | 行为                                            |
|---------------|---------------|-----------------------------------------------|
| `start()`     | Spring 容器启动完成 | 守护线程调用 `FusekiCmd.main()`，轮询等待 Jetty 就绪       |
| `stop()`      | Spring 容器关闭   | 调用 `JettyFusekiWebapp.instance.stop()`，等待线程退出 |
| `getPhase()`  | 决定启动/关闭顺序     | `Integer.MIN_VALUE + 100`（最早启动，最晚关闭）          |
| `isRunning()` | 健康检查          | 返回 Fuseki 服务是否存活                              |

**就绪判断：** 每 500ms 轮询 `JettyFusekiWebapp.instance != null`，超时由 `fuseki.start-timeout-seconds` 控制。

#### `GraphController`

**包路径：** `extension.io.github.jenafuseki.plus.GraphController`

图谱数据的核心后端，通过 `RestTemplate` 向 Fuseki 发送 SPARQL 查询，将结果转换为前端所需的 `{ nodes, edges }` 格式。

| 接口                              | 描述               | SPARQL 策略                               |
|---------------------------------|------------------|-----------------------------------------|
| `GET /api/graph/neighbors`      | 展开节点邻居           | 递归路径查询（depth 1~3）                       |
| `GET /api/graph/node`           | 节点属性详情           | `SELECT ?p ?o WHERE { <uri> ?p ?o }`    |
| `GET /api/graph/search`         | 关键词搜索节点          | UNION：rdfs:label + URI 片段 FILTER        |
| `GET /api/graph/search-by-prop` | 属性值搜索            | 谓词精确/模糊 + 字面量 CONTAINS                  |
| `POST /api/graph/sparql`        | 自定义 SPARQL → 图结构 | 用户自定义，提取 URI 列构建图                       |
| `GET /api/graph/overview`       | 数据集统计概览          | COUNT triples + rdf:type 分布             |
| `GET /api/graph/predicates`     | 谓词列表（补全用）        | `SELECT DISTINCT ?p WHERE { ?s ?p ?o }` |

#### `SparqlProxyServlet`

**包路径：** `extension.io.github.jenafuseki.plus.SparqlProxyServlet`

- `GET /api/fuseki/datasets` — 调用 Fuseki `GET :3030/$/datasets`，返回数据集列表
- `POST /api/fuseki/sparql` — 代理 SPARQL 查询请求到 Fuseki，供 SPARQL 编辑器使用

#### `HealthCheckServlet`

**包路径：** `extension.io.github.jenafuseki.plus.HealthCheckServlet`

- `GET /api/fuseki/health` — 返回 Fuseki 运行状态（可用于 K8s 探活）
- `GET /api/fuseki/admin-url` — 返回 Fuseki 官方管理台 URL（前端据此新标签跳转）

### 5.2 SPARQL 代理链路

```
Browser
  │  POST /api/fuseki/sparql { dataset, query }
  ▼
Spring Boot :3040 (SparqlProxyServlet)
  │  RestTemplate POST http://localhost:3030/{dataset}/sparql
  ▼
Fuseki Jetty :3030
  │  SPARQL Results JSON
  ▼
Spring Boot :3040  （透明转发响应）
  ▼
Browser（前端解析 SPARQL JSON 绑定格式）
```

---

## 6. 完整 API 清单

### Fuseki 原生端点（端口 3030，不变）

| 端点                       | 说明             |
|--------------------------|----------------|
| `GET  :3030/`            | Fuseki Web UI  |
| `POST :3030/{ds}/sparql` | SPARQL Query   |
| `POST :3030/{ds}/update` | SPARQL Update  |
| `GET  :3030/{ds}/data`   | Graph Store（读） |
| `PUT  :3030/{ds}/data`   | Graph Store（写） |
| `GET  :3030/$/ping`      | 心跳检测           |
| `GET  :3030/$/server`    | 服务器状态          |
| `GET  :3030/$/datasets`  | 数据集管理          |
| `GET  :3030/$/metrics`   | Prometheus 指标  |

### 自定义扩展端点（端口 3040，Spring Boot）

#### 图谱接口 `/api/graph/*`

| 端点                          | 方法   | 参数                                           | 说明                         |
|-----------------------------|------|----------------------------------------------|----------------------------|
| `/api/graph/neighbors`      | GET  | `dataset`, `uri`, `depth`(1-3), `limit`      | 展开节点邻居，返回 `{nodes, edges}` |
| `/api/graph/node`           | GET  | `dataset`, `uri`                             | 节点所有属性（谓词-值对）              |
| `/api/graph/search`         | GET  | `dataset`, `keyword`, `limit`                | 关键词/URI 搜索节点               |
| `/api/graph/search-by-prop` | GET  | `dataset`, `predicate`(可选), `value`, `limit` | 属性值搜索节点                    |
| `/api/graph/sparql`         | POST | `{dataset, sparql}`                          | 自定义 SPARQL → 图结构           |
| `/api/graph/overview`       | GET  | `dataset`                                    | 统计三元组数 + 类型分布              |
| `/api/graph/predicates`     | GET  | `dataset`, `limit`                           | 数据集所有谓词（补全用）               |

#### Fuseki 管理接口 `/api/fuseki/*`

| 端点                      | 方法   | 说明                          |
|-------------------------|------|-----------------------------|
| `/api/fuseki/datasets`  | GET  | 列出所有已挂载数据集                  |
| `/api/fuseki/sparql`    | POST | SPARQL 代理（转发给 Fuseki）       |
| `/api/fuseki/health`    | GET  | Fuseki 健康状态（running / port） |
| `/api/fuseki/admin-url` | GET  | 返回官方管理台 URL                 |

#### Spring Boot Actuator

| 端点                      | 说明          |
|-------------------------|-------------|
| `GET /actuator/health`  | 应用整体健康状态    |
| `GET /actuator/metrics` | JVM、HTTP 指标 |

---

## 7. 配置说明

所有配置集中在 `src/main/resources/application.yml`：

```yaml
server:
  port: 3040                     # Spring Boot（Tomcat）端口

fuseki:
  port: 3030                     # Fuseki（Jetty）监听端口
  home: ""                       # FUSEKI_HOME，留空自动推断为 ./libs/apache-jena-fuseki-4.10.0
  base: ""                       # FUSEKI_BASE，留空自动推断为 ./run
  start-timeout-seconds: 60      # 等待 Fuseki 就绪超时秒数
  allow-update: true             # 允许 SPARQL Update 写操作

  # 数据集配置（三选一，优先级从高到低）
  config-file: ""                # ① TTL 配置文件路径（最灵活，支持多数据集）
  mem-dataset: false             # ② 内存数据集（仅开发/测试）
  dataset-path: "/ds"            #    内存数据集挂载路径（mem-dataset=true 时有效）
  # ③ 不填：自动扫描 run/configuration/ 目录（默认）
  verbose: false                 # 开启 Fuseki 详细日志
```

### 数据集配置策略

| 场景         | 推荐配置                                              |
|------------|---------------------------------------------------|
| 本地开发快速验证   | `mem-dataset: true`，`dataset-path: /ds`           |
| 使用现有持久化数据集 | 不填 `config-file`，将 `.ttl` 放入 `run/configuration/` |
| 生产环境精确控制   | `config-file: /path/to/config.ttl`                |

---

## 8. 构建与运行

### 后端

```bash
cd jena-fuseki-plus-server

# 编译
mvn compile

# 打包（fat jar，含 fuseki-server.jar）
mvn package -DskipTests

# 方式一：Maven 直接运行（开发推荐）
mvn spring-boot:run

# 方式二：运行 fat jar
java -jar target/jena-fuseki-plus-server-1.0-SNAPSHOT.jar

# 方式三：覆盖配置
java -jar target/jena-fuseki-plus-server-1.0-SNAPSHOT.jar \
  --fuseki.port=3030 --fuseki.mem-dataset=true
```

### 前端

```bash
cd jena-fuseki-plus-ui

# 安装依赖
npm install

# 开发模式（Vite DevServer :5173，/api 代理到 :3040）
npm run dev

# 生产构建
npm run build
# 构建产物在 dist/，部署到 Nginx 或 Spring Boot static 目录
```

### 验证启动成功

```bash
# Fuseki 心跳
curl http://localhost:3030/$/ping

# 自定义健康检查
curl http://localhost:3040/api/fuseki/health

# Spring Boot Actuator
curl http://localhost:3040/actuator/health

# 列出已挂载数据集
curl http://localhost:3040/api/fuseki/datasets
```

---

## 9. 二次开发指引

### 9.1 添加业务逻辑

在 `MyExtensionService.init()` 中添加，此时 Fuseki 已就绪：

```java

@PostConstruct
public void init() {
    RDFConnection conn = RDFConnectionFactory.connect(
            "http://localhost:" + fusekiPort + "/ds");
    // 加载初始数据、启动定时任务等
}
```

### 9.2 新增自定义 REST API（Spring Boot 端，:3040）

在 `extension` 包下新建 `@RestController`，Spring 自动扫描注册：

```java

@RestController
@RequestMapping("/api/my")
public class MyController {
    private final FusekiLauncher fuseki;

    public MyController(FusekiLauncher fuseki) {
        this.fuseki = fuseki;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("fusekiRunning", fuseki.isRunning());
    }
}
```

> **注意：** `@RestController` 只在 Spring 容器中注册，**不要在 Fuseki 的 `web.xml` 中添加**。

### 9.3 新增 Fuseki 原生端点（Jetty 端，:3030）

继承 `javax.servlet.http.HttpServlet` 并在 `web.xml` 注册：

```java
public class MyFusekiAction extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("{\"ok\": true}");
    }
}
```

```xml
<!-- libs/apache-jena-fuseki-4.10.0/webapp/WEB-INF/web.xml -->
<servlet>
    <servlet-name>MyFusekiAction</servlet-name>
    <servlet-class>io.github.jenafuseki.plus.extension.MyFusekiAction</servlet-class>
</servlet>
<servlet-mapping>
<servlet-name>MyFusekiAction</servlet-name>
<url-pattern>/$/my-action</url-pattern>
</servlet-mapping>
```

### 9.4 新增前端页面

1. 在 `src/views/` 下创建 `MyView.vue`
2. 在 `src/router/index.js` 中添加路由：
   ```js
   { path: '/my', component: () => import('@/views/MyView.vue') }
   ```
3. 在 `src/App.vue` 的侧边栏中添加 `el-menu-item`

### 9.5 数据集 TTL 配置示例

在 `run/configuration/` 目录下创建 `.ttl` 文件，Fuseki 启动时自动加载：

```turtle
# run/configuration/my-dataset.ttl
PREFIX fuseki: <http://jena.apache.org/fuseki#>
PREFIX rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX tdb2:   <http://jena.apache.org/2016/tdb#>

<#service> rdf:type fuseki:Service ;
    fuseki:name      "my-dataset" ;
    fuseki:endpoint  [ fuseki:operation fuseki:query ;  fuseki:name "sparql" ] ;
    fuseki:endpoint  [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:endpoint  [ fuseki:operation fuseki:gsp-rw ; fuseki:name "data" ] ;
    fuseki:dataset   <#dataset> .

<#dataset> rdf:type tdb2:DatasetTDB2 ;
    tdb2:location "databases/my-dataset" .
```

---

## 10. 常见问题

### Q1: 启动报 `ClassCastException: SLF4JLoggerContext cannot be cast to LoggerContext`

**原因：** Spring Boot 默认 `logback` 与 `fuseki-server.jar` 内置 `log4j-core` 冲突。

**解决：** `pom.xml` 排除 `spring-boot-starter-logging`，改用 `spring-boot-starter-log4j2`，并对齐版本（
`<log4j2.version>2.21.0</log4j2.version>`）。

### Q2: 启动报 `Servlet class xxx is not a javax.servlet.Servlet`

**原因：** 将 Spring `@RestController` 误注册到 `web.xml`。

**解决：** 从 `web.xml` 移除对应 `<servlet>` 配置，Spring Controller 只在 Spring 容器中注册。

### Q3: Fuseki 超时未就绪

**排查步骤：**

1. 检查 `fuseki.home` 路径是否包含 `fuseki-server.jar`
2. 检查端口是否被占用：`lsof -i :3030`
3. 增大超时：`fuseki.start-timeout-seconds: 120`
4. 开启详细日志：`fuseki.verbose: true`

### Q4: IDE 大量红线报错（Cannot resolve symbol）

**原因：** `fuseki-server.jar` 以 `system` scope 引入，IDE 索引无法解析。

**解决：** 将 jar 安装到本地 Maven 仓库：

```bash
mvn install:install-file \
  -Dfile=libs/apache-jena-fuseki-4.10.0/fuseki-server.jar \
  -DgroupId=org.apache.jena \
  -DartifactId=apache-jena-fuseki \
  -Dversion=4.10.0 \
  -Dpackaging=jar
```

然后将 `pom.xml` 中的 `<scope>system</scope>` 改为 `<scope>compile</scope>`，并删除 `<systemPath>` 行。

### Q5: 前端图谱节点颜色都一样（全是灰色）

**原因：** 数据集中没有 `rdf:type` 三元组，节点类型无法识别。

**排查：** 在 SPARQL 页面执行 `SELECT * WHERE { ?s a ?type } LIMIT 10` 确认是否有类型数据；如有，检查 Fuseki 返回的边集中是否包含
`rdf:type` 谓词的边（完整 URI：`http://www.w3.org/1999/02/22-rdf-syntax-ns#type`）。

