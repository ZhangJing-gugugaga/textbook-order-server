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
import java.util.Set;

/**
 * access token 签发与解析（jjwt 0.12.x，SPEC §4：15 分钟；载荷 userId/userNo/roles/currentRole/roleVersion）。
 */
@Service
public class JwtService {

    /** token 类型声明，过滤器只接受 type=access（refresh 走独立端点） */
    public static final String TYPE_ACCESS = "access";

    private final SecretKey key;
    private final int accessMinutes;

    public JwtService(TextbookProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少 32 字节（SPEC §13）");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessMinutes = properties.getJwt().getAccessMinutes();
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
