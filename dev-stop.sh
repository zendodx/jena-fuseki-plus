#!/bin/bash
# =============================================================
#  jena-fuseki-plus — 开发环境停止脚本
#
#  用法：
#    ./dev-stop.sh          # 读取 .dev.pid 停止后台服务
#    ./dev-stop.sh --force  # 不依赖 PID 文件，直接强杀端口进程
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

# ─── 默认端口（可被 .dev.pid 中的值覆盖） ─────────────
BACKEND_PORT="${BACKEND_PORT:-3040}"
FUSEKI_PORT="${FUSEKI_PORT:-3030}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"

# ─── 解析参数 ─────────────────────────────────────────
FORCE=false
for ARG in "$@"; do
  case "$ARG" in
    --force|-f) FORCE=true ;;
    *) printf "Unknown option: %s\n" "$ARG"; exit 1 ;;
  esac
done

# ─── 工具函数 ─────────────────────────────────────────
log_ok()    { printf "%b✓%b %s\n"     "$GREEN" "$NC" "$*"; }
log_warn()  { printf "%b! %s%b\n"     "$YELLOW" "$*" "$NC"; }
log_error() { printf "%b✗ %s%b\n"     "$RED" "$*" "$NC"; }

# ─── 杀进程组 ────────────────────────────────────────
kill_proc() {
  local pid=$1 label=$2
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    log_warn "[$label] 进程 $pid 不存在或已停止，跳过"
    return
  fi
  PGID=$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')
  if [ -n "$PGID" ]; then
    kill -- "-$PGID" 2>/dev/null && \
      printf "%b[%s]%b 已发送终止信号（PGID %s）\n" "$BLUE" "$label" "$NC" "$PGID"
  else
    kill "$pid" 2>/dev/null && \
      printf "%b[%s]%b 已发送终止信号（PID %s）\n" "$BLUE" "$label" "$NC" "$pid"
  fi
}

# ─── 强杀端口 ────────────────────────────────────────
kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti TCP:"$port" -sTCP:LISTEN 2>/dev/null)
  if [ -n "$pids" ]; then
    printf "%b[dev-stop] 强制清理端口 %s 残留进程...%b\n" "$YELLOW" "$port" "$NC"
    echo "$pids" | xargs kill -9 2>/dev/null
    return 0
  fi
  return 1
}

# ─── 主逻辑 ──────────────────────────────────────────
printf "%b[dev-stop]%b 停止 jena-fuseki-plus 开发环境...\n" "$BOLD" "$NC"

if [ "$FORCE" = "true" ]; then
  # 强制模式：直接杀端口，不依赖 PID 文件
  printf "%b--force 模式：直接强杀端口进程%b\n" "$YELLOW" "$NC"
  KILLED=false
  for PORT in "$BACKEND_PORT" "$FUSEKI_PORT" "$FRONTEND_PORT"; do
    kill_port "$PORT" && KILLED=true
  done
  if [ "$KILLED" = "false" ]; then
    log_warn "未发现任何端口监听进程，服务可能已停止"
  fi
  rm -f "$PID_FILE"
  log_ok "完成"
  exit 0
fi

# 正常模式：读取 PID 文件
if [ ! -f "$PID_FILE" ]; then
  log_warn ".dev.pid 不存在（服务未通过 dev-start.sh 启动，或已停止）"
  printf "如需强制清理端口，请运行：%b./dev-stop.sh --force%b\n" "$BOLD" "$NC"
  exit 0
fi

# 加载 PID 文件中的变量
# shellcheck disable=SC1090
source "$PID_FILE"

printf "  后端 PID : %s\n" "${BACKEND_PID:-（未记录）}"
printf "  前端 PID : %s\n" "${FRONTEND_PID:-（未记录）}"
printf "  后端日志 : %s\n" "${BACKEND_LOG:-（未记录）}"
printf "  前端日志 : %s\n" "${FRONTEND_LOG:-（未记录）}"
echo ""

# 先用进程组优雅终止
kill_proc "${FRONTEND_PID:-}" "前端"
kill_proc "${BACKEND_PID:-}"  "后端"

# 等待进程退出
sleep 2

# 兜底：强杀仍在监听的端口
for PORT in "$BACKEND_PORT" "$FUSEKI_PORT" "$FRONTEND_PORT"; do
  kill_port "$PORT"
done

# 清理 PID 文件
rm -f "$PID_FILE"

echo ""
log_ok "所有服务已停止"

