# 教材征订系统 · 服务端技术规格（SPEC · textbook-order-server）

> 版本 V1.0.1 · 2026-09-23 · 依据：本仓库 `PRD.md`（V1.2.0）、`03-后端开发计划与决策.md`（v3 复审修订版）、`docs/01`（需求决策）、`docs/05`（复审 16 问）、`docs/06`（W1-W24 追问回执）
> 定位：PRD 说「做什么/为什么」，本文说「**怎么实现**」——DDL、接口清单、鉴权/隔离/双缓冲/通知的实现方式、事务边界、配置项、测试与部署。里程碑、分工与验收标准见 03 号文档 §12。
> **权威性声明（重要）**：系统已上线并冻结契约，**唯一契约源是 `API.md` V1.1.x + `GET /v3/api-docs`**；本文为**设计说明文档**（记录当初的设计意图与取舍），实现细节（枚举取值、状态机、调度节奏、配置键、端点清单）**以代码与 `API.md` 为准**。本文与实现冲突时，按「以代码改文档」处理并更新本文；`PRD.md` 为需求文档（V1.2.0），冲突时同样以 `API.md` + 代码为准。
> 冲突优先级：本文与 03 v3 冲突时以 03 v3 为准；与 `PRD.md` 冲突时以 PRD 为准并回改本文。
> 约定：接口路径为**契约基线**，M1 末由 springdoc-openapi 生成 OpenAPI 3 冻结（03 §14）；冻结后变更走「变更记录 + 双方确认」（变更记录见 `API.md` §6）。
> **一致性门禁**：`node scripts/check-api-md.mjs` 校验「§11 端点集合 == 代码实际端点（method + path）」「`API.md` 标题计数 == 表格行数」「错误码/枚举表覆盖代码常量」，已纳入 `scripts/verify-be-2026-09-23.sh`。

---

## 1. 技术栈与锁定版本（03 §1）

| 层 | 选型 | 锁定版本 | 备注 |
|----|------|----------|------|
| 语言/框架 | Java 17 (LTS) + Spring Boot | 3.3.x（M1 落地锁补丁号入 pom） | 单模块、单体分层 |
| ORM | MyBatis-Plus（`mybatis-plus-spring-boot3-starter`） | 3.5.x | DataPermissionInterceptor 数据权限插件 |
| DB | MySQL | 8.0.36+ | utf8mb4 / utf8mb4_0900_ai_ci |
| Excel | EasyExcel | 3.3.x | 流式读写，防 OOM |
| 定时任务 | Spring `@Scheduled` + 进程内锁 | 随 Boot | 单机；多实例时换 xxl-job |
| 鉴权 | Spring Security 过滤器链 + jjwt | 0.12.x | access + refresh 双令牌 |
| 微信 | weixin-java-miniapp | 4.6.x | code2session / access_token 集中管理 / 订阅消息 |
| 缓存 | Caffeine | 3.1.x | 仅限频加速与只读缓存（不承载业务真源） |
| 契约 | springdoc-openapi | 2.6.x | OpenAPI 3 为唯一契约源 |
| 构建 | Maven | 3.9+（`mvnw` 入库） | 产物 = 可执行 jar |
| 测试 | JUnit 5 + Spring Boot Test + Testcontainers(MySQL) | 1.20.x | 切片 + 集成 |

## 2. 工程结构与分层

```
textbook-order-server/
├─ PRD.md / SPEC.md / 03-后端开发计划与决策.md     # 需求 / 规格 / 计划三件套
├─ pom.xml / mvnw / mvnw.cmd
├─ src/main/resources/
│  ├─ application.yml / application-local.yml / application-trial.yml / application-school.yml
│  ├─ db/schema.sql                                  # §3 DDL（初始化脚本）
│  ├─ db/data-permission.sql                         # sys_permission 权限码种子（39 条：M1 冻结 37 + BE-2 新增 2，role:manage/role:permission:assign）
│  ├─ db/data-seed.sql                               # 种子数据（M1 交付物）
│  └─ templates/                                     # 5 张 Excel 导入模板 + 签字版导出模板
└─ src/main/java/com/tian/textbook/
   ├─ auth/          # 登录、JWT(access+refresh)、首登校验、密码策略、强制改密拦截
   ├─ system/        # RBAC 五表、账号、组织三表、审计（切面 + 查询）、系统配置
   ├─ semester/      # 学期、窗口引擎、双缓冲切换、按学期归属
   ├─ textbook/      # 教材库、课程、任课关系
   ├─ order/         # 教师征订单（两级审查）、学生选购
   ├─ approval/      # 异动两级审批（逐条 + 批量）
   ├─ importexport/  # Excel 导入中心（异步批次）、导出中心（同步/异步 + 一次性 token）
   ├─ notify/        # 通知任务、订阅消息适配、确认追踪、重发调度
   ├─ supplier/      # 供货商只读接口（物理隔离）
   └─ common/        # 统一响应、异常、审计注解、@WithinWindow、学期上下文
```

**分层约束**（`layered-architecture` skill）：`Controller → Service → Mapper`，业务逻辑不下沉到 Controller、不跨层调用；DTO/VO 与 Entity 分离；供货商模块**包内禁止 import 学生/教师相关 Mapper**。

**机检红线（CI 脚本 / ArchUnit）**：
1. `supplier` 包的 import 列表中不得出现 `order.*Mapper`（学生选购）、`system.*UserMapper`；
2. `common` 包不得 import 任何业务 Mapper；
3. 命中即构建失败（PRD 模块 2「评审机检」）。

## 3. 数据库 DDL（全量 · 初始化脚本 `db/schema.sql`）

**公共字段约定（W9）**：每表含 `id BIGINT PK AUTO_INCREMENT`、`created_at`、`updated_at`、`created_by`、`updated_by`、`deleted BIGINT NOT NULL DEFAULT 0`（0=未删，删除时写时间戳）、`reserve1 ~ reserve6 VARCHAR(255) DEFAULT NULL`。
**逻辑删除与唯一约束**：唯一键一律包含 `deleted`，规避「逻辑删除后无法重建同键数据」。

