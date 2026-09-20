package com.tian.textbook.integration.testcontainers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers MySQL 集成测试（SPEC §14：Docker 可用时以真实 MySQL 8 校验生产 DDL）。
 *
 * <p>本机无 Docker 时默认跳过：{@code mvn test} 不加 {@code -Drun.mysql.tests=true} 即门控关闭；
 * CI/开发机有 Docker 时 {@code mvn test -Drun.mysql.tests=true} 运行。</p>
 *
 * <p>不启动 Spring 上下文：直接用 JDBC 在容器上执行<b>未转换的 db/schema.sql</b>（生产初始化脚本），
 * 验证 MySQL 8 原生 DDL（JSON 列、生成列 active_flag、唯一键 uk_semester_active）可执行，
 * 并校验「同刻仅一个 active」在真实 MySQL 上由 DB 层保证（W1）。</p>
 */
@EnabledIfSystemProperty(named = "run.mysql.tests", matches = "true")
class MySqlContainerIntegrationTest {

    /** 与 SPEC §1 锁定的 MySQL 8.0.36+ 对齐。 */
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("textbook")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void applySchema() throws Exception {
        MYSQL.start();
        String ddl;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                MySqlContainerIntegrationTest.class.getResourceAsStream("/db/schema.sql"),
                StandardCharsets.UTF_8))) {
            ddl = reader.lines().collect(Collectors.joining("\n"));
        }
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            for (String sql : ddl.split(";")) {
                String executable = stripComments(sql);
                if (executable.isBlank()) {
                    continue;
                }
                statement.execute(executable);
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 去掉语句内的 -- 注释行（schema.sql 无字符串内分号，按 ; 切分安全）。 */
    private static String stripComments(String sql) {
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    @Test
    @DisplayName("MySQL 容器：db/schema.sql 原生 DDL 全量建表（24 张）")
    void schemaSql_appliesNativelyOnMySql() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'textbook'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(24);
        }
    }

    @Test
    @DisplayName("MySQL 容器：uk_semester_active 保证同刻仅一个 active（生成列唯一键）")
    void semesterActiveFlag_uniqueConstraintEnforced() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM semester");
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('T1', 'active')");
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('T2', 'active')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO semester (name, active_status) VALUES ('T3', 'active')"))
                    .hasMessageContaining("uk_semester_active");
            // draft 的 active_flag 为 NULL，不占唯一键
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('D1', 'draft')");
            statement.execute("INSERT INTO semester (name, active_status) VALUES ('D2', 'draft')");
        }
    }
}
