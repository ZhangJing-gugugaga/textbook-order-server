package com.tian.textbook.integration.mysql;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地真实 MySQL 集成测试（**无需 Docker**，替代 Testcontainers 的验证职责）。
 *
 * <p>为什么需要它：H2（MySQL 模式）与真实 MySQL 有实际差异，而生产 DDL 用的是
 * MySQL 原生语法。本用例直接在真实 MySQL 上执行<b>未转换的 db/*.sql</b>，
 * 校验 H2 无法覆盖的部分：</p>
 * <ul>
 *   <li>原生 DDL 可执行性（JSON 列、STORED 生成列、utf8mb4 COLLATE、DATETIME(3)）；</li>
 *   <li><b>三个生成列唯一约束</b>在真实 MySQL 上确实生效：
 *       {@code uk_semester_active}（同刻仅一个 active 学期）、
 *       {@code uk_task_active}（同学期仅一个 active 通知任务）、
 *       {@code uk_notice_confirm}（同一任务同一用户仅一条确认记录）——
 *       后两个是本次修复新增的，此前从未在真实 MySQL 上验证过；</li>
 *   <li>种子脚本幂等（{@code local} profile 的 {@code sql.init.mode=always} 依赖它）；</li>
 *   <li>迁移脚本的 {@code information_schema} 幂等判断与游标式 DATETIME 转换
 *       （存储过程是 MySQL 方言，H2 无法执行）。</li>
 * </ul>
 *
 * <p>启用方式（默认关闭，避免在无 MySQL 的环境上失败）：</p>
 * <pre>
 *   E:\tools\mysql-local.bat start
 *   ./mvnw test -Dmysql.local.enabled=true
 * </pre>
 *
 * <p>连接参数来自 pom 的 {@code mysql.local.*} 属性，可用 {@code -Dmysql.local.url=...} 覆盖。
 * 测试使用独立库 {@code textbook_verify}，不触碰开发库 {@code textbook_order}。</p>
 */
@EnabledIfSystemProperty(named = "mysql.local.enabled", matches = "true")
class LocalMySqlIntegrationTest {

    /** 专用验证库：与开发库隔离，用例内自行建/删 */
    private static final String VERIFY_DB = "textbook_verify";

    /** 表数量下限（schema.sql 当前 24 张业务表 + 可能的辅助表） */
    private static final int MIN_TABLES = 24;

    private static String baseUrl() {
        return System.getProperty("mysql.local.url",
                "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true");
    }

    private static String username() {
        return System.getProperty("mysql.local.username", "root");
    }

    private static String password() {
        return System.getProperty("mysql.local.password", "root");
    }

    /** 连到 MySQL 实例（不指定库），用于建库/删库 */
    private static Connection serverConnection() throws SQLException {
        return DriverManager.getConnection(baseUrl(), username(), password());
    }

    /** 连到验证库 */
    private static Connection verifyConnection() throws SQLException {
        return DriverManager.getConnection(withDatabase(baseUrl(), VERIFY_DB), username(), password());
    }