```sql
-- ============ 1. 账号与权限 ============
CREATE TABLE sys_user (
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
  lock_until           DATETIME     DEFAULT NULL COMMENT '锁定截止时间',
  role_version         INT          NOT NULL DEFAULT 1 COMMENT '角色版本号，变更即失效旧 token',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_no (user_no, deleted),
  KEY idx_user_college (college_id), KEY idx_user_class (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号';

CREATE TABLE sys_user_token (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(128) NOT NULL COMMENT 'refresh token 的 SHA-256',
  expire_at DATETIME NOT NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  device_id VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash),
  KEY idx_token_user (user_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='refresh 令牌（W21）';

CREATE TABLE sys_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_code VARCHAR(32) NOT NULL COMMENT 'ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER',
  role_name VARCHAR(64) NOT NULL, sort INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_code (role_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE sys_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  perm_code VARCHAR(64) NOT NULL COMMENT '模块:业务:操作',
  perm_name VARCHAR(64) NOT NULL, module VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_perm_code (perm_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限码（39 条种子见 db/data-permission.sql）';

CREATE TABLE sys_user_role (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_user_role (user_id, role_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色（多角色 = 多行）';

CREATE TABLE sys_role_permission (
  id BIGINT NOT NULL AUTO_INCREMENT, role_id BIGINT NOT NULL, perm_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT DEFAULT NULL,
  deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_perm (role_id, perm_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限';

-- ============ 2. 组织与学期 ============
CREATE TABLE college (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(64) NOT NULL, full_name VARCHAR(128) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_college_name (name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学院';

CREATE TABLE major (
  id BIGINT NOT NULL AUTO_INCREMENT, college_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
  full_name VARCHAR(128) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_major (college_id, name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业';

CREATE TABLE school_class (
  id BIGINT NOT NULL AUTO_INCREMENT, major_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
  grade VARCHAR(16) DEFAULT NULL, full_name VARCHAR(128) DEFAULT NULL,
  student_count INT NOT NULL DEFAULT 0 COMMENT '班级人数：数量上限来源（W2）；导入名单时计算或随模板带出',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_class (major_id, name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行政班（无教学班，需求 14）';

CREATE TABLE semester (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL, start_date DATE DEFAULT NULL, end_date DATE DEFAULT NULL,
  window_start DATETIME DEFAULT NULL, window_end DATETIME DEFAULT NULL,
  channel_open TINYINT NOT NULL DEFAULT 0 COMMENT '窗口总开关',
  auto_open TINYINT NOT NULL DEFAULT 1, auto_close TINYINT NOT NULL DEFAULT 1,
  window_status VARCHAR(16) NOT NULL DEFAULT 'not_open' COMMENT 'not_open/open/closed：落库唯一真源（W11）',
  active_status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/active/archived',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  active_flag TINYINT GENERATED ALWAYS AS (IF(active_status='active',1,NULL)) STORED,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_semester_name (name, deleted),
  UNIQUE KEY uk_semester_active (active_flag) COMMENT '同刻仅一个 active（DB 层保证，W1）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学期（含窗口引擎字段）';

CREATE TABLE user_semester_profile (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, semester_id BIGINT NOT NULL,
  college_id BIGINT DEFAULT NULL, class_id BIGINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '该学期是否在册（导入比对结果）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_usp (user_id, semester_id, deleted),
  KEY idx_usp_sem_college (semester_id, college_id), KEY idx_usp_sem_class (semester_id, class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按学期归属真源（W6，双缓冲的关键）';

-- ============ 3. 教材与课程 ============
CREATE TABLE textbook (
  id BIGINT NOT NULL AUTO_INCREMENT, isbn VARCHAR(20) NOT NULL, title VARCHAR(200) NOT NULL,
  edition VARCHAR(64) DEFAULT NULL, author VARCHAR(128) DEFAULT NULL, press VARCHAR(128) DEFAULT NULL,
  price DECIMAL(10,2) DEFAULT NULL, status TINYINT NOT NULL DEFAULT 1 COMMENT '1在库 0停用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  reserve1 VARCHAR(255) DEFAULT NULL, reserve2 VARCHAR(255) DEFAULT NULL, reserve3 VARCHAR(255) DEFAULT NULL,
  reserve4 VARCHAR(255) DEFAULT NULL, reserve5 VARCHAR(255) DEFAULT NULL, reserve6 VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_textbook_isbn (isbn, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教材库（跨学期共用，B5）';

CREATE TABLE course (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  code VARCHAR(32) DEFAULT NULL, name VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_course (semester_id, code, deleted), KEY idx_course_sem (semester_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程（学期域）';

CREATE TABLE teacher_course (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL, course_id BIGINT NOT NULL, class_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_tc (semester_id, teacher_id, course_id, class_id, deleted),
  KEY idx_tc_teacher (semester_id, teacher_id), KEY idx_tc_class (semester_id, class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任课关系（学期域；征订范围即此表，W17）';

-- ============ 4. 征订业务 ============
CREATE TABLE order_form (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'draft'
    COMMENT 'draft/pending_review/reviewed（终态）/rejected/rejected_auto；submitted 为历史死值（BE-8）',
  field_check_result JSON DEFAULT NULL COMMENT '[{field,rule,message}]（契约冻结项）',
  review_by BIGINT DEFAULT NULL, review_at DATETIME DEFAULT NULL, review_note VARCHAR(200) DEFAULT NULL,
  submitted_at DATETIME DEFAULT NULL, correct_deadline DATETIME DEFAULT NULL COMMENT '补正截止（关窗后 7 天，W4）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_form (semester_id, teacher_id, deleted) COMMENT '一人一学期一单（W9）',
  KEY idx_form_sem (semester_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师征订单';

CREATE TABLE order_form_item (
  id BIGINT NOT NULL AUTO_INCREMENT, form_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL, class_id BIGINT NOT NULL, textbook_id BIGINT NOT NULL,
  quantity INT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_item (form_id, course_id, class_id, textbook_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='征订明细（课程×班级×教材×数量）';

CREATE TABLE student_order (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/submitted',
  submit_snapshot JSON DEFAULT NULL COMMENT '{collegeId,collegeName,classId,className}（提交时归属快照）',
  submitted_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stu_order (semester_id, student_id, deleted) COMMENT '一人一学期一单（W9）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生选购单';

CREATE TABLE student_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, textbook_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1, course_id BIGINT DEFAULT NULL COMMENT '教材来源课程（可追溯）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_stu_item (order_id, textbook_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选购明细';

CREATE TABLE change_request (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'student/teacher', target_user_id BIGINT NOT NULL,
  payload_json JSON NOT NULL COMMENT '变更前后值；批量时逐行一条',
  status VARCHAR(24) NOT NULL DEFAULT 'pending_field_check'
    COMMENT 'pending_review/approved/rejected；pending_field_check 为历史值（字段审查已改为提交时同步完成，BE-7d）',
  field_check_result JSON DEFAULT NULL,
  batch_no VARCHAR(40) DEFAULT NULL COMMENT '批量导入批次号（Q10）',
  applicant_id BIGINT NOT NULL, reviewer_id BIGINT DEFAULT NULL, review_at DATETIME DEFAULT NULL,
  reason VARCHAR(200) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_change_batch (batch_no), KEY idx_change_status (semester_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异动审批（逐条/批量同链）';

-- ============ 5. 导入导出 ============
CREATE TABLE import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(32) NOT NULL
    COMMENT 'student/teacher/textbook/teacher_course/change',
  semester_id BIGINT DEFAULT NULL, file_name VARCHAR(255) DEFAULT NULL, file_path VARCHAR(255) DEFAULT NULL,
  total INT NOT NULL DEFAULT 0, ok_count INT NOT NULL DEFAULT 0, error_count INT NOT NULL DEFAULT 0,
  progress_pct INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'running'
    COMMENT 'running/done/failed',
  error_detail JSON DEFAULT NULL, error_file_path VARCHAR(255) DEFAULT NULL,
  batch_no VARCHAR(40) DEFAULT NULL COMMENT '异动批次号（与 change_request.batch_no 对应）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_batch (biz_type, status), KEY idx_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次';

CREATE TABLE export_task (
  id BIGINT NOT NULL AUTO_INCREMENT, biz_type VARCHAR(32) NOT NULL
    COMMENT 'order/signature/student/notice/supplier',
  params_json JSON DEFAULT NULL, row_estimate INT DEFAULT NULL,
  file_path VARCHAR(255) DEFAULT NULL, download_token VARCHAR(64) DEFAULT NULL,
  token_expire_at DATETIME DEFAULT NULL, expires_at DATETIME DEFAULT NULL COMMENT '文件保留 24 小时',
  status VARCHAR(16) NOT NULL DEFAULT 'queued' COMMENT 'queued/running/done/failed/expired',
  progress_pct INT NOT NULL DEFAULT 0, error_msg VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_export_status (status), KEY idx_export_token (download_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出任务（Q16/W18）';

-- ============ 6. 通知 ============
CREATE TABLE notice_task (
  id BIGINT NOT NULL AUTO_INCREMENT, semester_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL, content VARCHAR(500) NOT NULL,
  target_roles VARCHAR(64) NOT NULL DEFAULT 'STUDENT' COMMENT '逗号分隔角色码',
  round_limit INT NOT NULL DEFAULT 5 COMMENT '创建时快照，执行依据=system_config（W8）',
  interval_hours INT NOT NULL DEFAULT 24 COMMENT '同上',
  source VARCHAR(24) NOT NULL DEFAULT 'manual' COMMENT 'manual/system_window_change',
  status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
  closed_by BIGINT DEFAULT NULL, closed_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_task (semester_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知任务';

CREATE TABLE notice_record (
  id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, user_id BIGINT NOT NULL COMMENT '主体=用户（含教师/秘书，W7）',
  round_no INT DEFAULT NULL COMMENT '第几轮（确认记录为空）',
  sent_at DATETIME DEFAULT NULL,
  send_status VARCHAR(24) DEFAULT NULL COMMENT 'sent/unauthorized/failed/confirmed/confirmed_by_entry',
  confirmed_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_notice_round (task_id, user_id, round_no, deleted),
  KEY idx_notice_confirm (task_id, user_id, confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知历史（按学期归档）';

-- ============ 7. 治理 ============
CREATE TABLE system_config (
  id BIGINT NOT NULL AUTO_INCREMENT, config_key VARCHAR(64) NOT NULL, config_value VARCHAR(255) NOT NULL,
  remark VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by BIGINT DEFAULT NULL, updated_by BIGINT DEFAULT NULL, deleted BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_config_key (config_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置（重发参数唯一真源，W8）';

CREATE TABLE audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT DEFAULT NULL, user_no VARCHAR(32) DEFAULT NULL,
  action VARCHAR(64) NOT NULL COMMENT 'LOGIN/EXPORT/ACCOUNT/WINDOW/SEMESTER_SWITCH/REVIEW/CHANGE/CONFIG…',
  resource VARCHAR(64) DEFAULT NULL, resource_id VARCHAR(64) DEFAULT NULL,
  detail_json JSON DEFAULT NULL, ip VARCHAR(45) DEFAULT NULL, at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_audit_user (user_id, at), KEY idx_audit_action (action, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志（只写不改；不含密码/token）';
```

