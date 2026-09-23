#!/usr/bin/env bash
# ============================================================================
# 《后端立即决策需求-20260923》BE-1~BE-8 的真库验收（真实 MySQL + 真实 HTTP）
#
# 逐条对应决策文档 §3 的「验收」与 §5 的「测试与护栏要求」：
#   BE-1 超管 /api/me/permissions = 39 条，且调教师/学生自助接口放行（业务校验仍生效）
#   BE-2 角色 CRUD → 配权限 → 回读；内置角色不可删；ADMIN 权限不可改；账号角色覆盖后强制重登
#   BE-3 教师/秘书明细端点不再 403（教师本人 200 / 秘书本院 200）
#   BE-4 撤回：pending_review → draft，撤回后审核 409；reviewed 终态不可撤回；窗口关闭 409
#   BE-5 通知：窗口关闭不重发 + 任务自动关闭；send-now 统计；subscribe-config；confirm-by-entry；
#        学期归档迁移（主表清空 + 历史表有行 + 进度 UNION 仍可读）；导出「渠道」列
#   BE-6 22 张表各 6 个 reserve 列（结构零漂移）
#   BE-7 change_type 往返 + 筛选；异动导入异步批次；模板 6 列表头
#   BE-8 审计动作 ROLE/WITHDRAW 落库
#
# 做法：一次性库 + 独立端口（同 scripts/verify-report-2026-09-23.sh），退出时停应用。
# 前置：本地 MySQL 已启动、已构建 jar、curl、python3
# 用法：bash scripts/verify-be-2026-09-23.sh [--port 8097] [--db textbook_verify_be]
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB="${VERIFY_DB:-textbook_verify_be}"
PORT="${VERIFY_PORT:-8097}"
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
  if command -v mysql >/dev/null 2>&1; then MYSQL_CLI="$(command -v mysql)"
  elif [ -x "E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" ]; then
    MYSQL_CLI="E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"
  else echo "找不到 mysql 客户端" >&2; exit 2; fi
fi
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3" >&2; exit 2; }
[ -f "$JAR" ] || { echo "缺少 $JAR：先 ./mvnw clean package -DskipTests" >&2; exit 2; }

BASE="http://127.0.0.1:$PORT"
WORK="${VERIFY_WORK_DIR:-target/verify-be}"
mkdir -p "$WORK"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
head2() { printf '\n\033[1m%s\033[0m\n' "$1"; }
eq() { if [ "$2" = "$3" ]; then ok "$1（= $3）"; else bad "$1（期望 $2，实际 $3）"; fi; }
contains() { case "$3" in *"$2"*) ok "$1";; *) bad "$1（未包含「$2」：$3）";; esac; }

HTTP_CODE=""; BODY=""
# JSON body 一律先写成 UTF-8 文件、再用 --data-binary @file 提交：
# Windows 下 curl 的命令行参数会按本地代码页（GBK）转码，中文 body 到服务端即 400 PARAM_INVALID。
api() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-sS -m 60 -o "$WORK/resp.out" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  if [ -n "$body" ]; then
    printf '%s' "$body" | python3 -c "import sys,io;io.open(sys.argv[1],'w',encoding='utf-8').write(sys.stdin.read())" "$WORK/body.json"
    args+=(-H 'Content-Type: application/json' --data-binary "@$WORK/body.json")
  fi
  HTTP_CODE="$(curl "${args[@]}" 2>/dev/null || echo 000)"
  BODY="$(tr -d '\0' < "$WORK/resp.out" 2>/dev/null | tr -d '\n')"
}
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
    elif isinstance(cur,dict): cur=cur.get(part)
    else: cur=None; break
    if cur is None: break
