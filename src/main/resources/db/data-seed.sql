-- ============================================================================
-- 种子数据（M1 交付物）：组织三表、学期（active+draft）、系统配置 8 键、
-- 17 个测试账号（每角色 ≥3：正常/首登待改密/停用）、按学期归属、教材/课程/任课
--
-- 账号清单（初始密码 = 学号/工号后 6 位；「正常」账号使用文档口令可直接登录）：
--   ADMIN     900001 张管理  Admin@123（正常）   900002 李管理（首登待改密，初始 900002）   900003 王管理（停用）
--   SECRETARY 800101 秘书甲  Sec@12345（正常）   800102 秘书乙（首登待改密，初始 800102）   800103 秘书丙（停用）
--   TEACHER   700101 教师甲  Tea@12345（正常）   700102 教师乙（首登待改密，初始 700102）
--             700201 教师丙  Tea@12345（正常）   700202 教师丁（停用）
--             700103 教师戊  Tea@12345（正常，教师+秘书双角色，演示切换身份与多角色并集）
--   STUDENT   20230101 学生甲 Stu@12345（正常）  20230102 学生乙（首登待改密，初始 20230102）
--             20230103 学生丙（停用）            20230201 学生丁 Stu@12345（正常，外国语学院）
--   SUPPLIER  600001 供货商甲 Sup@12345（正常）  600002 供货商乙（停用）
-- 首登校验手机号后 4 位：各账号 phone 字段（如 学生甲=13700001001，后 4 位 0001）
-- ============================================================================
SET NAMES utf8mb4;

-- ============ 组织三表 ============
INSERT INTO college (name, full_name) VALUES
('计算机学院', '计算机学院'),
('外国语学院', '外国语学院');

INSERT INTO major (college_id, name, full_name)
SELECT c.id, m.name, m.full_name FROM college c
JOIN (SELECT '计算机学院' AS college, '软件工程' AS name, '软件工程专业' AS full_name
      UNION ALL SELECT '计算机学院', '网络工程', '网络工程专业'
      UNION ALL SELECT '外国语学院', '英语', '英语专业'
      UNION ALL SELECT '外国语学院', '日语', '日语专业') m
  ON m.college = c.name;

INSERT INTO school_class (major_id, name, grade, student_count)
SELECT m.id, k.name, '2023', k.student_count FROM major m
JOIN (SELECT '软件工程' AS major, '软工2023-1' AS name, 50 AS student_count
      UNION ALL SELECT '软件工程', '软工2023-2', 48
      UNION ALL SELECT '英语', '英语2023-1', 45
      UNION ALL SELECT '日语', '日语2023-1', 40) k
  ON k.major = m.name;

-- ============ 学期（active 窗口开放中 + draft） ============
INSERT INTO semester (name, start_date, end_date, window_start, window_end,
                      channel_open, auto_open, auto_close, window_status, active_status, version)
VALUES
('2026-2027学年秋季学期', '2026-09-01', '2027-01-15',
 '2026-09-14 00:00:00', '2026-10-31 23:59:59', 1, 1, 1, 'open', 'active', 0),
('2026-2027学年春季学期', '2027-02-20', '2027-07-10',
 '2027-03-01 00:00:00', '2027-06-20 23:59:59', 0, 1, 1, 'not_open', 'draft', 0);

-- ============ 系统配置 8 键（M1 冻结，03 §10.1） ============
INSERT INTO system_config (config_key, config_value, remark) VALUES
('notice.round_limit', '5', '订阅消息重发轮次上限（唯一真源，W8）'),
('notice.interval_hours', '24', '重发间隔小时'),
('notice.popup_queue_max', '5', '弹窗队列上限（W18）'),
('order.quantity.max_default', '999', '班级人数缺失时的数量上限回退值（W2）'),
('order.correct_window_days', '7', '关窗后补正窗口天数（W4）'),
('export.sync_row_threshold', '5000', '导出同步/异步阈值（Q16）'),
('export.download_token_minutes', '10', '一次性下载 token 有效期'),
('import.max_file_mb', '10', '上传文件大小上限');

-- ============ 账号（BCrypt strength 10） ============
INSERT INTO sys_user (user_no, name, password_hash, phone, college_id, class_id,
                      status, must_change_password, first_login_verified, fail_count, role_version) VALUES