    /** 把 URL 中的库名替换为目标库（保留原有参数）。 */
    private static String withDatabase(String url, String database) {
        int query = url.indexOf('?');
        String params = query < 0 ? "" : url.substring(query);
        String head = query < 0 ? url : url.substring(0, query);
        int slash = head.lastIndexOf('/');
        return head.substring(0, slash + 1) + database + params;
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        try (Connection server = serverConnection(); Statement statement = server.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + VERIFY_DB);
            statement.execute("CREATE DATABASE " + VERIFY_DB
                    + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        applyScript("db/schema.sql");
    }

    // ============ 1. 原生 DDL ============

    @Test
    @DisplayName("真实 MySQL：db/schema.sql 原生执行建表（无 H2 转换）")
    void schemaSql_appliesNatively() throws Exception {
        try (Connection connection = verifyConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + VERIFY_DB + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(MIN_TABLES);
        }
    }

    @Test
    @DisplayName("真实 MySQL：JSON 列与 DATETIME(3) 精度按 DDL 落库")
    void jsonColumnsAndDatetimePrecision() throws Exception {
        try (Connection connection = verifyConnection();
             Statement statement = connection.createStatement()) {
            // JSON 列（H2 中会被转成 VARCHAR，真实 MySQL 才是原生 JSON）
            try (ResultSet rs = statement.executeQuery(
                    "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + VERIFY_DB
                            + "' AND TABLE_NAME='order_form' AND COLUMN_NAME='field_check_result'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("json");
            }
            // 时间列精度统一 (3)（与 NOW(3) 写入对齐）
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + VERIFY_DB
                            + "' AND DATA_TYPE='datetime' AND (DATETIME_PRECISION IS NULL OR DATETIME_PRECISION=0)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("不应存在秒精度 DATETIME 列（schema.sql 已统一 DATETIME(3)）")
                        .isZero();
            }
        }
    }

    // ============ 2. 三个生成列唯一约束（H2 无法真实验证） ============

    @Test
    @DisplayName("真实 MySQL：uk_semester_active 保证同刻仅一个 active 学期")
    void semesterActiveFlag_enforced() throws Exception {
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM semester");
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('S-A', 'active')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO semester (name, active_status) VALUES ('S-B', 'active')"))
                    .hasMessageContaining("uk_semester_active");
            // draft 行的 active_flag 为 NULL，不参与唯一性
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('S-D1', 'draft')");
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('S-D2', 'draft')");
        }
    }

