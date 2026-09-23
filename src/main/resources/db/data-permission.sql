-- ============================================================================
-- 权限码种子（39 条 · 37 条 M1 冻结 + BE-2 新增 2 条角色管理 · W12：随 OpenAPI 契约一并冻结）
-- 权限码格式：模块:业务:操作（03 §3.1 清单）
-- ============================================================================
SET NAMES utf8mb4;

-- ============ 角色种子（五角色，SPEC §2） ============
INSERT INTO sys_role (role_code, role_name, sort) VALUES
('ADMIN',     '教材室超管', 1),
('SECRETARY', '学院秘书',   2),
('TEACHER',   '任课教师',   3),
('STUDENT',   '学生',       4),
('SUPPLIER',  '供货商',     5)
ON DUPLICATE KEY UPDATE sys_role.id = sys_role.id;

INSERT INTO sys_permission (perm_code, perm_name, module) VALUES
('semester:semester:manage',    '学期新建/编辑',                 'semester'),
('semester:semester:activate',  '学期激活（双缓冲切换）/归档',   'semester'),
('semester:window:manage',      '窗口设置/开启/提前截止/延长',   'semester'),
('semester:window:view',        '窗口状态查看（全角色）',        'semester'),
('user:account:manage',         '建号/停用/启用（含供货商）',    'account'),
('user:account:reset',          '重置密码',                     'account'),
('org:college:manage',          '学院维护',                     'org'),
('org:major:manage',            '专业维护',                     'org'),
('org:class:manage',            '班级维护',                     'org'),
('textbook:book:manage',        '教材 CRUD/停用',               'textbook'),
('textbook:book:import',        '教材导入',                     'textbook'),
('course:course:manage',        '课程维护',                     'course'),
('course:teacher:manage',       '任课关系维护/导入',            'course'),
('people:student:import',       '学生名单导入',                 'people'),
('people:teacher:import',       '教师名单导入',                 'people'),
('order:form:submit',           '教师填报提交/补正',            'order'),
('order:form:view:self',        '本人征订表单查看',             'order'),
('order:form:view:college',     '本院征订表单查看',             'order'),
('order:form:view:all',         '全院征订表单查看',             'order'),
('order:form:review',           '内容审核（通过/驳回）',        'order'),
('student:order:submit',        '学生选购提交',                 'student'),
('student:order:view:self',     '本人选购查看',                 'student'),
('student:order:view:all',      '全院选购查看',                 'student'),
('change:request:submit',       '异动提交',                     'change'),
('change:request:review',       '异动审批（含批量）',           'change'),
('import:batch:view',           '导入批次进度与错误明细',       'batch'),
('export:order:create',         '教师征订明细导出',             'export'),
('export:signature:create',     '秘书签字版导出',               'export'),
('export:student:create',       '学生选购汇总导出',             'export'),
('export:notice:create',        '通知汇总导出',                 'export'),
('notice:task:manage',          '通知任务创建/关闭',            'notice'),
('notice:task:view',            '通知进度与失败名单',           'notice'),
('dashboard:stat:view',         '数据看板',                     'dashboard'),
('config:config:manage',        '系统配置',                     'config'),
('audit:log:view',              '审计日志查询',                 'audit'),
('role:manage',                '角色查看/新建/编辑/删除',      'role'),
('role:permission:assign',     '角色-权限分配',               'role'),
('supplier:order:view',         '供货商清单查看',               'supplier'),
('supplier:order:export',       '供货商清单导出',               'supplier')
ON DUPLICATE KEY UPDATE sys_permission.id = sys_permission.id;

-- ============ 角色 → 权限映射 ============
-- ADMIN（教材室超管）：管理台全部，但**不含角色专属的自助类权限与角色管理权限**。
-- 说明：超管实际持有全部 39 条权限（BE-1 鉴权层短路），这里的 28 条授权行只服务于
-- 前端按权限码过滤菜单，因此保持 28 条口径不变。
--
-- 为什么必须排除这 7 条（2026-09-22 线上缺陷修复）：权限名本身即写明归属——
-- 「教师填报提交/补正」「本人征订表单查看」「学生选购提交」「本人选购查看」
-- 「本院征订表单查看」「秘书签字版导出」「异动提交」。超管持有它们并不会多出任何
-- 管理能力（管理面由 order:form:view:all / review / student:order:view:all 覆盖），
-- 却会让前端侧边栏按权限过滤后渲染出「学院秘书 / 任课老师 / 学生」三个别角色的
-- 分组菜单，且点进去都是对超管无意义的自助页（我的课程为空、选书无班级可归）。
--
-- 注意：semester:window:view **必须保留**——全局窗口横幅依赖它（stores/window.ts）。
-- 「窗口状态」页面本身是秘书页，由前端路由 meta.roles 约束，不靠撤权限来隐藏。
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON 1=1
WHERE r.role_code = 'ADMIN'
  AND p.perm_code NOT IN (
    -- 供货商（物理隔离，超管亦不可见）
    'supplier:order:view', 'supplier:order:export',
    -- 角色专属自助类：教师填报/本人表单、学生选购/本人选购、本院表单、签字版导出、异动提交
    'order:form:submit', 'order:form:view:self',
    'student:order:submit', 'student:order:view:self',
    'order:form:view:college', 'export:signature:create', 'change:request:submit',
    -- 角色管理（BE-2）：超管由鉴权层短路持有（AuthUserService#permissionsFor），
    -- 不落授权行——授权行只服务于前端菜单过滤，28 条口径保持不变
    'role:manage', 'role:permission:assign')
ON DUPLICATE KEY UPDATE sys_role_permission.id = sys_role_permission.id;

-- SECRETARY（学院秘书）：窗口查看、本院表单、本院导出、签字版、异动提交、批次查看
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.perm_code IN (
  'semester:window:view', 'order:form:view:college',
  'export:order:create', 'export:signature:create',
  'change:request:submit', 'import:batch:view')
WHERE r.role_code = 'SECRETARY'
ON DUPLICATE KEY UPDATE sys_role_permission.id = sys_role_permission.id;

-- TEACHER（任课教师）：窗口查看、填报提交/补正、本人表单、异动提交
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.perm_code IN (
  'semester:window:view', 'order:form:submit', 'order:form:view:self', 'change:request:submit')
WHERE r.role_code = 'TEACHER'
ON DUPLICATE KEY UPDATE sys_role_permission.id = sys_role_permission.id;

-- STUDENT（学生）：窗口查看、选购提交、本人选购
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.perm_code IN (
  'semester:window:view', 'student:order:submit', 'student:order:view:self')
WHERE r.role_code = 'STUDENT'
ON DUPLICATE KEY UPDATE sys_role_permission.id = sys_role_permission.id;

-- SUPPLIER（供货商）：仅供货商只读接口 2 条（物理隔离，/api/supplier/**）
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.perm_code IN (
  'supplier:order:view', 'supplier:order:export')
WHERE r.role_code = 'SUPPLIER'
ON DUPLICATE KEY UPDATE sys_role_permission.id = sys_role_permission.id;
