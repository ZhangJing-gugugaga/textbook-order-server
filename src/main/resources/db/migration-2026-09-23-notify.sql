-- ============================================================================
-- 迁移脚本：2026-09-23 BE-5d 通知历史归档 + BE-7a 异动类型（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**。全新空库直接执行 db/schema.sql。
--
-- 内容：
--   1. notice_record 新增 semester_id（归档迁移的判定列）+ 回填 + 索引
--   2. 新建 notice_record_history（结构 = notice_record + record_id + archived_at，无生成列）
--   3. notice_record.send_status 加宽到 24（confirmed_by_entry 需要 18 字符）
--   4. change_request 新增 change_type（MAJOR_TRANSFER/GRADE_REPEAT/UPGRADE/OTHER）
--
-- 为什么需要：
--   · 缺 semester_id / 历史表 → 学期归档时无法把通知记录迁出（数据量随学期线性增长），
--     BE-5d 的归档、历史任务导出全部不可用；
--   · 缺 change_request.change_type → 异动类型（转专业/留级/专升本）筛选与导入第 6 列 500。
--
-- 执行方式（建议先备份）：
--   mysql -uroot -p textbook_order < db/migration-2026-09-23-notify.sql
--
-- 幂等性：列/表均先查 information_schema；回填带 `WHERE semester_id IS NULL`，可重复执行。
-- 验证 SQL：
--   SELECT COUNT(1) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notice_record' AND COLUMN_NAME='semester_id';  -- 1
--   SELECT COUNT(1) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notice_record_history';                        -- 1
--   SELECT COUNT(1) FROM notice_record WHERE semester_id IS NULL;                                 -- 0
-- ============================================================================
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS textbook_migrate_20260923_notify$$
CREATE PROCEDURE textbook_migrate_20260923_notify()
BEGIN
  DECLARE db VARCHAR(64);
  SET db = DATABASE();

  -- ---------- 1. notice_record.semester_id ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'semester_id') THEN
    ALTER TABLE notice_record
      ADD COLUMN semester_id BIGINT DEFAULT NULL
        COMMENT '所属学期（BE-5d：学期归档时按此列迁移到 notice_record_history）'
      AFTER user_id;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND INDEX_NAME = 'idx_record_semester') THEN
    ALTER TABLE notice_record ADD KEY idx_record_semester (semester_id)
      COMMENT '学期归档迁移扫描（BE-5d）';
  END IF;

  -- ---------- 2. 存量回填（task → semester 映射） ----------
  UPDATE notice_record r JOIN notice_task t ON r.task_id = t.id
     SET r.semester_id = t.semester_id
   WHERE r.semester_id IS NULL;

  -- ---------- 3. notice_record_history ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record_history') THEN
    CREATE TABLE notice_record_history (
      id BIGINT NOT NULL AUTO_INCREMENT,
      record_id BIGINT NOT NULL COMMENT '原 notice_record.id',
      task_id BIGINT NOT NULL, semester_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
      round_no INT DEFAULT NULL, sent_at DATETIME(3) DEFAULT NULL,
      send_status VARCHAR(16) DEFAULT NULL, confirmed_at DATETIME(3) DEFAULT NULL,
      archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
      reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
      reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
      PRIMARY KEY (id), UNIQUE KEY uk_history_record (record_id, deleted),
      KEY idx_history_task (task_id, user_id), KEY idx_history_sem (semester_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
      COMMENT='通知历史（学期归档迁移目标，BE-5d）';
  END IF;

  -- ---------- 3.1 notice_record.send_status 加宽（BE-5g） ----------
  -- 原 VARCHAR(16) 放不下 'confirmed_by_entry'（18 字符）→ 入口确认会 Data too long 500
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
               AND COLUMN_NAME = 'send_status' AND CHARACTER_MAXIMUM_LENGTH < 24) THEN
    ALTER TABLE notice_record MODIFY COLUMN send_status VARCHAR(24) DEFAULT NULL
      COMMENT 'sent/unauthorized/failed/confirmed/confirmed_by_entry';
  END IF;

  -- ---------- 4. change_request.change_type（BE-7a） ----------
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'change_type') THEN
    ALTER TABLE change_request
      ADD COLUMN change_type VARCHAR(24) DEFAULT NULL
        COMMENT 'MAJOR_TRANSFER/GRADE_REPEAT/UPGRADE/OTHER（BE-7a，历史数据为 NULL）'
      AFTER target_user_id;
  END IF;
END$$

DELIMITER ;

CALL textbook_migrate_20260923_notify();
DROP PROCEDURE IF EXISTS textbook_migrate_20260923_notify;
