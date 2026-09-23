#!/usr/bin/env bash
# ============================================================================
# B-G2 性能项验证：EXPLAIN ANALYZE + 服务端实测耗时（一次性库，可重复执行）
#
#   ② audit_log 加 idx_audit_at (at)                        → 采纳（实测 40.3ms → 0.6ms）
#   ① notice_record 加 idx_notice_user (user_id, task_id, …) → **未采纳**（既有 idx_notice_confirm
#      已覆盖 /api/notice/mine 的谓词；220k 行 / 20 个 task 实测加与不加无差异）
#   ⑤ 看板未确认计数：内存集合差（多条 SQL + 万行传输）→ 单条聚合 SQL（返回 1 行）
#
# 做法：一次性库 textbook_explain → 造万级数据 → 对比「无索引 / 有索引」的
#       EXPLAIN ANALYZE 与**服务端**实测耗时（同一连接内 NOW(6) 差值，取 5 次最快值；
#       不用客户端进程耗时，避免被 mysql.exe 启动时间主导）。
# 用法：bash scripts/explain-bg2-2026-09-23.sh
# 前置：本地 MySQL 已启动（E:/tools/mysql）
# ============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB="${EXPLAIN_DB:-textbook_explain}"
MYSQL_CLI="${MYSQL_CLI:-E:/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe}"
if [ ! -x "$MYSQL_CLI" ]; then MYSQL_CLI="$(command -v mysql)" || { echo "找不到 mysql 客户端" >&2; exit 2; }; fi
Q="$MYSQL_CLI -uroot -proot --default-character-set=utf8mb4 --skip-column-names --batch $DB"

echo "== 0. 建临时基准存储过程 =="
$Q -e "DROP DATABASE IF EXISTS $DB;" 2>/dev/null
echo "== 1. 重建一次性库 $DB =="
"$MYSQL_CLI" -uroot -proot -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null
$Q < src/main/resources/db/schema.sql
# 基线：先删掉本次要加的两个索引，模拟「迁移前」的存量库
$Q -e "ALTER TABLE audit_log DROP INDEX idx_audit_at;"

echo "== 2. 造数据（notice_record 3 万 / audit_log 3 万 / 学生 1 万）=="
$Q <<'SQL'
SET SESSION cte_max_recursion_depth = 40000;
INSERT INTO sys_role (id, role_code, role_name, sort, deleted)
  VALUES (1,'STUDENT','学生',1,0),(2,'TEACHER','教师',2,0);
CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums (n)
  WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 10000)
  SELECT n FROM seq;
INSERT INTO sys_user (id, user_no, name, password_hash, status, must_change_password,
                      first_login_verified, fail_count, role_version, deleted)
  SELECT n, CONCAT('2023', LPAD(n,5,'0')), CONCAT('学生', n), 'x', 1, 0, 1, 0, 1, 0 FROM nums;
INSERT INTO sys_user_role (user_id, role_id, deleted) SELECT n, 1, 0 FROM nums;
INSERT INTO user_semester_profile (user_id, semester_id, college_id, class_id, status, deleted)
  SELECT n, 1, 1, 1, 1, 0 FROM nums;
INSERT INTO notice_task (id, semester_id, title, content, target_roles, round_limit,
                         interval_hours, source, status, deleted)
  VALUES (1, 1, '征订通知', '请在窗口内提交', 'STUDENT', 5, 24, 'manual', 'active', 0);
-- notice_record：1 万学生 × 3 轮 = 3 万行；第 3 轮为已确认（1 万行 confirmed_at 非空）
INSERT INTO notice_record (task_id, user_id, semester_id, round_no, sent_at, send_status, confirmed_at, deleted)
  SELECT 1, n, 1, r, NOW(3), 'sent', IF(r = 3, NOW(3), NULL), 0
  FROM nums CROSS JOIN (SELECT 1 AS r UNION ALL SELECT 2 UNION ALL SELECT 3) rounds;
-- audit_log：3 万行，时间跨 30 天
INSERT INTO audit_log (user_id, user_no, action, resource, resource_id, at)
  SELECT n, CONCAT('2023', LPAD(n,5,'0')), 'LOGIN', 'auth', NULL,
         NOW(3) - INTERVAL (n MOD 30) DAY FROM nums;
INSERT INTO audit_log (user_id, user_no, action, resource, resource_id, at)
  SELECT NULL, NULL, 'EXPORT', 'export_task', NULL, NOW(3) - INTERVAL 5 DAY FROM nums;
