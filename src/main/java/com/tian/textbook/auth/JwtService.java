package com.tian.textbook.auth;

import com.tian.textbook.common.config.TextbookProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * access token 签发与解析（jjwt 0.12.x，SPEC §4：15 分钟；载荷 userId/userNo/roles/currentRole/roleVersion）。
 *
 * <p>密钥强度在构造期强校验（fail-fast）：缺失/过短/命中已知弱值一律启动失败。
 * 拒绝弱值的意义在于：一旦漏设 JWT_SECRET 而回落到仓库内公开字符串，
 * 任何读过源码的人都能离线签出超管 token，构成完整的认证绕过。</p>
 */
@Service
public class JwtService {

    /** token 类型声明，过滤器只接受 type=access（refresh 走独立端点） */
    public static final String TYPE_ACCESS = "access";

    /** 密钥最小字节数（HS256 要求 ≥ 32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 已知弱密钥（历史默认值/示例值）：出现即拒绝启动。
     * 比较前统一小写去空白，避免大小写或空白变体绕过。
     */
    private static final Set<String> REJECTED_SECRETS = Set.of(
            "textbook-dev-only-insecure-secret-key-32b",
            "local-dev-only-insecure-secret-key-32bytes",
            "change-me",
            "secret",
            "jwt-secret");

    private final SecretKey key;
    private final int accessMinutes;

    public JwtService(TextbookProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (isUnset(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置：请在环境变量中注入至少 " + MIN_SECRET_BYTES
                            + " 字节的随机密钥（例如 openssl rand -base64 48）。服务拒绝以空密钥启动。");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 至少 " + MIN_SECRET_BYTES + " 字节（当前 " + secretBytes.length + " 字节，SPEC §13）");
        }
        if (REJECTED_SECRETS.contains(secret.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "JWT_SECRET 命中已知弱密钥（仓库内公开的示例/历史默认值），必须更换为随机密钥后重启。");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessMinutes = properties.getJwt().getAccessMinutes();
    }

    /**
     * 密钥是否等于「未配置」：空白、或占位符未被解析时 Spring 原样透传的 {@code ${...}} 文本。
     *
     * <p>后者是运维最常见的误配形态（环境变量名写错、systemd EnvironmentFile 未加载），
     * 若按普通字符串处理会报「至少 32 字节（当前 13 字节）」，让人以为是长度问题。</p>
     */
    private static boolean isUnset(String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        String trimmed = secret.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }

    public String issueAccessToken(Long userId, String userNo, String name,
                                   Set<String> roles, String currentRole, int roleVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userNo", userNo)
                .claim("name", name)
                .claim("roles", List.copyOf(roles))
                .claim("cur", currentRole)
                .claim("rv", roleVersion)
                .claim("typ", TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parseAccessToken(String token) throws JwtException {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!TYPE_ACCESS.equals(claims.get("typ", String.class))) {
            throw new JwtException("not an access token");
        }
        return claims;
    }

    public long accessExpiresInSeconds() {
        return accessMinutes * 60L;
    }
}
