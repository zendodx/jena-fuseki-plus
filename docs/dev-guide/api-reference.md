---
layout: default
title: 接口参考文档
parent: 开发指南
nav_order: 2
---

# 接口参考文档

本服务运行两个 HTTP 服务器，监听不同端口：

| 服务器 | 端口 | 管理方 | 用途 |
|--------|------|--------|------|
| **Fuseki Jetty** | 3030 | `web.xml` + TTL 配置 | SPARQL 数据操作 + 服务管理（原生接口） |
| **Spring Boot Tomcat** | 3040 | `@RestController` | 自定义扩展 API + Actuator 监控 |

---

## 一、Fuseki 原生接口（端口 3030）

### 1.1 数据集接口 — `travel_kg_v4`

数据集挂载路径：`/travel_kg_v4`，配置文件：`run/configuration/travel_kg_v4.ttl`

#### SPARQL Query（查询）

```
POST http://localhost:3030/travel_kg_v4/sparql
POST http://localhost:3030/travel_kg_v4/query
GET  http://localhost:3030/travel_kg_v4/sparql?query=...
GET  http://localhost:3030/travel_kg_v4/query?query=...
```

| 参数/Header | 说明 |
|------------|------|
| `query` | SPARQL SELECT / CONSTRUCT / ASK / DESCRIBE 语句 |
| `Content-Type` | `application/x-www-form-urlencoded`（POST 表单）或 `application/sparql-query`（POST 直接发送查询体） |
| `Accept` | 控制响应格式，见下方格式表 |

**常用 Accept 格式：**

| Accept 值 | 响应格式 |
|-----------|---------|
| `application/sparql-results+json`（默认） | JSON |
| `application/sparql-results+xml` | XML |
| `text/csv` | CSV |
| `text/tab-separated-values` | TSV |
| `application/sparql-results+thrift` | Thrift 二进制 |

**示例：**

```bash
# POST 表单方式
curl -X POST http://localhost:3030/travel_kg_v4/sparql \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode "query=SELECT * WHERE { ?s ?p ?o } LIMIT 10"

# GET 方式
curl "http://localhost:3030/travel_kg_v4/sparql?query=SELECT+*+WHERE+{+?s+?p+?o+}+LIMIT+10" \
  -H "Accept: application/sparql-results+json"
```

---

#### SPARQL Update（写入/更新）

```
POST http://localhost:3030/travel_kg_v4/update
```

| 参数/Header | 说明 |
|------------|------|
| `update` | SPARQL INSERT / DELETE / LOAD 语句 |
| `Content-Type` | `application/x-www-form-urlencoded` 或 `application/sparql-update` |

**示例：**

```bash
# 插入三元组
curl -X POST http://localhost:3030/travel_kg_v4/update \
  --data-urlencode "update=INSERT DATA { <http://example.org/s> <http://example.org/p> \"value\" }"

# 清空图
curl -X POST http://localhost:3030/travel_kg_v4/update \
  --data-urlencode "update=CLEAR DEFAULT"
```

---

#### Graph Store Protocol（图数据读写）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET`    | `/travel_kg_v4/data` 或 `/travel_kg_v4/get` | 下载默认图或指定图（`?graph=<uri>` 或 `?default`） |
| `PUT`    | `/travel_kg_v4/data` | 替换整个图（覆盖写） |
| `POST`   | `/travel_kg_v4/data` | 向图中追加 RDF 数据 |
| `DELETE` | `/travel_kg_v4/data` | 删除指定图 |
| `PATCH`  | `/travel_kg_v4/data` | 增量更新（SPARQL 1.1 Graph Store HTTP Protocol） |

**示例：**

```bash
# 上传 Turtle 文件到默认图
curl -X PUT http://localhost:3030/travel_kg_v4/data?default \
  -H "Content-Type: text/turtle" \
  --data-binary @my-data.ttl

# 下载默认图为 Turtle
curl "http://localhost:3030/travel_kg_v4/data?default" \
  -H "Accept: text/turtle" -o output.ttl

# 上传到指定命名图
curl -X PUT "http://localhost:3030/travel_kg_v4/data?graph=http://example.org/mygraph" \
  -H "Content-Type: text/turtle" \
  --data-binary @my-data.ttl
```

---

### 1.2 服务管理接口（`/$/` 前缀）

以下接口由 `web.xml` 注册，路径均以 `/$/` 开头，需要管理员权限（由 `run/shiro.ini` 控制）。

#### 心跳检测

```
GET http://localhost:3030/$/ping
```

响应：`200 OK`，Body 为当前时间戳字符串。

```bash
curl http://localhost:3030/$/ping
```

---

#### 服务器状态

```
GET http://localhost:3030/$/server
GET http://localhost:3030/$/status   # 等价别名
```

响应：JSON，包含服务器版本、已加载数据集列表、JVM 信息等。

```bash
curl http://localhost:3030/$/server
```

---

#### 数据集管理

