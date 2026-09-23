#!/usr/bin/env bash
# ============================================================================
# 《后端测试报告-textbook-order-server-20260923》失败项的真库回归（B10 / B11 / B12 / B13 / B15
# + 种子账号清理脚本），另附 B8/B9 记录项的行为断言。
#
# 为什么需要它：报告里的失败全部发生在**真实 MySQL + 真实 HTTP** 上（archive 空 body 直接归档、
# 时间格式跨接口不一致、导入把班级人数刷成文件行数）。H2 集成测试能锁定逻辑，但证明不了
# 「MySQL 上 UPDATE ... SET version = version + 1 / 多表 JOIN UPDATE 的脚本可用」这类事实。
# 本脚本按报告 §三 的复现步骤逐条重放，并断言修复后的期望结果。
#
# 做法（与 scripts/smoke-isolated.sh 同一套思路）：
#   ① DROP + CREATE 一次性库（不碰开发库 textbook_order）
#   ② SPRING_PROFILES_ACTIVE=local 起应用（自动建表 + 灌种子；独立端口）
#   ③ curl 逐条探针 + python3 解析 JSON 断言
#   ④ 退出时停应用；库保留供事后排查，下次运行开头重建
#
# 前置：本地 MySQL 已启动（E:\tools\mysql-local.bat start）、已构建 jar（./mvnw package -DskipTests）、
#       curl、python3（含 openpyxl，用于构造名单 xlsx）
# 用法：bash scripts/verify-report-2026-09-23.sh [--port 8094] [--db textbook_verify_report]
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB="${VERIFY_DB:-textbook_verify_report}"
PORT="${VERIFY_PORT:-8094}"
JAR="target/textbook-order-server.jar"
DB_USER="${DB_USERNAME:-root}"
DB_PASS="${DB_PASSWORD:-root}"

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --db) DB="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

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
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3" >&2; exit 2; }
[ -f "$JAR" ] || { echo "缺少 $JAR：先执行 ./mvnw clean package -DskipTests" >&2; exit 2; }

BASE="http://127.0.0.1:$PORT"
WORK="${VERIFY_WORK_DIR:-target/verify-report}"
mkdir -p "$WORK"

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
head2() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# 断言：$1 描述，$2 期望，$3 实际
eq() { if [ "$2" = "$3" ]; then ok "$1（= $3）"; else bad "$1（期望 $2，实际 $3）"; fi; }
contains() { case "$3" in *"$2"*) ok "$1";; *) bad "$1（未包含「$2」：$3）";; esac }

# api METHOD PATH TOKEN [BODY] → 全局 HTTP_CODE / BODY（BODY 已压成单行）
HTTP_CODE=""
BODY=""
api() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-sS -m 30 -o "$WORK/resp.json" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  HTTP_CODE="$(curl "${args[@]}" 2>/dev/null || echo 000)"
  BODY="$(tr -d '\n' < "$WORK/resp.json" 2>/dev/null || echo '')"
}

