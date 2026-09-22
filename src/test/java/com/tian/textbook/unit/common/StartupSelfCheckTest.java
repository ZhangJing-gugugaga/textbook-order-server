package com.tian.textbook.unit.common;

import com.tian.textbook.common.config.StartupSelfCheck;
import com.tian.textbook.common.config.TextbookProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动自检单元测试：profile 必填、local profile 只允许连本机库、可信代理配置绑定。
 *
 * <p>回归背景：文档让运维设置 {@code TEXTBOOK_TRUSTED_PROXIES}，但属性全名是
 * {@code textbook.security.login.trusted-proxies}，application.yml 里没有对应占位符时
 * Spring 松散绑定会去找 {@code textbook.trusted-proxies}（不存在）并静默忽略——「照文档配了
 * 但不生效」，审计 IP 全记 127.0.0.1、登录限频丢失 IP 维度。</p>
 */
class StartupSelfCheckTest {

    private TextbookProperties propertiesWithTmpDir(String tmpDir) {
        TextbookProperties properties = new TextbookProperties();
        properties.getExport().setTmpDir(tmpDir);
        return properties;
    }

    private MockEnvironment environment(String profile, String dbUrl) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null && !profile.isBlank()) {
            environment.setActiveProfiles(profile);
        }
        if (dbUrl != null) {
            environment.setProperty("spring.datasource.url", dbUrl);
        }
        return environment;
    }

    @Test
    @DisplayName("local profile + 非本机数据库 → 拒绝启动（防误把种子数据写进生产库）")
    void localProfileAgainstRemoteDb_isRefused() {
        StartupSelfCheck check = new StartupSelfCheck(
                environment("local", "jdbc:mysql://10.20.30.40:3306/textbook_order?useSSL=false"),
                propertiesWithTmpDir("target/tmp/selfcheck"));

        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile 只能连接本机数据库");
    }

    @Test
    @DisplayName("local profile + 本机数据库 → 通过；trial profile + 远端库 → 通过")
    void localProfileAgainstLoopback_andTrialProfile_pass() {
        new StartupSelfCheck(environment("local", "jdbc:mysql://127.0.0.1:3306/textbook_order"),
                propertiesWithTmpDir("target/tmp/selfcheck")).afterPropertiesSet();
        new StartupSelfCheck(environment("local", "jdbc:mysql://localhost:3306/textbook_order"),
                propertiesWithTmpDir("target/tmp/selfcheck")).afterPropertiesSet();
        new StartupSelfCheck(environment("trial", "jdbc:mysql://10.20.30.40:3306/textbook_order"),
                propertiesWithTmpDir("target/tmp/selfcheck")).afterPropertiesSet();
    }

    @Test
    @DisplayName("无 profile → 拒绝启动")
    void missingProfile_isRefused() {
        StartupSelfCheck check = new StartupSelfCheck(
                environment("", "jdbc:mysql://127.0.0.1:3306/textbook_order"),
                propertiesWithTmpDir("target/tmp/selfcheck"));

        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }
}
