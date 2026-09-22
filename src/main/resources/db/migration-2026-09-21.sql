-- ============================================================================
-- 迁移脚本：2026-09-21 审查修复（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**（trial / school / 已有数据的本地库）。
-- 全新空库请直接执行 db/schema.sql，不要跑本脚本。
--
-- 为什么需要本脚本：schema.sql 已全部改为 CREATE TABLE IF NOT EXISTS，
-- 对已存在的表是空操作，**不会**补上本轮新增的列/唯一键/索引。不执行本脚本
-- 直接部署新代码，会出现：
--   · order_form.content_version 缺失 → 教师提交与管理员审核全部 500（Unknown column）
--   · notice_task.uk_task_active / notice_record.uk_notice_confirm 缺失
--     → 并发保护静默失效（可建 2 个 active 任务、重复确认可插多行）
--   · 4 个新索引缺失 → 相关查询退化为全表扫描
--
-- 执行方式（建议先备份，见 docs/deployment.md §6）：
--   mysqldump ... > 备份.sql
--   mysql -uroot -p textbook_order < db/migration-2026-09-21.sql
--
-- 幂等性说明：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS / ADD INDEX IF NOT EXISTS，
-- 本脚本因此按「先查 information_schema，存在则跳过」的存储过程方式实现，
-- 可重复执行（重复执行不会报错、不会重复加索引）。
-- 如你使用的 MySQL 版本已支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，
-- 也可直接执行文件末尾「简化版」注释中的语句。
-- ============================================================================
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS textbook_migrate_20260921$$
CREATE PROCEDURE textbook_migrate_20260921()
BEGIN
  DECLARE db VARCHAR(64);
  SET db = DATABASE();

  -- ---------- 1. order_form.content_version（S3：审核对象漂移防护） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'content_version') THEN
    ALTER TABLE order_form
      ADD COLUMN content_version INT NOT NULL DEFAULT 0
        COMMENT '内容版本：教师每次整单覆盖 +1，审核 CAS 谓词之一（防审核对象漂移）'
      AFTER correct_deadline;
  END IF;

  -- ---------- 2. order_form 本人历史索引（S19） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND INDEX_NAME = 'idx_form_teacher') THEN
    ALTER TABLE order_form ADD KEY idx_form_teacher (teacher_id, id)
      COMMENT '本人历史提交（selectTeacherForms：WHERE teacher_id ORDER BY id DESC）';
  END IF;

  -- ---------- 3. student_order 本人历史索引（S19） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND INDEX_NAME = 'idx_stu_order_student') THEN
    ALTER TABLE student_order ADD KEY idx_stu_order_student (student_id, id)
      COMMENT '本人历史选购（selectStudentOrders：WHERE student_id ORDER BY id DESC）';
  END IF;

  -- ---------- 4. change_request 申请人索引（S19） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND INDEX_NAME = 'idx_change_applicant') THEN
    ALTER TABLE change_request ADD KEY idx_change_applicant (applicant_id, id)
      COMMENT '我的提交记录（selectMyRequests：WHERE applicant_id ORDER BY id DESC）';
  END IF;

  -- ---------- 5. audit_log 资源维度索引（S19/S20） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND INDEX_NAME = 'idx_audit_resource') THEN
    ALTER TABLE audit_log ADD KEY idx_audit_resource (resource, resource_id, at)
      COMMENT '窗口变更记录查询（selectByResource）';
  END IF;

  -- ---------- 6. notice_task 同学期唯一 active（S6，DB 层兜底） ----------
  -- 先清理存量重复：同学期保留 id 最小的 active 任务，其余置 closed
  -- （否则加唯一键会因既有重复数据失败）
  UPDATE notice_task t
    JOIN (SELECT semester_id, MIN(id) AS keep_id
            FROM notice_task
           WHERE status = 'active' AND deleted = 0
           GROUP BY semester_id
          HAVING COUNT(1) > 1) dup
      ON t.semester_id = dup.semester_id
   SET t.status = 'closed', t.closed_at = NOW(3)
 WHERE t.status = 'active' AND t.deleted = 0 AND t.id <> dup.keep_id;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'active_flag') THEN
    ALTER TABLE notice_task
      ADD COLUMN active_flag TINYINT
        GENERATED ALWAYS AS (IF(status='active',1,NULL)) STORED
        AFTER closed_at;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND INDEX_NAME = 'uk_task_active') THEN
    ALTER TABLE notice_task
      ADD UNIQUE KEY uk_task_active (semester_id, active_flag, deleted)
      COMMENT '同学期至多 1 个 active 任务（W18，DB 层兜底；NULL 不参与唯一性，故 closed 行不受限）';
  END IF;

  -- ---------- 7. notice_record 确认记录唯一（S25b） ----------
  -- 先清理存量重复确认：同一 (task,user) 保留最早一条 confirmed_at 记录，其余逻辑删除
  UPDATE notice_record r
    JOIN (SELECT task_id, user_id, MIN(id) AS keep_id
            FROM notice_record
           WHERE confirmed_at IS NOT NULL AND deleted = 0
           GROUP BY task_id, user_id
          HAVING COUNT(1) > 1) dup
      ON r.task_id = dup.task_id AND r.user_id = dup.user_id
   SET r.deleted = UNIX_TIMESTAMP(NOW(3)) * 1000
 WHERE r.confirmed_at IS NOT NULL AND r.deleted = 0 AND r.id <> dup.keep_id;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'confirm_flag') THEN
    ALTER TABLE notice_record
      ADD COLUMN confirm_flag TINYINT
        GENERATED ALWAYS AS (IF(confirmed_at IS NOT NULL AND deleted = 0,1,NULL)) STORED
        AFTER confirmed_at;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND INDEX_NAME = 'uk_notice_confirm') THEN
    ALTER TABLE notice_record
      ADD UNIQUE KEY uk_notice_confirm (task_id, user_id, confirm_flag)
      COMMENT '确认记录唯一（round_no 为 NULL 不参与 uk_notice_round 唯一性，故单列生成列兜底）';
  END IF;

  -- ---------- 8. sys_user_token 唯一键含 deleted（规则自洽） ----------
  -- 原唯一键 uk_token_hash(token_hash) 未含 deleted，与该文件「唯一键一律含 deleted」的规则不符；
  -- 加宽前先按 (token_hash, deleted) 去重（逻辑删除的旧行可能与在用行同 hash）
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
               AND INDEX_NAME = 'uk_token_hash'
               AND SEQ_IN_INDEX = 2) THEN
    SET @already_wide := 1;
  ELSE
    SET @already_wide := 0;
  END IF;

  IF @already_wide = 0 THEN
    UPDATE sys_user_token t
      JOIN (SELECT token_hash, deleted, MIN(id) AS keep_id
              FROM sys_user_token
             GROUP BY token_hash, deleted
            HAVING COUNT(1) > 1) dup
        ON t.token_hash = dup.token_hash AND t.deleted = dup.deleted
     SET t.revoked = 1,
         t.token_hash = CONCAT(t.token_hash, '-dup-', t.id)
   WHERE t.id <> dup.keep_id;

    ALTER TABLE sys_user_token DROP INDEX uk_token_hash;
    ALTER TABLE sys_user_token ADD UNIQUE KEY uk_token_hash (token_hash, deleted);
  END IF;

  -- ---------- 9. 存量数据回填：rejected_auto 的补正截止（S1） ----------
  -- 新判定把「correct_deadline 为空」视为补正窗口已过；升级前已存在的 rejected_auto 行
  -- 若仍为空，教师将永久无法补正（只能请管理员驳回）。按「窗口截止 + correct_window_days」
  -- 回填，窗口截止缺失时以 updated_at 为基准（与 TeacherOrderService.correctionDeadline 同口径）。
  UPDATE order_form f
    JOIN semester s ON s.id = f.semester_id
   SET f.correct_deadline = DATE_ADD(COALESCE(s.window_end, f.updated_at),
                                     INTERVAL COALESCE(
                                       (SELECT CAST(c.config_value AS UNSIGNED) FROM system_config c
                                         WHERE c.config_key = 'order.correct_window_days' AND c.deleted = 0
                                         LIMIT 1), 7) DAY)
 WHERE f.status = 'rejected_auto'
   AND f.correct_deadline IS NULL
   AND f.deleted = 0;