# json PATH → 取字段（点路径，如 data.windowStart）
json() { python3 -c "
import json,sys
raw=sys.stdin.read().strip()
if not raw: print(''); raise SystemExit
try: obj=json.loads(raw)
except Exception: print(''); raise SystemExit
cur=obj
for part in '$1'.split('.'):
    if isinstance(cur,list):
        cur=cur[int(part)] if part.isdigit() and int(part)<len(cur) else None
    elif isinstance(cur,dict):
        cur=cur.get(part)
    else:
        cur=None; break
    if cur is None: break
print('' if cur is None else cur)
" <<<"$BODY"; }

sql() { "$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -N -B -e "$1" "$DB" 2>/dev/null; }

# ---- ① 重建一次性库 ----
"$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -e \
  "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null \
  || { echo "重建库失败：确认 MySQL 已启动（E:\\tools\\mysql-local.bat start）" >&2; exit 1; }
echo "== 目标：库=$DB 端口=$PORT（开发库 textbook_order 不会被触碰）=="

# ---- ② 启动应用 ----
APP_LOG="$WORK/app-$PORT.log"
mkdir -p "$WORK/export"
SPRING_PROFILES_ACTIVE=local \
DB_URL="jdbc:mysql://127.0.0.1:3306/$DB?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASS" \
JWT_SECRET="verify-report-secret-32-bytes-minimum-0123456789" \
TEXTBOOK_EXPORT_TMP="$WORK/export" \
TEXTBOOK_LOG_DIR="$WORK" \
TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE=500 \
SERVER_PORT="$PORT" \
  java -jar "$JAR" > "$APP_LOG" 2>&1 &
APP_PID=$!

cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null
  fi
}
trap cleanup EXIT INT TERM

echo "== 启动应用（日志：$APP_LOG）=="
for i in $(seq 1 90); do
  curl -sS -m 3 "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && { echo "== 应用就绪（${i}s）=="; break; }
  kill -0 "$APP_PID" 2>/dev/null || { echo "启动失败，日志尾部：" >&2; tail -30 "$APP_LOG" >&2; exit 1; }
  sleep 1
done
curl -sS -m 3 "$BASE/actuator/health" | grep -q '"status":"UP"' \
  || { echo "90s 内未就绪，日志尾部：" >&2; tail -30 "$APP_LOG" >&2; exit 1; }

# ---- 登录 ----
api POST /api/auth/login "" '{"userNo":"900001","password":"Admin@123"}'
ADMIN="$(json data.accessToken)"
[ -n "$ADMIN" ] || { echo "超管登录失败：$BODY" >&2; exit 1; }

# ============================================================================
head2 "B11 archive 二次门禁（报告 §B11：空 body 即归档 = P1 生产事故）"
# ============================================================================
api GET /api/admin/semester "$ADMIN"
ACTIVE_ID="$(python3 -c "
import json,sys
d=json.loads(sys.stdin.read())
print(next((s['id'] for s in d['data'] if s['activeStatus']=='active'), ''))
" <<<"$BODY")"
ACTIVE_VER="$(python3 -c "
import json,sys
d=json.loads(sys.stdin.read())
print(next((s['version'] for s in d['data'] if s['activeStatus']=='active'), ''))
" <<<"$BODY")"
WINDOW_STATUS="$(python3 -c "
import json,sys
d=json.loads(sys.stdin.read())
print(next((s['windowStatus'] for s in d['data'] if s['activeStatus']=='active'), ''))
" <<<"$BODY")"
echo "  · active 学期 id=$ACTIVE_ID version=$ACTIVE_VER windowStatus=$WINDOW_STATUS（种子数据窗口进行中）"

# ① 空 body（线上事故的原始调用形态）
api POST "/api/admin/semester/$ACTIVE_ID/archive" "$ADMIN"
eq "① 空 body 归档被拒（400）" "400" "$HTTP_CODE"
eq "① 拒绝码为 PARAM_INVALID" "PARAM_INVALID" "$(json code)"
eq "① 学期仍为 active" "active" "$(sql "SELECT active_status FROM semester WHERE id=$ACTIVE_ID;")"

# ② 只带 version（旧版前端形态）
api POST "/api/admin/semester/$ACTIVE_ID/archive" "$ADMIN" "{\"version\":$ACTIVE_VER}"
eq "② 窗口进行中未确认 → 409" "409" "$HTTP_CODE"
contains "② 文案说明需 confirmWindowOpen=true" "confirmWindowOpen=true" "$(json message)"
contains "② 文案说明全站停摆影响" "停止" "$(json message)"
eq "② 学期仍为 active" "active" "$(sql "SELECT active_status FROM semester WHERE id=$ACTIVE_ID;")"

# ③ 显式确认 → 归档成功，且窗口被同步关闭（B11 的修复目标：不再"一次调用静默成功"）
api POST "/api/admin/semester/$ACTIVE_ID/archive" "$ADMIN" "{\"version\":$ACTIVE_VER,\"confirmWindowOpen\":true}"
eq "③ 显式确认后归档成功（200）" "200" "$HTTP_CODE"
eq "③ 学期已 archived" "archived" "$(sql "SELECT active_status FROM semester WHERE id=$ACTIVE_ID;")"
eq "③ 归档同时关闭窗口" "0|closed" "$(sql "SELECT CONCAT(channel_open,'|',window_status) FROM semester WHERE id=$ACTIVE_ID;")"

# ④ 复现报告里的停摆症状：无 active 学期时业务接口报错
api POST /api/admin/export/orders "$ADMIN" '{}'
contains "④ 无 active 学期 → 业务接口报「当前没有激活学期」（复现报告停摆症状）" "当前没有激活学期" "$BODY"

# ============================================================================
head2 "B15 撤销归档（受限回滚：报告 §B15「恢复只能靠改库」）"
# ============================================================================
ARCH_VER="$(sql "SELECT version FROM semester WHERE id=$ACTIVE_ID;")"

# ① 未确认 → 400
api POST "/api/admin/semester/$ACTIVE_ID/unarchive" "$ADMIN" "{\"version\":$ARCH_VER}"
eq "① 未确认撤销 → 400" "400" "$HTTP_CODE"
contains "① 文案要求 confirm=true" "confirm=true" "$(json message)"

# ② 确认 → 恢复 active，窗口保持 closed（不自动恢复对外填报）
api POST "/api/admin/semester/$ACTIVE_ID/unarchive" "$ADMIN" "{\"version\":$ARCH_VER,\"confirm\":true}"
eq "② 撤销归档成功（200）" "200" "$HTTP_CODE"
eq "② 学期恢复 active" "active" "$(sql "SELECT active_status FROM semester WHERE id=$ACTIVE_ID;")"
eq "② 窗口保持关闭（需手动重开）" "0|closed" "$(sql "SELECT CONCAT(channel_open,'|',window_status) FROM semester WHERE id=$ACTIVE_ID;")"

# ③ 重新开启窗口 → 业务恢复（证明回滚闭环可用）
api POST "/api/admin/semester/$ACTIVE_ID/window/open" "$ADMIN"
eq "③ 手动重新开启窗口（200）" "200" "$HTTP_CODE"
api POST /api/admin/export/orders "$ADMIN" '{}'
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ]; then ok "③ 业务恢复可用（导出 200）"; else bad "③ 业务未恢复：$HTTP_CODE $BODY"; fi

