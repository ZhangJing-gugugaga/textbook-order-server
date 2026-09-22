package com.tian.textbook.auth;

import com.tian.textbook.common.util.AppTime;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class AuthService {

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    /**
     * 弱口令黑名单（小写比较）：这些口令即使满足「8 位含字母数字」也必须在改密时拒绝。
     *
     * <p>初始口令规则是「学号/工号后 6 位」（学号可枚举），若改密只校验字符类别，
     * 用户把初始口令改成一个同样易猜的口令（如 password1、abc12345）等于没改。</p>
     */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "password", "password1", "password123", "passw0rd", "12345678", "123456789",
            "1234567890", "abc12345", "qwerty123", "admin123", "admin@123", "test1234",
            "a1234567", "1qaz2wsx", "iloveyou", "welcome1", "letmein1", "changeme1");

    private final SysUserMapper userMapper;
    private final LoginAttemptGuard loginAttemptGuard;
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

    public AuthService(SysUserMapper userMapper, LoginAttemptGuard loginAttemptGuard,
                       AuthUserService authUserService, JwtService jwtService,
                       RefreshTokenService refreshTokenService, AuditService auditService,
                       PasswordEncoder passwordEncoder, WxMaClient wxMaClient,
                       TextbookProperties properties) {
        this.userMapper = userMapper;
        this.loginAttemptGuard = loginAttemptGuard;
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
        LocalDateTime now = AppTime.now();
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(now)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 计数与锁定必须走独立事务（LoginAttemptGuard，REQUIRES_NEW）：
            // 本方法在 @Transactional 内且此处要抛 BizException(RuntimeException)，
            // 默认回滚规则会把同事务的写入全部撤销——写在这里等于没写，max-fail 锁定失效。
            loginAttemptGuard.recordFailure(user.getId());
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        loginAttemptGuard.clearFailureState(user.getId());
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
        // 同时递增 role_version 使当前 access token 立即失效：access 无状态、默认还要存活 15 分钟，
        // 只撤 refresh 时「登出」并未真正结束会话（被窃取的 access 仍可用满 TTL）。
        // 代价：该用户其他设备/会话也会一并下线（role_version 是用户级版本号）。
        userMapper.incrRoleVersion(current.userId());
        authUserService.evict(current.userId());
        auditService.record(AuditService.LOGOUT, "auth", String.valueOf(current.userId()), null);
    }

    /**
     * 首登校验（W19）：手机号后 4 位匹配 或 绑定 openid（wxCode 换取）。
     * 通过后 first_login_verified=1，才允许改密。
     *
     * <p>失败限频：仅比对手机号后 4 位（10⁴ 空间），叠加「初始密码 = 学号/工号后 6 位
     * （学号可枚举）」构成完整的账号接管链路。故按账号维度对失败计数与锁定，
     * 与登录失败共用 fail_count/lock_until 字段（同一把锁，避免两处各自计数被绕过）。</p>
     */
    @Transactional
    public void firstLoginVerify(FirstLoginVerifyRequest request) {
        var current = com.tian.textbook.common.SecurityUtils.requireCurrentUser();
        // 与登录同一把限频尺：本端点此前完全无频次控制，而「手机号后 4 位」只有 10⁴ 空间
        checkVerifyRateLimit(current.userId());
        SysUser user = userMapper.selectByIdSoft(current.userId());
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        LocalDateTime now = AppTime.now();
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(now)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        // 先完成「校验判定」，暂不写库：openid 与 first_login_verified 的写入必须排在
        // loginAttemptGuard.clearFailureState 之后（见方法末尾的锁顺序说明）。
        String openidToBind = null;
        if (request.phoneTail() != null && !request.phoneTail().isBlank()) {
            if (user.getPhone() == null || user.getPhone().length() < 4
                    || !user.getPhone().substring(user.getPhone().length() - 4).equals(request.phoneTail().trim())) {
                // 同 login：计数必须独立事务提交，否则被本方法的回滚规则撤销
                int failCount = loginAttemptGuard.recordFailure(user.getId());
                auditService.record(AuditService.ACCOUNT, "first-login", String.valueOf(user.getId()),
                        java.util.Map.of("verified", false, "failCount", failCount));
                throw new BizException(ErrorCode.FIRST_LOGIN_VERIFY_FAILED);
            }
        } else if (request.wxCode() != null && !request.wxCode().isBlank()) {
            openidToBind = wxMaClient.code2Openid(request.wxCode());
            if (openidToBind == null) {
                throw new BizException(ErrorCode.FIRST_LOGIN_VERIFY_FAILED, "微信绑定失败，请重试或改用手机号校验");
            }
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "请提供手机号后 4 位或微信授权 code");
        }

        // 【锁顺序】清失败计数走 REQUIRES_NEW（独立连接），必须在**任何本事务对 sys_user 的写之前**执行：
        // 若先写了 openid / first_login_verified，本事务就持有了该行 X 锁，独立事务再去 UPDATE 同一行
        // 会阻塞在本事务的锁上，而本事务又在等独立事务返回 —— 自死锁（直到 innodb_lock_wait_timeout）。
        loginAttemptGuard.clearFailureState(user.getId());

        if (openidToBind != null) {
            SysUser bindOpenid = new SysUser();
            bindOpenid.setId(user.getId());
            bindOpenid.setOpenid(openidToBind);
            userMapper.update(bindOpenid, com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getId, user.getId()));
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setFirstLoginVerified(1);
        userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, user.getId()));
        auditService.record(AuditService.ACCOUNT, "first-login", String.valueOf(user.getId()),
                java.util.Map.of("verified", true));
    }

    /**
     * 首登校验限频（按账号维度，与登录共用 {@code rate-per-minute} 配置）。
     *
     * <p>本端点无 userNo 入参（用当前登录用户），故限频键用 userId；
     * 计数缓存的过期窗口与登录限频一致（1 分钟）。</p>
     */
    private void checkVerifyRateLimit(Long userId) {
        String key = "first-login|" + userId;
        int limit = properties.getSecurity().getLogin().getRatePerMinute();
        AtomicInteger count = loginAttempts.get(key, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > limit) {
            throw new BizException(ErrorCode.RATE_LIMITED);
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
        assertNotWeakPassword(request.newPassword(), user);
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
        // 撤销 refresh + 递增 role_version：JwtAuthFilter 每请求比对 token.rv 与库中 role_version，
        // 只撤销 refresh 时旧 access token 仍可用满 15 分钟（改密后应立即失效）。
        refreshTokenService.revokeAll(user.getId());
        userMapper.incrRoleVersion(user.getId());
        authUserService.evict(user.getId());
        List<SysRole> roles = authUserService.loadRoles(user.getId());
        String currentRole = current.currentRole() != null ? current.currentRole() : roles.get(0).getRoleCode();
        SysUser refreshed = userMapper.selectByIdSoft(user.getId());
        return issueTokens(refreshed == null ? user : refreshed, roles, currentRole, deviceId);
    }

    /**
     * 弱口令拒绝：黑名单命中、或与本人学号/工号（含后 6 位初始口令规则）相同。
     *
     * <p>「新密码 = 学号/工号」等于把初始口令规则又用了一遍，攻击者已知学号即可登录。</p>
     */
    private void assertNotWeakPassword(String newPassword, SysUser user) {
        String lower = newPassword.toLowerCase(java.util.Locale.ROOT);
        if (WEAK_PASSWORDS.contains(lower)) {
            throw new BizException(ErrorCode.PASSWORD_POLICY, "新密码过于简单，请更换更复杂的密码");
        }
        String userNo = user.getUserNo() == null ? "" : user.getUserNo();
        if (userNo.isEmpty()) {
            return;
        }
        String lowerUserNo = userNo.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals(lowerUserNo)) {
            throw new BizException(ErrorCode.PASSWORD_POLICY, "新密码不能与学号/工号相同");
        }
        if (userNo.length() >= 6 && lower.equals(lowerUserNo.substring(lowerUserNo.length() - 6))) {
            throw new BizException(ErrorCode.PASSWORD_POLICY, "新密码不能是学号/工号后 6 位");
        }
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
