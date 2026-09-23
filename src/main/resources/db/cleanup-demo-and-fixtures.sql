-- ============================================================================
-- 演示数据与联调夹具清理（正式投用前由超管执行一次）
--
-- 背景：2026-09-23 的全链路线上验证在生产试运行库留下了两类**非真实业务数据**：
--   A) 契约测试的 `[IT]` 前缀夹具（账号 / 学院 / 专业 / 班级 / 课程 / 教材 / 学期）；
--   B) 用于跑通链路的演示数据（演示账号、演示教材、演示征订单 / 选购单 / 通知 / 异动）。
-- 甲方已确认：这两类数据**在正式开放上线后由超管清理**，本脚本即该清理动作。
--
-- 口径：**一律逻辑删除（deleted 置非 0）**，不做物理删除。
--   * 逻辑删除后应用侧一律不可见（所有查询都带 deleted = 0），且保留审计链
--     （created_by / review_by 等引用不悬空）。
--   * 演示账号沿用既有 `cleanup-seed-accounts.sql`（停用 + 撤销令牌），本脚本只做提示。
--
-- 执行方式（**务必先备份**；用有写权限的运维账号，而非只读的应用账号 textbook_app）：
--   mysqldump --single-transaction -uroot textbook_order | gzip > 清理前备份.sql.gz
--   mysql -uroot -p textbook_order < cleanup-demo-and-fixtures.sql
--
-- 幂等：全部语句都带 `deleted = 0` 守卫，可重复执行。
-- 分段执行：§1/§2/§3 相互独立，可按需只跑其中一段（例如保留演示教材、只清 [IT] 夹具）。
-- ============================================================================
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- §0 执行前盘点（先看清要清什么；不改数据）
-- ---------------------------------------------------------------------------
SELECT '【盘点】[IT] 夹具账号' AS 项目, COUNT(*) AS 条数 FROM sys_user
  WHERE deleted = 0 AND user_no LIKE 'IT%'
UNION ALL SELECT '[IT] 夹具教材', COUNT(*) FROM textbook
  WHERE deleted = 0 AND (title LIKE '%IT%' OR isbn LIKE '9787000%')
UNION ALL SELECT '[IT] 夹具学院/专业/班级/课程/学期', (SELECT COUNT(*) FROM college WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM major WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM school_class WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM course WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM semester WHERE deleted = 0 AND name LIKE '%IT%')
UNION ALL SELECT '演示征订单', COUNT(*) FROM order_form WHERE deleted = 0
UNION ALL SELECT '演示学生选购单', COUNT(*) FROM student_order WHERE deleted = 0
UNION ALL SELECT '演示通知任务', COUNT(*) FROM notice_task WHERE deleted = 0
UNION ALL SELECT '演示异动申请', COUNT(*) FROM change_request WHERE deleted = 0;

-- ============================================================================
-- §1 清理契约测试的 [IT] 夹具
-- ============================================================================
-- 1.1 夹具账号：停用 + 令牌失效（与 cleanup-seed-accounts.sql 同口径，不物理删除）
UPDATE sys_user
SET status = 0, role_version = role_version + 1, updated_at = NOW(3)
WHERE deleted = 0 AND status = 1 AND user_no LIKE 'IT%';

UPDATE sys_user_token t JOIN sys_user u ON u.id = t.user_id
SET t.revoked = 1, t.updated_at = NOW(3)
WHERE t.revoked = 0 AND t.deleted = 0 AND u.user_no LIKE 'IT%';

-- 1.2 夹具教材（含 101 本批量教材；ISBN 前缀 9787000 为夹具专用段）
UPDATE textbook
SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND (title LIKE '%IT%' OR isbn LIKE '9787000%');

-- 1.3 夹具任课关系 / 课程 / 班级 / 专业 / 学院（自下而上）
UPDATE teacher_course SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND class_id IN (SELECT id FROM (SELECT id FROM school_class WHERE deleted = 0 AND name LIKE '%IT%') x);

UPDATE course SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND name LIKE '%IT%';

UPDATE school_class SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND name LIKE '%IT%';

UPDATE major SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND name LIKE '%IT%';

UPDATE college SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND name LIKE '%IT%';

-- 1.4 夹具学期（`[IT] 联调学期#N`，始终为 draft，从未激活）
UPDATE semester SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND name LIKE '%IT%';

-- 1.5 夹具导入批次与导出任务（无 deleted 语义的表用物理删除；仅 [IT] 相关）
DELETE FROM import_batch WHERE batch_no LIKE 'IT%' OR biz_type IS NULL AND created_by IS NULL;