# ④ 已有 active 学期时不得再回滚
DRAFT_ID="$(sql "SELECT id FROM semester WHERE active_status='draft' LIMIT 1;")"
sql "UPDATE semester SET active_status='archived' WHERE id=$DRAFT_ID;" >/dev/null
DRAFT_VER="$(sql "SELECT version FROM semester WHERE id=$DRAFT_ID;")"
api POST "/api/admin/semester/$DRAFT_ID/unarchive" "$ADMIN" "{\"version\":$DRAFT_VER,\"confirm\":true}"
eq "④ 已有 active 学期 → 撤销被拒（409）" "409" "$HTTP_CODE"
contains "④ 文案说明先归档当前学期" "已有激活学期" "$(json message)"
sql "UPDATE semester SET active_status='draft' WHERE id=$DRAFT_ID;" >/dev/null

# ============================================================================
head2 "B10 重复 activate 语义（报告 §B10：实测 400，契约要求 409）"
# ============================================================================
api POST "/api/admin/semester/$ACTIVE_ID/activate" "$ADMIN"
eq "① 空 body 重复激活 → 409（不再是 400 参数错误）" "409" "$HTTP_CODE"
contains "① 文案为「已是激活学期」" "已是激活学期" "$(json message)"

api POST "/api/admin/semester/$DRAFT_ID/activate" "$ADMIN" '{}'
eq "② draft 缺 version → 400" "400" "$HTTP_CODE"
contains "② 文案指明 version 不能为空" "version 不能为空" "$(json message)"

# ============================================================================
head2 "B12 时间格式口径（报告 §B12：PUT semester 空格格式被接受，与联调结论矛盾）"
# ============================================================================
api PUT "/api/admin/semester/$DRAFT_ID" "$ADMIN" '{"windowStart":"2027-09-10 00:00:00","windowEnd":"2027-10-31 23:59:59"}'
eq "① body 空格格式被接受（200，与窗口接口同口径）" "200" "$HTTP_CODE"
eq "① 出参恒为 ISO-8601" "2027-09-10T00:00:00" "$(json data.windowStart)"
eq "① 空格格式真实写库" "2027-09-10 00:00:00" "$(sql "SELECT DATE_FORMAT(window_start,'%Y-%m-%d %H:%i:%s') FROM semester WHERE id=$DRAFT_ID;")"

api PUT "/api/admin/semester/$DRAFT_ID" "$ADMIN" '{"windowStart":"2027-09-11T08:00:00"}'
eq "② body ISO-8601 同样接受（200）" "200" "$HTTP_CODE"
eq "② ISO 出参不变形" "2027-09-11T08:00:00" "$(json data.windowStart)"

api PUT "/api/admin/semester/$DRAFT_ID" "$ADMIN" '{"startDate":"2027-09-01 00:00:00","endDate":"2028-01-15"}'
eq "③ LocalDate 字段容忍带时间写法（200）" "200" "$HTTP_CODE"
eq "③ 取日期部分" "2027-09-01" "$(json data.startDate)"
eq "③ 纯日期原样" "2028-01-15" "$(json data.endDate)"

api PUT "/api/admin/semester/$DRAFT_ID" "$ADMIN" '{"startDate":"2027/09/01"}'
eq "④ 真正非法值仍 400" "400" "$HTTP_CODE"
contains "④ 提示指明 yyyy-MM-dd" "yyyy-MM-dd" "$BODY"

