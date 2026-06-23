#!/bin/bash
# =============================================================
#  jena-fuseki-plus — 开发环境一键启动脚本
#
#  启动顺序：
#    1. 后端 Spring Boot（:3040）+ 内嵌 Fuseki（:3030）
#    2. 轮询等待后端健康检查通过
#    3. 启动前端 Vite Dev Server（:5173）
#
#  使用方法：
#    chmod +x dev-start.sh        # 首次使用需授权（只需执行一次）
#    ./dev-start.sh               # 前台模式：Ctrl+C 停止所有服务
#    ./dev-start.sh --daemon      # 后台模式：PID 写入 .dev.pid，用 dev-stop.sh 停止
#
# =============================================================

set -e

# ─── 解析参数 ─────────────────────────────────────────
DAEMON=false
for ARG in "$@"; do
  case "$ARG" in
    --daemon|-d) DAEMON=true ;;
    *) printf "Unknown option: %s\n" "$ARG"; exit 1 ;;
  esac
done

# ─── 颜色定义 ─────────────────────────────────────────
RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
BLUE=$'\033[0;34m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'  # No Color

# ─── 路径配置 ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/jena-fuseki-plus-server"
FRONTEND_DIR="$SCRIPT_DIR/jena-fuseki-plus-ui"
PID_FILE="$SCRIPT_DIR/.dev.pid"        # PID 持久化文件（后台模式 / stop 脚本使用）
LOG_DIR="${TMPDIR:-/tmp}"

# ─── 地址配置 ─────────────────────────────────────────
BACKEND_PORT="${BACKEND_PORT:-3040}"
FUSEKI_PORT="${FUSEKI_PORT:-3030}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
HEALTH_URL="http://localhost:${BACKEND_PORT}/api/fuseki/health"

# ─── 等待参数 ─────────────────────────────────────────
MAX_WAIT=120       # 最长等待后端就绪的秒数
POLL_INTERVAL=2    # 每次探测间隔（秒）

# ─── 进程 PID ────────────────────────────────────────
BACKEND_PID=""
FRONTEND_PID=""

# ─── 公共停止逻辑（前台 cleanup 和 dev-stop.sh 共用） ──
do_stop() {
  local backend_pid=$1
  local frontend_pid=$2

  # 终止前端进程组
  if [ -n "$frontend_pid" ] && kill -0 "$frontend_pid" 2>/dev/null; then
    FRONTEND_PGID=$(ps -o pgid= -p "$frontend_pid" 2>/dev/null | tr -d ' ')
    if [ -n "$FRONTEND_PGID" ] && [ "$FRONTEND_PGID" != "$$" ]; then
      kill -- "-$FRONTEND_PGID" 2>/dev/null
    else
      kill "$frontend_pid" 2>/dev/null
    fi
    printf "%b[前端]%b Vite Dev Server 已停止\n" "$CYAN" "$NC"
  fi

  # 终止后端进程组（覆盖 mvn 及其 fork 出的 Spring Boot 子进程）
  if [ -n "$backend_pid" ] && kill -0 "$backend_pid" 2>/dev/null; then
    BACKEND_PGID=$(ps -o pgid= -p "$backend_pid" 2>/dev/null | tr -d ' ')
    if [ -n "$BACKEND_PGID" ] && [ "$BACKEND_PGID" != "$$" ]; then
      kill -- "-$BACKEND_PGID" 2>/dev/null
    else
      kill "$backend_pid" 2>/dev/null
    fi
    printf "%b[后端]%b Spring Boot 已停止\n" "$BLUE" "$NC"
  fi

  # 兜底：强杀仍在监听的端口（应对孤儿进程）
  sleep 1
  for PORT in "$BACKEND_PORT" "$FUSEKI_PORT" "$FRONTEND_PORT"; do
    PIDS=$(lsof -ti TCP:"$PORT" -sTCP:LISTEN 2>/dev/null)
    if [ -n "$PIDS" ]; then
      printf "%b[dev-stop] 强制清理端口 %s 残留进程...%b\n" "$YELLOW" "$PORT" "$NC"
      echo "$PIDS" | xargs kill -9 2>/dev/null
    fi
  done

  rm -f "$PID_FILE"
  printf "%b[dev-stop] 所有服务已停止。%b\n" "$GREEN" "$NC"
}

