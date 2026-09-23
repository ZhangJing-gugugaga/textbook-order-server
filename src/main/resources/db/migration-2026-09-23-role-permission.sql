-- ============================================================================
-- 迁移脚本：2026-09-23 BE-2 角色与权限管理（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**（trial / school / 已有数据的本地库）。
-- 全新空库直接执行 db/schema.sql + db/data-permission.sql，不要跑本脚本。
--
-- 内容：新增 2 条角色管理权限码（37 → 39 条）
--   role:manage               角色查看/新建/编辑/删除
--   role:permission:assign    角色-权限分配
--
-- 为什么**不**给 ADMIN 插 sys_role_permission 行：BE-1 已在鉴权层对超管短路
-- （ADMIN 持有全部权限码），插行既无意义，又会与 migration-2026-09-22.sql 的
-- 「回收角色专属权限」语义打架（那条迁移专门删掉超管的角色专属授权）。
-- 内置角色在角色配置页由 builtIn 标记保护（不可删、编码不可改、权限不可改）。
--
-- 执行方式（建议先备份，见 docs/deployment.md §6）：
--   mysqldump ... > 备份.sql
--   mysql -uroot -p textbook_order < db/migration-2026-09-23-role-permission.sql
--
-- 幂等性：INSERT ... ON DUPLICATE KEY UPDATE，可重复执行。
-- 验证 SQL（期望 2 行）：
--   SELECT perm_code, module FROM sys_permission
--    WHERE perm_code IN ('role:manage','role:permission:assign') AND deleted = 0;
-- 验证总数（期望 39）：
--   SELECT COUNT(1) FROM sys_permission WHERE deleted = 0;
-- ============================================================================
SET NAMES utf8mb4;

INSERT INTO sys_permission (perm_code, perm_name, module) VALUES
('role:manage',            '角色查看/新建/编辑/删除', 'role'),
('role:permission:assign', '角色-权限分配',           'role')
ON DUPLICATE KEY UPDATE sys_permission.perm_name = VALUES(perm_name),
                        sys_permission.module    = VALUES(module);
