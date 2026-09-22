package com.tian.textbook.unit.common;

import com.tian.textbook.common.util.IpUtils;
import com.tian.textbook.common.util.SqlLike;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析（S16）与 LIKE 关键字转义（P2）单元测试。
 */
class RequestUtilTest {

    @AfterEach
    void tearDown() {
        IpUtils.configureTrustedProxies(null);
    }

    private MockHttpServletRequest request(String remoteAddr, String xff, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    @Test
    @DisplayName("未配置可信代理：X-Forwarded-For 一律不采信，取 remoteAddr（防伪造绕过限频）")
    void untrustedProxy_ignoresForwardedHeader() {
        IpUtils.configureTrustedProxies("");

        String ip = IpUtils.clientIp(request("10.0.0.9", "1.2.3.4", "5.6.7.8"));

        assertThat(ip).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("可信代理 + 追加式 XFF：从右往左跳过代理，取最靠近服务端的真实客户端")
    void trustedProxy_takesRightmostUntrustedHop() {
        IpUtils.configureTrustedProxies("127.0.0.1,10.0.0.9");

        // nginx 用 proxy_add_x_forwarded_for 追加真实 IP；攻击者自填的 1.2.3.4 在最左侧
        String ip = IpUtils.clientIp(request("10.0.0.9", "1.2.3.4, 203.0.113.7", null));

        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("可信代理但整条链都可信：退化为最左侧值")
    void trustedProxy_allHopsTrusted_usesLeftmost() {
        IpUtils.configureTrustedProxies("127.0.0.1");

        String ip = IpUtils.clientIp(request("127.0.0.1", "127.0.0.1, 127.0.0.1", null));

        assertThat(ip).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("可信代理但无 XFF：回退 X-Real-IP，再回退 remoteAddr")
    void trustedProxy_fallsBackToRealIpThenRemoteAddr() {
        IpUtils.configureTrustedProxies("127.0.0.1");

        assertThat(IpUtils.clientIp(request("127.0.0.1", null, "203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(IpUtils.clientIp(request("127.0.0.1", null, null)))
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("LIKE 转义：% / _ / 转义符被转义，空值返回 null")
    void sqlLike_escapesWildcards() {
        assertThat(SqlLike.escape(null)).isNull();
        assertThat(SqlLike.escape("   ")).isNull();
        assertThat(SqlLike.escape(" 张三 ")).isEqualTo("张三");
        // 单个 % 不再退化为「匹配全表」
        assertThat(SqlLike.escape("%")).isEqualTo("|%");
        assertThat(SqlLike.escape("a_b")).isEqualTo("a|_b");
        assertThat(SqlLike.escape("a|b")).isEqualTo("a||b");
        assertThat(SqlLike.escape("50%_off")).isEqualTo("50|%|_off");
    }

    @Test
    @DisplayName("LIKE 转义：超长关键字截断到 64 字符")
    void sqlLike_truncatesLongKeyword() {
        String escaped = SqlLike.escape("x".repeat(200));

        assertThat(escaped).hasSize(64);
    }
}