INSERT INTO audit_log (user_id, user_no, action, resource, resource_id, at)
  SELECT NULL, NULL, 'CONFIG', 'config', NULL, NOW(3) - INTERVAL 100 DAY FROM nums;
ANALYZE TABLE notice_record, audit_log, notice_record_history, sys_user, sys_user_role,
              user_semester_profile, notice_task;
SQL
# 基准存储过程：同一连接内跑 5 次，取最快（避免客户端进程启动时间干扰）
$Q <<'SQL'
DROP PROCEDURE IF EXISTS textbook_bench;
DELIMITER $$
CREATE PROCEDURE textbook_bench(IN sql_text TEXT, OUT best_ms DECIMAL(10,1))
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE el BIGINT;
  DECLARE best BIGINT DEFAULT 999999999;
  DECLARE s DATETIME(6);
  WHILE i < 5 DO
    SET s = NOW(6);
    SET @stmt = CONCAT('SELECT COUNT(*) INTO @dummy FROM (', sql_text, ') _probe');
    PREPARE st FROM @stmt;
    EXECUTE st;
    DEALLOCATE PREPARE st;
    SET el = TIMESTAMPDIFF(MICROSECOND, s, NOW(6));
    IF el < best THEN SET best = el; END IF;
    SET i = i + 1;
  END WHILE;
  SET best_ms = ROUND(best / 1000, 1);
END$$
DELIMITER ;
SQL

echo "行数核对：notice_record=$($Q -e 'SELECT COUNT(*) FROM notice_record')  audit_log=$($Q -e 'SELECT COUNT(*) FROM audit_log')  sys_user=$($Q -e 'SELECT COUNT(*) FROM sys_user')"

NOTICE_SQL="SELECT * FROM notice_record WHERE user_id = 9999 AND confirmed_at IS NOT NULL AND deleted = 0 AND task_id IN (1)"
AUDIT_SQL="SELECT * FROM audit_log WHERE at >= NOW() - INTERVAL 1 DAY AND at <= NOW() ORDER BY at DESC LIMIT 20"
DASH_SQL="SELECT COUNT(DISTINCT u.id) FROM notice_task t
  JOIN sys_role r ON r.deleted = 0 AND r.role_code IN ('STUDENT')
  JOIN sys_user_role ur ON ur.role_id = r.id AND ur.deleted = 0
  JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 AND u.status = 1
  JOIN user_semester_profile p ON p.user_id = u.id AND p.semester_id = t.semester_id AND p.deleted = 0 AND p.status = 1
  WHERE t.semester_id = 1 AND t.status = 'active' AND t.deleted = 0
    AND NOT EXISTS (SELECT 1 FROM notice_record nr WHERE nr.task_id = t.id AND nr.user_id = u.id
                    AND nr.confirmed_at IS NOT NULL AND nr.deleted = 0)"
# 旧实现的组成部分（内存版）：①全部在册 profile ②目标角色的全部 user_role
# ③该任务的全部确认记录 ④按 id 分块拉取的用户行
# 旧实现的组成部分（内存版，返回**整行**以计入传输成本）：
#   ① 该学期全部在册 profile（1 万行）② 目标角色的全部 user_role（1 万行）
#   ③ 该任务的全部确认记录（1 万行）④ 按 USER_CHUNK=500 分块拉取的用户（1 万行 / 20 条 SQL）
OLD_PARTS=(
  "SELECT * FROM user_semester_profile WHERE semester_id = 1 AND deleted = 0"
  "SELECT * FROM sys_user_role WHERE role_id IN (1) AND deleted = 0"
  "SELECT * FROM notice_record WHERE task_id = 1 AND confirmed_at IS NOT NULL AND deleted = 0"
)
for k in $(seq 0 19); do
  lo=$((k * 500 + 1)); hi=$(((k + 1) * 500))
  OLD_PARTS+=("SELECT * FROM sys_user WHERE id BETWEEN $lo AND $hi AND status = 1 AND deleted = 0")
done

timeit() { # $1=SQL → 服务端耗时（ms，5 次取最快；存储过程内 PREPARE/EXECUTE 计时）
  $Q -e "CALL textbook_bench('$(printf '%s' "$1" | sed "s/'/''/g")', @best_ms); SELECT @best_ms;" | tail -1
}

show_explain() { # $1=标签 $2=SQL
  echo "--- $1 ---"
  $Q -e "EXPLAIN ANALYZE $2" | sed 's/^/    /'
}

