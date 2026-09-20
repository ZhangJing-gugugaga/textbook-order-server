package com.tian.textbook;

import com.tian.textbook.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 上下文冒烟：完整加载（含 MyBatis Mapper XML、安全过滤器链、切面、线程池），
 * 且 schema.sql 经 H2 转换后全部 24 张表建成。
 */
class ContextSmokeTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads_andAllTablesCreated() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('PUBLIC','public')")) {
            assertThat(rs.next()).isTrue();
            int tables = rs.getInt(1);
            // schema.sql 共 24 张表
            assertThat(tables).isGreaterThanOrEqualTo(24);
        }
    }
}
