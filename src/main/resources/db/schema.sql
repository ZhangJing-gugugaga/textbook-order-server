-- ============================================================================
-- 教材征订系统 · 全量 DDL（SPEC §3，契约冻结）
-- 公共字段（W9）：id / created_at / updated_at / created_by / updated_by /
--                deleted BIGINT DEFAULT 0（0=未删，删除写时间戳）/ reserve1~6
-- 唯一键一律含 deleted，规避逻辑删除后同键数据无法重建
--
-- 幂等性：全部 CREATE TABLE 均为 IF NOT EXISTS，配合 local profile 的
--         sql.init.mode=always 可重复执行（此前二次启动必然因 Table already exists 失败）。
--         注意：本脚本只负责「建表」，不做列级迁移；已有库的结构变更需走独立迁移脚本。
-- 排序规则：显式 COLLATE=utf8mb4_general_ci，避免随服务端默认（utf8mb4_0900_ai_ci 等）
--         变化导致 user_no/isbn 的大小写敏感性与唯一约束行为在环境间不一致。
-- ============================================================================
SET NAMES utf8mb4;

-- ============ 1. 账号与权限 ============
CREATE TABLE IF NOT EXISTS sys_user (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  user_no              VARCHAR(32)  NOT NULL COMMENT '学号/工号，登录名',
  name                 VARCHAR(64)  NOT NULL,
  password_hash        VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  phone                VARCHAR(20)  DEFAULT NULL COMMENT '首登校验用（后4位比对）',
  college_id           BIGINT       DEFAULT NULL COMMENT '当前 active 学期归属（冗余，真源见 user_semester_profile）',
  class_id             BIGINT       DEFAULT NULL COMMENT '同上，学生适用',
  openid               VARCHAR(64)  DEFAULT NULL COMMENT '仅订阅消息推送用，不作认证',
  status               TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  must_change_password TINYINT      NOT NULL DEFAULT 1,
  first_login_verified TINYINT      NOT NULL DEFAULT 0 COMMENT '首登校验（手机号后4位/openid）是否通过',
  fail_count           INT          NOT NULL DEFAULT 0 COMMENT '登录失败计数（落库，W20）',
  lock_until           DATETIME(3)  DEFAULT NULL COMMENT '锁定截止时间',
  role_version         INT          NOT NULL DEFAULT 1 COMMENT '角色版本号，变更即失效旧 token',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_no (user_no, deleted),
  KEY idx_user_college (college_id), KEY idx_user_class (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账号';

CREATE TABLE IF NOT EXISTS sys_user_token (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(128) NOT NULL COMMENT 'refresh token 的 SHA-256',
  expire_at DATETIME(3) NOT NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  device_id VARCHAR(64) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash, deleted),
  KEY idx_token_user (user_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='refresh 令牌（W21）';

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_code VARCHAR(32) NOT NULL COMMENT 'ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER',
  role_name VARCHAR(64) NOT NULL, sort INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_code (role_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  perm_code VARCHAR(64) NOT NULL COMMENT '模块:业务:操作',
  perm_name VARCHAR(64) NOT NULL, module VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_perm_code (perm_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限码（37 条种子见 db/data-permission.sql）';

CREATE TABLE IF NOT EXISTS sys_user_role (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), created_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_user_role (user_id, role_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户-角色（多角色 = 多行）';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id BIGINT NOT NULL AUTO_INCREMENT, role_id BIGINT NOT NULL, perm_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), created_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_perm (role_id, perm_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-权限';

-- ============ 2. 组织与学期 ============
CREATE TABLE IF NOT EXISTS college (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(64) NOT NULL, full_name VARCHAR(128) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_college_name (name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学院';

CREATE TABLE IF NOT EXISTS major (
  id BIGINT NOT NULL AUTO_INCREMENT, college_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
  full_name VARCHAR(128) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_major (college_id, name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='专业';

CREATE TABLE IF NOT EXISTS school_class (
  id BIGINT NOT NULL AUTO_INCREMENT, major_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
  grade VARCHAR(16) DEFAULT NULL, full_name VARCHAR(128) DEFAULT NULL,
  student_count INT NOT NULL DEFAULT 0 COMMENT '班级人数：数量上限来源（W2）；导入名单时计算或随模板带出',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_class (major_id, name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='行政班（无教学班，需求 14）';

CREATE TABLE IF NOT EXISTS semester (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL, start_date DATE DEFAULT NULL, end_date DATE DEFAULT NULL,
  window_start DATETIME(3) DEFAULT NULL, window_end DATETIME(3) DEFAULT NULL,
  channel_open TINYINT NOT NULL DEFAULT 0 COMMENT '窗口总开关',
  auto_open TINYINT NOT NULL DEFAULT 1, auto_close TINYINT NOT NULL DEFAULT 1,
  window_status VARCHAR(16) NOT NULL DEFAULT 'not_open' COMMENT 'not_open/open/closed：落库唯一真源（W11）',
  active_status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/active/archived',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  active_flag TINYINT GENERATED ALWAYS AS (IF(active_status='active',1,NULL)) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_semester_name (name, deleted),
  UNIQUE KEY uk_semester_active (active_flag) COMMENT '同刻仅一个 active（DB 层保证，W1）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学期（含窗口引擎字段）';

CREATE TABLE IF NOT EXISTS user_semester_profile (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, semester_id BIGINT NOT NULL,
  college_id BIGINT DEFAULT NULL, class_id BIGINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '该学期是否在册（导入比对结果）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_usp (user_id, semester_id, deleted),
  KEY idx_usp_sem_college (semester_id, college_id), KEY idx_usp_sem_class (semester_id, class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='按学期归属真源（W6，双缓冲的关键）';

-- ============ 3. 教材与课程 ============
CREATE TABLE IF NOT EXISTS textbook (
  id BIGINT NOT NULL AUTO_INCREMENT, isbn VARCHAR(20) NOT NULL, title VARCHAR(200) NOT NULL,
  edition VARCHAR(64) DEFAULT NULL, author VARCHAR(128) DEFAULT NULL, press VARCHAR(128) DEFAULT NULL,
  price DECIMAL(10,2) DEFAULT NULL, status TINYINT NOT NULL DEFAULT 1 COMMENT '1在库 0停用',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_textbook_isbn (isbn, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='教材库（跨学期共用，B5）';

CREATE TABLE IF NOT EXISTS course (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  code VARCHAR(32) DEFAULT NULL, name VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_course (semester_id, code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程（学期域）';

CREATE TABLE IF NOT EXISTS teacher_course (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL, course_id BIGINT NOT NULL, class_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_tc (semester_id, teacher_id, course_id, class_id, deleted),
  KEY idx_tc_class (semester_id, class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='任课关系（学期域；征订范围即此表，W17）';

-- ============ 4. 征订业务 ============
CREATE TABLE IF NOT EXISTS order_form (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'draft'
    COMMENT 'draft/submitted/rejected_auto/rejected/pending_review/reviewed',
  field_check_result JSON DEFAULT NULL COMMENT '[{field,rule,message}]（契约冻结项）',
  review_by BIGINT DEFAULT NULL, review_at DATETIME(3) DEFAULT NULL, review_note VARCHAR(200) DEFAULT NULL,
  submitted_at DATETIME(3) DEFAULT NULL, correct_deadline DATETIME(3) DEFAULT NULL COMMENT '补正截止（关窗后 7 天，W4）',
  content_version INT NOT NULL DEFAULT 0 COMMENT '内容版本：教师每次整单覆盖 +1，审核 CAS 谓词之一（防审核对象漂移）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_form (semester_id, teacher_id, deleted) COMMENT '一人一学期一单（W9）',
  KEY idx_form_sem (semester_id, status),
  KEY idx_form_teacher (teacher_id, id) COMMENT '本人历史提交（selectTeacherForms：WHERE teacher_id ORDER BY id DESC）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='教师征订单';

CREATE TABLE IF NOT EXISTS order_form_item (
  id BIGINT NOT NULL AUTO_INCREMENT, form_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL, class_id BIGINT NOT NULL, textbook_id BIGINT NOT NULL,
  quantity INT NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_item (form_id, course_id, class_id, textbook_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='征订明细（课程×班级×教材×数量）';

CREATE TABLE IF NOT EXISTS student_order (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/submitted',
  submit_snapshot JSON DEFAULT NULL COMMENT '{collegeId,collegeName,classId,className}（提交时归属快照）',
  submitted_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stu_order (semester_id, student_id, deleted) COMMENT '一人一学期一单（W9）',
  KEY idx_stu_order_student (student_id, id) COMMENT '本人历史选购（selectStudentOrders：WHERE student_id ORDER BY id DESC）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学生选购单';

CREATE TABLE IF NOT EXISTS student_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, textbook_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1, course_id BIGINT DEFAULT NULL COMMENT '教材来源课程（可追溯）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_stu_item (order_id, textbook_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='选购明细';

CREATE TABLE IF NOT EXISTS change_request (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'student/teacher', target_user_id BIGINT NOT NULL,
  payload_json JSON NOT NULL COMMENT '变更前后值；批量时逐行一条',
  status VARCHAR(24) NOT NULL DEFAULT 'pending_field_check'
    COMMENT 'pending_field_check/pending_review/approved/rejected',
  field_check_result JSON DEFAULT NULL,
  batch_no VARCHAR(40) DEFAULT NULL COMMENT '批量导入批次号（Q10）',
  applicant_id BIGINT NOT NULL, reviewer_id BIGINT DEFAULT NULL, review_at DATETIME(3) DEFAULT NULL,
  reason VARCHAR(200) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_change_batch (batch_no), KEY idx_change_status (semester_id, status),
  KEY idx_change_applicant (applicant_id, id) COMMENT '我的提交记录（selectMyRequests：WHERE applicant_id ORDER BY id DESC）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='异动审批（逐条/批量同链）';

-- ============ 5. 导入导出 ============
CREATE TABLE IF NOT EXISTS import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(32) NOT NULL
    COMMENT 'student/teacher/textbook/teacher_course/change',
  semester_id BIGINT DEFAULT NULL, file_name VARCHAR(255) DEFAULT NULL, file_path VARCHAR(255) DEFAULT NULL,
  total INT NOT NULL DEFAULT 0, ok_count INT NOT NULL DEFAULT 0, error_count INT NOT NULL DEFAULT 0,
  progress_pct INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'running'
    COMMENT 'running/done/failed',
  error_detail JSON DEFAULT NULL, error_file_path VARCHAR(255) DEFAULT NULL,
  batch_no VARCHAR(40) DEFAULT NULL COMMENT '异动批次号（与 change_request.batch_no 对应）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_batch (biz_type, status), KEY idx_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='导入批次';

CREATE TABLE IF NOT EXISTS export_task (
  id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(32) NOT NULL
    COMMENT 'order/signature/student/notice/supplier',
  params_json JSON DEFAULT NULL, row_estimate INT DEFAULT NULL,
  file_path VARCHAR(255) DEFAULT NULL, download_token VARCHAR(64) DEFAULT NULL,
  token_expire_at DATETIME(3) DEFAULT NULL, expires_at DATETIME(3) DEFAULT NULL COMMENT '文件保留 24 小时',
  status VARCHAR(16) NOT NULL DEFAULT 'queued' COMMENT 'queued/running/done/failed/expired',
  progress_pct INT NOT NULL DEFAULT 0, error_msg VARCHAR(255) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_export_status (status), KEY idx_export_token (download_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='导出任务（Q16/W18）';

-- ============ 6. 通知 ============
CREATE TABLE IF NOT EXISTS notice_task (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL, content VARCHAR(500) NOT NULL,
  target_roles VARCHAR(64) NOT NULL DEFAULT 'STUDENT' COMMENT '逗号分隔角色码',
  round_limit INT NOT NULL DEFAULT 5 COMMENT '创建时快照，执行依据=system_config（W8）',
  interval_hours INT NOT NULL DEFAULT 24 COMMENT '同上',
  source VARCHAR(24) NOT NULL DEFAULT 'manual' COMMENT 'manual/system_window_change',
  status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
  closed_by BIGINT DEFAULT NULL, closed_at DATETIME(3) DEFAULT NULL,
  active_flag TINYINT GENERATED ALWAYS AS (IF(status='active',1,NULL)) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_task (semester_id, status),
  UNIQUE KEY uk_task_active (semester_id, active_flag, deleted)
    COMMENT '同学期至多 1 个 active 任务（W18，DB 层兜底；NULL 不参与唯一性，故 closed 行不受限）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知任务';

CREATE TABLE IF NOT EXISTS notice_record (
  id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, user_id BIGINT NOT NULL COMMENT '主体=用户（含教师/秘书，W7）',
  round_no INT DEFAULT NULL COMMENT '第几轮（确认记录为空）',
  sent_at DATETIME(3) DEFAULT NULL,
  send_status VARCHAR(16) DEFAULT NULL COMMENT 'sent/unauthorized/failed',
  confirmed_at DATETIME(3) DEFAULT NULL,
  confirm_flag TINYINT GENERATED ALWAYS AS (IF(confirmed_at IS NOT NULL AND deleted = 0, 1, NULL)) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_notice_round (task_id, user_id, round_no, deleted),
  UNIQUE KEY uk_notice_confirm (task_id, user_id, confirm_flag)
    COMMENT '确认记录唯一（round_no 为 NULL 不参与 uk_notice_round 唯一性，故单列生成列兜底；并发重复确认只会插入一行）',
  KEY idx_notice_confirm (task_id, user_id, confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知历史（按学期归档）';

-- ============ 7. 治理 ============
CREATE TABLE IF NOT EXISTS system_config (
  id BIGINT NOT NULL AUTO_INCREMENT, config_key VARCHAR(64) NOT NULL, config_value VARCHAR(255) NOT NULL,
  remark VARCHAR(255) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_config_key (config_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统配置（重发参数唯一真源，W8）';

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT DEFAULT NULL, user_no VARCHAR(32) DEFAULT NULL,
  action VARCHAR(64) NOT NULL COMMENT 'LOGIN/EXPORT/ACCOUNT/WINDOW/SEMESTER_SWITCH/REVIEW/CHANGE/CONFIG…',
  resource VARCHAR(64) DEFAULT NULL, resource_id VARCHAR(64) DEFAULT NULL,
  detail_json JSON DEFAULT NULL, ip VARCHAR(45) DEFAULT NULL, at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), KEY idx_audit_user (user_id, at), KEY idx_audit_action (action, at),
  KEY idx_audit_resource (resource, resource_id, at) COMMENT '窗口变更记录查询（selectByResource）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审计日志（只写不改；不含密码/token）';
