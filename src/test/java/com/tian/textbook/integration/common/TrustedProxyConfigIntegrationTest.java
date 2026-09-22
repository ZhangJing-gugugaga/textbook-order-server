package com.tian.textbook.integration.common;

import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.util.IpUtils;
import com.tian.textbook.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可信反向代理配置绑定（部署手册要求运维设置 {@code TEXTBOOK_TRUSTED_PROXIES}）。
 *
 * <p>回归背景：属性全名是 {@code textbook.security.login.trusted-proxies}，若 application.yml
 * 里没有 {@code ${TEXTBOOK_TRUSTED_PROXIES:}} 占位符，Spring 松散绑定会去找
 * {@code textbook.trusted-proxies}（不存在）并静默忽略——运维「照文档配了但不生效」：
 * 审计 IP 全记成 127.0.0.1、登录限频从「IP+账号」退化为「仅账号」。本用例锁定
 * 「环境变量名 → 占位符 → 属性 → IpUtils」整条链路。</p>
 */
@TestPropertySource(properties = "TEXTBOOK_TRUSTED_PROXIES=127.0.0.1,::1")
class TrustedProxyConfigIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TextbookProperties properties;

    @Test
    @DisplayName("TEXTBOOK_TRUSTED_PROXIES 生效：绑定到 security.login.trusted-proxies 并被 IpUtils 采信")
    void trustedProxies_bindsAndApplies() {
        assertThat(properties.getSecurity().getLogin().getTrustedProxies())
                .as("环境变量必须能绑定到 textbook.security.login.trusted-proxies")
                .isEqualTo("127.0.0.1,::1");

        // IpUtils 是静态工具：启动自检已把白名单注入，采信 XFF 的前提是 remoteAddr 在白名单内
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(IpUtils.clientIp(request))
                .as("来自可信代理的 XFF 应被采信（否则审计 IP 恒为 127.0.0.1）")
                .isEqualTo("203.0.113.7");
    }
}
