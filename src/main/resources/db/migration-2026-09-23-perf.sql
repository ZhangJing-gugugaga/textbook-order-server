-- ============================================================================
-- 迁移脚本：2026-09-23 审计时间索引（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**。全新空库直接执行 db/schema.sql（已含该索引）。
--
-- 内容：audit_log 新增 KEY idx_audit_at (at)
--
-- 为什么需要（对应 docs/13 的 B-G2②）：
--   `GET /api/admin/audit` 允许**只按时间范围**筛（userId/action/resource 全空），
--   现有索引 `idx_audit_user (user_id, at)`、`idx_audit_action (action, at)`、
--   `idx_audit_resource (resource, resource_id, at)` 都是别的列前导 → 全表扫 + filesort。
--   实测（3 万行 audit_log，查最近 1 天 + 倒序取 20 条）：40.3 ms → 0.6 ms，
--   EXPLAIN ANALYZE 由 `Table scan + Sort` 变为 `Index range scan on idx_audit_at (reverse)`。
--   验证脚本：scripts/explain-bg2-2026-09-23.sh；结论：docs/性能验证-B-G2-20260923.md
--
-- 关于 B-G2① （notice_record 加 user_id 前导索引）**未采纳**：
--   实测 `/api/notice/mine` 的谓词是 `user_id = ? AND confirmed_at IS NOT NULL
--   AND task_id IN (...)`，既有 `idx_notice_confirm (task_id, user_id, confirmed_at)`
--   已完全覆盖（220k 行 / 20 个 task 下 EXPLAIN ANALYZE 走该索引，1.4-1.7 ms，与加索引后无差异）。
--   加一个用不到的索引只带来写放大与存储开销，故不加。证据见上述报告。
--
-- 执行方式（建议先备份）：
--   mysqldump --single-transaction -uroot textbook_order | gzip > 升级前备份.sql.gz
--   mysql -uroot -p textbook_order < db/migration-2026-09-23-perf.sql
--
-- 幂等性：先查 information_schema.STATISTICS，存在即跳过，可重复执行。
-- 说明：纯加索引，不改列、不改数据、不改约束；对应用**向后兼容**（无需停机，
--      但大表加索引会短暂持有 MDL，建议在低峰执行）。
--
-- 验证 SQL：
--   SELECT COUNT(1) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
--     AND TABLE_NAME='audit_log' AND INDEX_NAME='idx_audit_at';   -- 1
-- ============================================================================
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS textbook_migrate_20260923_perf$$
CREATE PROCEDURE textbook_migrate_20260923_perf()
BEGIN
  DECLARE db VARCHAR(64);
  SET db = DATABASE();

  -- audit_log：at 前导索引（只按时间范围筛审计，B-G2②）
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND INDEX_NAME = 'idx_audit_at') THEN
    ALTER TABLE audit_log
      ADD KEY idx_audit_at (at) COMMENT '只按时间范围筛审计（B-G2②）';
  END IF;
END$$

DELIMITER ;

CALL textbook_migrate_20260923_perf();
DROP PROCEDURE textbook_migrate_20260923_perf;
