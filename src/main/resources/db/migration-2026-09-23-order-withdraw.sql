-- ============================================================================
-- 迁移脚本：2026-09-23 BE-4 教师主动撤回（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**。全新空库直接执行 db/schema.sql。
--
-- 内容：order_form 新增 withdrawn_at（最近一次主动撤回时间）
--
-- 为什么需要：BE-4 让教师可在管理员审核前把 pending_review 撤回为 draft。
-- 缺这一列 → 撤回接口 500（Unknown column 'withdrawn_at'），教师端「撤回修改」不可用。
--
-- 执行方式（建议先备份）：
--   mysql -uroot -p textbook_order < db/migration-2026-09-23-order-withdraw.sql
--
-- 幂等性：先查 information_schema 再 ALTER，可重复执行。
-- 验证 SQL（期望 1 行，且 COLUMN_TYPE = datetime(3)）：
--   SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_form'
--      AND COLUMN_NAME = 'withdrawn_at';
-- ============================================================================
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS textbook_migrate_20260923_withdraw$$
CREATE PROCEDURE textbook_migrate_20260923_withdraw()
BEGIN
  DECLARE db VARCHAR(64);
  SET db = DATABASE();

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'withdrawn_at') THEN
    ALTER TABLE order_form
      ADD COLUMN withdrawn_at DATETIME(3) DEFAULT NULL
        COMMENT '最近一次主动撤回时间（BE-4：下一流程审核前可撤回为 draft）'
      AFTER correct_deadline;
  END IF;
END$$

DELIMITER ;

CALL textbook_migrate_20260923_withdraw();
DROP PROCEDURE IF EXISTS textbook_migrate_20260923_withdraw;
