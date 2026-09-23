-- ============================================================================
-- 迁移脚本：2026-09-23 BE-6 reserve1~6 全表补齐（存量库升级专用）
--
-- 适用对象：**已上线/已初始化的库**。全新空库直接执行 db/schema.sql。
--
-- 内容：给 22 张表补齐预留扩展列 reserve1 ~ reserve6（VARCHAR(255) NULL）
--
-- 为什么需要：甲方决策「所有表预留 5–6 个扩展字段」，此前 26 张表中只有 3 张
-- （sys_user / college / textbook）有这 6 列。本脚本让**存量库与全新安装的结构完全一致**
-- （不补齐则两边结构漂移，后续按 reserve 列做的扩展会在旧库上失败）。
--
-- 实体类不需要加字段：预留列不参与业务映射（MyBatis-Plus 不查询未声明列）。
--
-- 执行方式（建议先备份）：
--   mysql -uroot -p textbook_order < db/migration-2026-09-23-reserve.sql
--
-- 幂等性：逐列先查 information_schema 再 ALTER，可重复执行（MySQL 8 不支持
-- ADD COLUMN IF NOT EXISTS，故用存储过程外壳）。
-- 验证 SQL（期望每张表 6 行、共 22 张表）：
--   SELECT TABLE_NAME, COUNT(1) AS reserve_cols FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME LIKE 'reserve%'
--    GROUP BY TABLE_NAME ORDER BY TABLE_NAME;
-- ============================================================================
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS textbook_migrate_20260923_reserve$$
CREATE PROCEDURE textbook_migrate_20260923_reserve()
BEGIN
  DECLARE db VARCHAR(64);
  SET db = DATABASE();

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_token'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE sys_user_token ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE sys_role ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE sys_role ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE sys_role ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE sys_role ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE sys_role ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE sys_role ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_permission'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE sys_permission ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_user_role'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE sys_user_role ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'sys_role_permission'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE sys_role_permission ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE major ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE major ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE major ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE major ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE major ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'major'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE major ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE school_class ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE school_class ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE school_class ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE school_class ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE school_class ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'school_class'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE school_class ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE semester ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE semester ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE semester ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE semester ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE semester ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'semester'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE semester ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'user_semester_profile'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE user_semester_profile ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE course ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE course ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE course ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE course ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE course ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'course'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE course ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'teacher_course'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE teacher_course ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE order_form ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE order_form ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE order_form ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE order_form ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE order_form ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE order_form ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'order_form_item'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE order_form_item ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE student_order ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE student_order ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE student_order ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE student_order ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE student_order ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE student_order ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'student_order_item'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE student_order_item ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE change_request ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE change_request ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE change_request ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE change_request ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE change_request ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'change_request'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE change_request ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE import_batch ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE import_batch ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE import_batch ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE import_batch ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE import_batch ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'import_batch'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE import_batch ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE export_task ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE export_task ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE export_task ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE export_task ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE export_task ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'export_task'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE export_task ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE notice_task ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE notice_task ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE notice_task ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE notice_task ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE notice_task ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_task'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE notice_task ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE notice_record ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE notice_record ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE notice_record ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE notice_record ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE notice_record ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'notice_record'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE notice_record ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE system_config ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE system_config ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE system_config ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE system_config ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE system_config ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'system_config'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE system_config ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve1') THEN
    ALTER TABLE audit_log ADD COLUMN reserve1 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve2') THEN
    ALTER TABLE audit_log ADD COLUMN reserve2 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve3') THEN
    ALTER TABLE audit_log ADD COLUMN reserve3 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve4') THEN
    ALTER TABLE audit_log ADD COLUMN reserve4 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve5') THEN
    ALTER TABLE audit_log ADD COLUMN reserve5 VARCHAR(255) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = db AND TABLE_NAME = 'audit_log'
                   AND COLUMN_NAME = 'reserve6') THEN
    ALTER TABLE audit_log ADD COLUMN reserve6 VARCHAR(255) DEFAULT NULL;
  END IF;

END$$

DELIMITER ;

CALL textbook_migrate_20260923_reserve();
DROP PROCEDURE IF EXISTS textbook_migrate_20260923_reserve;