-- ============================================================================
-- §2 清理演示业务数据（链路跑通时产生的征订单 / 选购单 / 通知 / 异动）
-- 说明：这些都是**真实业务流程产生的数据**，只是不是学校的真实业务；
--       若希望保留「系统能跑通」的直观证据，可跳过本段，只跑 §1 与 §3。
-- ============================================================================
-- 2.1 通知（先记录后任务；notice_record 的确认唯一键 uk_notice_confirm 不含 deleted，
--     因此逻辑删除后同一 (task,user) 无法再插入新确认记录 —— 如需彻底清空请见 §4 可选段）
UPDATE notice_record SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;
UPDATE notice_task SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;

-- 2.2 学生选购单
UPDATE student_order_item SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;
UPDATE student_order SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;

-- 2.3 教师征订单（含明细）
UPDATE order_form_item SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;
UPDATE order_form SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;

-- 2.4 异动申请
UPDATE change_request SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;

-- 2.5 导出任务（演示导出的产物文件本身由应用按 24h 保留期自动清理）
UPDATE export_task SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0;

-- ============================================================================
-- §3 清理演示教材（2026-09-23 灌入的第一批演示书目，按 ISBN 白名单精确匹配）
-- 若希望**保留**这批教材作为教材库初始数据，跳过本段即可。
-- ============================================================================
UPDATE textbook
SET deleted = UNIX_TIMESTAMP(NOW(3)) * 1000 + id, updated_at = NOW(3)
WHERE deleted = 0 AND isbn IN (
  '9787302147510', -- 数据结构（C语言版·第2版）严蔚敏 清华
  '9787121411748', -- 计算机网络（第8版）谢希仁 电子工业
  '9787302481447', -- C程序设计（第五版）谭浩强 清华
  '9787040591255', -- 数据库系统概论（第6版）王珊 高教
  '9787560633503', -- 计算机操作系统（第4版）汤小丹 西电
  '9787302330981', -- 软件工程导论（第6版）张海藩 清华
  '9787111604365', -- 操作系统概念（原书第9版）Silberschatz 机工
  '9787302464259', -- Java 2实用教程（第5版）耿祥义 清华
  '9787111636878', -- 离散数学及其应用（原书第8版）Rosen 机工
  '9787040589818', -- 高等数学（第八版）上册 同济 高教
  '9787040396614', -- 工程数学 线性代数（第六版）同济 高教
  '9787040516609', -- 概率论与数理统计（第五版）盛骤 高教
  '9787040616200', -- 离散数学（第3版）屈婉玲 高教
  '9787302509806', -- 大学物理学（第4版）力学、热学 张三慧 清华
  '9787302509844', -- 大学物理学（第4版）电磁学、光学、量子物理 张三慧 清华
  '9787040429190', -- 普通物理学（第七版）上册 程守洙 高教
  '9787560025063'  -- 新视野大学英语 读写教程1 外研社
);

-- ============================================================================
-- §4 演示账号（公开口令）—— 由既有脚本处理，本脚本不重复实现
-- 执行：mysql -uroot -p textbook_order < cleanup-seed-accounts.sql
-- 覆盖 18 个公开口令账号（900001/Admin@123 等）：停用 + role_version+1 + 撤销全部 refresh
-- + 学期归属置不在册。**正式开放上线后必须执行**（公网保留 status=1 的超管等于交出系统）。
-- ============================================================================

-- ============================================================================
-- §5 可选：彻底清空通知记录（仅在确实要重置通知模块时使用，执行前务必备份）
-- 原因：notice_record 的 uk_notice_confirm (task_id,user_id,confirm_flag) 不含 deleted，
--       逻辑删除会让该唯一键槽位被长期占用。
-- ============================================================================
-- DELETE FROM notice_record;

-- ============================================================================
-- §6 执行后校验（期望全部为 0）
-- ============================================================================
SELECT '【校验】[IT] 夹具账号（应为 0）' AS 项目, COUNT(*) AS 条数 FROM sys_user
  WHERE deleted = 0 AND status = 1 AND user_no LIKE 'IT%'
UNION ALL SELECT '[IT] 夹具教材（应为 0）', COUNT(*) FROM textbook
  WHERE deleted = 0 AND (title LIKE '%IT%' OR isbn LIKE '9787000%')
UNION ALL SELECT '[IT] 夹具组织/课程/学期（应为 0）', (SELECT COUNT(*) FROM college WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM major WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM school_class WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM course WHERE deleted = 0 AND name LIKE '%IT%')
  + (SELECT COUNT(*) FROM semester WHERE deleted = 0 AND name LIKE '%IT%')
UNION ALL SELECT '演示征订单（跑过 §2 应为 0）', COUNT(*) FROM order_form WHERE deleted = 0
UNION ALL SELECT '演示账号仍可登录（跑过 §4 应为 0）', COUNT(*) FROM sys_user
  WHERE deleted = 0 AND status = 1 AND user_no IN (
    '900001','900002','900003','800101','800102','800103','700101','700102','700103',
    '700201','700202','20230101','20230102','20230103','20230201','600001','600002','600003');