```
GET    http://localhost:3030/$/datasets          # 列出所有数据集
POST   http://localhost:3030/$/datasets          # 创建新数据集（表单参数: dbName, dbType）
GET    http://localhost:3030/$/datasets/{name}   # 查询指定数据集详情
DELETE http://localhost:3030/$/datasets/{name}   # 删除数据集
POST   http://localhost:3030/$/datasets/{name}?state=offline  # 下线数据集
POST   http://localhost:3030/$/datasets/{name}?state=active   # 上线数据集
```

**创建内存数据集示例：**

```bash
curl -X POST http://localhost:3030/$/datasets \
  --data "dbName=/newds&dbType=mem"
```

---

#### 统计信息

```
GET http://localhost:3030/$/stats          # 所有数据集统计
GET http://localhost:3030/$/stats/{name}   # 指定数据集统计（请求次数、响应时间等）
```

---

#### Prometheus 指标

```
GET http://localhost:3030/$/metrics
```

响应：Prometheus text 格式，可直接接入 Prometheus / Grafana。

```bash
curl http://localhost:3030/$/metrics
```

---

#### 备份与压缩

```
POST http://localhost:3030/$/backup/{name}      # 触发指定数据集备份（异步任务）
POST http://localhost:3030/$/backups/{name}     # 同上（备用路径）
GET  http://localhost:3030/$/backups-list       # 列出所有备份文件
POST http://localhost:3030/$/compact/{name}     # 压缩 TDB2 数据库（释放空间）
```

---

#### 任务管理

```
GET  http://localhost:3030/$/tasks          # 列出所有后台任务（备份/压缩等）
GET  http://localhost:3030/$/tasks/{id}     # 查询指定任务状态
```

---

#### 日志级别

```
GET  http://localhost:3030/$/logs           # 查看当前日志级别
POST http://localhost:3030/$/logs           # 动态调整日志级别
```

---

## 二、自定义扩展接口（端口 3040）

由 Spring Boot `@RestController` 提供，运行在 Tomcat 3040 端口。

### 2.1 Fuseki 健康检查

```
GET http://localhost:3040/api/fuseki/health
```

响应示例（Fuseki 正常）：

```json
{
  "status": "UP",
  "fuseki": "4.10.0",
  "fusekiRunning": true,
  "timestamp": 1750000000000
}
```

响应示例（Fuseki 未就绪）：HTTP 503

```json
{
  "status": "DOWN",
  "fuseki": "4.10.0",
  "fusekiRunning": false,
  "timestamp": 1750000000000
}
```

| 场景 | HTTP 状态码 |
|------|-----------|
| Fuseki 运行正常 | `200 OK` |
| Fuseki 未启动/启动中 | `503 Service Unavailable` |

---

### 2.2 数据集列表

```
GET http://localhost:3040/api/fuseki/datasets
```

响应示例：

```json
{
  "datasets": [
    {
      "name": "/travel_kg_v4",
      "accepting": true
    }
  ],
  "count": 1
}
```

---

### 2.3 SPARQL 审计日志

```
GET http://localhost:3040/api/fuseki/query?dataset=/travel_kg_v4&query=SELECT...
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `dataset` | 否 | 数据集路径，如 `/travel_kg_v4` |
| `query` | 否 | SPARQL 查询语句（仅记录日志，不执行，实际执行请调用 Fuseki 原生端点） |

响应示例：

```json
{
  "message": "请直接访问 Fuseki 原生端点执行查询: http://localhost:3030/travel_kg_v4/sparql",
  "auditLogged": true,
  "dataset": "/travel_kg_v4",
  "queryPreview": "SELECT * WHERE { ?s ?p ?o } LIMIT 10",
  "elapsed": 2
}
```

---

### 2.4 Spring Boot Actuator

```
GET http://localhost:3040/actuator/health    # 应用健康状态（含 JVM、磁盘等）
GET http://localhost:3040/actuator/info      # 应用信息
GET http://localhost:3040/actuator/metrics   # 应用指标列表
GET http://localhost:3040/actuator/metrics/{name}  # 指定指标详情
```

`/actuator/health` 响应示例：

```json
{
  "status": "UP",
  "components": {
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

---

## 三、Content-Type 与数据格式参考

### RDF 数据上传（请求 Content-Type）

| Content-Type | 格式 |
|-------------|------|
| `text/turtle` | Turtle |
| `application/rdf+xml` | RDF/XML |
| `application/n-triples` | N-Triples |
| `application/n-quads` | N-Quads |
| `application/trig` | TriG |
| `application/ld+json` | JSON-LD |
| `application/rdf+thrift` | RDF Thrift（二进制） |

### SPARQL 查询结果（请求 Accept）

| Accept | 格式 |
|--------|------|
| `application/sparql-results+json` | JSON（默认） |
| `application/sparql-results+xml` | XML |
| `text/csv` | CSV |
| `text/tab-separated-values` | TSV |
| `application/sparql-results+thrift` | Thrift 二进制 |
| `text/turtle` | Turtle（CONSTRUCT/DESCRIBE 查询） |
| `application/rdf+xml` | RDF/XML（CONSTRUCT/DESCRIBE 查询） |