## 4. 鉴权与会话实现（W19/W20/W21 · Q6）

```
Spring Security 过滤器链：
  JwtAuthFilter（解析 access → 校验签名/过期 → 比对 role_version 与用户状态 → 写 SecurityContext）
  → MustChangePasswordFilter（must_change_password=1 或 first_login_verified=0 时，**仅放行显式枚举的 4 个路径**：
      POST /api/auth/login、POST /api/auth/refresh、POST /api/auth/first-login/verify、POST /api/auth/logout，
      外加前缀 /api/me（含改密 PUT /api/me/password）；其余业务接口一律 403 FIRST_LOGIN_REQUIRED）
      ⚠ **不得写成 /api/auth/** 通配**：该前缀会连带放行 POST /api/auth/switch-role（重新签发 access/refresh），
        等于让未完成首登校验的会话换到一份新的长效令牌（实现见 MustChangePasswordFilter.ALLOWED_PATHS，
        契约表述见 API.md §1.4；单测见 FirstLoginSecurityIntegrationTest）
  → @PreAuthorize("hasAuthority('order:form:review')") 方法级权限码校验
```

| 项 | 实现 |
|----|------|
| access token | 15 分钟；载荷 = userId、userNo、roles、currentRole、roleVersion；不落库 |
| refresh token | 7 天；随机串的 SHA-256 落 `sys_user_token`；**轮换**（旧行置 `revoked=1`，发新行） |
| 撤销时机 | 登出、停用、改密、角色变更（`role_version+1`）→ 该用户全部 refresh 置 `revoked=1` |
| 401 细分（契约冻结项） | ① `TOKEN_EXPIRED`（access 过期，可 refresh 重放）② `REFRESH_INVALID`（refresh 失效/角色版本失效，强制登出）③ `ACCOUNT_DISABLED`（账号停用，强制登出） |
| 登录锁定 | 失败计数 `fail_count+1`；达 5 次写 `lock_until = now+15min`；成功登录清零；Caffeine 仅做同 IP/账号 1 分钟粒度限频加速 |
| 首登流程 | 初始密码登录 → `must_change_password=1` → `POST /api/auth/first-login/verify`（手机号后 4 位 或 openid 绑定）→ 通过后 `PUT /api/me/password` 改密 → `must_change_password=0`、`first_login_verified=1` |
| 密码策略 | BCrypt(strength 10)；新密码 8-64 位且含字母与数字；与初始密码不得相同 |
| 身份切换 | `POST /api/auth/switch-role` 仅切换 `currentRole` 与返回的权限码集合，**不改数据范围**（W10）；旧 access 作废并重发 |