END$$

DELIMITER ;

CALL textbook_migrate_20260921();
DROP PROCEDURE IF EXISTS textbook_migrate_20260921;

-- ============================================================================
-- 10. 存量数据说明：import_batch.created_by
--
-- import_batch.created_by 列**本就存在**（初始 DDL 即含），无需 ALTER。
-- 但历史行的 created_by 为 NULL，而新增的归属校验（getBatchForUser）对非 ADMIN
-- 要求 created_by = 本人，因此**历史批次对秘书不可见（按 404 处理，不泄露存在性）**。
-- 这是有意为之的 fail-closed 选择：无法从数据推断历史批次的上传者，
-- 若为兼容而放行，等于对历史数据重新打开「枚举 batchId 读他人批次」的漏洞。
-- 影响：秘书只能看到升级之后自己发起的批次；导入错误明细通常在导入后立即下载，
-- 历史可追溯性影响很小。如需保留历史可见性，可由 ADMIN 查询后另行处理。
-- ============================================================================

-- ============================================================================
-- 验证（迁移后执行，应全部返回 1）
-- ============================================================================
-- SELECT COUNT(1) FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_form' AND COLUMN_NAME='content_version';
-- SELECT COUNT(1) FROM information_schema.STATISTICS
--   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notice_task' AND INDEX_NAME='uk_task_active';
-- SELECT COUNT(1) FROM information_schema.STATISTICS
--   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notice_record' AND INDEX_NAME='uk_notice_confirm';
-- SELECT COUNT(1) FROM information_schema.STATISTICS
--   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_log' AND INDEX_NAME='idx_audit_resource';
-- SELECT COUNT(1) FROM order_form WHERE status='rejected_auto' AND correct_deadline IS NULL AND deleted=0;
--   -- 期望 0

