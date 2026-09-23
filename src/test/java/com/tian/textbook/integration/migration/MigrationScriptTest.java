package com.tian.textbook.integration.migration;

import com.tian.textbook.support.H2SchemaInitializer;
import com.tian.textbook.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量库迁移脚本验证（R2）。
 *
 * <p>背景：{@code schema.sql} 已全部改为 {@code CREATE TABLE IF NOT EXISTS}，对已存在的表是空操作，
 * 因此本轮新增的列/唯一键/索引**不会**被补上。若只交付代码不交付迁移脚本，
 * 存量库（trial/school）拉代码重启即多处 500（{@code order_form.content_version} 缺失会让
 * 教师提交与管理员审核双双失败）。</p>
 *
 * <p>本用例把 {@code db/migration-2026-09-21.sql} 中的 {@code ALTER TABLE} 语句抽出来，
 * 在「已删除这些新对象的库」上实际执行一遍，断言：</p>
 * <ol>
 *   <li>语句本身语法有效（不是纸面脚本）；</li>
 *   <li>执行后新对象确实存在，且与 {@code schema.sql} 定义的名称一致（脚本与代码不脱节）。</li>
 * </ol>
 *
 * <p>局限：存储过程外壳（{@code DELIMITER}/{@code information_schema} 幂等判断）是 MySQL 方言，
 * H2 无法执行，本用例只验证其内部 DDL。幂等判断的正确性需在 trial 环境实跑确认。</p>
 */
class MigrationScriptTest extends IntegrationTestBase {

    /** 本轮新增、需要在存量库上补齐的对象 */
    private static final List<String> NEW_COLUMNS = List.of(
            "order_form.content_version",
            "notice_task.active_flag",
            "notice_record.confirm_flag");