## 5. 数据隔离实现（W10）

```java
// MyBatis-Plus DataPermissionInterceptor + 自定义 DataPermissionHandler
// 多角色并集：OR 连接各角色范围；无角色命中则不追加条件（ADMIN）
ADMIN     → 不追加条件
SECRETARY → college_id = #{currentUserCollegeId}
TEACHER   → teacher_id = #{currentUserId}（教师维度表）/ user_id = #{currentUserId}
STUDENT   → user_id = #{currentUserId}
并集示例  → (college_id = ? OR teacher_id = ?)   // 秘书 + 教师
```

- 归属来源：`user_semester_profile`（当前 active 学期行），**不再读 `sys_user.college_id`**（后者仅为冗余展示列）。
- `@CollegeScope` 注解标注需要隔离的 Mapper 方法；**未标注即不隔离**，故新表接入时必须显式标注（CI 检查清单项）。
- 资源归属二次校验：按 id 取单条资源时，Service 层校验资源归属（防 IDOR），失败返回 403 + 审计。
- 供货商模块：独立包 + `/api/supplier/**` + 无隔离切面（物理隔离，见 §2 机检红线）。

## 6. 窗口引擎实现（W11）

| 项 | 实现 |
|----|------|
| 真源 | `semester.window_status`（落库），非推导 |
| 定时扫描 | `@Scheduled(cron = "0 * * * * ?")`，ZoneId=`Asia/Shanghai`；**仅扫 `active_status='active'`** |
| 自动开启 | `auto_open=1 && channel_open=1 && window_status='not_open' && now >= window_start` → `open` |
| 自动截止 | `auto_close=1 && window_status='open' && now >= window_end` → `closed` |
| auto 关闭时 | 到点**不改状态**（保留手动控制） |
| 幂等 | 状态已变则跳过；扫描前后比对 `version`；进程内 `ReentrantLock` 串行化窗口变更与学期切换 |
| 延长 | 更新 `window_end`；若 `closed` 则同时 `channel_open=1` + `window_status='open'`；无限次；写审计 |
| 提前截止 | `window_status='closed'`（`channel_open=0`）；写审计 |
| 自动通知 | 每次变更调用 `NotifyService.onWindowChange(...)`：**合并进本学期 active 任务**（无则创建，source=`system_window_change`），内容含本次延长时间与新截止时间 |
| 校验注解 | `@WithinWindow` 挂在填报/选购**提交**接口；`window_status='open' && channel_open=1` 才放行；**例外：被驳回表单补正（W4）**；违规返回 409 |
| 服务器时间 | `GET /api/semester/window/status` 返回 `serverTime`，前端据此算倒计时（PRD 功能 2） |

## 7. 双缓冲实现（W1/W6）

```
学期域表（带 semester_id）：course / teacher_course / order_form / order_form_item /
  student_order / student_order_item / notice_task / notice_record / user_semester_profile / change_request
跨学期共用：college / major / school_class / textbook / sys_user / sys_role / sys_permission / system_config

导入（draft 学期）：
  写学期域表的 draft 行 + user_semester_profile(semester_id=draft)
  ※ 绝不写 sys_user.college_id/class_id（避免污染 active 学期归属）

切换（POST /api/admin/semester/{id}/activate，body 带 version）：
  @Transactional（单事务，REQUIRED）
    ① SELECT ... FOR UPDATE 旧 active 学期，校验 active_status='active'
    ② 校验目标学期 active_status='draft' 且 version 匹配（乐观锁）
    ③ UPDATE semester SET active_status='archived' WHERE id = 旧 active
    ④ UPDATE semester SET active_status='active'   WHERE id = 目标
    ⑤ 由 user_semester_profile 同步 sys_user.college_id/class_id（冗余列）
    ⑥ 写 audit_log(action='SEMESTER_SWITCH')
  失败（version 冲突 / 唯一约束 uk_semester_active 命中）→ 回滚 + 409「存在更新的学期状态，请刷新」

归档：仅置 active_status='archived'，数据只读保留（可查可导），不做物理迁移（D2-A）
在途请求：请求进入时把 activeSemesterId 写入请求上下文（ThreadLocal），全程使用该快照；
         切换持同一把锁，窗口扫描与提交在切换期间排队（R12）
```

## 8. 两级审查实现

**字段审查引擎**（`FieldCheckService`，白名单规则集，规则文档化进 OpenAPI 描述）：

| 规则码 | 校验 | 错误文案 |
|--------|------|----------|
| `REQUIRED` | 必填完整（课程/班级/教材/数量） | 「第 N 行：请选择教材」 |
| `QTY_RANGE` | `1 ≤ quantity ≤ 班级人数`（缺失回退 `order.quantity.max_default`，W2） | 「第 N 行：数量需在 1-N 之间」 |
| `BOOK_ACTIVE` | 教材存在且 `status=1` | 「第 N 行：教材已停用，请重新选择」 |
| `COURSE_OWNER` | 课程归属本人（`teacher_course.teacher_id`） | 「第 N 行：该课程不在您的任课范围内」 |
| `CLASS_LINK` | 课程-班级关联存在 | 「第 N 行：该课程未关联此班级」 |
| `ISBN_FORMAT` | ISBN-10/13 校验位 | 「第 N 行：ISBN 格式不正确」 |

