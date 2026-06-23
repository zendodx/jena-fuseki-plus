## Fuseki 版本升级方案

### 当前版本依赖清单

项目对 `fuseki-server.jar` 的依赖分为两类：**直接调用的 API** 和**内嵌的第三方库版本**。

| 类型       | 内容                                              | 当前版本                      |
|----------|-------------------------------------------------|---------------------------|
| 直接调用 API | `FusekiCmd.main(String...)`                     | 4.10.0                    |
| 直接调用 API | `JettyFusekiWebapp.instance`（静态字段）              | 4.10.0                    |
| 直接调用 API | `JettyFusekiWebapp.stop()`                      | 4.10.0                    |
| 直接调用 API | `DataAccessPointRegistry`（SparqlProxyServlet 中） | 4.10.0                    |
| 内嵌第三方库   | log4j-core                                      | **2.21.0**（须与 pom.xml 对齐） |
| 内嵌第三方库   | Jetty                                           | 10.0.17                   |
| 内嵌第三方库   | Apache Shiro                                    | 1.12.0                    |

**升级的主要风险点只有两处：**

1. `JettyFusekiWebapp` 的包路径或 `instance` 字段在新版本中是否变化
2. 新版 jar 内置的 **log4j 版本**是否变化（须在 `pom.xml` 同步更新）

### 升级步骤

**第 1 步：下载新版 Fuseki 发行包**

从 [Apache Jena 下载页](https://jena.apache.org/download/index.cgi) 下载新版 `apache-jena-fuseki-X.Y.Z.zip`，解压到
`libs/` 目录：

```bash
# 以升级到 5.x.x 为例
unzip apache-jena-fuseki-5.x.x.zip -d libs/
```

**第 2 步：查询新版内置的 log4j 版本**

```bash
# 查看新版 jar 内置的 log4j 版本
jar xf libs/apache-jena-fuseki-X.Y.Z/fuseki-server.jar \
    META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties -C /tmp/check
cat /tmp/check/META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties
```

**第 3 步：确认关键 API 是否变化**

```bash
# 提取并检查两个关键类的公开方法签名
jar xf libs/apache-jena-fuseki-X.Y.Z/fuseki-server.jar \
    org/apache/jena/fuseki/cmd/FusekiCmd.class \
    org/apache/jena/fuseki/cmd/JettyFusekiWebapp.class -C /tmp/check

javap -p /tmp/check/org/apache/jena/fuseki/cmd/FusekiCmd.class
javap -p /tmp/check/org/apache/jena/fuseki/cmd/JettyFusekiWebapp.class
```

预期结果应包含：

- `FusekiCmd`：`public static void main(java.lang.String...)`
- `JettyFusekiWebapp`：`public static ... instance`、`public void start()`、`public void stop()`

若签名不变，可直接替换，无需修改任何 Java 代码。

**第 4 步：更新 `pom.xml`**（需修改 3 处）：

pom.xml 的修改位置很清晰，在升级文档里说明：

---

在 `pom.xml` 中需要同步修改 **3 处**：

```xml

<properties>
    <!-- ① 同步为新版 jar 内置的 log4j 版本（第 2 步查询到的值） -->
    <log4j2.version>X.Y.Z</log4j2.version>
</properties>

<dependencies>
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>apache-jena-fuseki</artifactId>
    <!-- ② 同步版本号 -->
    <version>X.Y.Z</version>
    <scope>system</scope>
    <!-- ③ 同步路径 -->
    <systemPath>${project.basedir}/libs/apache-jena-fuseki-X.Y.Z/fuseki-server.jar</systemPath>
</dependency>
</dependencies>
```

**第 5 步：更新 `start-fuseki-4.10.0.sh`（可选，仍需保留原脚本作为备用）**

```bash
# 脚本中 FUSEKI_HOME 路径须对应新版目录
FUSEKI_HOME="$SCRIPT_DIR/libs/apache-jena-fuseki-X.Y.Z"
```

**第 6 步：验证编译与启动**

```bash
mvn compile          # 确保无编译错误
mvn spring-boot:run  # 验证启动正常
curl http://localhost:3030/$/ping   # Fuseki 心跳
curl http://localhost:3040/api/fuseki/health  # 自定义健康检查
```

---

### 升级风险等级评估

| 升级场景                     | 风险    | 原因                                                                                                                          |
|--------------------------|-------|-----------------------------------------------------------------------------------------------------------------------------|
| **4.10.x → 4.10.y**（小补丁） | 🟢 极低 | API 稳定，只需同步 log4j 版本                                                                                                        |
| **4.10.x → 4.y.z**（次版本）  | 🟡 低  | 检查 `JettyFusekiWebapp` 签名即可                                                                                                 |
| **4.x → 5.x**（大版本）       | 🟠 中  | Fuseki 5.x 基于 Jakarta EE（`jakarta.servlet`），`javax.servlet` 包名变更，Spring Boot 2.7 仍用 `javax.servlet`，需同步升级 Spring Boot 到 3.x |

### Fuseki 4.x → 5.x 的额外注意事项

Fuseki 5.x 将 Servlet API 从 `javax.servlet` 迁移到了 `jakarta.servlet`。如需升级到 5.x，还需要：

1. **Spring Boot 同步升级到 3.x**（Spring Boot 3.x 才使用 `jakarta.servlet`）
2. **Java 版本升至 17+**（Spring Boot 3.x 最低要求 Java 17）
3. 代码中所有 `javax.annotation.PostConstruct` / `PreDestroy` 改为 `jakarta.annotation.*`