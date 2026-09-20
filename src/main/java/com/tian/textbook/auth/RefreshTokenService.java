package com.tian.textbook.auth;

import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.system.entity.SysUserToken;
import com.tian.textbook.system.mapper.SysUserTokenMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * refresh 令牌服务（W21：随机串 SHA-256 落库；轮换 = 旧行 revoked=1 发新行；
 * 登出/停用/改密/角色变更 → 撤销该用户全部 refresh）。
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserTokenMapper tokenMapper;
    private final int refreshDays;

    public RefreshTokenService(SysUserTokenMapper tokenMapper, TextbookProperties properties) {
        this.tokenMapper = tokenMapper;
        this.refreshDays = properties.getJwt().getRefreshDays();
    }

    /** 签发 refresh token：返回明文（仅此一次），落库为 SHA-256 */
    @Transactional
    public String issue(Long userId, String deviceId) {
        String plain = newToken();
        SysUserToken token = new SysUserToken();
        token.setUserId(userId);
        token.setTokenHash(sha256(plain));
        token.setExpireAt(LocalDateTime.now().plusDays(refreshDays));
        token.setRevoked(0);
        token.setDeviceId(deviceId);
        token.setDeleted(0L);
        tokenMapper.insert(token);
        return plain;
    }

    /** 按明文查库（不含撤销/过期判定，供 refresh 端点定位用户）。 */
    @Transactional(readOnly = true)
    public SysUserToken find(String plainToken) {
        return tokenMapper.selectByHash(sha256(plainToken));
    }

    /** 校验并轮换：旧行置 revoked，发新行；失败返回 null（调用方按 REFRESH_INVALID 处理） */
    @Transactional
    public String rotate(String plainToken, Long userId, String deviceId) {
        SysUserToken existing = tokenMapper.selectByHash(sha256(plainToken));
        if (existing == null || existing.getRevoked() != 0
                || existing.getExpireAt() == null || existing.getExpireAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        if (!existing.getUserId().equals(userId)) {
            return null;
        }
        tokenMapper.revokeById(existing.getId());
        return issue(userId, deviceId);
    }

    @Transactional
    public void revokeAll(Long userId) {
        tokenMapper.revokeAllByUser(userId);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256(String plain) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