- 输出结构（契约冻结项）：`field_check_result = [{"field":"items[2].quantity","rule":"QTY_RANGE","message":"..."}]`；任一不过 → `rejected_auto`，**逐字段**返回，教师可修复后窗口内无限次重提。
- 内容审核：`POST /api/admin/order-forms/{id}/review`，`action ∈ {pass, reject}`；`reject` 时 `review_note` 必填 1-200 字；驳回写 `correct_deadline = 窗口截止（semester.window_end，缺失则以当前时间为基准）+ order.correct_window_days`（默认 7 天，`TeacherOrderService#correctionDeadline`）。**不存在「超管关闭补正」这个概念**，也没有对应的时刻参与计算。
- 异动审批同引擎（规则集不同）：`TARGET_EXISTS` / `COLLEGE_EXISTS` / `CLASS_EXISTS`（仅 student）/ `TYPE_VALID` / `VALUE_CHANGED`；批量按行执行，`batch_no` 关联。

## 9. 通知实现（W5/W7/W8/W18）

| 项 | 实现 |
|----|------|
| 任务来源 | ① 手动（超管，同学期仅 1 个 active，重复创建返回 409 或提示合并）② 窗口变更自动（**合并进 active 任务**：追加内容 + 重置轮次计数） |
| 确认 | `GET /api/notice/unconfirmed` 返回未确认任务（含已停止重发但未确认的任务，Q7）；`POST /api/notice/{taskId}/confirm` 幂等（`notice_record` 首次写 `confirmed_at`），返回 204 |
| 订阅授权上报 | confirm 请求体可带 `subscribe_result`（accepted/rejected）；accepted 时 `sys_user.openid` 存在即可进入可发送名单 |
| 重发调度 | `@Scheduled(cron = "0 5 * * * ?")`（**每小时第 5 分钟**，Asia/Shanghai）：扫 active 任务 → 距最近一轮 ≥ `notice.interval_hours`（无发送记录的新任务立即发首轮）→ 未确认且**已授权**用户 → 订阅消息发送（仅 STUDENT） |
| 轮次与间隔 | 以 `system_config.notice.round_limit / notice.interval_hours` 为唯一真源，**两者均为实时判定**（改动对未完结任务生效：轮次立即生效，间隔最迟下一小时生效）；`notice_task` 两字段仅作创建时快照展示与追溯，不参与判定 |
| 落库 | 每次尝试写 `notice_record(round_no, sent_at, send_status)`；`send_status ∈ {sent, unauthorized, failed, confirmed, confirmed_by_entry}`（后两者为确认记录，`confirm-by-entry` 入口确认走 `confirmed_by_entry`），如实记录（不做假「已送达」） |
| 停止条件 | 达 `round_limit` → 停止**订阅消息**重发；**弹窗通道不设轮次上限**，直到用户确认或超管关闭任务 |
| 触达结论 | 一次性订阅「一次授权一条」→ 未确认者≈未授权者，重发对其基本无效（R10）；未授权名单进汇总导出的线下兜底列 |
| 汇总导出 | 学号/工号、姓名、角色、学院、班级、渠道、各轮发送时间、发送状态、确认状态、确认时间 |
| 弹窗队列 | 前端队列上限 `notice.popup_queue_max`（默认 5）；服务端按 `created_at DESC` 返回，全量在「我的」页可查 |

## 10. 导入导出实现

**导入**：`@Async("importExecutor")`（专用线程池：核心 2 / 最大 4 / 队列 50）→ EasyExcel `ReadListener` 流式解析 → 每 500 行批量 upsert + 更新 `progress_pct` → 错误行收集（不中断）→ 完成写 `ok_count/error_count` + 错误明细 JSON（可下载）。

| 模板 | 列（M1 冻结，W13） | upsert 键 | 停用比对范围（W14） |
|------|-------------------|-----------|--------------------|
| 学生全量 | 学号、姓名、学院、专业、班级、手机号 | `user_no` | 文件内学院 + STUDENT 角色 |
| 教师全量 | 工号、姓名、学院、手机号 | `user_no` | 文件内学院 + TEACHER 角色 |
| 教材库 | ISBN、书名、版次、作者、出版社、单价、状态 | `isbn` | 不适用 |
| 课程任课 | 课程代码、课程名、教师工号、班级名称、学期 | `(semester_id, teacher_id, course_id, class_id)` | 不适用 |
| 异动 | 学号/工号、变更类型、目标学院、目标班级、原因 | 无（逐行生成 change_request） | 不适用 |

**导入顺序**：学院 → 专业 → 班级 → 教师 → 课程任课 → 学生 → 异动（外键依赖）；前端按此顺序引导，服务端不强制拦截但校验外键并逐行报错。
**导入权威性**：导入可覆盖归属（写 `user_semester_profile`）；与异动结果冲突时以导入为准，结果摘要提示「覆盖 N 条异动结果」。

**导出**：

```
行数预估 ≤ export.sync_row_threshold(5000) → 同步流式下载（EasyExcel 直接写 response）
行数预估 > 阈值 → 建 export_task → 异步生成 → 前端轮询 → 一次性下载链接
下载：GET /api/export-task/{id}/download?token=xxx
  · token 单次有效（首次下载后置空）、默认 10 分钟过期（export.download_token_minutes）
  · 过期/失效返回 410「下载链接已失效，请重新导出」
  · 文件保留 24 小时，定时任务清理过期文件（expires_at）
```

**签字版模板**：`templates/secretary-signature.xlsx`（标准表格 + 签字栏三行占位：学院盖章 / 教材室签字 / 日期）；田老师样张到位后替换模板，**代码不改**。

## 11. 接口清单（契约基线 · 已与 `API.md` 逐条对齐）

