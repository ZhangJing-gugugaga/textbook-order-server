#!/usr/bin/env bash
# ============================================================================
# 独立库冒烟：为每次运行新建一次性数据库 + 独立端口，跑完即弃
#
# 为什么需要它（对照 docs/deployment.md §5.2 / docs/FRONTEND-HANDOFF.md §D）：
#   e2e-smoke.sh 会真实地提交并**审核通过**一张征订单，而 reviewed 是终态、没有撤销接口，
#   且系统里没有删除接口（严格模式）——直接对着演示库跑，第二轮就必然失败，
#   还会在库里留下 [IT] 夹具与一个被刷小的班级人数。把「库」本身做成一次性的，
#   既保证可重复执行，也不需要任何清理接口/DBA 介入。
#
# 做法（与 Testcontainers 的思路一致，但不需要 Docker）：
#   ① 开始时就 DROP + CREATE 目标库（不依赖上一次运行正常退出）
#   ② 以 SPRING_PROFILES_ACTIVE=local 启动应用（local profile 自动执行
#      schema.sql + data-permission.sql + data-seed.sql，且启动自检保证它只连本机库）
#   ③ 跑 e2e-smoke.sh（BASE 指向独立端口，SMOKE_DB 声明目标库）
#   ④ 退出时停应用；库保留供事后排查，下次运行开头重建
#
# 前置：本地 MySQL 已启动（E:\tools\mysql-local.bat start）、已构建 jar、curl、python3
# 用法：bash scripts/smoke-isolated.sh [--keep-db] [--port 8090] [--db textbook_smoke]
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB="${SMOKE_DB_NAME:-textbook_smoke}"
PORT="${SMOKE_PORT:-8090}"
JAR="target/textbook-order-server.jar"
KEEP_DB=0

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-db) KEEP_DB=1; shift ;;
    --port) PORT="$2"; shift 2 ;;
    --db) DB="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# ---- 依赖与产物 ----
MYSQL_CLI="${MYSQL_CLI:-}"
if [ -z "$MYSQL_CLI" ]; then
  if command -v mysql >/dev/null 2>&1; then
    MYSQL_CLI="$(command -v mysql)"
  elif [ -x "E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" ]; then
    MYSQL_CLI="E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"
  else
    echo "找不到 mysql 客户端：请把 mysql 加入 PATH 或用 MYSQL_CLI=<路径> 指定" >&2
    exit 2
  fi
fi
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3（e2e-smoke.sh 解析 JSON 用）" >&2; exit 2; }
[ -f "$JAR" ] || { echo "缺少 $JAR：先执行 ./mvnw clean package -DskipTests" >&2; exit 2; }

DB_USER="${DB_USERNAME:-root}"
DB_PASS="${DB_PASSWORD:-root}"
BASE="http://127.0.0.1:$PORT"

echo "== 目标：库=$DB 端口=$PORT（演示库 textbook_order 不会被触碰）=="

# ---- ① 重建一次性库（放在开头，避免上次异常退出影响本次）----
"$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -e \
  "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null \
  || { echo "重建库失败：确认 MySQL 已启动且账号口令正确（E:\\tools\\mysql-local.bat start）" >&2; exit 1; }

# ---- ② 启动应用（local profile 自动建表 + 灌种子）----
LOG_DIR="${SMOKE_LOG_DIR:-target/smoke}"
mkdir -p "$LOG_DIR" "$LOG_DIR/export"
APP_LOG="$LOG_DIR/app-$PORT.log"
echo "== 启动应用（日志：$APP_LOG）=="

SPRING_PROFILES_ACTIVE=local \
DB_URL="jdbc:mysql://127.0.0.1:3306/$DB?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASS" \
JWT_SECRET="smoke-isolated-secret-32-bytes-minimum-0123456789" \
TEXTBOOK_EXPORT_TMP="$LOG_DIR/export" \
TEXTBOOK_LOG_DIR="$LOG_DIR" \
TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE="${TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE:-500}" \
SERVER_PORT="$PORT" \
  java -jar "$JAR" > "$APP_LOG" 2>&1 &
APP_PID=$!

cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null
  fi
  echo "== 应用已停止（库 $DB 保留，下次运行会重建；--keep-db 仅影响提示）=="
}
trap cleanup EXIT INT TERM

for i in $(seq 1 90); do
  if curl -sS -m 3 "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "== 应用就绪（${i}s）=="
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "应用启动失败，日志尾部：" >&2
    tail -30 "$APP_LOG" >&2
    exit 1
  fi
  sleep 1
done
curl -sS -m 3 "$BASE/actuator/health" | grep -q '"status":"UP"' \
  || { echo "90s 内未就绪，日志尾部：" >&2; tail -30 "$APP_LOG" >&2; exit 1; }

# ---- ③ 跑冒烟（SMOKE_DB 声明目标库；独立库名满足脚本的 scratch 库校验）----
echo "== 执行 e2e-smoke.sh =="
SMOKE_DB="$DB" bash scripts/e2e-smoke.sh "$BASE"
RC=$?

# ---- ④ 结果 ----
if [ $RC -eq 0 ]; then
  echo "== 独立库冒烟通过（库 $DB）=="
else
  echo "== 独立库冒烟失败（exit=$RC），应用日志：$APP_LOG ==" >&2
fi
exit $RC