-- ADMIN
('900001', '张管理', '$2a$10$BgUEhAsWDDnOP7dUFd70pOfsSaTb6NuNjD7Qv5TubIS22pSGE6GLC', '13600000001', NULL, NULL, 1, 0, 1, 0, 1),
('900002', '李管理', '$2a$10$sNUdyJzbDOoQIyUTJnyHtehrEQUhZEYuJ52LMqEZyAHRdcyPPLnra', '13600000002', NULL, NULL, 1, 1, 0, 0, 1),
('900003', '王管理', '$2a$10$AbuzPy0jMY2UL/x6m2630.r5ajO0tIZL4S6RxmanTTc26qdrlKbMW', '13600000003', NULL, NULL, 0, 1, 0, 0, 1),
-- SECRETARY（计算机学院）
('800101', '秘书甲', '$2a$10$fM1qkSSQUJv91hEOpq1J7.8q.qTvRpp.RTY9w4lxiEw1WsXjJvrg.', '13800001101', (SELECT id FROM college WHERE name='计算机学院'), NULL, 1, 0, 1, 0, 1),
('800102', '秘书乙', '$2a$10$jOYS18erfe4yvGMe89Wt8OKE7OBW/9kTZSYskLBlJs0I/bPcACmNy', '13800001102', (SELECT id FROM college WHERE name='计算机学院'), NULL, 1, 1, 0, 0, 1),
('800103', '秘书丙', '$2a$10$MiBK5he58ASXeFhGKUjnWeVW5XxgesscocPI5Ie0MkXCm3ZN7r1bW', '13800001103', (SELECT id FROM college WHERE name='计算机学院'), NULL, 0, 1, 0, 0, 1),
-- TEACHER（计算机学院 ×2 + 外国语学院 ×1 + 双角色 ×1）
('700101', '教师甲', '$2a$10$rQ9btd0SYr1RdFudYz3mh.e1CPIFUI4/sXsVneRZvu/gXMSgeKSQq', '1390000101', (SELECT id FROM college WHERE name='计算机学院'), NULL, 1, 0, 1, 0, 1),
('700102', '教师乙', '$2a$10$E00.qTGZCYpyD0CzBzbIPeFeLc55KyjxNxI4pHEvJXLleZl3s32le', '1390000102', (SELECT id FROM college WHERE name='计算机学院'), NULL, 1, 1, 0, 0, 1),
('700103', '教师戊', '$2a$10$rQ9btd0SYr1RdFudYz3mh.e1CPIFUI4/sXsVneRZvu/gXMSgeKSQq', '1390000103', (SELECT id FROM college WHERE name='计算机学院'), NULL, 1, 0, 1, 0, 1),
('700201', '教师丙', '$2a$10$rQ9btd0SYr1RdFudYz3mh.e1CPIFUI4/sXsVneRZvu/gXMSgeKSQq', '1390000201', (SELECT id FROM college WHERE name='外国语学院'), NULL, 1, 0, 1, 0, 1),
('700202', '教师丁', '$2a$10$JMZ6t7URwRq5GfA4/Q4PWOW2BPjFWtWQw5/iUJFJu341hRBHdLBZS', '1390000202', (SELECT id FROM college WHERE name='外国语学院'), NULL, 0, 1, 0, 0, 1),
-- STUDENT
('20230101', '学生甲', '$2a$10$tPstQvIwGzgEFjCA2YWdCekx8rlfQzCrGotpVJz7I6otFCurl9zCa', '13700001001', (SELECT id FROM college WHERE name='计算机学院'), (SELECT id FROM school_class WHERE name='软工2023-1'), 1, 0, 1, 0, 1),
('20230102', '学生乙', '$2a$10$Vl07J2AZj.LhGs6GxWHMeu6D6QpAkbQ/R./Sk/sx75UgN3kz/2oeG', '13700001002', (SELECT id FROM college WHERE name='计算机学院'), (SELECT id FROM school_class WHERE name='软工2023-1'), 1, 1, 0, 0, 1),
('20230103', '学生丙', '$2a$10$Gt5MkznaNTWssmbn6sU5W.wiAf8W.hYECTzYDqef5c74PLK/wxt0u', '13700001003', (SELECT id FROM college WHERE name='计算机学院'), (SELECT id FROM school_class WHERE name='软工2023-1'), 0, 1, 0, 0, 1),
('20230201', '学生丁', '$2a$10$tPstQvIwGzgEFjCA2YWdCekx8rlfQzCrGotpVJz7I6otFCurl9zCa', '13700002001', (SELECT id FROM college WHERE name='外国语学院'), (SELECT id FROM school_class WHERE name='英语2023-1'), 1, 0, 1, 0, 1),
-- SUPPLIER
('600001', '供货商甲', '$2a$10$tIPuCasNS75zg12R9MIH/eMCvlGl1BAPLy5msUNWzl3yBmXrzW0Ci', '13500000001', NULL, NULL, 1, 0, 1, 0, 1),
('600002', '供货商乙', '$2a$10$zjrSEOTMmHXMeFfB7.Jjr.57jPJfd/Ihp49u.k8BYbfowr4W/gFfG', '13500000002', NULL, NULL, 0, 1, 0, 0, 1);

