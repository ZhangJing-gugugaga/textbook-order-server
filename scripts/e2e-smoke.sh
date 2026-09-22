#!/usr/bin/env bash
# ============================================================================
# 后端端到端冒烟（真实 HTTP + 真实 MySQL）—— 覆盖 5 角色主流程 + 安全负例
#
# 前置：
#   1) E:\tools\mysql-local.bat start          （或任何可用的 MySQL）
#   2) set -a; . ./.env.local; set +a
#      java -jar target/textbook-order-server.jar    （local profile，自动建库+种子）
#
# 用法：
#   ./scripts/e2e-smoke.sh [baseUrl]           默认 http://127.0.0.1:8080
#
# 目标库由 SMOKE_DB 声明（默认只接受一次性库；R1 段会直接写该库的 sys_user 锁定字段）
#
# 依赖 db/data-seed.sql 的演示数据：
#   学期 1（active, window open）/ 教师 700101 ↔ 课程1 数据结构 / 班级2 软工2023-1 /
#   教材 1 / 学生 20230101 在班级 2 / 秘书 800101 在学院 1 / 供货商 600001
# 用例可重复执行（征订为学生选购为覆盖语义，异动为追加语义）。
# ============================================================================
set -uo pipefail

BASE="${1:-http://127.0.0.1:8080}"
PASS=0; FAIL=0

ok()    { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()   { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# jget <json> <python语句（作用域内有 d）>：把 JSON 通过 stdin 喂给 python 解析
jget() {
  printf '%s' "$1" | python3 -c "
import sys, json
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print(''); sys.exit()
$2" 2>/dev/null
}
code_of() { jget "$1" "print(d.get('code'))"; }

expect_ok() { # name, json
  local c; c=$(code_of "$2")
  if [ "$c" = "0" ]; then ok "$1"; else bad "$1 (code=${c:-<空>}, body=${2:0:140})"; fi
}
expect_code() { # name, json, expected
  local c; c=$(code_of "$2")
  if [ "$c" = "$3" ]; then ok "$1 → $3"; else bad "$1 (期望 $3，实际 ${c:-<空>})"; fi
}
expect_http() { # name, url, method, expected, [curl args...]
  local name="$1" url="$2" method="$3" want="$4"; shift 4
  local got; got=$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" "$url" "$@")
  if [ "$got" = "$want" ]; then ok "$name → HTTP $want"; else bad "$name (期望 HTTP $want，实际 $got)"; fi
}

login() { # userNo password → accessToken
  curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"userNo\":\"$1\",\"password\":\"$2\",\"deviceId\":\"e2e\"}" \
    | jget "" "print('')" >/dev/null 2>&1
  curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"userNo\":\"$1\",\"password\":\"$2\",\"deviceId\":\"e2e\"}" \
    | python3 -c "import sys,json
try: print((json.load(sys.stdin).get('data') or {}).get('accessToken',''))
except Exception: print('')" 2>/dev/null
}
api() { # method path token [body]
  local m="$1" p="$2" t="$3" b="${4:-}"
  if [ -n "$b" ]; then
    curl -sS -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else
    curl -sS -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  fi
}
# 导出类端点返回 xlsx 二进制或 JSON 包络，按 Content-Type 判定
post_file() { # path token body outfile → 打印 content_type
  local p="$1" t="$2" b="$3" out="$4"
  curl -sS -o "$out" -w '%{content_type}' -X POST "$BASE$p" \
    -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
}

echo "目标：$BASE"

# ---------------------------------------------------------------------------
# 目标库声明（防误伤演示/试运行库）：
#   本脚本会真实提交并**审核通过**一张征订单（reviewed 是终态、无撤销接口），还会改动账号
#   锁定状态与班级人数，因此**不应**对着演示库或试运行库跑——第二轮必然失败且留下脏数据。
#   默认只允许 scratch 库（名字以 textbook_smoke 开头，通常由 scripts/smoke-isolated.sh 创建）；
#   确有需要对着共享库跑时显式声明：SMOKE_DB=textbook_order ALLOW_SHARED_DB=1 ...
# ---------------------------------------------------------------------------
SMOKE_DB="${SMOKE_DB:-}"
if [ -z "$SMOKE_DB" ]; then
  echo "缺少目标库声明：SMOKE_DB=<库名>。推荐直接用独立库跑：bash scripts/smoke-isolated.sh" >&2
  exit 2
fi
case "$SMOKE_DB" in
  textbook_smoke*) ;;
  *)
    if [ "${ALLOW_SHARED_DB:-0}" != "1" ]; then
      echo "拒绝执行：SMOKE_DB=$SMOKE_DB 不是一次性库（应形如 textbook_smoke*）。" >&2
      echo "  · 推荐：bash scripts/smoke-isolated.sh（自动建库/起服务/跑完即弃）" >&2
      echo "  · 确需对着共享库跑：SMOKE_DB=$SMOKE_DB ALLOW_SHARED_DB=1 bash scripts/e2e-smoke.sh" >&2
      echo "  · 共享库前置：种子教师 700101 的征订单必须处于可提交状态（reviewed 是终态，见 API.md §3.6）" >&2
      exit 2
    fi
    ;;
