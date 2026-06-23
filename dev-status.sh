#!/bin/bash
# =============================================================
#  jena-fuseki-plus — 开发环境运行状态查看脚本
#
#  用法：
#    ./dev-status.sh          # 显示服务状态摘要
#    ./dev-status.sh --log    # 额外显示最近 20 行日志
# =============================================================

# ─── 颜色定义 ─────────────────────────────────────────
RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
BLUE=$'\033[0;34m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'

# ─── 路径配置 ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/.dev.pid"

# ─── 默认端口 ─────────────────────────────────────────
BACKEND_PORT="${BACKEND_PORT:-3040}"
FUSEKI_PORT="${FUSEKI_PORT:-3030}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
HEALTH_URL="http://localhost:${BACKEND_PORT}/api/fuseki/health"

# ─── 解析参数 ─────────────────────────────────────────
SHOW_LOG=false
for ARG in "$@"; do
  case "$ARG" in
    --log|-l) SHOW_LOG=true ;;
    *) printf "Unknown option: %s\n" "$ARG"; exit 1 ;;
  esac
done

# ─── 工具函数 ─────────────────────────────────────────
# 检查进程是否存活
proc_status() {
  local pid=$1
  if [ -z "$pid" ]; then
    printf "%b未知%b" "$YELLOW" "$NC"
  elif kill -0 "$pid" 2>/dev/null; then
    printf "%b运行中%b (PID %s)" "$GREEN" "$NC" "$pid"
  else
    printf "%b已停止%b (PID %s)" "$RED" "$NC" "$pid"
  fi
}

# 检查端口是否在监听
port_status() {
  local port=$1
  if lsof -iTCP:"$port" -sTCP:LISTEN -t &>/dev/null; then
    local pid
    pid=$(lsof -ti TCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
    printf "%b监听中%b (PID %s)" "$GREEN" "$NC" "$pid"
  else
    printf "%b未监听%b" "$RED" "$NC"
  fi
}

# 计算进程运行时长
proc_uptime() {
  local pid=$1
  [ -z "$pid" ] && return
  kill -0 "$pid" 2>/dev/null || return
  # macOS: ps -o etime=
  local etime
  etime=$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')
  [ -n "$etime" ] && printf " 已运行 %s" "$etime"
}

# ─── 标题 ─────────────────────────────────────────────
echo ""
printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$CYAN" "$NC"
printf "%b%b  jena-fuseki-plus 开发环境状态%b\n"                       "$BOLD" "$CYAN" "$NC"
printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$CYAN" "$NC"
echo ""

# ─── 读取 PID 文件 ────────────────────────────────────
BACKEND_PID=""
FRONTEND_PID=""
BACKEND_LOG=""
FRONTEND_LOG=""

if [ -f "$PID_FILE" ]; then
  # shellcheck disable=SC1090
  source "$PID_FILE"
  printf "  %b.dev.pid%b  %s\n" "$BOLD" "$NC" "$PID_FILE"
else
  printf "  %b.dev.pid%b  %b未找到（服务未通过 dev-start.sh 启动，或已停止）%b\n" \
    "$BOLD" "$NC" "$YELLOW" "$NC"
fi

echo ""

# ─── 进程状态 ─────────────────────────────────────────
printf "%b进程状态%b\n" "$BOLD" "$NC"
printf "  %-12s " "后端进程"
proc_status "$BACKEND_PID"
proc_uptime "$BACKEND_PID"
echo ""

printf "  %-12s " "前端进程"
proc_status "$FRONTEND_PID"
proc_uptime "$FRONTEND_PID"
echo ""

echo ""

# ─── 端口监听 ─────────────────────────────────────────
printf "%b端口监听%b\n" "$BOLD" "$NC"
printf "  %-28s " ":${BACKEND_PORT}  Spring Boot API"
port_status "$BACKEND_PORT"
echo ""

printf "  %-28s " ":${FUSEKI_PORT}  Fuseki SPARQL"
port_status "$FUSEKI_PORT"
echo ""

printf "  %-28s " ":${FRONTEND_PORT}  Vite Dev Server"
port_status "$FRONTEND_PORT"
echo ""

echo ""

# ─── 健康检查 ─────────────────────────────────────────
printf "%b健康检查%b  %s\n" "$BOLD" "$NC" "$HEALTH_URL"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "$HEALTH_URL" 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ]; then
  HEALTH_RESP=$(curl -s --connect-timeout 3 "$HEALTH_URL" 2>/dev/null || echo "{}")
  printf "  %bHTTP %s%b  %s\n" "$GREEN" "$HTTP_CODE" "$NC" "$HEALTH_RESP"
else
  printf "  %bHTTP %s%b  无法连接\n" "$RED" "$HTTP_CODE" "$NC"
fi

echo ""

# ─── 访问地址 ─────────────────────────────────────────
printf "%b访问地址%b\n" "$BOLD" "$NC"
printf "  %b前端管理台%b  http://localhost:%s\n"               "$CYAN" "$NC" "$FRONTEND_PORT"
printf "  %b后端 API  %b  http://localhost:%s/api\n"           "$BLUE" "$NC" "$BACKEND_PORT"
printf "  %bFuseki   %b  http://localhost:%s\n"                "$BLUE" "$NC" "$FUSEKI_PORT"
printf "  %bActuator %b  http://localhost:%s/actuator/health\n" "$BLUE" "$NC" "$BACKEND_PORT"

echo ""

# ─── 日志路径 ─────────────────────────────────────────
printf "%b日志文件%b\n" "$BOLD" "$NC"
if [ -n "$BACKEND_LOG" ] && [ -f "$BACKEND_LOG" ]; then
  printf "  后端: %s\n" "$BACKEND_LOG"
else
  printf "  后端: %b未记录%b\n" "$YELLOW" "$NC"
fi
if [ -n "$FRONTEND_LOG" ] && [ -f "$FRONTEND_LOG" ]; then
  printf "  前端: %s\n" "$FRONTEND_LOG"
else
  printf "  前端: %b未记录%b\n" "$YELLOW" "$NC"
fi

# ─── 可选：打印最近日志 ───────────────────────────────
if [ "$SHOW_LOG" = "true" ]; then
  echo ""
  printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$BLUE" "$NC"
  printf "%b后端日志（最近 20 行）%b\n" "$BOLD" "$NC"
  printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$BLUE" "$NC"
  if [ -n "$BACKEND_LOG" ] && [ -f "$BACKEND_LOG" ]; then
    tail -20 "$BACKEND_LOG" | sed "s/^/  /"
  else
    printf "  %b（日志文件不存在）%b\n" "$YELLOW" "$NC"
  fi

  echo ""
  printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$CYAN" "$NC"
  printf "%b前端日志（最近 20 行）%b\n" "$BOLD" "$NC"
  printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$CYAN" "$NC"
  if [ -n "$FRONTEND_LOG" ] && [ -f "$FRONTEND_LOG" ]; then
    tail -20 "$FRONTEND_LOG" | sed "s/^/  /"
  else
    printf "  %b（日志文件不存在）%b\n" "$YELLOW" "$NC"
  fi
fi

echo ""
printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$CYAN" "$NC"
echo ""