-- ============ 用户-角色绑定 ============
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.role_code IN
  ('ADMIN','SECRETARY','TEACHER','STUDENT','SUPPLIER')
WHERE (u.user_no, r.role_code) IN (
  ('900001','ADMIN'), ('900002','ADMIN'), ('900003','ADMIN'),
  ('800101','SECRETARY'), ('800102','SECRETARY'), ('800103','SECRETARY'),
  ('700101','TEACHER'), ('700102','TEACHER'), ('700103','TEACHER'),
  ('700103','SECRETARY'),
  ('700201','TEACHER'), ('700202','TEACHER'),
  ('20230101','STUDENT'), ('20230102','STUDENT'), ('20230103','STUDENT'), ('20230201','STUDENT'),
  ('600001','SUPPLIER'), ('600002','SUPPLIER'));

-- ============ 按学期归属（active 学期，真源 W6） ============
INSERT INTO user_semester_profile (user_id, semester_id, college_id, class_id, status)
SELECT u.id, s.id, u.college_id, u.class_id, 1
FROM sys_user u JOIN semester s ON s.active_status = 'active'
WHERE u.user_no IN ('800101','800102','800103','700101','700102','700103','700201','700202',
                    '20230101','20230102','20230103','20230201');

-- ============ 教材库（跨学期共用） ============
INSERT INTO textbook (isbn, title, edition, author, press, price, status) VALUES
('9787302517993', '数据结构（C语言版）', '第2版', '严蔚敏', '清华大学出版社', 49.00, 1),
('9787111421641', '计算机网络', '第7版', '谢希仁', '电子工业出版社', 59.00, 1),
('9787040418248', '大学英语综合教程', '第3版', '季佩英', '高等教育出版社', 45.00, 1),
('9787040509762', '新编日语教程', '第4版', '周平', '华东师范大学出版社', 42.00, 1);

-- ============ 课程（active 学期） ============
INSERT INTO course (semester_id, code, name)
SELECT s.id, c.code, c.name FROM semester s
JOIN (SELECT 'CS101' AS code, '数据结构' AS name
      UNION ALL SELECT 'CS102', '计算机网络'
      UNION ALL SELECT 'EN201', '大学英语'
      UNION ALL SELECT 'JP202', '日语视听说') c
WHERE s.active_status = 'active';

-- ============ 任课关系（W17：征订范围即此表） ============
INSERT INTO teacher_course (semester_id, teacher_id, course_id, class_id)
SELECT s.id, u.id, c.id, k.id
FROM semester s
JOIN sys_user u ON u.user_no IN ('700101','700102','700201')
JOIN course c ON c.semester_id = s.id
JOIN school_class k ON k.name IN ('软工2023-1','软工2023-2','英语2023-1')
WHERE s.active_status = 'active'
  AND ((u.user_no='700101' AND c.code='CS101' AND k.name='软工2023-1')
    OR (u.user_no='700102' AND c.code='CS102' AND k.name='软工2023-2')
    OR (u.user_no='700201' AND c.code='EN201' AND k.name='英语2023-1'));