> 权限码列留空表示仅需登录（或为公开接口）。统一包络 `{code, message, data}`；分页 `page`/`size`（默认 1/20，上限 200）。
>
> **本节共 111 个端点，与 `API.md` §3 及 Controller 源码逐条一致**（`node scripts/check-api-md.mjs` 按 method + path 自动比对，纳入 `verify-be` 门禁）。端点形状、字段、错误码以 `API.md` 与 `GET /v3/api-docs` 为准。
>
> ⚠ 本表**一行一个端点**（不再使用 `GET/POST/PUT /api/x[/{id}]` 这类简写），以保证脚本可比对。端点总数从契约基线冻结时的 92 增至 111：V1.0.1–3 联调补 3 个最小权限只读端点，V1.0.7 补导入预览与撤销归档，V1.1.0（BE-1~BE-8）补 14 个（含角色管理 6 个）。

### 11.1 认证与会话（8）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| POST | `/api/auth/login` | 公开 | 登录 → access + refresh + mustChangePassword + roles |
| POST | `/api/auth/refresh` | 凭 refresh | 轮换 refresh，返回新 access + 新 refresh |
| POST | `/api/auth/logout` | 登录 | 撤销本人全部 refresh |
| POST | `/api/auth/first-login/verify` | 待改密 | 首登校验（手机号后 4 位 / 校验已绑定 openid） |
| POST | `/api/auth/switch-role` | 登录 | 切换身份（返回新权限码集合，数据范围不变）；**首登待完成时不在放行清单** |
| GET | `/api/me` | 登录 | 用户信息 + 角色列表 + 当前身份 + 授权状态 + active 学期归属 |
| GET | `/api/me/permissions` | 登录 | 权限码列表（前端动态路由/菜单/`v-perm` 数据源） |
| PUT | `/api/me/password` | 登录 | 改密（改密后撤销全部 refresh 并重发） |

### 11.2 窗口与学期（13）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| GET | `/api/semester/window/status` | `semester:window:view` | `{semesterId, semesterName, windowStatus, windowStart, windowEnd, channelOpen, serverTime, activeStatus}` |
| GET | `/api/admin/semester` | `semester:semester:manage` | 学期列表（draft/active/archived） |
| GET | `/api/admin/semester/{id}` | `semester:semester:manage` | 学期详情（含 `version`，供乐观锁回传） |
| POST | `/api/admin/semester` | `semester:semester:manage` | 新建学期（draft） |
| PUT | `/api/admin/semester/{id}` | `semester:semester:manage` | 编辑基本信息（只写请求字段） |
| POST | `/api/admin/semester/{id}/activate` | `semester:semester:activate` | **双缓冲原子切换**（body 带 version；不可逆） |
| POST | `/api/admin/semester/{id}/archive` | `semester:semester:activate` | 归档（二次门禁：version 必填 + 窗口进行中须 confirmWindowOpen） |
| POST | `/api/admin/semester/{id}/unarchive` | `semester:semester:activate` | 撤销归档（受限回滚：仅当前无 active 学期时可用） |
| PUT | `/api/admin/semester/{id}/window` | `semester:window:manage` | 设置起止 + auto 开关 |
| POST | `/api/admin/semester/{id}/window/open` | `semester:window:manage` | 手动开启 |
| POST | `/api/admin/semester/{id}/window/close` | `semester:window:manage` | 提前截止 |
| POST | `/api/admin/semester/{id}/window/extend` | `semester:window:manage` | 延长（无限次） |
| GET | `/api/admin/semester/{id}/window/changes` | `semester:window:manage` | 变更记录（谁/何时/原值→新值） |

### 11.3 组织、教材、课程、账号、角色与批次（39）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| GET | `/api/admin/college` | `org:college:manage` | 学院列表 |
| POST | `/api/admin/college` | `org:college:manage` | 新增学院 |
| PUT | `/api/admin/college/{id}` | `org:college:manage` | 编辑学院 |
| GET | `/api/admin/major` | `org:major:manage` | 专业列表（`?collegeId=`） |
| POST | `/api/admin/major` | `org:major:manage` | 新增专业 |
| PUT | `/api/admin/major/{id}` | `org:major:manage` | 编辑专业 |
| GET | `/api/admin/class` | `org:class:manage` | 班级列表（含 student_count） |
| POST | `/api/admin/class` | `org:class:manage` | 新增班级 |
| PUT | `/api/admin/class/{id}` | `org:class:manage` | 编辑班级（含手工修正 studentCount） |
| GET | `/api/admin/textbook` | `textbook:book:manage` | 教材分页检索 |
| POST | `/api/admin/textbook` | `textbook:book:manage` | 新增教材 |
| PUT | `/api/admin/textbook/{id}` | `textbook:book:manage` | 编辑教材 |
| POST | `/api/admin/textbook/{id}/status` | `textbook:book:manage` | 停用/启用 |
| POST | `/api/admin/textbook/import` | `textbook:book:import` | 教材导入（返回 batchId） |
| GET | `/api/admin/textbook/template` | `textbook:book:import` | 模板下载 |
| GET | `/api/admin/course` | `course:course:manage` | 课程列表（`?semesterId=`） |
| POST | `/api/admin/course` | `course:course:manage` | 新增课程 |
| PUT | `/api/admin/course/{id}` | `course:course:manage` | 编辑课程 |
| GET | `/api/admin/teacher-course` | `course:teacher:manage` | 任课关系列表 |
| POST | `/api/admin/teacher-course` | `course:teacher:manage` | 新增任课关系 |
| DELETE | `/api/admin/teacher-course/{id}` | `course:teacher:manage` | 删除任课关系（逻辑删除） |
| POST | `/api/admin/teacher-course/import` | `course:teacher:manage` | 任课导入（返回 batchId） |
| GET | `/api/admin/teacher-course/template` | `course:teacher:manage` | 模板下载 |
| GET | `/api/admin/user` | `user:account:manage` | 账号检索（角色/学院/状态/关键字） |
| POST | `/api/admin/user` | `user:account:manage` | 建号（含供货商） |
| PUT | `/api/admin/user/{id}/status` | `user:account:manage` | 停用/启用（即时踢下线） |
| PUT | `/api/admin/user/{id}/reset-password` | `user:account:reset` | 重置密码（清首登标记） |
| PUT | `/api/admin/user/{id}/roles` | `user:account:manage` | 账号角色全量覆盖（BE-2；`role_version+1` + 撤销 refresh） |
| POST | `/api/admin/user/import` | `people:student:import` / `people:teacher:import` | 名单导入（biz_type 区分；含局部名单门禁） |
| POST | `/api/admin/user/import/preview` | 同上 | 导入预览（只读，不落库不建批次） |
| GET | `/api/admin/user/import/template` | 同上 | 模板下载 |
| GET | `/api/admin/role` | `role:manage` | 角色列表（BE-2） |
| POST | `/api/admin/role` | `role:manage` | 新建角色（响应 `data` = 新角色 id） |
| PUT | `/api/admin/role/{id}` | `role:manage` | 编辑名称/排序（编码不可改） |
| DELETE | `/api/admin/role/{id}` | `role:manage` | 逻辑删除 + 级联清授权 |
| PUT | `/api/admin/role/{id}/permissions` | `role:permission:assign` | 角色-权限全量覆盖 |
| GET | `/api/admin/permission` | `role:manage` | 权限目录（按模块分组，共 39 条） |
| GET | `/api/batch/{batchId}` | `import:batch:view` | 批次进度（非 ADMIN 仅本人批次） |
| GET | `/api/batch/{batchId}/errors` | `import:batch:view` | 错误明细下载 |