    @Test
    @DisplayName("真实 MySQL：uk_task_active 保证同学期仅一个 active 通知任务（S6 的 DB 层兜底）")
    void noticeTaskActiveFlag_enforced() throws Exception {
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM notice_task");
            statement.execute("INSERT INTO notice_task (semester_id, title, content, status) "
                    + "VALUES (901, 'T-A', 'c', 'active')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO notice_task (semester_id, title, content, status) "
                            + "VALUES (901, 'T-B', 'c', 'active')"))
                    .as("同学期第二个 active 任务必须被唯一键拒绝")
                    .hasMessageContaining("uk_task_active");
            // closed 行的 active_flag 为 NULL，不参与唯一性 → 可累积多条历史
            statement.execute("INSERT INTO notice_task (semester_id, title, content, status) "
                    + "VALUES (901, 'T-C', 'c', 'closed')");
            statement.execute("INSERT INTO notice_task (semester_id, title, content, status) "
                    + "VALUES (901, 'T-D', 'c', 'closed')");
            // 不同学期互不影响
            statement.execute("INSERT INTO notice_task (semester_id, title, content, status) "
                    + "VALUES (902, 'T-E', 'c', 'active')");
        }
    }

    @Test
    @DisplayName("真实 MySQL：uk_notice_confirm 保证同一任务同一用户仅一条确认记录（S25 的 DB 层兜底）")
    void noticeConfirmFlag_enforced() throws Exception {
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM notice_record");
            // 确认记录：round_no 为 NULL（契约），此时 uk_notice_round 因 NULL 不参与唯一性而失效，
            // 故新增了基于 confirm_flag 生成列的唯一键
            statement.execute("INSERT INTO notice_record (task_id, user_id, round_no, confirmed_at, deleted) "
                    + "VALUES (901, 7001, NULL, NOW(3), 0)");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO notice_record (task_id, user_id, round_no, confirmed_at, deleted) "
                            + "VALUES (901, 7001, NULL, NOW(3), 0)"))
                    .as("同一任务同一用户的第二条确认记录必须被拒绝（原缺陷：可插多行）")
                    .hasMessageContaining("uk_notice_confirm");
            // 未确认的发送记录（confirmed_at 为 NULL）不受该唯一键限制，可按轮次累积
            statement.execute("INSERT INTO notice_record (task_id, user_id, round_no, send_status, deleted) "
                    + "VALUES (901, 7001, 1, 'sent', 0)");
            statement.execute("INSERT INTO notice_record (task_id, user_id, round_no, send_status, deleted) "
                    + "VALUES (901, 7001, 2, 'sent', 0)");
            // 同轮次重复发送记录由 uk_notice_round 兜底
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO notice_record (task_id, user_id, round_no, send_status, deleted) "
                    + "VALUES (901, 7001, 2, 'sent', 0)"))
                    .hasMessageContaining("uk_notice_round");
        }
    }

    // ============ 3. 种子脚本幂等（local profile 反复重启依赖它） ============

    @Test
    @DisplayName("真实 MySQL：权限与演示种子脚本可重复执行（幂等），数据不重复")
    void seedScripts_areIdempotent() throws Exception {
        // 前一个用例可能残留 semester/notice 数据；种子脚本本身幂等，无需清库
        for (int round = 1; round <= 3; round++) {
            applyScript("db/data-permission.sql");
            applyScript("db/data-seed.sql");
        }
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "sys_role")).isEqualTo(5);
            assertThat(count(statement, "sys_permission")).isEqualTo(37);
            assertThat(count(statement, "sys_user")).isEqualTo(18);
            assertThat(count(statement, "sys_user_role")).isEqualTo(19);
            // 43 = ADMIN 28（37 条权限去掉 2 条供货商 + 7 条角色专属自助类，2026-09-22 收权）
            //     + SECRETARY 6 + TEACHER 4 + STUDENT 3 + SUPPLIER 2
            // 生产实测口径见《后端测试报告-…-20260923》B1/B2（ADMIN=28 / 6 / 4 / 3 / 2）
            assertThat(count(statement, "sys_role_permission")).isEqualTo(43);
            assertThat(count(statement, "college")).isEqualTo(2);
            assertThat(count(statement, "system_config")).isEqualTo(8);
        }
    }

    // ============ 4. 迁移脚本：MySQL 方言的幂等判断与游标转换 ============

    @Test
    @DisplayName("真实 MySQL：迁移脚本可在当前 schema 上重复执行（information_schema 幂等判断生效）")
    void migrationScript_isIdempotentOnRealMySql() throws Exception {
        // 存储过程内是 MySQL 方言（DELIMITER / information_schema / PREPARE），H2 无法覆盖
        applyScript("db/migration-2026-09-21.sql");
        applyScript("db/migration-2026-09-21.sql");

        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            assertThat(columnExists(connection, "order_form", "content_version")).isTrue();
            assertThat(indexExists(connection, "notice_task", "uk_task_active")).isTrue();
            assertThat(indexExists(connection, "notice_record", "uk_notice_confirm")).isTrue();
            assertThat(indexExists(connection, "audit_log", "idx_audit_resource")).isTrue();
        }
    }

    @Test
    @DisplayName("真实 MySQL：迁移脚本能补齐被删掉的新对象（模拟存量库升级）")
    void migrationScript_restoresDroppedObjects() throws Exception {
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            // 退回「迁移前」状态
            statement.execute("ALTER TABLE notice_record DROP INDEX uk_notice_confirm");
            statement.execute("ALTER TABLE notice_record DROP COLUMN confirm_flag");
            statement.execute("ALTER TABLE notice_task DROP INDEX uk_task_active");
            statement.execute("ALTER TABLE notice_task DROP COLUMN active_flag");
            statement.execute("ALTER TABLE order_form DROP INDEX idx_form_teacher");
            statement.execute("ALTER TABLE order_form DROP COLUMN content_version");

            assertThat(columnExists(connection, "order_form", "content_version")).isFalse();
        }
        applyScript("db/migration-2026-09-21.sql");

        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            assertThat(columnExists(connection, "order_form", "content_version")).isTrue();
            assertThat(columnExists(connection, "notice_task", "active_flag")).isTrue();
            assertThat(columnExists(connection, "notice_record", "confirm_flag")).isTrue();
            assertThat(indexExists(connection, "order_form", "idx_form_teacher")).isTrue();
            assertThat(indexExists(connection, "notice_task", "uk_task_active")).isTrue();
            assertThat(indexExists(connection, "notice_record", "uk_notice_confirm")).isTrue();
            // 冗余索引应已被清理（与 schema.sql 收敛）
            assertThat(indexExists(connection, "course", "idx_course_sem")).isFalse();
            assertThat(indexExists(connection, "teacher_course", "idx_tc_teacher")).isFalse();
            assertThat(statement).isNotNull();
        }
    }

    // ============ 5. 方言敏感 SQL 直跑（H2 会掩盖的写法） ============

    @Test
    @DisplayName("真实 MySQL：方言敏感查询可执行（NOW(3)/LIMIT-OFFSET/ESCAPE/JSON/生成列）")
    void dialectSensitiveSql_executes() throws Exception {
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            // 审计日志：NOW(3) 默认值 + JSON 列读写
            statement.execute("INSERT INTO audit_log (action, resource, resource_id, detail_json, at) "
                    + "VALUES ('SMOKE', 'verify', '1', '{\"k\":\"v\"}', NOW(3))");
            // LIMIT/OFFSET 分页（审计下推分页用到）
            statement.execute("SELECT * FROM audit_log ORDER BY at DESC, id DESC LIMIT 20 OFFSET 0");
            // LIKE + ESCAPE '|'（LIKE 通配符转义用到）
            statement.execute("SELECT COUNT(*) FROM sys_user WHERE user_no LIKE CONCAT('9', '%') ESCAPE '|'");
            statement.execute("SELECT COUNT(*) FROM sys_user WHERE user_no LIKE CONCAT('9|%', '%') ESCAPE '|'");
            // 生成列参与查询（通知任务 active 判定）
            statement.execute("SELECT COUNT(*) FROM notice_task WHERE active_flag = 1");
            // 软删唯一键：同业务键在 deleted 不同取值下可共存
            statement.execute("DELETE FROM textbook");
            statement.execute("INSERT INTO textbook (isbn, title, status, deleted) VALUES ('ISBN-X', 'B1', 1, 0)");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO textbook (isbn, title, status, deleted) VALUES ('ISBN-X', 'B2', 1, 0)"))
                    .hasMessageContaining("uk_textbook_isbn");
            statement.execute("UPDATE textbook SET deleted = UNIX_TIMESTAMP(NOW(3))*1000 WHERE isbn='ISBN-X'");
            statement.execute("INSERT INTO textbook (isbn, title, status, deleted) VALUES ('ISBN-X', 'B3', 1, 0)");
        }
    }

    // ============ 工具 ============

    /** 读取 classpath 上的 SQL 脚本并按 ; 切分执行（跳过注释行）。 */
    private static void applyScript(String resource) throws Exception {
        String sql;
        try (InputStream in = LocalMySqlIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 资源缺失: %s", resource).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取脚本失败: " + resource, e);
        }
        try (Connection connection = verifyConnection(); Statement statement = connection.createStatement()) {
            for (String raw : splitStatements(sql)) {
                String executable = stripComments(raw);
                if (!executable.isBlank()) {
                    statement.execute(executable);
                }
            }
        }
    }

    /**
     * 按 ; 切分，但保留 {@code DELIMITER $$ ... $$} 块（迁移脚本用存储过程）。
     * 迁移脚本自带 {@code DELIMITER} 与 {@code CALL}/{@code DROP PROCEDURE}，
     * 直接整体交给驱动会失败，故按 DELIMITER 语义手工切分。
     */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(delimiter)) {
                String stmt = current.toString().trim();
                statements.add(stmt.substring(0, stmt.length() - delimiter.length()));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static String stripComments(String sql) {
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(VERIFY_DB, null, table, column)) {
            return rs.next();
        }
    }

    private static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + VERIFY_DB
                        + "' AND TABLE_NAME='" + table + "' AND INDEX_NAME='" + index + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