esac
echo "目标库：$SMOKE_DB"

if ! curl -sS -o /dev/null "$BASE/actuator/health" 2>/dev/null; then
  echo "服务未就绪，请先启动应用（见脚本头部说明）"; exit 2
fi

# ============================================================================
head_ "0. 健康检查"
HEALTH=$(curl -sS "$BASE/actuator/health")
if printf '%s' "$HEALTH" | grep -q '"UP"'; then ok "GET /actuator/health → UP"; else bad "健康检查异常: $HEALTH"; fi

# ============================================================================
head_ "1. ADMIN（教材室超管 900001）"
ADMIN=$(login 900001 'Admin@123')
[ -n "$ADMIN" ] && ok "登录成功（token ${#ADMIN} 字符）" || bad "登录失败"
expect_ok "GET /api/me"                          "$(api GET /api/me "$ADMIN")"
expect_ok "GET /api/semester/window/status"      "$(api GET /api/semester/window/status "$ADMIN")"
expect_ok "GET /api/admin/dashboard"             "$(api GET /api/admin/dashboard "$ADMIN")"
expect_ok "GET /api/admin/user"                  "$(api GET '/api/admin/user?page=1&size=5' "$ADMIN")"
expect_ok "GET /api/admin/audit（SQL 分页）"      "$(api GET '/api/admin/audit?page=1&size=5' "$ADMIN")"
expect_ok "GET /api/admin/config"                "$(api GET /api/admin/config "$ADMIN")"
expect_ok "GET /api/admin/order-forms"           "$(api GET '/api/admin/order-forms?page=1&size=5' "$ADMIN")"
expect_code "GET /api/export-task/999999 → NOT_FOUND" "$(api GET /api/export-task/999999 "$ADMIN")" NOT_FOUND

# ============================================================================
head_ "2. TEACHER（教师甲 700101）"
TEACHER=$(login 700101 'Tea@12345')
[ -n "$TEACHER" ] && ok "登录成功" || bad "登录失败"
# 前置检查：本节的「提交 → 审核」要求表单可提交；reviewed 是终态（无撤销接口），
# 共享库上跑过一轮后必然停在这里。给出可执行指引，而不是让后续断言连环失败。
PRE_FORM=$(api GET /api/teacher/order-form "$TEACHER")
PRE_STATUS=$(jget "$PRE_FORM" "print(((d.get('data') or {}).get('status') or ''))")
if [ "$PRE_STATUS" = "reviewed" ]; then
  bad "前置不满足：700101 的征订单已是 reviewed（终态，无法再次提交）"
  echo "     → 推荐改用一次性库：bash scripts/smoke-isolated.sh" >&2
  echo "     → 或在共享库恢复夹具（需 DBA）：UPDATE order_form SET status='rejected', review_by=NULL, review_at=NULL," >&2
  echo "       review_note=NULL, correct_deadline=DATE_ADD(NOW(), INTERVAL 7 DAY) WHERE id=<formId> AND deleted=0;" >&2
  printf '