# ─── 清理函数：前台模式 Ctrl+C / EXIT 时触发 ──────────
cleanup() {
  printf "\n"
  printf "%b[dev-start] 正在停止所有服务...%b\n" "$YELLOW" "$NC"
  do_stop "$BACKEND_PID" "$FRONTEND_PID"
  exit 0
}
trap cleanup INT TERM EXIT

# ─── 工具函数 ─────────────────────────────────────────
log_info()    { printf "%b[dev-start]%b %s\n" "$BOLD" "$NC" "$*"; }
log_backend() { printf "%b[后端]%b %s\n"    "$BLUE" "$NC" "$*"; }
log_frontend(){ printf "%b[前端]%b %s\n"    "$CYAN" "$NC" "$*"; }
log_ok()      { printf "%b✓%b %s\n"         "$GREEN" "$NC" "$*"; }
log_error()   { printf "%b✗ %s%b\n"         "$RED" "$*" "$NC"; }

# ─── 检查依赖 ─────────────────────────────────────────
check_dependency() {
  if ! command -v "$1" &>/dev/null; then
    log_error "未找到命令: $1。请先安装后重试。"
    exit 1
  fi
}

check_dependency java
check_dependency mvn
check_dependency node
check_dependency npm
check_dependency curl

# ─── 检查端口是否已被占用 ──────────────────────────────
check_port_free() {
  local port=$1 name=$2
  if lsof -iTCP:"$port" -sTCP:LISTEN -t &>/dev/null; then
    printf "%b✗ 端口 %s（%s）已被占用。请先执行：%b\n" "$RED" "$port" "$name" "$NC"
    printf "  lsof -ti:%s | xargs kill -9\n" "$port"
    exit 1
  fi
}

log_info "检查端口占用情况..."
check_port_free "$BACKEND_PORT" "Spring Boot"
check_port_free "$FUSEKI_PORT"  "Fuseki"
check_port_free "$FRONTEND_PORT" "Vite"
log_ok "端口检查通过（${BACKEND_PORT} / ${FUSEKI_PORT} / ${FRONTEND_PORT} 均空闲）"

# ─── Step 1: 启动后端 ──────────────────────────────────
echo ""
printf "%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$NC"
log_backend "启动 Spring Boot + Fuseki..."
printf "%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$NC"

if [ ! -d "$BACKEND_DIR" ]; then
  log_error "后端目录不存在: $BACKEND_DIR"
  exit 1
fi

BACKEND_LOG="$(mktemp "${LOG_DIR}/jena-fuseki-plus-backend.XXXXXX")"
(
  cd "$BACKEND_DIR"
  mvn spring-boot:run 2>&1
) > "$BACKEND_LOG" &
BACKEND_PID=$!

log_backend "进程 PID: $BACKEND_PID，日志: $BACKEND_LOG"

# ─── Step 2: 等待后端健康检查通过 ──────────────────────
echo ""
log_info "等待后端就绪（最多 ${MAX_WAIT}s，每 ${POLL_INTERVAL}s 探测一次）..."

ELAPSED=0
READY=false
while [ $ELAPSED -lt $MAX_WAIT ]; do
  # 检查后端进程是否意外退出
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    log_error "后端进程已意外退出！日志如下："
    tail -40 "$BACKEND_LOG"
    exit 1
  fi

  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 "$HEALTH_URL" 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    READY=true
    break
  fi

  if [ $((ELAPSED % 10)) -eq 0 ] && [ $ELAPSED -gt 0 ]; then
    log_info "  已等待 ${ELAPSED}s，后端尚未就绪（HTTP $HTTP_CODE），继续等待..."
  else
    printf "."
  fi

  sleep $POLL_INTERVAL
  ELAPSED=$((ELAPSED + POLL_INTERVAL))
done

echo ""

if [ "$READY" != "true" ]; then
  log_error "后端在 ${MAX_WAIT}s 内未就绪！最近日志如下："
  tail -40 "$BACKEND_LOG"
  exit 1
fi

HEALTH_RESP=$(curl -s --connect-timeout 3 "$HEALTH_URL" 2>/dev/null || echo "{}")
log_ok "后端就绪！（用时 ${ELAPSED}s）"
printf "  %bSpring Boot%b  http://localhost:%s\n" "$BLUE" "$NC" "$BACKEND_PORT"
printf "  %bFuseki     %b  http://localhost:%s\n" "$BLUE" "$NC" "$FUSEKI_PORT"
printf "  %b健康状态   %b  %s\n"                  "$BLUE" "$NC" "$HEALTH_RESP"