# ============================================================================
head2 "B13 局部名单门禁（报告 §B13：50 → 2，教师填报被卡死 = P1）"
# ============================================================================
# 种子班级 软工2023-1 人数 50；构造 2 行名单 → 触发门禁
CLASS_ID="$(sql "SELECT id FROM school_class WHERE name='软工2023-1' LIMIT 1;")"
CLASS_BEFORE="$(sql "SELECT student_count FROM school_class WHERE id=$CLASS_ID;")"
echo "  · 目标班级 id=$CLASS_ID 当前人数=$CLASS_BEFORE（种子值 50）"

python3 - "$WORK/students-2rows.xlsx" <<'PY'
import sys
from openpyxl import Workbook
wb = Workbook(); ws = wb.active
ws.append(['学号', '姓名', '学院', '专业', '班级', '手机号'])
ws.append(['20230101', '学生甲', '计算机学院', '软件工程', '软工2023-1', '13700001001'])
ws.append(['20239999', '测试学生', '计算机学院', '软件工程', '软工2023-1', '13700009999'])
wb.save(sys.argv[1])
PY

# ① 预览（只读）：返回 diff 且要求确认，不落库
HTTP_CODE="$(curl -sS -m 30 -o "$WORK/preview.json" -w '%{http_code}' -X POST \
  "$BASE/api/admin/user/import/preview?role=student" -H "Authorization: Bearer $ADMIN" \
  -F "file=@$WORK/students-2rows.xlsx" 2>/dev/null)"
BODY="$(tr -d '\n' < "$WORK/preview.json")"
eq "① 预览返回 200" "200" "$HTTP_CODE"
eq "① 预览标记需确认" "True" "$(json data.requiresConfirm)"
eq "① diff：当前 50" "50" "$(json data.classSizeDiffs.0.currentCount)"
eq "① diff：导入后 2" "2" "$(json data.classSizeDiffs.0.incomingCount)"
eq "① 预览不落库（人数不变）" "$CLASS_BEFORE" "$(sql "SELECT student_count FROM school_class WHERE id=$CLASS_ID;")"

# ② 未确认导入 → 409 且不改人数、不建批次
BATCH_BEFORE="$(sql "SELECT COUNT(*) FROM import_batch;")"
HTTP_CODE="$(curl -sS -m 60 -o "$WORK/import-reject.json" -w '%{http_code}' -X POST \
  "$BASE/api/admin/user/import?role=student" -H "Authorization: Bearer $ADMIN" \
  -F "file=@$WORK/students-2rows.xlsx" 2>/dev/null)"
BODY="$(tr -d '\n' < "$WORK/import-reject.json")"
eq "② 未确认导入 → 409" "409" "$HTTP_CODE"
contains "② 文案含逐班 diff（50 → 2）" "50 → 2" "$(json message)"
contains "② 文案给出确认参数" "confirmClassSizeShrink=true" "$(json message)"
eq "② 人数未被改动" "$CLASS_BEFORE" "$(sql "SELECT student_count FROM school_class WHERE id=$CLASS_ID;")"
eq "② 未创建批次" "$BATCH_BEFORE" "$(sql "SELECT COUNT(*) FROM import_batch;")"

# ③ 确认后导入 → 正常执行，人数按去重学号重算为 2
HTTP_CODE="$(curl -sS -m 60 -o "$WORK/import-ok.json" -w '%{http_code}' -X POST \
  "$BASE/api/admin/user/import?role=student&confirmClassSizeShrink=true" -H "Authorization: Bearer $ADMIN" \
  -F "file=@$WORK/students-2rows.xlsx" 2>/dev/null)"
BODY="$(tr -d '\n' < "$WORK/import-ok.json")"
eq "③ 确认后受理（200）" "200" "$HTTP_CODE"
NEW_BATCH="$(json data.batchId)"
for _ in $(seq 1 30); do
  ST="$(sql "SELECT status FROM import_batch WHERE id=$NEW_BATCH;")"
  [ "$ST" = "done" ] || [ "$ST" = "failed" ] && break
  sleep 1
done
eq "③ 批次执行完成" "done" "$(sql "SELECT status FROM import_batch WHERE id=$NEW_BATCH;")"
eq "③ 班级人数按名单重算为 2" "2" "$(sql "SELECT student_count FROM school_class WHERE id=$CLASS_ID;")"
# 收尾审计在批次置 done 之后写入且为独立事务 → 轮询等待；列名为 detail_json（不是 detail）
AUDIT_DETAIL=""
for _ in $(seq 1 10); do
  AUDIT_DETAIL="$(sql "SELECT detail_json FROM audit_log WHERE action='IMPORT' AND resource_id='$NEW_BATCH' LIMIT 1;" | tr -d ' \r')"
  [ -n "$AUDIT_DETAIL" ] && break
  sleep 1
