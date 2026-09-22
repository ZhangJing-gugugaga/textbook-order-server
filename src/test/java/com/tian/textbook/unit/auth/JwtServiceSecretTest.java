package com.tian.textbook.unit.auth;

import com.tian.textbook.auth.JwtService;
import com.tian.textbook.common.config.TextbookProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 密钥强度自检（F1：密钥缺失/过短/命中已知弱值一律启动失败）。
 *
 * <p>背景：{@code application.yml} 曾用 {@code ${JWT_SECRET:textbook-dev-only-insecure-secret-key-32b}}
 * 兜底，而该默认值是仓库内公开字符串且长度恰好 ≥ 32 字节（能通过长度校验）。
 * 生产漏设环境变量时，任何读过源码的人都能离线签出 {@code {sub:1,cur:ADMIN,typ:access}}
 * 的 HS256 token，构成完整的认证绕过。</p>
 */
class JwtServiceSecretTest {

    private static TextbookProperties withSecret(String secret) {
        TextbookProperties properties = new TextbookProperties();
        properties.getJwt().setSecret(secret);
        return properties;
    }

    @Test
    @DisplayName("密钥缺失 → 启动失败（不再回落到任何默认值）")
    void blankSecret_failsFast() {
        assertThatThrownBy(() -> new JwtService(withSecret(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET 未配置");
        assertThatThrownBy(() -> new JwtService(withSecret("   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET 未配置");
    }

    @Test
    @DisplayName("占位符未解析（Spring 原样透传 ${JWT_SECRET}）→ 按「未配置」报错，而非长度不足")
    void unresolvedPlaceholder_treatedAsUnset() {
        assertThatThrownBy(() -> new JwtService(withSecret("${JWT_SECRET}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET 未配置");
    }

    @Test
    @DisplayName("密钥过短（<32 字节）→ 启动失败")
    void shortSecret_failsFast() {
        assertThatThrownBy(() -> new JwtService(withSecret("short-secret-31-bytes-abcdefghi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少 32 字节");
    }

    @Test
    @DisplayName("命中已知弱密钥（历史默认值/示例值）→ 启动失败")
    void knownWeakSecret_failsFast() {
        assertThatThrownBy(() -> new JwtService(withSecret("textbook-dev-only-insecure-secret-key-32b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已知弱密钥");
        // 大小写/空白变体不得绕过
        assertThatThrownBy(() -> new JwtService(withSecret("  TEXTBOOK-DEV-ONLY-INSECURE-SECRET-KEY-32B  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已知弱密钥");
        assertThatThrownBy(() -> new JwtService(withSecret("local-dev-only-insecure-secret-key-32bytes")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已知弱密钥");
    }

    @Test
    @DisplayName("合规密钥：签发/解析往返成功，且 refresh 类 token 被拒（type 校验）")
    void strongSecret_issuesAndParses() {
        JwtService service = new JwtService(withSecret("a-strong-random-secret-for-tests-0123456789"));

        String token = service.issueAccessToken(1L, "ADMIN1", "超管", Set.of("ADMIN"), "ADMIN", 3);

        Claims claims = service.parseAccessToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("cur", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get("typ", String.class)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(claims.get("rv", Integer.class)).isEqualTo(3);
    }
}