echo
echo "== 3. 迁移前（无这两个索引）=="
show_explain "① /api/notice/mine 用户批查" "$NOTICE_SQL"
show_explain "② 审计只按时间范围筛" "$AUDIT_SQL"
show_explain "⑤ 看板未确认计数（聚合 SQL）" "$DASH_SQL"
B_NOTICE="$(timeit "$NOTICE_SQL")"; B_AUDIT="$(timeit "$AUDIT_SQL")"; B_DASH="$(timeit "$DASH_SQL")"
B_OLD="$(for stmt in "${OLD_PARTS[@]}"; do timeit "$stmt"; echo; done | awk 'NF{s+=$1} END{printf "%.1f", s}')"
OLD_ROWS="$($Q -e 'SELECT (SELECT COUNT(*) FROM user_semester_profile WHERE semester_id=1 AND deleted=0)
  + (SELECT COUNT(*) FROM sys_user_role WHERE role_id IN (1) AND deleted=0)
  + (SELECT COUNT(*) FROM notice_record WHERE task_id=1 AND confirmed_at IS NOT NULL AND deleted=0)
  + (SELECT COUNT(*) FROM sys_user WHERE id <= 10000 AND status=1 AND deleted=0)')"
OLD_QUERIES=$(( ${#OLD_PARTS[@]} ))

echo
echo "== 4. 执行迁移脚本 =="
$Q < src/main/resources/db/migration-2026-09-23-perf.sql
$Q -e "ANALYZE TABLE notice_record, audit_log;"
echo "索引已建："
$Q -e "SELECT CONCAT(TABLE_NAME,'.',INDEX_NAME,'(',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX),')')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$DB'
   AND INDEX_NAME IN ('idx_notice_user','idx_audit_at') GROUP BY TABLE_NAME, INDEX_NAME;" | sed 's/^/    /'

echo
echo "== 5. 迁移后（有索引）=="
show_explain "① /api/notice/mine 用户批查" "$NOTICE_SQL"
show_explain "② 审计只按时间范围筛" "$AUDIT_SQL"
show_explain "⑤ 看板未确认计数（聚合 SQL）" "$DASH_SQL"
A_NOTICE="$(timeit "$NOTICE_SQL")"; A_AUDIT="$(timeit "$AUDIT_SQL")"; A_DASH="$(timeit "$DASH_SQL")"

echo
echo "== 5.5 B-G2① 复核：notice_record 加 user_id 前导索引是否必要 =="
# 场景放大到 20 个 task（一个学期内的历史任务）+ 22 万行 notice_record
$Q -e "SET SESSION cte_max_recursion_depth = 40000;
  INSERT INTO notice_task (id, semester_id, title, content, target_roles, round_limit, interval_hours, source, status, deleted)
  SELECT n, 1, CONCAT('历史通知', n), 'x', 'STUDENT', 5, 24, 'manual', 'closed', 0
  FROM (WITH RECURSIVE seq(n) AS (SELECT 2 UNION ALL SELECT n+1 FROM seq WHERE n < 20) SELECT n FROM seq) s;"
$Q -e "SET SESSION cte_max_recursion_depth = 40000;
  INSERT INTO notice_record (task_id, user_id, semester_id, round_no, sent_at, send_status, confirmed_at, deleted)
  SELECT t.id, u.n, 1, 1, NOW(3), 'sent', IF(u.n MOD 4 = 0, NOW(3), NULL), 0
  FROM notice_task t JOIN (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 10000) SELECT n FROM seq) u
  WHERE t.id >= 2;"
$Q -e "ANALYZE TABLE notice_record;"
MANY_SQL="SELECT * FROM notice_record WHERE user_id = 9999 AND confirmed_at IS NOT NULL AND deleted = 0 AND task_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20)"
echo "  数据量：notice_record $($Q -e 'SELECT COUNT(*) FROM notice_record') 行 / 20 个 task"
NO_IDX="$(timeit "$MANY_SQL")"
$Q -e "ALTER TABLE notice_record ADD KEY idx_notice_user (user_id, task_id, confirmed_at); ANALYZE TABLE notice_record;"
WITH_IDX="$(timeit "$MANY_SQL")"
$Q -e "ALTER TABLE notice_record DROP INDEX idx_notice_user; ANALYZE TABLE notice_record;"
echo "  · 无 idx_notice_user：$NO_IDX ms"
echo "  · 有 idx_notice_user：$WITH_IDX ms"
echo "  · 结论：既有 idx_notice_confirm (task_id, user_id, confirmed_at) 已覆盖该谓词，"
echo "          MySQL 始终选它 → **不加 idx_notice_user**（避免无谓写放大与存储）"

echo
echo
echo "== 6. 规模放大复核（3 万学生 / 24 万 notice_record）=="
$Q -e "SET SESSION cte_max_recursion_depth = 40000;
  INSERT INTO sys_user (id, user_no, name, password_hash, status, must_change_password, first_login_verified, fail_count, role_version, deleted)
    SELECT n+10000, CONCAT('2024', LPAD(n,5,'0')), CONCAT('学生', n), 'x', 1, 0, 1, 0, 1, 0
    FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000) SELECT n FROM seq) s;
  INSERT INTO sys_user_role (user_id, role_id, deleted)
    SELECT n+10000, 1, 0 FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000) SELECT n FROM seq) s;
  INSERT INTO user_semester_profile (user_id, semester_id, college_id, class_id, status, deleted)
    SELECT n+10000, 1, 1, 1, 1, 0 FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000) SELECT n FROM seq) s;
  INSERT INTO notice_record (task_id, user_id, semester_id, round_no, sent_at, send_status, confirmed_at, deleted)
    SELECT 1, n+10000, 1, 1, NOW(3), 'sent', NULL, 0
    FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000) SELECT n FROM seq) s;
  ANALYZE TABLE notice_record, sys_user, sys_user_role, user_semester_profile;"
