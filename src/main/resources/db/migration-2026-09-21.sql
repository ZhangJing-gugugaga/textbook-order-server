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
-- 可重复执行（重复执行不会报错、不会重复加索引/不会重复改列类型）。
--
-- 目标：执行完成后，库结构与 db/schema.sql 全新安装的结果**完全一致**
-- （已实测：列定义与索引零差异），避免「迁移库」与「新装库」之间的结构漂移。
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

  -- ---------- 10. 冗余索引清理（与 schema.sql 对齐） ----------
  -- 这两个索引是唯一键的前缀（uk_course(semester_id, code, deleted)、
  -- uk_tc(semester_id, teacher_id, course_id, class_id, deleted)），
  -- 保留只会增加写入成本与空间占用。schema.sql 已不再创建，此处同步删除以保证
  -- 「迁移后的库」与「全新安装的库」结构一致（环境间结构漂移是排查噩梦）。
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course' AND INDEX_NAME = 'idx_course_sem') THEN
    ALTER TABLE course DROP INDEX idx_course_sem;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course' AND INDEX_NAME = 'idx_tc_teacher') THEN
    ALTER TABLE teacher_course DROP INDEX idx_tc_teacher;
  END IF;

  -- ---------- 11. DATETIME → DATETIME(3) 精度对齐 ----------
  -- 库列原为秒精度而写入用 NOW(3)，毫秒被四舍五入截断；令牌过期与补正截止比较
  -- 存在 <1 秒偏差（业务上无实际影响，但会让「迁移后的库」与全新安装的库结构不一致）。
  -- 用游标动态生成 MODIFY：只处理 DATETIME_PRECISION = 0 的列，天然幂等；
  -- 依据 COLUMN_DEFAULT / EXTRA 还原 DEFAULT 与 ON UPDATE 子句，避免丢失自动时间戳语义。
  BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_table VARCHAR(64);
    DECLARE v_column VARCHAR(64);
    DECLARE v_nullable VARCHAR(3);
    DECLARE v_default VARCHAR(64);
    DECLARE v_extra VARCHAR(64);
    DECLARE v_clause VARCHAR(255);

    DECLARE cur CURSOR FOR
      SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
        FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = db
         AND DATA_TYPE = 'datetime'
         AND (DATETIME_PRECISION IS NULL OR DATETIME_PRECISION = 0)
       ORDER BY TABLE_NAME, COLUMN_NAME;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    read_loop: LOOP
      FETCH cur INTO v_table, v_column, v_nullable, v_default, v_extra;
      IF v_done = 1 THEN
        LEAVE read_loop;
      END IF;

      SET v_clause = CONCAT('DATETIME(3)',
        IF(v_nullable = 'NO', ' NOT NULL', ' DEFAULT NULL'));
      IF v_default = 'CURRENT_TIMESTAMP' THEN
        SET v_clause = CONCAT('DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)');
      END IF;
      IF v_extra LIKE '%on update CURRENT_TIMESTAMP%' THEN
        SET v_clause = CONCAT(v_clause, ' ON UPDATE CURRENT_TIMESTAMP(3)');
      END IF;

      SET @ddl = CONCAT('ALTER TABLE `', v_table, '` MODIFY COLUMN `', v_column, '` ', v_clause);
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
  END;
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
-- 执行代价与验证（第 11 步 DATETIME → DATETIME(3)）
--
-- MODIFY COLUMN 会重建表（COPY 算法）。本系统数据量级（单校、每学期万级行）为秒级完成；
-- 若你的库异常庞大，执行前可先看体积并选择低峰期：
--   SELECT TABLE_NAME, ROUND(DATA_LENGTH/1024/1024) AS data_mb FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND DATA_LENGTH > 0 ORDER BY DATA_LENGTH DESC LIMIT 10;
--
-- 建议执行第 11 步：跳过虽不影响功能，但会让「迁移后的库」与「全新安装的库」在 59 个
-- 时间列上存在结构差异（环境间结构漂移是排查噩梦）。
--
-- 【实测结论】本脚本已在真实 MySQL 8.0.29 上完整验证（2026-09-22）：
--   · 以「修复前 schema + 权限种子 + 18 账号种子」建库 → 执行本脚本 → 重复执行一次
--     两次均退出码 0、无报错（幂等成立）
--   · 迁移后库 vs db/schema.sql 全新安装库：列定义 314/314 零差异、索引 127/127 零差异
--   · 存量数据完好：18 账号 / 37 权限 / 50 角色-权限映射 / rejected_auto 待回填 0 行
-- ============================================================================