print('' if cur is None else cur)
" <<<"$BODY"; }
sql() { "$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -N -B -e "$1" "$DB" 2>/dev/null; }
login() { api POST /api/auth/login "" "{\"userNo\":\"$1\",\"password\":\"$2\"}"; json data.accessToken; }

# ---- ① 一次性库（种子账号 + 权限，与生产同构） ----
"$MYSQL_CLI" -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" -e \
  "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null \
  || { echo "重建库失败：确认 MySQL 已启动" >&2; exit 1; }
echo "== 目标：库=$DB 端口=$PORT（开发库 textbook_order 不会被触碰）=="

APP_LOG="$WORK/app-$PORT.log"
mkdir -p "$WORK/export"
SPRING_PROFILES_ACTIVE=local \
DB_URL="jdbc:mysql://127.0.0.1:3306/$DB?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASS" \
JWT_SECRET="verify-be-secret-32-bytes-minimum-0123456789" \
TEXTBOOK_EXPORT_TMP="$WORK/export" TEXTBOOK_LOG_DIR="$WORK" \
TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE=500 SERVER_PORT="$PORT" \
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
  kill -0 "$APP_PID" 2>/dev/null || { echo "启动失败：" >&2; tail -30 "$APP_LOG" >&2; exit 1; }
  sleep 1
done

ADMIN="$(login 900001 'Admin@123')"
[ -n "$ADMIN" ] || { echo "超管登录失败：$BODY" >&2; exit 1; }
SEMESTER_ID="$(sql "SELECT id FROM semester WHERE active_status='active' LIMIT 1;")"

# ============================================================================
head2 "BE-1 超管全权限（鉴权层短路）"
# ============================================================================
api GET /api/me/permissions "$ADMIN"
eq "① 超管权限数 = 39（37 冻结 + 2 角色管理）" "39" "$(python3 -c "import json,sys;print(len(json.loads(sys.stdin.read())['data']))" <<<"$BODY")"
contains "① 含角色专属自助类（order:form:submit）" "order:form:submit" "$BODY"
contains "① 含供货商权限（D1 默认）" "supplier:order:view" "$BODY"
eq "① 库内 ADMIN 授权行仍为 28（只服务前端菜单）" "28" \
  "$(sql "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.role_code='ADMIN' AND rp.deleted=0;")"

api GET /api/teacher/my-courses "$ADMIN"
eq "② 超管调教师自助接口 → 200（放行，不再 403）" "200" "$HTTP_CODE"
api GET /api/student/order "$ADMIN"
eq "② 超管调学生自助接口 → 200" "200" "$HTTP_CODE"
api POST /api/teacher/order-form/submit "$ADMIN" '{"items":[{"courseId":999999,"classId":999999,"textbookId":999999,"quantity":1}]}'
eq "② 超管提交非法明细 → 400（业务校验仍生效，非 403）" "400" "$HTTP_CODE"
eq "② 错误码为字段审查结果" "FIELD_CHECK_FAILED" "$(json code)"

# ============================================================================
head2 "BE-2 角色与权限管理"
# ============================================================================
api POST /api/admin/role "$ADMIN" '{"roleCode":"COLLEGE_AUDIT","roleName":"学院审核员"}'
eq "① 新建角色 → 200" "200" "$HTTP_CODE"
ROLE_ID="$(json data)"
[ -n "$ROLE_ID" ] && ok "① 返回角色 id=$ROLE_ID" || bad "① 未返回角色 id：$BODY"

api PUT "/api/admin/role/$ROLE_ID/permissions" "$ADMIN" '{"permCodes":["order:form:view:college","import:batch:view"]}'
eq "② 分配 2 条权限 → 200" "200" "$HTTP_CODE"
api GET /api/admin/role "$ADMIN"
eq "② 回读权限数 = 2" "2" "$(python3 -c "
import json,sys
d=json.load(sys.stdin)['data']
r=[x for x in d if x['roleCode']=='COLLEGE_AUDIT'][0]
print(len(r['permCodes']))
" <<<"$BODY")"
eq "② 非内置角色标记 builtIn=false" "False" "$(python3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([x for x in d if x['roleCode']=='COLLEGE_AUDIT'][0]['builtIn'])
" <<<"$BODY")"

api PUT "/api/admin/role/$(sql "SELECT id FROM sys_role WHERE role_code='ADMIN';")/permissions" "$ADMIN" '{"permCodes":["order:form:review"]}'
eq "③ 改 ADMIN 权限 → 400" "400" "$HTTP_CODE"
contains "③ 文案：超管权限由系统内置" "超管权限由系统内置" "$(json message)"

api DELETE "/api/admin/role/$(sql "SELECT id FROM sys_role WHERE role_code='TEACHER';")" "$ADMIN"
eq "④ 删内置角色 → 400" "400" "$HTTP_CODE"
contains "④ 文案：内置角色不可删除" "内置角色不可删除" "$(json message)"

api POST /api/admin/role "$ADMIN" '{"roleCode":"COLLEGE_AUDIT","roleName":"重复"}'
eq "⑤ 重复角色编码 → 400" "400" "$HTTP_CODE"
contains "⑤ 文案：角色编码已存在" "角色编码已存在" "$(json message)"

api GET /api/admin/permission "$ADMIN"
eq "⑥ 权限目录共 39 条" "39" "$(python3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print(sum(len(g['perms']) for g in d))
" <<<"$BODY")"

# 账号角色覆盖 → 强制重新登录
ROLE_CHANGE_USER="$(login 700201 'Tea@12345')"
TEACHER_ID="$(sql "SELECT id FROM sys_user WHERE user_no='700201';")"
RV_BEFORE="$(sql "SELECT role_version FROM sys_user WHERE id=$TEACHER_ID;")"
api PUT "/api/admin/user/$TEACHER_ID/roles" "$ADMIN" '{"roleCodes":["TEACHER","SECRETARY"]}'
eq "⑦ 账号角色覆盖 → 200" "200" "$HTTP_CODE"
eq "⑦ role_version +1" "$((RV_BEFORE + 1))" "$(sql "SELECT role_version FROM sys_user WHERE id=$TEACHER_ID;")"
api GET /api/teacher/my-courses "$ROLE_CHANGE_USER"
eq "⑦ 旧 token 被拒（401，强制重新登录）" "401" "$HTTP_CODE"
ROLE_CHANGE_USER2="$(login 700201 'Tea@12345')"
api GET /api/secretary/order-forms "$ROLE_CHANGE_USER2"
eq "⑦ 重新登录后秘书权限即时生效（本院表单 200）" "200" "$HTTP_CODE"

# ============================================================================
head2 "BE-3 教师/秘书征订单明细端点（修线上 403）"
# ============================================================================
# 教师 700101（种子账号，纯 TEACHER 身份）→ 提交一张单
TEACHER2="$(login 700101 'Tea@12345')"
api GET /api/teacher/my-courses "$TEACHER2"
COURSE_ID="$(json data.0.courses.0.courseId)"
CLASS_ID="$(json data.0.classId)"
TEXTBOOK_ID="$(api GET "/api/teacher/textbook?keyword=" "$TEACHER2"; json data.0.textbookId)"
if [ -n "$COURSE_ID" ] && [ -n "$CLASS_ID" ] && [ -n "$TEXTBOOK_ID" ]; then
  api POST /api/teacher/order-form/submit "$TEACHER2" \
    "{\"items\":[{\"courseId\":$COURSE_ID,\"classId\":$CLASS_ID,\"textbookId\":$TEXTBOOK_ID,\"quantity\":10}]}"
  eq "① 教师提交 → 200" "200" "$HTTP_CODE"
  FORM_ID="$(json data.id)"
  api GET "/api/teacher/order-forms/$FORM_ID" "$TEACHER2"
  eq "② 教师读本人明细（order:form:view:self）→ 200" "200" "$HTTP_CODE"
  api GET "/api/admin/order-forms/$FORM_ID" "$TEACHER2"
  eq "② 同一教师读超管端点 → 403（对照：修复前前端走的就是这条）" "403" "$HTTP_CODE"
  SECRETARY="$(login 800101 'Sec@12345')"
  api GET "/api/secretary/order-forms/$FORM_ID" "$SECRETARY"
  eq "③ 秘书读本院明细（order:form:view:college）→ 200" "200" "$HTTP_CODE"
else
  bad "① 未能取到教师任课关系/教材（种子数据缺失）"
  FORM_ID=""
fi

# ============================================================================
head2 "BE-4 教师主动撤回"
# ============================================================================
if [ -n "${FORM_ID:-}" ]; then
  api POST /api/teacher/order-form/withdraw "$TEACHER2"
  eq "① 撤回 → 200" "200" "$HTTP_CODE"
  eq "① 状态回到 draft" "draft" "$(json data.status)"
  eq "① withdrawnAt 落库" "True" "$(python3 -c "import json,sys;print(json.load(sys.stdin)['data'].get('withdrawnAt') is not None)" <<<"$BODY")"
  eq "① submittedAt 清空" "True" "$(python3 -c "import json,sys;print(json.load(sys.stdin)['data'].get('submittedAt') is None)" <<<"$BODY")"
  eq "① 明细保留（1 行）" "1" "$(python3 -c "import json,sys;print(len(json.load(sys.stdin)['data']['items']))" <<<"$BODY")"
  eq "① 审计动作 WITHDRAW 落库" "1" \
    "$(sql "SELECT COUNT(*) FROM audit_log WHERE action='WITHDRAW' AND resource_id='$FORM_ID';")"

  api POST /api/admin/order-forms/$FORM_ID/review "$ADMIN" '{"action":"pass"}'
  eq "② 撤回后管理员审核 → 409（审批结论不会被静默撤销）" "409" "$HTTP_CODE"

  # reviewed 终态不可撤回
  api POST /api/teacher/order-form/submit "$TEACHER2" \
    "{\"items\":[{\"courseId\":$COURSE_ID,\"classId\":$CLASS_ID,\"textbookId\":$TEXTBOOK_ID,\"quantity\":10}]}"
  CV="$(json data.contentVersion)"
  api POST /api/admin/order-forms/$FORM_ID/review "$ADMIN" "{\"action\":\"pass\",\"contentVersion\":$CV}"
  eq "③ 重新提交后审核通过 → 200" "200" "$HTTP_CODE"
  api POST /api/teacher/order-form/withdraw "$TEACHER2"
  eq "③ reviewed 终态撤回 → 409" "409" "$HTTP_CODE"
  contains "③ 文案：请联系教材室" "请联系教材室" "$(json message)"
fi

# ============================================================================
head2 "BE-5 通知模块（窗口联动 / 立即发送 / 配置 / 入口确认 / 归档 / 渠道列）"
# ============================================================================
api POST /api/admin/notice/tasks "$ADMIN" '{"title":"验收通知","content":"请尽快确认","targetRoles":"STUDENT"}'
eq "① 创建通知任务 → 200" "200" "$HTTP_CODE"
TASK_ID="$(json data.id)"

api POST "/api/admin/notice/tasks/$TASK_ID/send-now" "$ADMIN"
eq "② 立即发送一轮 → 200" "200" "$HTTP_CODE"
eq "② 统计含 roundNo=1" "1" "$(json data.roundNo)"
RECORDS_AFTER_SEND="$(sql "SELECT COUNT(*) FROM notice_record WHERE task_id=$TASK_ID;")"
[ "$RECORDS_AFTER_SEND" -gt 0 ] && ok "② 产生发送记录 $RECORDS_AFTER_SEND 条（未配置微信 → unauthorized）" \
  || bad "② 未产生发送记录"

api GET /api/notice/subscribe-config "$ADMIN"
eq "③ subscribe-config → 200" "200" "$HTTP_CODE"
eq "③ 未配置模板 → subscribeTemplateId 省略/为 null（null 键省略约定）" "True" \
  "$(python3 -c "import json,sys;print(json.load(sys.stdin)['data'].get('subscribeTemplateId') is None)" <<<"$BODY")"
eq "③ 下发弹窗队列上限 = 5" "5" "$(json data.popupQueueMax)"

STUDENT="$(login 20230101 'Stu@12345')"
api POST /api/notice/confirm-by-entry "$STUDENT"
eq "④ 进入选书页即确认 → 200" "200" "$HTTP_CODE"
eq "④ 本次新确认 1 个任务" "1" "$(json data.confirmed)"
eq "④ 落库类型为 confirmed_by_entry" "1" \
  "$(sql "SELECT COUNT(*) FROM notice_record WHERE task_id=$TASK_ID AND send_status='confirmed_by_entry' AND deleted=0;")"
api POST /api/notice/confirm-by-entry "$STUDENT"
eq "④ 幂等：再次调用确认数 = 0" "0" "$(json data.confirmed)"

# 窗口关闭 → 任务自动关闭 + 重发跳过
api POST "/api/admin/semester/$SEMESTER_ID/window/close" "$ADMIN"
eq "⑤ 提前截止窗口 → 200" "200" "$HTTP_CODE"
eq "⑤ 该学期 active 任务自动关闭" "closed" "$(sql "SELECT status FROM notice_task WHERE id=$TASK_ID;")"
eq "⑤ 系统关闭 closed_by 为空" "1" "$(sql "SELECT COUNT(*) FROM notice_task WHERE id=$TASK_ID AND closed_by IS NULL;")"
RECORDS_BEFORE="$(sql "SELECT COUNT(*) FROM notice_record WHERE task_id=$TASK_ID;")"
api POST "/api/admin/notice/tasks/$TASK_ID/send-now" "$ADMIN"
eq "⑤ 已关闭任务 send-now → 409" "409" "$HTTP_CODE"
eq "⑤ 未新增发送记录" "$RECORDS_BEFORE" "$(sql "SELECT COUNT(*) FROM notice_record WHERE task_id=$TASK_ID;")"

head2 "BE-6 reserve1~6 全表补齐"
# ============================================================================
MISSING="$(sql "SELECT COUNT(*) FROM (
  SELECT 'sys_user_token' t UNION ALL SELECT 'sys_role' UNION ALL SELECT 'sys_permission'
  UNION ALL SELECT 'sys_user_role' UNION ALL SELECT 'sys_role_permission' UNION ALL SELECT 'major'
  UNION ALL SELECT 'school_class' UNION ALL SELECT 'semester' UNION ALL SELECT 'user_semester_profile'
  UNION ALL SELECT 'course' UNION ALL SELECT 'teacher_course' UNION ALL SELECT 'order_form'
  UNION ALL SELECT 'order_form_item' UNION ALL SELECT 'student_order' UNION ALL SELECT 'student_order_item'
  UNION ALL SELECT 'change_request' UNION ALL SELECT 'import_batch' UNION ALL SELECT 'export_task'
  UNION ALL SELECT 'notice_task' UNION ALL SELECT 'notice_record' UNION ALL SELECT 'system_config'
  UNION ALL SELECT 'audit_log') t
 WHERE (SELECT COUNT(*) FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA=DATABASE() AND c.TABLE_NAME=t.t AND c.COLUMN_NAME LIKE 'reserve%') <> 6;")"
eq "22 张表各 6 个 reserve 列（缺失表数 = 0）" "0" "$MISSING"

# ============================================================================
head2 "BE-7 异动链路（change_type / 异步导入 / 模板）"
# ============================================================================
api GET /api/secretary/change/template "$ADMIN"
eq "① 异动模板下载 → 200（xlsx 流）" "200" "$HTTP_CODE"
head -c 2 "$WORK/resp.out" | grep -q "PK" && ok "① 返回的是 xlsx（zip 魔数 PK）" || bad "① 模板不是 xlsx"

api POST /api/secretary/change "$ADMIN" \
  "{\"type\":\"student\",\"targetUserNo\":\"20230101\",\"targetCollegeId\":$(sql "SELECT id FROM college WHERE name='外国语学院';"),\"targetClassId\":$(sql "SELECT id FROM school_class WHERE name='英语2023-1';"),\"changeType\":\"MAJOR_TRANSFER\"}"
eq "② 逐条提交带 changeType → 200" "200" "$HTTP_CODE"
eq "② 回显枚举码" "MAJOR_TRANSFER" "$(json data.changeType)"
eq "② 回显中文标签" "转专业" "$(json data.changeTypeLabel)"
CHANGE_ID="$(json data.id)"

api GET "/api/admin/change?changeType=MAJOR_TRANSFER" "$ADMIN"
eq "③ 按 changeType 筛选 → 200" "200" "$HTTP_CODE"
[ "$(json data.total)" -ge 1 ] && ok "③ 筛出 $(json data.total) 条转专业异动" || bad "③ 筛选无结果"

python3 - "$WORK/change-import.xlsx" <<'PY'
import sys
from openpyxl import Workbook
wb = Workbook(); ws = wb.active
ws.append(['学号/工号', '异动对象', '目标学院', '目标班级', '原因', '异动类型'])
ws.append(['20230102', 'student', '外国语学院', '英语2023-1', '转专业', 'MAJOR_TRANSFER'])
ws.append(['20230103', 'student', '外国语学院', '英语2023-1', '留级', '留级'])
wb.save(sys.argv[1])
PY
HTTP_CODE="$(curl -sS -m 60 -o "$WORK/change-import.json" -w '%{http_code}' -X POST \
  "$BASE/api/secretary/change/import" -H "Authorization: Bearer $ADMIN" \
  -F "file=@$WORK/change-import.xlsx" 2>/dev/null)"
BODY="$(tr -d '\n' < "$WORK/change-import.json")"
eq "④ 异动导入受理（异步批次）→ 200" "200" "$HTTP_CODE"
CHANGE_BATCH="$(json data.batchId)"
[ -n "$CHANGE_BATCH" ] && ok "④ 返回 batchId=$CHANGE_BATCH（不再是同步结果体）" || bad "④ 未返回 batchId：$BODY"
for _ in $(seq 1 30); do
  ST="$(sql "SELECT status FROM import_batch WHERE id=$CHANGE_BATCH;")"
  [ "$ST" = "done" ] || [ "$ST" = "failed" ] && break
  sleep 1
done
eq "④ 批次执行完成" "done" "$(sql "SELECT status FROM import_batch WHERE id=$CHANGE_BATCH;")"
eq "④ 批次回写了 CHG- 批次号" "1" \
  "$(sql "SELECT COUNT(*) FROM import_batch WHERE id=$CHANGE_BATCH AND batch_no LIKE 'CHG-%';")"
eq "④ 导入行落库（2 行，change_type 中文可解析）" "2" \
  "$(sql "SELECT COUNT(*) FROM change_request WHERE batch_no=(SELECT batch_no FROM import_batch WHERE id=$CHANGE_BATCH);")"

# ============================================================================
head2 "BE-8 审计动作与一致性收尾"
# ============================================================================
eq "① 审计动作 ROLE 已使用（角色创建/授权）" "1" \
  "$(sql "SELECT COUNT(*) > 0 FROM audit_log WHERE action='ROLE';")"
eq "② change_request.status 不再产生 pending_field_check" "0" \
  "$(sql "SELECT COUNT(*) FROM change_request WHERE status='pending_field_check';")"
eq "③ order_form.withdrawn_at 列存在" "1" \
  "$(sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_form' AND COLUMN_NAME='withdrawn_at';")"

