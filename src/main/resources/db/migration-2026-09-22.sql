-- ============================================================================
-- 迁移脚本：2026-09-22 超管授权范围收窄（存量库升级专用）
--
-- 适用对象：**已初始化/已上线且已灌过权限种子的库**（trial / school / 本地库）。
-- 全新空库无需执行：`db/data-permission.sql` 已同步收窄授权范围。
--
-- 为什么需要本脚本：`data-permission.sql` 的 ADMIN 授权是
--   `SELECT ... WHERE p.perm_code NOT IN (...)` + `ON DUPLICATE KEY UPDATE id = id`
-- 即**只增不删**。因此仅更新种子文件，对已存在的库不会收回任何已授予的权限——
-- 必须用 DELETE 显式回收。
--
-- 缺陷现象（线上实测）：超管登录后侧边栏渲染出「学院秘书 / 任课老师 / 学生」
-- 三个别角色的分组菜单（共 20 项，其中约 9 项对超管是死路：我的课程为空、
-- 选书无班级可归、本院记录无学院可归），根因是超管被授予了角色专属的自助类权限。
--
-- 执行方式（建议先备份）：
--   mysqldump -uroot -p textbook_order > 备份-$(date +%F).sql
--   mysql -uroot -p textbook_order < db/migration-2026-09-22.sql
--
-- 幂等性：纯 DELETE，重复执行结果一致（第二次影响 0 行）。
--
-- 影响面：**仅回收超管的 7 条角色专属权限**，不触碰任何其他角色；
-- 超管的管理能力完全不受影响（管理面由 order:form:view:all、order:form:review、
-- student:order:view:all、export:order:create/student/notice 等覆盖）。
-- 唯一的行为变化是：超管不再能调用「以自己身份填报/选购/提交异动/导出签字版」这类
-- 自助端点——这些能力对超管本就无意义。
-- ============================================================================
SET NAMES utf8mb4;

-- 回收超管的角色专属（自助类）权限
DELETE rp
  FROM sys_role_permission rp
  JOIN sys_role r       ON r.id = rp.role_id
  JOIN sys_permission p ON p.id = rp.perm_id
 WHERE r.role_code = 'ADMIN'
   AND p.perm_code IN (
     'order:form:submit',        -- 教师填报提交/补正
     'order:form:view:self',     -- 本人征订表单查看
     'student:order:submit',     -- 学生选购提交
     'student:order:view:self',  -- 本人选购查看
     'order:form:view:college',  -- 本院征订表单查看（秘书）
     'export:signature:create',  -- 秘书签字版导出
     'change:request:submit'     -- 异动提交（秘书/教师）
   );

-- ============================================================================
-- 验证（执行后运行，期望值见注释）
-- ============================================================================
-- 1) 超管权限数：期望 28（= 37 条 − 供货商 2 条 − 角色专属 7 条）
-- SELECT COUNT(1) FROM sys_role_permission rp JOIN sys_role r ON r.id = rp.role_id
--  WHERE r.role_code = 'ADMIN';
--
-- 2) 超管不应再持有这 7 条：期望 0
-- SELECT COUNT(1) FROM sys_role_permission rp
--   JOIN sys_role r ON r.id = rp.role_id
--   JOIN sys_permission p ON p.id = rp.perm_id
--  WHERE r.role_code = 'ADMIN' AND p.perm_code IN (
--    'order:form:submit','order:form:view:self','student:order:submit','student:order:view:self',
--    'order:form:view:college','export:signature:create','change:request:submit');
--
-- 3) 超管必须仍持有窗口横幅依赖的权限：期望 1
-- SELECT COUNT(1) FROM sys_role_permission rp
--   JOIN sys_role r ON r.id = rp.role_id
--   JOIN sys_permission p ON p.id = rp.perm_id
--  WHERE r.role_code = 'ADMIN' AND p.perm_code = 'semester:window:view';
--
-- 4) 其他角色授权不受影响：期望 SECRETARY=6 / TEACHER=4 / STUDENT=3 / SUPPLIER=2
-- SELECT r.role_code, COUNT(1) FROM sys_role_permission rp JOIN sys_role r ON r.id = rp.role_id
--  GROUP BY r.role_code ORDER BY r.role_code;
-- ============================================================================