# ─── Step 3: 启动前端 ──────────────────────────────────
echo ""
printf "%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$NC"
log_frontend "启动 Vite Dev Server..."
printf "%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$NC"

if [ ! -d "$FRONTEND_DIR" ]; then
  log_error "前端目录不存在: $FRONTEND_DIR"
  exit 1
fi

if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
  log_frontend "未检测到 node_modules，执行 npm install..."
  (cd "$FRONTEND_DIR" && npm install)
fi

FRONTEND_LOG="$(mktemp "${LOG_DIR}/jena-fuseki-plus-frontend.XXXXXX")"
(
  cd "$FRONTEND_DIR"
  npm run dev 2>&1
) > "$FRONTEND_LOG" &
FRONTEND_PID=$!

log_frontend "进程 PID: $FRONTEND_PID，日志: $FRONTEND_LOG"

# 等待 Vite 端口监听（最多 30s）
VITE_WAIT=0
VITE_READY=false
while [ $VITE_WAIT -lt 30 ]; do
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    log_error "前端进程意外退出！日志如下："
    tail -20 "$FRONTEND_LOG"
    exit 1
  fi
  if lsof -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN -t &>/dev/null; then
    VITE_READY=true
    break
  fi
  printf "."
  sleep 1
  VITE_WAIT=$((VITE_WAIT + 1))
done

echo ""

if [ "$VITE_READY" = "true" ]; then
  log_ok "前端就绪！（用时 ${VITE_WAIT}s）"
else
  log_info "前端启动中（Vite 端口尚未监听，可能仍在编译）..."
fi

# ─── 启动完成：打印汇总 ────────────────────────────────
echo ""
printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$GREEN" "$NC"
printf "%b%b  🚀  jena-fuseki-plus 开发环境已就绪！%b\n"       "$BOLD" "$GREEN" "$NC"
printf "%b%b━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%b\n" "$BOLD" "$GREEN" "$NC"
printf "\n"
printf "  %b前端管理台%b  http://localhost:%s\n"               "$CYAN" "$NC" "$FRONTEND_PORT"
printf "  %b后端 API  %b  http://localhost:%s/api\n"           "$BLUE" "$NC" "$BACKEND_PORT"
printf "  %bFuseki   %b  http://localhost:%s\n"                "$BLUE" "$NC" "$FUSEKI_PORT"
printf "  %bActuator %b  http://localhost:%s/actuator/health\n" "$BLUE" "$NC" "$BACKEND_PORT"
printf "\n"
printf "  后端日志: %s\n" "$BACKEND_LOG"
printf "  前端日志: %s\n" "$FRONTEND_LOG"
printf "\n"

# ─── 将 PID 和日志路径写入 .dev.pid（供 dev-stop.sh 读取） ──
cat > "$PID_FILE" <<EOF
BACKEND_PID=$BACKEND_PID
FRONTEND_PID=$FRONTEND_PID
BACKEND_PORT=$BACKEND_PORT
FUSEKI_PORT=$FUSEKI_PORT
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_LOG=$BACKEND_LOG
FRONTEND_LOG=$FRONTEND_LOG
EOF

if [ "$DAEMON" = "true" ]; then
  # ─── 后台模式：解除 trap 后退出，子进程继续运行 ──────
  trap - INT TERM EXIT
  printf "%b后台模式：服务已在后台运行。%b\n" "$GREEN" "$NC"
  printf "  停止服务：./dev-stop.sh\n"
  printf "  查看日志：tail -f %s\n" "$BACKEND_LOG"
  printf "           tail -f %s\n" "$FRONTEND_LOG"
  exit 0
else
  # ─── 前台模式：实时跟踪日志，Ctrl+C 停止 ───────────
  printf "%b  按 Ctrl+C 停止所有服务%b\n" "$YELLOW" "$NC"
  echo ""
  tail -f "$BACKEND_LOG" --pid="$BACKEND_PID" \
    | sed "s/^/${BLUE}[后端]${NC} /" &
  tail -f "$FRONTEND_LOG" --pid="$FRONTEND_PID" \
    | sed "s/^/${CYAN}[前端]${NC} /" &
  wait "$BACKEND_PID" "$FRONTEND_PID"
fi