echo "  数据量：在册学生 $($Q -e 'SELECT COUNT(*) FROM user_semester_profile WHERE semester_id=1 AND deleted=0') / notice_record $($Q -e 'SELECT COUNT(*) FROM notice_record')"
# 旧实现 = 3 条全量查询（各 1 次）+ 分块用户查询（3 万 / USER_CHUNK 500 = 60 次）
BIG_FULL="$(for stmt in "SELECT * FROM user_semester_profile WHERE semester_id = 1 AND deleted = 0"                         "SELECT * FROM sys_user_role WHERE role_id IN (1) AND deleted = 0"                         "SELECT * FROM notice_record WHERE task_id = 1 AND confirmed_at IS NOT NULL AND deleted = 0"; do timeit "$stmt"; echo; done              | awk 'NF{s+=$1} END{printf "%.1f", s}')"
BIG_CHUNK="$(timeit "SELECT * FROM sys_user WHERE id BETWEEN 1 AND 500 AND status = 1 AND deleted = 0")"
BIG_OLD="$(awk -v f="$BIG_FULL" -v c="$BIG_CHUNK" 'BEGIN{printf "%.1f", f + 60*c}')"
BIG_NEW="$(timeit "$DASH_SQL")"
echo "  · 旧实现：3 条全量查询（$BIG_FULL ms）+ 60 条分块用户查询（$BIG_CHUNK ms/条）≈ $BIG_OLD ms（另需把 12 万行经 MyBatis 映射为实体）"
echo "  · 新实现：1 条聚合 SQL = $BIG_NEW ms（返回 1 行）"
echo "  · 结论：**DB 侧耗时旧实现更低**（避免 3 万次嵌套循环）；新实现的收益在"
echo "          查询次数（64 → 1）、网络与内存（12 万行 → 1 行，Java 侧 O(1) 内存）——"
echo "          生产 JVM 堆仅 -Xmx320m，12 万实体 + 4 个 3 万元素 HashSet 是实打实的 GC/风险成本。"
echo "          若日后看板成为瓶颈，再考虑物化计数或 notice_record(task_id, confirmed_at) 覆盖索引。"

echo
echo "== 7. 汇总 =="
echo "数据量：notice_record 3 万行 / audit_log 3 万行 / 在册学生 1 万 / 确认记录 1 万"
echo
printf '| 项 | 迁移前 | 迁移后 |\n|----|--------|--------|\n'
printf '| ① /api/notice/mine 用户批查 | %s ms | %s ms |\n' "$B_NOTICE" "$A_NOTICE"
printf '| ② 审计只按时间范围筛 | %s ms | %s ms |\n' "$B_AUDIT" "$A_AUDIT"
echo
echo "① /api/notice/mine 用户批查：**未采纳索引**（既有 idx_notice_confirm 已覆盖，见 §5.5 实测）"
echo "  单 task 场景（3 万行）加索引前后：$B_NOTICE ms / $A_NOTICE ms（无差异）"
echo
echo "⑤ 看板未确认计数（实现变更，非索引变更）："
printf '  · 旧实现（内存集合差）：%s 条 SQL、%s 行数据进 JVM（另有 MyBatis 实体映射与集合运算的 Java 侧成本，未计入 SQL 耗时），SQL 合计 %s ms
' "$OLD_QUERIES" "$OLD_ROWS" "$B_OLD"
printf '  · 新实现（单条聚合 SQL）：1 条 SQL 返回 1 行，%s ms\n' "$A_DASH"
