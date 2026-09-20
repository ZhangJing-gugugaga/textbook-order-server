package com.tian.textbook.support;

import com.tian.textbook.TextbookOrderServerApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.BeforeEach;

/**
 * 集成测试基类：H2（MySQL 模式）+ schema.sql 转换加载 + 完整 Spring 上下文。
 *
 * <p>schema 由 test 的 spring.factories 注册的 ReadyListener 在应用就绪后执行；
 * 定时任务在 test profile 关闭（{@code @Profile("!test")}），测试直接调 Service 方法。</p>
 *
 * <p>每个用例前全量清表 + 补种 RBAC（test profile 不执行 data-permission.sql），
 * 保证用例间隔离；{@link TestDataSeeder} 提供 Builder 风格数据工厂。</p>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @Autowired
    protected TestDataSeeder seeder;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
    }
}