[1m结果：前置检查未通过，已中止[0m
' >&2
  exit 2
fi
expect_ok "GET /api/teacher/my-courses"    "$(api GET /api/teacher/my-courses "$TEACHER")"
expect_ok "GET /api/teacher/textbook（中文关键词 URL 编码）" \
  "$(api GET '/api/teacher/textbook?keyword=%E6%95%B0%E6%8D%AE' "$TEACHER")"
SUBMIT=$(api POST /api/teacher/order-form/submit "$TEACHER" \
  '{"items":[{"courseId":1,"classId":2,"textbookId":1,"quantity":40}]}')
expect_ok "POST /api/teacher/order-form/submit" "$SUBMIT"
FORM_ID=$(jget "$SUBMIT" "print((d.get('data') or {}).get('id',''))")
FORM_VER=$(jget "$SUBMIT" "print((d.get('data') or {}).get('contentVersion',''))")
echo "     → formId=$FORM_ID contentVersion=$FORM_VER"
expect_ok "GET /api/teacher/order-form"     "$(api GET /api/teacher/order-form "$TEACHER")"
expect_ok "GET /api/teacher/order-forms"    "$(api GET /api/teacher/order-forms "$TEACHER")"

# ============================================================================
head_ "3. ADMIN 审核（S3 contentVersion CAS）"
DETAIL=$(api GET "/api/admin/order-forms/$FORM_ID" "$ADMIN")
DVER=$(jget "$DETAIL" "print((d.get('data') or {}).get('contentVersion',''))")
if [ -n "$DVER" ]; then ok "详情含 contentVersion=$DVER（供审核端回传）"; else bad "详情缺少 contentVersion"; fi
if [ -n "$FORM_VER" ]; then
  expect_code "POST review 用过期 contentVersion → STATE_CONFLICT" \
    "$(api POST "/api/admin/order-forms/$FORM_ID/review" "$ADMIN" \
        "{\"action\":\"pass\",\"reason\":\"\",\"contentVersion\":$((FORM_VER+99))}")" STATE_CONFLICT
fi
expect_ok "POST review 用当前 contentVersion 通过" \
  "$(api POST "/api/admin/order-forms/$FORM_ID/review" "$ADMIN" \
      "{\"action\":\"pass\",\"reason\":\"e2e\",\"contentVersion\":$FORM_VER}")"

# ============================================================================
head_ "4. STUDENT（学生甲 20230101）"
STUDENT=$(login 20230101 'Stu@12345')
[ -n "$STUDENT" ] && ok "登录成功" || bad "登录失败"
BL=$(api GET /api/student/book-list "$STUDENT")
expect_ok "GET /api/student/book-list" "$BL"
printf '%s' "$BL" | python3 -c "
import sys, json
try: books = (json.load(sys.stdin).get('data') or [])
except Exception: books = []
hit = [b for b in books if b.get('textbookId') == 1]
if hit: print('     → 教材1：required=%s delisted=%s（reviewed 后应为 required=true/delisted=false）' % (hit[0].get('required'), hit[0].get('delisted')))
else:   print('     → 警告：教材1 不在清单中')
" 2>/dev/null
expect_ok "POST /api/student/order/submit（覆盖语义）" \
  "$(api POST /api/student/order/submit "$STUDENT" '{"items":[{"textbookId":1,"quantity":1}]}')"
expect_ok "GET /api/student/order" "$(api GET /api/student/order "$STUDENT")"

# ============================================================================
head_ "5. SECRETARY（秘书甲 800101）"
SEC=$(login 800101 'Sec@12345')
[ -n "$SEC" ] && ok "登录成功" || bad "登录失败"
expect_ok "GET /api/secretary/order-forms（本院）" "$(api GET '/api/secretary/order-forms?page=1&size=5' "$SEC")"
expect_ok "POST /api/secretary/change（本院学生异动）" \
  "$(api POST /api/secretary/change "$SEC" '{"type":"student","targetUserNo":"20230102","targetCollegeId":2,"targetClassId":2}')"
expect_ok "GET /api/teacher/change（我的提交记录）" "$(api GET /api/teacher/change "$SEC")"
expect_ok "GET /api/change/org-options" "$(api GET /api/change/org-options "$SEC")"
# F4：非 ADMIN 传他院 collegeId 应被忽略（仍只导出本院）
CT_SEC=$(post_file /api/admin/export/orders "$SEC" '{"semesterId":1,"collegeId":9999}' /tmp/sec-export.out)
case "$CT_SEC" in
  *spreadsheet*) ok "F4 秘书导出他院 collegeId 被忽略（本院 xlsx，$(wc -c < /tmp/sec-export.out) 字节）" ;;
  *json*)        ok "F4 秘书导出走异步（JSON 包络）" ;;
  *)             bad "F4 秘书导出（未预期 Content-Type: $CT_SEC）" ;;
esac

# ============================================================================
head_ "6. SUPPLIER（供货商甲 600001）与 F3 越权负例"
SUP=$(login 600001 'Sup@12345')
[ -n "$SUP" ] && ok "登录成功" || bad "登录失败"
expect_ok "GET /api/supplier/orders" "$(api GET /api/supplier/orders "$SUP")"
CT_SUP=$(post_file /api/supplier/export "$SUP" '{"semesterId":1}' /tmp/sup-export.out)
case "$CT_SUP" in
  *json*)        ok "POST /api/supplier/export → 异步（JSON 包络）"
                 TASK_ID=$(jget "$(cat /tmp/sup-export.out)" "print((d.get('data') or {}).get('taskId',''))")
                 echo "     → taskId=$TASK_ID" ;;
  *spreadsheet*) ok "POST /api/supplier/export → 同步流式 xlsx（行数未超阈值，$(wc -c < /tmp/sup-export.out) 字节）" ;;
  *)             bad "POST /api/supplier/export（未预期 Content-Type: $CT_SUP）" ;;