done
contains "③ 审计留痕「已确认下调」" '"classSizeShrinkConfirmed":true' "$AUDIT_DETAIL"

# ④ 恢复班级人数（一次性库内，避免影响后续步骤的语义）
sql "UPDATE school_class SET student_count=$CLASS_BEFORE WHERE id=$CLASS_ID;" >/dev/null

# ============================================================================
head2 "B8/B9 记录项回归（权限门在前 / 内部导出端点所有者判定）"
# ============================================================================
api POST /api/auth/login "" '{"userNo":"700101","password":"Tea@12345"}'
TEACHER="$(json data.accessToken)"
api GET /api/export-task/999999 "$TEACHER"
eq "B9 教师读他人/不存在导出任务 → 404（内部端点无角色墙但所有者判定生效）" "404" "$HTTP_CODE"
# B8 的语义是「权限门在前」：学生无 import:batch:view → 403；超管有权限但非本人批次 → 404
api POST /api/auth/login "" '{"userNo":"20230101","password":"Stu@12345"}'
STUDENT="$(json data.accessToken)"
api GET /api/batch/999999 "$STUDENT"
eq "B8 学生无批次查看权限 → 403（权限门在前）" "403" "$HTTP_CODE"
api GET /api/batch/999999 "$ADMIN"
eq "B8 超管有权限但批次不存在 → 404（归属/存在性判定）" "404" "$HTTP_CODE"

head2 "种子账号清理脚本（报告 P1 风险项：18 个公开口令账号）"
# ============================================================================
SEED_ACTIVE_BEFORE="$(sql "SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND status=1 AND user_no IN ('900001','900002','900003','800101','800102','800103','700101','700102','700103','700201','700202','20230101','20230102','20230103','20230201','600001','600002','600003');")"
[ "$SEED_ACTIVE_BEFORE" -gt 0 ] && ok "清理前存在可登录种子账号（$SEED_ACTIVE_BEFORE 个，复现风险现场）" \
  || bad "清理前未找到可登录种子账号（脚本前置不成立）"
REAL_ACTIVE_BEFORE="$(sql "SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND status=1 AND user_no='20230101';")"

# ① 执行清理脚本（用应用账号/root 均可；此处 root）
"$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" "$DB" < src/main/resources/db/cleanup-seed-accounts.sql 2>/dev/null \
  && ok "① 清理脚本执行成功（MySQL 原生多表 UPDATE JOIN）" || bad "① 清理脚本执行失败"
eq "① 可登录种子账号归零" "0" \
  "$(sql "SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND status=1 AND user_no IN ('900001','900002','900003','800101','800102','800103','700101','700102','700103','700201','700202','20230101','20230102','20230103','20230201','600001','600002','600003');")"
eq "① 种子账号 refresh 令牌全部撤销" "0" \
  "$(sql "SELECT COUNT(*) FROM sys_user_token t JOIN sys_user u ON u.id=t.user_id WHERE t.revoked=0 AND t.deleted=0 AND u.user_no IN ('900001','800101','700101','20230101','600001');")"

# ② 幂等：重复执行不报错、不重复累加
V1="$(sql "SELECT role_version FROM sys_user WHERE user_no='900001';")"
"$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" "$DB" < src/main/resources/db/cleanup-seed-accounts.sql 2>/dev/null \
  && ok "② 重复执行成功（幂等）" || bad "② 重复执行失败"
eq "② role_version 未被二次累加" "$V1" "$(sql "SELECT role_version FROM sys_user WHERE user_no='900001';")"

# ③ 真实账号不受影响（导入生成的学生账号仍在册）
eq "③ 导入生成的真实账号未被误停用" "1" "$(sql "SELECT status FROM sys_user WHERE user_no='20239999';")"

# ④ 种子账号已无法登录
api POST /api/auth/login "" '{"userNo":"900001","password":"Admin@123"}'
eq "④ 被清理的种子账号登录被拒（401）" "401" "$HTTP_CODE"
contains "④ 提示账号已停用" "ACCOUNT_DISABLED" "$BODY"

# ============================================================================
# ---- 结果 ----
printf '\n\033[1m结果：PASS=%d  FAIL=%d\033[0m（库 %s 保留供排查，应用日志 %s）\n' "$PASS" "$FAIL" "$DB" "$APP_LOG"
[ "$FAIL" -eq 0 ] || exit 1