### 11.4 教师征订、学生选购、异动（26）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| GET | `/api/teacher/my-courses` | `order:form:submit` | 本学期任课关系（按班级分组） |
| GET | `/api/teacher/textbook` | `order:form:submit` | 填报选书器（在库教材检索，裸数组、封顶 50 条） |
| GET | `/api/teacher/order-form` | `order:form:submit` | 当前学期征订单（无单时 `data=null`） |
| POST | `/api/teacher/order-form/submit` | `order:form:submit` | 提交/补正（返回字段审查结果） |
| POST | `/api/teacher/order-form/withdraw` | `order:form:submit` | 主动撤回（BE-4：`pending_review → draft`） |
| GET | `/api/teacher/order-forms` | `order:form:view:self` | 历史提交记录 |
| GET | `/api/teacher/order-forms/{id}` | `order:form:view:self` | 本人表单详情（BE-3） |
| GET | `/api/secretary/order-forms` | `order:form:view:college` | 本院表单（分页） |
| GET | `/api/secretary/order-forms/{id}` | `order:form:view:college` | 本院表单详情（BE-3） |
| GET | `/api/admin/order-forms` | `order:form:view:all` | 全院表单（复核工作台） |
| GET | `/api/admin/order-forms/{id}` | `order:form:view:all` | 详情（含 field_check_result、contentVersion） |
| POST | `/api/admin/order-forms/{id}/review` | `order:form:review` | 通过/驳回（理由必填；`contentVersion` 做 CAS） |
| GET | `/api/student/book-list` | `student:order:submit` | 本班教材清单（必修标识/是否已下架） |
| GET | `/api/student/order` | `student:order:submit` | 本人选购单 |
| POST | `/api/student/order/submit` | `student:order:submit` | 提交（覆盖语义） |
| GET | `/api/student/orders` | `student:order:view:self` | 历史选购记录（跨学期摘要） |
| GET | `/api/admin/student-orders` | `student:order:view:all` | 全院选购（分页） |
| POST | `/api/teacher/change` | `change:request:submit` | 逐条提交异动 |
| POST | `/api/secretary/change` | `change:request:submit` | 逐条提交异动 |
| POST | `/api/secretary/change/import` | `change:request:submit` | 批量导入（BE-7b：**异步批次，只返回 `{batchId}`**） |
| GET | `/api/secretary/change/template` | `change:request:submit` | 异动导入模板下载（BE-7c） |
| GET | `/api/teacher/change` | `change:request:submit` | 我的提交记录 |
| GET | `/api/change/org-options` | `change:request:submit` | 提交端目标归属选项（只读 id + 名称） |
| GET | `/api/admin/change` | `change:request:review` | 审批列表（支持 batchNo/type 过滤） |
| POST | `/api/admin/change/{id}/review` | `change:request:review` | 单条审批 |
| POST | `/api/admin/change/batch/review` | `change:request:review` | 按批次批量通过/驳回（仅支持按 batchNo） |

### 11.5 通知、导出、看板、配置、审计、供货商（25）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| GET | `/api/notice/unconfirmed` | 登录 | 未确认任务队列（阻塞弹窗数据源） |
| GET | `/api/notice/mine` | 登录 | 我的通知（全量含已确认，分页） |
| POST | `/api/notice/{taskId}/confirm` | 登录 | 确认（幂等，可带 subscribe_result，204） |
| GET | `/api/notice/subscribe-config` | 登录 | 通知配置下发（BE-5e：`{subscribeTemplateId, popupQueueMax}`） |
| POST | `/api/notice/confirm-by-entry` | 登录 | 进入选书页即确认（BE-5g：`{confirmed:n}`，幂等） |
| GET | `/api/admin/notice/tasks` | `notice:task:view` | 任务列表（`?semesterId=` 可查历史学期） |
| POST | `/api/admin/notice/tasks` | `notice:task:manage` | 手动创建（同学期仅 1 个 active） |
| POST | `/api/admin/notice/tasks/{id}/close` | `notice:task:manage` | 手动关闭 |
| POST | `/api/admin/notice/tasks/{id}/send-now` | `notice:task:manage` | 立即发送一轮（BE-5b） |
| GET | `/api/admin/notice/tasks/{id}/progress` | `notice:task:view` | 发送/确认进度 |
| GET | `/api/admin/notice/tasks/{id}/failures` | `notice:task:view` | 未授权/失败名单 |
| POST | `/api/admin/export/orders` | `export:order:create` | 教师征订明细导出 |
| POST | `/api/secretary/export/signature` | `export:signature:create` | 秘书签字版导出 |
| POST | `/api/admin/export/students` | `export:student:create` | 学生选购汇总（参考用量） |
| POST | `/api/admin/export/notice` | `export:notice:create` | 通知汇总（含渠道列） |
| GET | `/api/export-task/{id}` | 登录（**非 ADMIN 仅本人任务，否则 404**） | 导出进度（无 `@PreAuthorize`，按归属判定） |
| GET | `/api/export-task/{id}/download` | 同上 | 一次性下载（`?token=`，410 过期） |
| GET | `/api/admin/dashboard` | `dashboard:stat:view` | 各学院提交进度/窗口状态/待复核数/未确认通知数 |
| GET | `/api/admin/config` | `config:config:manage` | 配置列表（8 键） |
| PUT | `/api/admin/config` | `config:config:manage` | 更新（键白名单 + 值域校验） |
| GET | `/api/admin/audit` | `audit:log:view` | 审计查询（操作者/动作/资源/时间过滤） |
| GET | `/api/supplier/orders` | `supplier:order:view` | 按学院分组清单（书名/ISBN/数量/教师姓名/学院） |
| POST | `/api/supplier/export` | `supplier:order:export` | 一学院一 sheet 导出（异步阈值同导出中心） |
| GET | `/api/supplier/export-task/{id}` | `supplier:order:export` | 供货商导出进度（仅限本人任务） |
| GET | `/api/supplier/export-task/{id}/download` | `supplier:order:export` | 供货商导出一次性下载（`?token=`） |