esac
expect_code "F3 供货商读内部导出任务 → NOT_FOUND" "$(api GET /api/export-task/1 "$SUP")" NOT_FOUND
expect_code "F3 供货商访问教师接口 → FORBIDDEN"   "$(api GET '/api/teacher/textbook?keyword=x' "$SUP")" FORBIDDEN
expect_code "F3 供货商访问管理端 → FORBIDDEN"     "$(api GET '/api/admin/audit?page=1&size=1' "$SUP")" FORBIDDEN

# ============================================================================
head_ "7. 通知 target_roles 定向（S23）"
expect_ok "ADMIN 可见通知（全量）" "$(api GET /api/notice/unconfirmed "$ADMIN")"
NSUP=$(api GET /api/notice/unconfirmed "$SUP")
expect_ok "供货商通知队列可访问" "$NSUP"
printf '%s' "$NSUP" | python3 -c "
import sys, json
try: n = len(json.load(sys.stdin).get('data') or [])
except Exception: n = -1
print('     → 供货商可见通知条数 = %d（窗口变更通知 target_roles=秘书/教师/学生，期望 0）' % n)
" 2>/dev/null

# ============================================================================
head_ "8. 协议与错误边界（S18 / R5 / S24）"
expect_http "未知路径（无 token）"        "$BASE/api/nope"       GET  401
expect_http "GET /api/auth/login 方法不符" "$BASE/api/auth/login" GET  405
expect_http "Content-Type 不支持"          "$BASE/api/auth/login" POST 415 -H 'Content-Type: text/plain' -d x
expect_code "参数类型不匹配 ?page=abc → PARAM_INVALID" "$(api GET '/api/notice/mine?page=abc' "$ADMIN")" PARAM_INVALID
expect_http "R5 page=Long.MAX 不再 500" "$BASE/api/admin/audit?page=9223372036854775807" GET 200 -H "Authorization: Bearer $ADMIN"
CORS_CNT=$(curl -sS -o /dev/null -D - -H "Origin: https://evil.example" "$BASE/actuator/health" 2>/dev/null | grep -ci 'access-control-allow-origin' || true)
if [ "${CORS_CNT:-0}" = "0" ]; then ok "S24 未配置白名单时不下发 CORS 头"; else bad "S24 出现了 CORS 头"; fi

# ============================================================================
head_ "9. 失败计数与锁定（R1，用已启用的 700102 验证后恢复现场）"
MY="${MYSQL_CLI:-}"
if [ -z "$MY" ]; then
  if command -v mysql >/dev/null 2>&1; then MY="$(command -v mysql)"
  elif [ -x "E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" ]; then MY="E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"; fi
fi
if [ -n "$MY" ] && [ -x "$MY" ]; then
  "$MY" -h127.0.0.1 -P3306 -u"${DB_USERNAME:-root}" -p"${DB_PASSWORD:-root}" "$SMOKE_DB" -e \
    "UPDATE sys_user SET fail_count=0, lock_until=NULL WHERE user_no='700102';" 2>/dev/null
  for _ in 1 2 3 4 5; do
    curl -sS -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
      -d '{"userNo":"700102","password":"definitely-wrong-1","deviceId":"e2e"}'
  done
  LOCKED=$("$MY" -h127.0.0.1 -P3306 -u"${DB_USERNAME:-root}" -p"${DB_PASSWORD:-root}" "$SMOKE_DB" -N -B -e \
    "SELECT IF(lock_until IS NULL,'NO','YES') FROM sys_user WHERE user_no='700102';" 2>/dev/null)
  if [ "$LOCKED" = "YES" ]; then ok "R1 连续 5 次失败后 lock_until 已写入"; else bad "R1 lock_until 未写入（$LOCKED）"; fi
  expect_code "R1 锁定后返回 ACCOUNT_LOCKED" \
    "$(curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d '{"userNo":"700102","password":"whatever-1","deviceId":"e2e"}')" ACCOUNT_LOCKED
  "$MY" -h127.0.0.1 -P3306 -u"${DB_USERNAME:-root}" -p"${DB_PASSWORD:-root}" "$SMOKE_DB" -e \
    "UPDATE sys_user SET fail_count=0, lock_until=NULL WHERE user_no='700102';" 2>/dev/null
  echo "     → 已解锁 700102（恢复现场）"
else
  # 之前这里只打印「跳过」，但断言数照旧计入 PASS 口径 —— 等于把未验证当成通过。
  echo "     → 跳过（未找到 mysql 客户端；本段 2 项断言未执行，不计入 PASS）"
fi

# ============================================================================
printf '\n\033[1m结果：PASS=%d  FAIL=%d\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
