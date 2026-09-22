package com.tian.textbook.support;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * H2 测试库初始化：读取 db/schema.sql（MySQL DDL）并转换为 H2（MySQL 模式）可执行 DDL。
 *
 * <p>本机无 MySQL/Docker 时用 H2 承载集成测试；生产 DDL 单一来源仍是 db/schema.sql，
 * 转换只发生在测试期（Testcontainers MySQL 用例在 Docker 可用时运行）。</p>
 *
 * <p>转换规则：去列/表 COMMENT、JSON→VARCHAR、去 ENGINE/CHARSET、去 ON UPDATE、
 * UNIQUE KEY→CONSTRAINT、普通 KEY 索引移除、CREATE TABLE→IF NOT EXISTS（幂等）。</p>
 */
public final class H2SchemaInitializer {

    private static final Pattern COLUMN_COMMENT = Pattern.compile("\\s+COMMENT\\s+'[^']*'");
    private static final Pattern TABLE_COMMENT = Pattern.compile("\\s*COMMENT\\s*=\\s*'[^']*'");
    private static final Pattern TABLE_ENGINE = Pattern.compile(
            "\\s*ENGINE=\\w+(\\s+DEFAULT\\s+CHARSET=\\w+)?(\\s+COLLATE=\\w+)?");
    private static final Pattern JSON_TYPE = Pattern.compile("\\bJSON\\b");
    private static final Pattern ON_UPDATE = Pattern.compile(
            "\\s+ON\\s+UPDATE\\s+CURRENT_TIMESTAMP\\(3\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIQUE_KEY = Pattern.compile("UNIQUE\\s+KEY\\s+(\\w+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    /** 普通二级索引（H2 不支持表内联 KEY 索引；前视断言避免误伤 config_key 等列名） */
    private static final Pattern PLAIN_KEY = Pattern.compile(
            ",?\\s*(?<![A-Za-z0-9_])KEY\\s+\\w+\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    /** 补 IF NOT EXISTS（schema.sql 已自带时不再重复添加，否则生成 "IF NOT EXISTS IF NOT EXISTS"） */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?!IF\\s+NOT\\s+EXISTS\\s+)", Pattern.CASE_INSENSITIVE);
    /** H2 不支持 IF()：生成列表达式改写为 CASE WHEN */
    private static final Pattern GENERATED_IF = Pattern.compile(
            "IF\\(active_status='active',1,NULL\\)", Pattern.CASE_INSENSITIVE);
    /** 同上：通知任务的 active 生成列（status 列名与 semester 不同，需独立改写） */
    private static final Pattern GENERATED_IF_TASK = Pattern.compile(
            "IF\\(status='active',1,NULL\\)", Pattern.CASE_INSENSITIVE);
    /** 同上：通知确认记录唯一性生成列 */
    private static final Pattern GENERATED_IF_CONFIRM = Pattern.compile(
            "IF\\(confirmed_at IS NOT NULL AND deleted = 0\\s*,\\s*1\\s*,\\s*NULL\\)", Pattern.CASE_INSENSITIVE);
    /** H2 生成列不支持 STORED 关键字 */
    private static final Pattern GENERATED_STORED = Pattern.compile("\\)\\s*STORED", Pattern.CASE_INSENSITIVE);

    private H2SchemaInitializer() {
    }

    /** 应用就绪后执行（mapper 为懒代理，此时建表不影响启动；定时任务已在 test profile 关闭）。 */
    public static final class ReadyListener implements ApplicationListener<ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            ApplicationContext context = event.getApplicationContext();
            DataSource dataSource;
            try {
                dataSource = context.getBean(DataSource.class);
            } catch (NoSuchBeanDefinitionException e) {
                // 切片测试（@WebMvcTest）等无数据源上下文：跳过建表
                return;
            }
            run(dataSource);
        }
    }

    /** 在给定数据源上执行转换后的 schema.sql（幂等）。 */
    public static void run(DataSource dataSource) {
        String ddl = transform(readSchema());
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement statement = connection.createStatement()) {
            for (String sql : ddl.split(";")) {
                if (sql.isBlank()) {
                    continue;
                }
                try {
                    statement.execute(sql);
                } catch (Exception e) {
                    throw new IllegalStateException("H2 schema 初始化失败，出错语句（前 600 字符）: >>>"
                            + sql.substring(0, Math.min(600, sql.length())) + "<<<", e);
                }
            }
        } catch (Exception e) {
            throw e instanceof IllegalStateException ? (IllegalStateException) e
                    : new IllegalStateException("H2 schema 初始化失败: " + e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    static String transform(String mysqlDdl) {
        String ddl = COLUMN_COMMENT.matcher(mysqlDdl).replaceAll("");
        ddl = TABLE_COMMENT.matcher(ddl).replaceAll("");
        ddl = TABLE_ENGINE.matcher(ddl).replaceAll("");
        ddl = ON_UPDATE.matcher(ddl).replaceAll("");
        ddl = JSON_TYPE.matcher(ddl).replaceAll("VARCHAR(4000)");
        ddl = UNIQUE_KEY.matcher(ddl).replaceAll("CONSTRAINT $1 UNIQUE (");
        ddl = PLAIN_KEY.matcher(ddl).replaceAll("");
        ddl = GENERATED_IF.matcher(ddl).replaceAll("CASE WHEN active_status='active' THEN 1 ELSE NULL END");
        ddl = GENERATED_IF_TASK.matcher(ddl).replaceAll("CASE WHEN status='active' THEN 1 ELSE NULL END");
        ddl = GENERATED_IF_CONFIRM.matcher(ddl).replaceAll(
                "CASE WHEN confirmed_at IS NOT NULL AND deleted = 0 THEN 1 ELSE NULL END");
        ddl = GENERATED_STORED.matcher(ddl).replaceAll(")");
        ddl = CREATE_TABLE.matcher(ddl).replaceAll("CREATE TABLE IF NOT EXISTS ");
        return ddl;
    }

    private static String readSchema() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("db/schema.sql").getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("读取 db/schema.sql 失败", e);
        }
    }
}
