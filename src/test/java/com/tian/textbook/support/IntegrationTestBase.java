package com.tian.textbook.support;

import com.tian.textbook.TextbookOrderServerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：H2（MySQL 模式）+ schema.sql 转换加载 + 完整 Spring 上下文。
 *
 * <p>schema 由 test 的 spring.factories 注册的 ReadyListener 在应用就绪后执行；
 * 定时任务在 test profile 关闭（{@code @Profile("!test")}），测试直接调 Service 方法。</p>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
}