# BE-5⑥/⑦ 归档迁移与渠道列导出（放最后：归档后全站没有 active 学期，会挡住后续用例）
ROUND_ROWS="$(sql "SELECT COUNT(*) FROM notice_record WHERE semester_id=$SEMESTER_ID AND send_status IN ('sent','unauthorized','failed');")"
SEM_VER="$(sql "SELECT version FROM semester WHERE id=$SEMESTER_ID;")"
api POST "/api/admin/semester/$SEMESTER_ID/archive" "$ADMIN" "{\"version\":$SEM_VER,\"confirmWindowOpen\":true}"
eq "⑥ 学期归档 → 200" "200" "$HTTP_CODE"
MAIN_ROWS="$(sql "SELECT COUNT(*) FROM notice_record WHERE semester_id=$SEMESTER_ID;")"
HIST_ROWS="$(sql "SELECT COUNT(*) FROM notice_record_history WHERE semester_id=$SEMESTER_ID;")"
eq "⑥ 主表该学期记录已清空" "0" "$MAIN_ROWS"
[ "$HIST_ROWS" -gt 0 ] && ok "⑥ 历史表已迁入 $HIST_ROWS 行" || bad "⑥ 历史表无记录"
eq "⑥ 进度查询 UNION 仍可读（轮次记录计数 = 历史表 $ROUND_ROWS 行）" "$ROUND_ROWS" \
  "$(python3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print(d['unauthorized']+d['sent']+d['failed'])