-- ============================================================================
-- 简化版（仅当你的 MySQL 支持 IF NOT EXISTS 语法时可用；否则用上面的存储过程版）
-- 说明：MySQL 8.0 官方**不支持** ADD COLUMN IF NOT EXISTS，以下语句仅供参考对照，
--       直接执行在重复运行时会在已存在的对象上报错。
-- ============================================================================
-- ALTER TABLE order_form ADD COLUMN IF NOT EXISTS content_version INT NOT NULL DEFAULT 0;
-- ALTER TABLE order_form ADD INDEX IF NOT EXISTS idx_form_teacher (teacher_id, id);
-- ALTER TABLE student_order ADD INDEX IF NOT EXISTS idx_stu_order_student (student_id, id);
-- ALTER TABLE change_request ADD INDEX IF NOT EXISTS idx_change_applicant (applicant_id, id);
-- ALTER TABLE audit_log ADD INDEX IF NOT EXISTS idx_audit_resource (resource, resource_id, at);
-- ALTER TABLE notice_task ADD COLUMN IF NOT EXISTS active_flag TINYINT
--   GENERATED ALWAYS AS (IF(status='active',1,NULL)) STORED;
-- ALTER TABLE notice_task ADD UNIQUE KEY IF NOT EXISTS uk_task_active (semester_id, active_flag, deleted);
-- ALTER TABLE notice_record ADD COLUMN IF NOT EXISTS confirm_flag TINYINT
--   GENERATED ALWAYS AS (IF(confirmed_at IS NOT NULL AND deleted = 0,1,NULL)) STORED;
-- ALTER TABLE notice_record ADD UNIQUE KEY IF NOT EXISTS uk_notice_confirm (task_id, user_id, confirm_flag);

-- ============================================================================
-- 可选（低危，按需执行）：DATETIME → DATETIME(3) 精度对齐
--
-- 影响：库列原为秒精度，而写入用 NOW(3)，毫秒被四舍五入截断；令牌过期与补正截止
--       比较存在 <1 秒偏差，业务上无实际影响。MODIFY COLUMN 会重建表（小库秒级完成），
--       大表请在低峰期执行或跳过（跳过不产生功能问题）。
-- ============================================================================
-- ALTER TABLE sys_user_token MODIFY expire_at DATETIME(3) NOT NULL;
-- ALTER TABLE order_form MODIFY correct_deadline DATETIME(3) DEFAULT NULL;
-- ALTER TABLE export_task MODIFY token_expire_at DATETIME(3) DEFAULT NULL,
--                      MODIFY expires_at DATETIME(3) DEFAULT NULL;
-- ALTER TABLE sys_user MODIFY lock_until DATETIME(3) DEFAULT NULL;
-- ALTER TABLE semester MODIFY window_start DATETIME(3) DEFAULT NULL,
--                      MODIFY window_end DATETIME(3) DEFAULT NULL;
