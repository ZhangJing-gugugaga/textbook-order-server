package com.tian.textbook.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.auth.dto.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 认证服务（PRD 模块 1 / SPEC §4）：登录锁定落库、首登校验、refresh 轮换、身份切换。
 */
@Service
public class AuthService {

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private final SysUserMapper userMapper;
    private final AuthUserService authUserService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final WxMaClient wxMaClient;
    private final TextbookProperties properties;

    /** 同 IP/账号 1 分钟粒度限频（Caffeine 加速，W20） */
    private final Cache<String, AtomicInteger> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    public AuthService(SysUserMapper userMapper, AuthUserService authUserService, JwtService jwtService,
                       RefreshTokenService refreshTokenService, AuditService auditService,
                       PasswordEncoder passwordEncoder, WxMaClient wxMaClient,
                       TextbookProperties properties) {
        this.userMapper = userMapper;
        this.authUserService = authUserService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.wxMaClient = wxMaClient;
        this.properties = properties;
    }

    public PasswordEncoder passwordEncoder() {
        return passwordEncoder;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ip, String deviceId) {
        checkRateLimit(ip, request.userNo());
        SysUser user = userMapper.selectByUserNo(request.userNo());
        if (user == null) {
            // 防账号枚举：与密码错误同一文案，且不落失败计数
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(now)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int failCount = (user.getFailCount() == null ? 0 : user.getFailCount()) + 1;
            if (failCount >= properties.getSecurity().getLogin().getMaxFail()) {
                userMapper.resetFailCountAndLock(user.getId(), now.plusMinutes(properties.getSecurity().getLogin().getLockMinutes()));
            } else {
                userMapper.incrFailCount(user.getId());
            }
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        userMapper.clearFailState(user.getId());
        List<SysRole> roles = authUserService.loadRoles(user.getId());
        if (roles.isEmpty()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "账号未分配角色，请联系教材室");
        }
        String currentRole = roles.get(0).getRoleCode();
        AuthResponse response = issueTokens(user, roles, currentRole, deviceId);
        auditService.record(AuditService.LOGIN, "auth", String.valueOf(user.getId()),
                java.util.Map.of("userNo", user.getUserNo()));
        return response;
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, String deviceId) {
        SysUserTokenHolder holder = resolveRefreshToken(request.refreshToken());
        if (holder == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        SysUser user = userMapper.selectByIdSoft(holder.userId());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        String newRefresh = refreshTokenService.rotate(request.refreshToken(), user.getId(), deviceId);
        if (newRefresh == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        List<SysRole> roles = authUserService.loadRoles(user.getId());
        String currentRole = roles.get(0).getRoleCode();
        return issueTokens(user, roles, currentRole, deviceId, newRefresh);
    }

    @Transactional
    public void logout() {
        var current = com.tian.textbook.common.SecurityUtils.requireCurrentUser();
        refreshTokenService.revokeAll(current.userId());
        auditService.record(AuditService.LOGOUT, "auth", String.valueOf(current.userId()), null);
    }

    /**
     * 首登校验（W19）：手机号后 4 位匹配 或 绑定 openid（wxCode 换取）。
     * 通过后 first_login_verified=1，才允许改密。
     */
    @Transactional
    public void firstLoginVerify(FirstLoginVerifyRequest request) {
        var current = com.tian.textbook.common.SecurityUtils.requireCurrentUser();
        SysUser user = userMapper.selectByIdSoft(current.userId());
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        boolean verified = false;
        if (request.phoneTail() != null && !request.phoneTail().isBlank()) {
            if (user.getPhone() == null || user.getPhone().length() < 4
                    || !user.getPhone().substring(user.getPhone().length() - 4).equals(request.phoneTail().trim())) {
                throw new BizException(ErrorCode.FIRST_LOGIN_VERIFY_FAILED);
            }
            verified = true;
        } else if (request.wxCode() != null && !request.wxCode().isBlank()) {
            String openid = wxMaClient.code2Openid(request.wxCode());
            if (openid == null) {
                throw new BizException(ErrorCode.FIRST_LOGIN_VERIFY_FAILED, "微信绑定失败，请重试或改用手机号校验");
            }
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setOpenid(openid);
            userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getId, user.getId()));
            verified = true;
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "请提供手机号后 4 位或微信授权 code");
        }
        if (verified) {
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setFirstLoginVerified(1);
            userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getId, user.getId()));
            auditService.record(AuditService.ACCOUNT, "first-login", String.valueOf(user.getId()),
                    java.util.Map.of("verified", true));
        }
    }

    /**
     * 切换身份（W10）：仅切换 currentRole 与权限码集合，数据范围不变；旧 access 作废重发。
     */
    @Transactional
    public AuthResponse switchRole(SwitchRoleRequest request, String deviceId) {
        var current = com.tian.textbook.common.SecurityUtils.requireCurrentUser();
        List<SysRole> roles = authUserService.loadRoles(current.userId());
        boolean has = roles.stream().anyMatch(r -> r.getRoleCode().equals(request.roleCode()));
        if (!has) {
            throw new BizException(ErrorCode.FORBIDDEN, "不存在的身份");
        }
        SysUser user = userMapper.selectByIdSoft(current.userId());
        AuthResponse response = issueTokens(user, roles, request.roleCode(), deviceId);
        auditService.record(AuditService.ACCOUNT, "switch-role", String.valueOf(user.getId()),
                java.util.Map.of("from", String.valueOf(current.currentRole()), "to", request.roleCode()));
        return response;
    }

    /**
     * 修改密码：新密码 8-64 位含字母数字；首登流程须先通过首登校验；
     * 改密后撤销全部 refresh 并重发（SPEC §11.1）。
     */
    @Transactional
    public AuthResponse changePassword(ChangePasswordRequest request, String deviceId) {
        var current = com.tian.textbook.common.SecurityUtils.requireCurrentUser();
        SysUser user = userMapper.selectByIdSoft(current.userId());
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "原密码不正确");
        }
        if (!PASSWORD_POLICY.matcher(request.newPassword()).matches()) {
            throw new BizException(ErrorCode.PASSWORD_POLICY);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.PASSWORD_POLICY, "新密码不能与当前密码相同");
        }
        if (Integer.valueOf(1).equals(user.getMustChangePassword())
                && !Integer.valueOf(1).equals(user.getFirstLoginVerified())) {
            throw new BizException(ErrorCode.FIRST_LOGIN_VERIFY_FAILED, "请先完成首登校验再修改密码");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        update.setMustChangePassword(0);
        userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, user.getId()));
        refreshTokenService.revokeAll(user.getId());
        List<SysRole> roles = authUserService.loadRoles(user.getId());
        String currentRole = current.currentRole() != null ? current.currentRole() : roles.get(0).getRoleCode();
        return issueTokens(user, roles, currentRole, deviceId);
    }

    /** 签发 access + refresh（currentRole 的权限码集合）。 */
    private AuthResponse issueTokens(SysUser user, List<SysRole> roles, String currentRole, String deviceId) {
        return issueTokens(user, roles, currentRole, deviceId, null);
    }

    private AuthResponse issueTokens(SysUser user, List<SysRole> roles, String currentRole,
                                     String deviceId, String existingRefresh) {
        Set<String> permissions = authUserService.permissionsOf(roles, currentRole);
        String access = jwtService.issueAccessToken(user.getId(), user.getUserNo(), user.getName(),
                roles.stream().map(SysRole::getRoleCode).collect(Collectors.toSet()),
                currentRole, user.getRoleVersion());
        String refresh = existingRefresh != null ? existingRefresh : refreshTokenService.issue(user.getId(), deviceId);
        return new AuthResponse(access, refresh, jwtService.accessExpiresInSeconds(),
                Integer.valueOf(1).equals(user.getMustChangePassword()),
                Integer.valueOf(1).equals(user.getFirstLoginVerified()),
                roles.stream().map(SysRole::getRoleCode).collect(Collectors.toList()),
                currentRole, user.getUserNo(), user.getName());
    }

    private SysUserTokenHolder resolveRefreshToken(String plain) {
        // 直接查库轮换（hash 查找在 RefreshTokenService.rotate 内完成）；此处仅取 userId 供签发
        var token = refreshTokenService.find(plain);
        return token == null ? null : new SysUserTokenHolder(token.getUserId(), token.getExpireAt());
    }

    private record SysUserTokenHolder(Long userId, LocalDateTime expireAt) {
    }

    private void checkRateLimit(String ip, String userNo) {
        String key = (ip == null ? "?" : ip) + "|" + userNo;
        int limit = properties.getSecurity().getLogin().getRatePerMinute();
        AtomicInteger count = loginAttempts.get(key, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > limit) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }
}