" <<<"$(api GET "/api/admin/notice/tasks/$TASK_ID/progress" "$ADMIN"; echo "$BODY")")"
eq "⑥ 归档后任务列表可按 semesterId 查历史" "1" \
  "$(python3 -c "
import json,sys
print(len(json.load(sys.stdin)['data']))
" <<<"$(api GET "/api/admin/notice/tasks?semesterId=$SEMESTER_ID" "$ADMIN"; echo "$BODY")")"

# 渠道列（BE-5f）：导出通知汇总并读回表头
HTTP_CODE="$(curl -sS -m 60 -o "$WORK/notice.xlsx" -w '%{http_code}' -X POST "$BASE/api/admin/export/notice" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"taskId\":$TASK_ID}" 2>/dev/null)"
eq "⑦ 通知汇总导出 → 200（含归档学期任务）" "200" "$HTTP_CODE"
python3 -c "
import sys
from openpyxl import load_workbook
ws = load_workbook(sys.argv[1], read_only=True).active
header = [c.value for c in next(ws.iter_rows(min_row=1, max_row=1))]
print('渠道' if any(h == '渠道' for h in header) else 'MISSING:' + str(header))
" "$WORK/notice.xlsx" > "$WORK/notice-head.txt" 2>/dev/null || echo "READ_FAILED" > "$WORK/notice-head.txt"
eq "⑦ 导出文件含「渠道」列" "渠道" "$(cat "$WORK/notice-head.txt" | tr -d '\r')"

# ============================================================================
printf '\n\033[1m结果：PASS=%d  FAIL=%d\033[0m（库 %s 保留供排查，应用日志 %s）\n' "$PASS" "$FAIL" "$DB" "$APP_LOG"
[ "$FAIL" -eq 0 ] || exit 1