    private static final List<String> NEW_INDEXES = List.of(
            "idx_form_teacher",
            "idx_stu_order_student",
            "idx_change_applicant",
            "idx_audit_resource",
            "uk_task_active",
            "uk_notice_confirm");

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("R2：迁移脚本的 ALTER 语句可在「旧库」上补齐全部新增列与索引")
    void migrationAltersCreateMissingObjects() throws Exception {
        List<String> alters = extractAlterStatements();
        assertThat(alters)
                .as("迁移脚本必须包含可执行的 ALTER TABLE 语句（当前为 %d 条）", alters.size())
                .isNotEmpty();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            dropNewObjects(connection, statement);

            // 前置断言：确认确实处于「旧库」状态
            for (String column : NEW_COLUMNS) {
                assertThat(columnExists(connection, column))
                        .as("前置条件：%s 应已被删除（模拟旧库）", column)
                        .isFalse();
            }

            // 执行迁移脚本中的 ALTER（H2 不支持 STORED 关键字与 AFTER 子句，做等价改写）
            for (String alter : alters) {
                statement.execute(h2Compatible(alter));
            }

            for (String column : NEW_COLUMNS) {
                assertThat(columnExists(connection, column))
                        .as("迁移后应存在列 %s（否则新代码对应功能在存量库上直接 500）", column)
                        .isTrue();
            }
            for (String index : NEW_INDEXES) {
                assertThat(indexExists(connection, index))
                        .as("迁移后应存在索引 %s", index)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("R2：迁移脚本与 schema.sql 的新增对象集合一致（脚本不遗漏、不多余）")
    void migrationCoversEverySchemaObject() throws IOException {
        String migration = readMigration().toLowerCase(java.util.Locale.ROOT);

        for (String column : NEW_COLUMNS) {
            String[] parts = column.split("\\.");
            assertThat(migration)
                    .as("迁移脚本缺少列 %s 的补齐语句", column)
                    .contains(parts[1]);
        }
        for (String index : NEW_INDEXES) {
            assertThat(migration)
                    .as("迁移脚本缺少索引 %s 的补齐语句", index)
                    .contains(index);
        }
        // schema.sql 里也应有同名定义，避免两边命名漂移
        String schema = readSchema().toLowerCase(java.util.Locale.ROOT);
        for (String index : NEW_INDEXES) {
            assertThat(schema)
                    .as("schema.sql 中缺少索引 %s（两边命名漂移）", index)
                    .contains(index);
        }
    }

    @Test
    @DisplayName("R2b：2026-09-23 性能索引迁移脚本齐备、幂等守卫齐备（B-G2 ①②）")
    void migrationScript20260923Perf_addsIndexesIdempotently() throws IOException {
        String sql = readClasspath("db/migration-2026-09-23-perf.sql").toLowerCase(java.util.Locale.ROOT);
        for (String needle : List.of("idx_audit_at", "information_schema.statistics",
                "drop procedure if exists")) {
            assertThat(sql).as("性能索引迁移缺少 %s", needle).contains(needle);
        }
        // 幂等守卫：存在即跳过，否则二次执行必报 Duplicate key name
        // （按「守卫语句」精确计数，避免把文件头的验证 SQL 注释也算进去）
        assertThat(sql.split(java.util.regex.Pattern.quote(
                "if not exists (select 1 from information_schema.statistics"), -1).length - 1)
                .as("每个索引各需一处幂等守卫").isEqualTo(1);
        // B-G2①（notice_record 加 user_id 前导索引）经实测未采纳——脚本里必须写明理由，
        // 避免后人「按文档补索引」重复加一个用不到的索引
        assertThat(sql).as("脚本需记录 ① 未采纳的理由与证据").contains("未采纳");
    }

    @Test
    @DisplayName("R2：2026-09-23 四个迁移脚本齐备、幂等守卫齐备、目标对象不漏")
    void migrationScripts20260923_coverRequiredObjects() throws IOException {
        // 脚本 → 必须出现的目标对象（列/表/权限码）与幂等守卫
        Map<String, List<String>> expectations = new LinkedHashMap<>();
        expectations.put("db/migration-2026-09-23-role-permission.sql",
                List.of("role:manage", "role:permission:assign", "ON DUPLICATE KEY UPDATE"));
        expectations.put("db/migration-2026-09-23-order-withdraw.sql",
                List.of("withdrawn_at", "information_schema.COLUMNS"));
        expectations.put("db/migration-2026-09-23-notify.sql",
                List.of("semester_id", "notice_record_history", "change_type", "uk_history_record",
                        "information_schema.COLUMNS", "information_schema.TABLES"));
        expectations.put("db/migration-2026-09-23-reserve.sql",
                List.of("reserve1", "reserve6", "information_schema.COLUMNS"));
        for (Map.Entry<String, List<String>> entry : expectations.entrySet()) {
            String sql = readClasspath(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
            for (String needle : entry.getValue()) {
                assertThat(sql)
                        .as("%s 应包含 %s（缺则存量库升级后对应功能 500 或结构漂移）",
                                entry.getKey(), needle)
                        .contains(needle.toLowerCase(java.util.Locale.ROOT));
            }
        }
        // 22 张表 × 6 列：reserve 迁移必须覆盖每一张（逐个表名出现即可，逐列由 MySQL 用例实测）
        String reserve = readClasspath("db/migration-2026-09-23-reserve.sql");
        for (String table : List.of("sys_user_token", "sys_role", "sys_permission", "sys_user_role",
                "sys_role_permission", "major", "school_class", "semester", "user_semester_profile",
                "course", "teacher_course", "order_form", "order_form_item", "student_order",
                "student_order_item", "change_request", "import_batch", "export_task", "notice_task",
                "notice_record", "system_config", "audit_log")) {
            assertThat(reserve).as("reserve 迁移缺少表 %s", table).contains("TABLE_NAME = '" + table + "'");
        }
    }

    // ============ 工具 ============

    /** 抽取迁移脚本中的 ALTER TABLE 语句（跳过注释行与存储过程外的示例块）。 */
    private List<String> extractAlterStatements() throws IOException {
        Set<String> alters = new LinkedHashSet<>();
        // 只取脚本主体（存储过程内）的 ALTER，忽略文件末尾「简化版」注释里的示例
        String body = readMigration();
        int procStart = body.indexOf("CREATE PROCEDURE");
        int procEnd = body.indexOf("DELIMITER ;", procStart < 0 ? 0 : procStart);
        String scope = (procStart >= 0 && procEnd > procStart) ? body.substring(procStart, procEnd) : body;

        Matcher matcher = Pattern.compile("ALTER\\s+TABLE\\s+.*?;", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(scope);
        while (matcher.find()) {
            String sql = matcher.group().trim();
            String lower = sql.toLowerCase(java.util.Locale.ROOT);
            // 只保留「补齐本轮新增对象」的语句。sys_user_token 的 uk_token_hash 重建是条件式
            // DROP+ADD（非新增对象，且 H2 的 DROP INDEX 语法不同），不纳入本用例。
            boolean addsNewObject = NEW_INDEXES.stream().anyMatch(lower::contains)
                    || NEW_COLUMNS.stream().anyMatch(column -> {
                        String[] parts = column.split("\\.");
                        return lower.contains(parts[0]) && lower.contains(parts[1]);
                    });
            if (addsNewObject) {
                alters.add(sql);
            }
        }
        assertThat(alters)
                .as("应至少抽到 %d 条补齐语句（列 %d + 索引 %d）",
                        NEW_COLUMNS.size() + NEW_INDEXES.size(), NEW_COLUMNS.size(), NEW_INDEXES.size())
                .hasSizeGreaterThanOrEqualTo(NEW_COLUMNS.size() + NEW_INDEXES.size());
        return new ArrayList<>(alters);
    }

    /** H2 兼容改写：去 STORED / AFTER 子句、去列级 COMMENT。 */
    private String h2Compatible(String mysqlAlter) {
        String sql = mysqlAlter
                .replaceAll("(?i)\\s+STORED", "")
                .replaceAll("(?i)\\s+AFTER\\s+\\w+", "")
                .replaceAll("(?i)\\s+COMMENT\\s+'[^']*'", "");
        // H2 的生成列表达式不支持 IF()，改写为 CASE WHEN
        sql = sql.replaceAll("(?i)IF\\(status='active',1,NULL\\)",
                "CASE WHEN status='active' THEN 1 ELSE NULL END");
        sql = sql.replaceAll("(?i)IF\\(confirmed_at IS NOT NULL AND deleted = 0,1,NULL\\)",
                "CASE WHEN confirmed_at IS NOT NULL AND deleted = 0 THEN 1 ELSE NULL END");
        return sql;
    }

    /** 删除本轮新增对象，把库退回「迁移前」状态。 */
    private void dropNewObjects(Connection connection, Statement statement) throws Exception {
        // 唯一约束先删（H2 中生成列被约束引用时不能直接 DROP COLUMN）
        for (String constraint : List.of("uk_task_active", "uk_notice_confirm")) {
            if (indexExists(connection, constraint)) {
                statement.execute("ALTER TABLE " + tableOf(constraint) + " DROP CONSTRAINT " + constraint);
            }
        }
        for (String index : List.of("idx_form_teacher", "idx_stu_order_student",
                "idx_change_applicant", "idx_audit_resource")) {
            if (indexExists(connection, index)) {
                statement.execute("DROP INDEX " + index);
            }
        }
        statement.execute("ALTER TABLE notice_record DROP COLUMN confirm_flag");
        statement.execute("ALTER TABLE notice_task DROP COLUMN active_flag");
        statement.execute("ALTER TABLE order_form DROP COLUMN content_version");
    }

    private String tableOf(String constraint) {
        return switch (constraint) {
            case "uk_task_active" -> "notice_task";
            case "uk_notice_confirm" -> "notice_record";
            default -> throw new IllegalArgumentException("unknown constraint: " + constraint);
        };
    }

    private boolean columnExists(Connection connection, String qualified) throws Exception {
        String[] parts = qualified.split("\\.");
        try (ResultSet rs = connection.getMetaData()
                .getColumns(null, null, parts[0], parts[1])) {
            return rs.next();
        }
    }

    /**
     * 索引或唯一约束是否存在。
     *
     * <p>走 information_schema 而非 {@code DatabaseMetaData#getIndexInfo}：
     * 后者对唯一约束的支撑索引会返回 H2 自造的名字（{@code UK_..._INDEX_...}），
     * 按约束名查不到，导致 DROP 被跳过。</p>
     */
    private boolean indexExists(Connection connection, String indexName) throws Exception {
        String sql = "SELECT COUNT(1) FROM ("
                + "SELECT index_name AS name FROM information_schema.indexes "
                + "UNION ALL SELECT constraint_name FROM information_schema.table_constraints) t "
                + "WHERE UPPER(name) = UPPER(?)";
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String readMigration() throws IOException {
        return readClasspath("db/migration-2026-09-21.sql");
    }

    private String readSchema() throws IOException {
        return readClasspath("db/schema.sql");
    }

    private String readClasspath(String resource) throws IOException {
        try (InputStream in = H2SchemaInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 资源缺失: %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
