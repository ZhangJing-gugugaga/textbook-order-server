package com.tian.textbook.integration.system;

import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计查询集成测试（W24）：JSON 明细列（detail_json）经 MP 自动 resultMap 读回。
 *
 * <p>回归背景：自定义 {@code @Select} 不会自动套用 {@code @TableName(autoResultMap=true)} 生成的
 * resultMap，JSON 列被自动映射静默丢弃（读回 null）——{@code GET /api/admin/audit} 的
 * detail 因此全为空，而审计页「谁/何时/原值→新值」正依赖该列。本用例锁定该缺陷不再回归。</p>
 */
class AuditQueryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuditService auditService;
    @Autowired
    private TestDataSeeder seeder;

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    @DisplayName("审计查询：detailJson 经 resultMap 读回非空（含原值→新值）")
    void query_returnsDetailJson() {
        var admin = seeder.user("AU1", "超管", "13800000001", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AU1", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        auditService.record(AuditService.WINDOW, "semester", "7", Map.of(
                "op", "extend",
                "before", "2026-10-01 23:59:59",
                "after", "2026-10-08 23:59:59"));

        List<AuditLog> logs = auditService.query(null, null, AuditService.WINDOW, "semester", null, null, 1, 20)
                .list();

        assertThat(logs).singleElement()
                .satisfies(log -> {
                    assertThat(log.getAction()).isEqualTo(AuditService.WINDOW);
                    assertThat(log.getDetailJson()).isNotNull();
                    assertThat(log.getDetailJson())
                            .containsEntry("op", "extend")
                            .containsEntry("after", "2026-10-08 23:59:59");
                });
    }
}
