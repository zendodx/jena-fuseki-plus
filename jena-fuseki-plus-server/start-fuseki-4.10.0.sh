#!/bin/bash
# 启动 Apache Jena Fuseki 服务器的便捷脚本

# 获取脚本所在目录（项目根目录）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Fuseki 安装目录
FUSEKI_HOME="$SCRIPT_DIR/libs/apache-jena-fuseki-4.10.0"

# 数据存储目录（默认在项目根目录下的 run 文件夹）
export FUSEKI_BASE="${FUSEKI_BASE:-$SCRIPT_DIR/run}"

# 检查 Fuseki 目录是否存在
if [ ! -d "$FUSEKI_HOME" ]; then
    echo "错误: Fuseki 目录不存在: $FUSEKI_HOME" 1>&2
    exit 1
fi

# 查找 JAR 文件
JAR="$FUSEKI_HOME/fuseki-server.jar"
if [ ! -e "$JAR" ]; then
    echo "错误: 找不到 fuseki-server.jar: $JAR" 1>&2
    exit 1
fi

# 检测 Java
if [ -z "$JAVA" ]; then
    if [ -n "$JAVA_HOME" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        JAVA="$(which java)"
    fi
fi

if [ -z "$JAVA" ]; then
    echo "错误: 找不到 Java。请设置 JAVA_HOME 或将 java 加入 PATH。" 1>&2
    exit 1
fi

# JVM 参数
JVM_ARGS="${JVM_ARGS:--Xmx4G}"

# 类路径：JAR + run/extra 目录下的自定义 JAR（如有）
CP="$JAR"
if [ -d "$FUSEKI_BASE/extra" ]; then
    CP="${CP}:${FUSEKI_BASE}/extra/*"
fi

# 日志配置
LOG_CONF="$FUSEKI_HOME/log4j2.properties"
if [ -z "$LOGGING" ] && [ -e "$LOG_CONF" ]; then
    LOGGING="-Dlog4j.configurationFile=$LOG_CONF"
fi

# 服务器模式（默认带 UI 的完整服务器）
# 可选值: serverUI | main | plain | basic
# Fuseki 4.x 主类
MAIN_CLASS='org.apache.jena.fuseki.cmd.FusekiCmd'

echo "=========================================="
echo "  Apache Jena Fuseki 4.10.0 启动中..."
echo "  FUSEKI_HOME : $FUSEKI_HOME"
echo "  FUSEKI_BASE : $FUSEKI_BASE"
echo "  Java        : $JAVA"
echo "  模式        : $MAIN"
echo "  端口        : ${PORT:-3030} (默认)"
echo "=========================================="

# 创建 run 目录（如不存在）
mkdir -p "$FUSEKI_BASE"

# 启动服务器
if [ -n "$LOGGING" ]; then
    exec "$JAVA" $JVM_ARGS "$LOGGING" -cp "$CP" "$MAIN_CLASS" "$@"
else
    exec "$JAVA" $JVM_ARGS -cp "$CP" "$MAIN_CLASS" "$@"
fi