## 12. 事务与幂等边界

| 场景 | 事务/幂等策略 |
|------|--------------|
| 学期切换 | 单事务（§7）；乐观锁 version + DB 唯一约束兜底 |
| 导入批次 | 每 500 行一个事务（失败仅回滚该批，错误行继续收集）；批次最终状态一次性更新 |
| 教师提交/重提 | `UNIQUE(semester_id, teacher_id, deleted)` 兜底；重提=整单覆盖（先逻辑删旧明细再插新） |
| 学生选购提交 | 同上（`UNIQUE(semester_id, student_id, deleted)`）；覆盖语义 |
| 异动审批通过 | 更新 `user_semester_profile` + 写 `audit_log` **同事务** |
| 复核（内容审核） | 更新 `order_form` + 写 `audit_log` 同事务 |
| 通知确认 | `UNIQUE(task_id, user_id, round_no, deleted)` + 首次生效；重复调用返回 204 |
| 窗口状态变更 | 进程内锁 + version 乐观校验；状态已变则幂等跳过 |
| 导出任务 | 状态机流转 queued→running→done/failed/expired；下载 token 首次使用即置空 |
| 配置更新 | 单事务 + 审计；对未完结通知任务立即生效（不缓存在 Caffeine） |

## 13. 配置项清单（部署手册交付物）

**环境变量（必填，不硬编码）**

| 变量 | 用途 |
|------|------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `JWT_SECRET` | access/refresh 签名密钥（≥32 字节随机） |
| `WX_MINIAPP_APPID` / `WX_MINIAPP_SECRET` | 小程序 code2session / 订阅消息 |
| `TZ` | `Asia/Shanghai`（与 JVM 参数双保险） |

**application-{env}.yml 主要键**

| 键 | 说明 |
|----|------|
| `server.port` / `server.servlet.context-path` | 后端端口（默认 8080，反代到 `/api`） |
| `spring.datasource.*` | 连接池（HikariCP） |
| `mybatis-plus.*` | 逻辑删除字段 `deleted`（0 未删，时间戳删除） |
| `springdoc.api-docs.enabled` | 生产环境建议关闭或加权限 |
| `import.max_file_mb`（**DB `system_config` 键**，非 yml） | 上传上限（默认 10；`ConfigService.IMPORT_MAX_FILE_MB`，值域 1-100） |
| `textbook.import.pool.*` | 导入线程池参数 |
| `textbook.export.tmp-dir` | 导出临时目录（24 小时后清理） |
| `textbook.export.sync-row-threshold` | 同步/异步阈值（默认 5000） |
| `textbook.security.login.max-fail` / `lock-minutes` | 5 / 15 |
| `textbook.jwt.access-minutes` / `refresh-days` | 15 / 7 |

**Nginx（试运行与移交）**

```nginx
location /textbook/ { root /var/www; try_files $uri $uri/ /textbook/index.html; }
location /api/      { proxy_pass http://127.0.0.1:8080/api/; proxy_set_header Host $host;
                      proxy_set_header X-Real-IP $remote_addr; client_max_body_size 12m; }
```

## 14. 测试策略（`testing-pyramid` + `basic-testing-strategy`）

| 层 | 内容 |
|----|------|
| 单元测试 | 字段审查规则集（6 规则 × 边界）、窗口状态机流转、数量上限回退、停用比对范围、清单生成规则、弹窗队列上限、导出阈值判定 |
| 切片测试（`@WebMvcTest`） | **越权矩阵**：5 角色 × 资源 × 操作 → 期望 403/404 + 审计写入；401 三类语义；`must_change_password` 拦截 |
| 集成测试（Testcontainers MySQL） | 双缓冲原子切换（含 version 冲突回滚）、窗口自动开关幂等、导入批次（万行级样本 ≤5 分钟）、重提覆盖、confirm 幂等、一次性 token 过期 410 |
| 机检（CI） | ArchUnit：supplier 包禁 import 学生/教师 Mapper；`@CollegeScope` 覆盖检查 |
| 端到端（M5） | Web 端 Playwright（`webapp-testing`）+ 小程序 `weixin-devtools-mcp`；边界回归 S1-S6、S9-S13 + G5 补正 + R1-R13 |
| 性能 | 1 万行数据量下列表接口 P95 < 500ms；万行导入 ≤5 分钟 |

## 15. 部署与运维（03 §15）

```
systemd unit: /etc/systemd/system/textbook-order-server.service
  ExecStart=/usr/bin/java -Duser.timezone=Asia/Shanghai -jar /opt/textbook/app.jar --spring.profiles.active=trial
  Restart=always

备份: crontab 0 2 * * *  /opt/textbook/backup.sh     # mysqldump 全量 → 保留 14 天
日志: logback 按天轮转，保留 30 天；不含密码/token
证书: certbot renew（或云厂商托管证书）→ 部署手册含续期步骤
清理: 导出文件 24 小时后删除；导入临时文件解析后即删
```

**移交检查单**：① 环境变量清单交接；② `schema.sql` + `data-permission.sql` + `data-seed.sql` 初始化；③ Nginx 双 location（`/textbook/` + `/api/`）；④ systemd 与备份 crontab；⑤ 管理员账号交接与首登流程演练；⑥ OpenAPI 文档访问方式说明；⑦ 小程序 baseUrl 与微信后台合法域名更新清单（前端主责，后端提供接口域名确认）。
