package com.tian.textbook.integration.auth;

import com.tian.textbook.auth.AuthService;
import com.tian.textbook.auth.dto.LoginRequest;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.tian.textbook.support.TestSecurity;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败计数与账号锁定回归测试（R1）。
 *
 * <p>缺陷背景：{@code login} 标注 {@code @Transactional}，失败路径抛 {@code BizException}
 * （RuntimeException）→ 默认回滚规则撤销同事务内的所有写入。计数与锁定写在同事务里，
 * <b>写多少都被回滚</b>，{@code max-fail=5} 锁定沦为死代码——攻击者可无限次尝试口令。
 * 该缺陷此前无任何测试覆盖（全仓无 {@code fail_count}/{@code ACCOUNT_LOCKED} 断言），
 * 所以修复后仍带病通过 193 个用例。</p>
 *
 * <p>本用例断言的是<b>落库状态</b>（fail_count / lock_until），而不是异常类型——
 * 只看异常会再次漏掉「逻辑写对但被回滚」这一类问题。</p>
 */
class LoginLockIntegrationTest extends IntegrationTestBase {

    /** 与 application-test.yml 的 textbook.security.login.max-fail 一致 */
    private static final int MAX_FAIL = 5;

    /** 测试用已知口令（满足策略：8 位以上含字母数字） */
    private static final String KNOWN_PASSWORD = "LockTest123";

    @Autowired
    private AuthService authService;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private SysUser givenUser(String userNo) {
        return seeder.user(userNo, "锁定测试", "13800009999", null, null, 1, 0, 1, "ADMIN");
    }

    private void loginWithWrongPassword(String userNo) {
        assertThatThrownBy(() -> authService.login(
                new LoginRequest(userNo, "definitely-wrong-password-1"), "10.0.0.1", "dev"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.LOGIN_FAILED));
    }

    @Test
    @DisplayName("R1：失败计数真实落库（不被事务回滚撤销）")
    void failedLogin_persistsFailCount() {
        SysUser user = givenUser("LOCK1");

        loginWithWrongPassword("LOCK1");

        SysUser reloaded = userMapper.selectByIdSoft(user.getId());
        assertThat(reloaded.getFailCount())
                .as("失败计数必须落库：写在 @Transactional 内且随后抛异常时会被回滚，"
                        + "必须走 REQUIRES_NEW 独立事务")
                .isEqualTo(1);
        assertThat(reloaded.getLockUntil()).isNull();
    }

    @Test
    @DisplayName("R1：连续 max-fail 次失败后账号被锁定，且第 max-fail+1 次返回 ACCOUNT_LOCKED")
    void repeatedFailedLogin_locksAccount() {
        SysUser user = givenUser("LOCK2");

        for (int i = 0; i < MAX_FAIL; i++) {
            loginWithWrongPassword("LOCK2");
        }

        SysUser locked = userMapper.selectByIdSoft(user.getId());
        assertThat(locked.getLockUntil())
                .as("达到 max-fail 必须写入锁定时间（原缺陷：写入被回滚，lock_until 恒为 null）")
                .isNotNull();
        assertThat(locked.getLockUntil()).isAfter(java.time.LocalDateTime.now());

        // 已锁定：即使口令正确也拒绝，且错误码为 ACCOUNT_LOCKED（区别于 LOGIN_FAILED）
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("LOCK2", "whatever-password-1"), "10.0.0.1", "dev"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_LOCKED));
    }

    @Test
    @DisplayName("R1：失败若干次后用正确口令登录成功 → 失败计数清零（清零同样不受回滚影响）")
    void successfulLogin_clearsFailCount() {
        SysUser user = givenUser("LOCK3");
        setPassword(user.getId(), KNOWN_PASSWORD);

        loginWithWrongPassword("LOCK3");
        loginWithWrongPassword("LOCK3");
        assertThat(userMapper.selectByIdSoft(user.getId()).getFailCount()).isEqualTo(2);

        authService.login(new LoginRequest("LOCK3", KNOWN_PASSWORD), "10.0.0.1", "dev");

        assertThat(userMapper.selectByIdSoft(user.getId()).getFailCount())
                .as("口令校验通过即清零（独立事务提交，不受后续流程成败影响）")
                .isZero();
    }

    // ============ 首登校验：失败计数同样落库 + 锁顺序（无自死锁） ============

    @Test
    @DisplayName("首登校验失败：计数落库并计入锁定阈值（同 login，不被回滚撤销）")
    void firstLoginVerify_failurePersistsCount() {
        SysUser user = givenUser("VERIFY1");
        asCurrentUser(user);

        assertThatThrownBy(() -> authService.firstLoginVerify(
                new com.tian.textbook.auth.dto.FirstLoginVerifyRequest("0000", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FIRST_LOGIN_VERIFY_FAILED));

        assertThat(userMapper.selectByIdSoft(user.getId()).getFailCount())
                .as("首登校验失败计数必须落库（同样走 REQUIRES_NEW，不受外层回滚影响）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("首登校验成功：清计数 + 置 verified，且不因 REQUIRES_NEW 与自身事务互锁")
    void firstLoginVerify_successClearsCountWithoutDeadlock() {
        SysUser user = givenUser("VERIFY2");
        // 先制造一次失败，验证成功路径会清零
        asCurrentUser(user);
        assertThatThrownBy(() -> authService.firstLoginVerify(
                new com.tian.textbook.auth.dto.FirstLoginVerifyRequest("0000", null)))
                .isInstanceOf(BizException.class);

        // 手机号 13800009999 → 后 4 位 9999
        // 成功路径会写 first_login_verified；若 clearFailureState（REQUIRES_NEW）排在这些写之后，
        // 独立事务会阻塞在本事务持有的行锁上，本事务又在等它返回 → 自死锁（直到锁等待超时）。
        authService.firstLoginVerify(new com.tian.textbook.auth.dto.FirstLoginVerifyRequest("9999", null));

        SysUser reloaded = userMapper.selectByIdSoft(user.getId());
        assertThat(reloaded.getFirstLoginVerified()).isEqualTo(1);
        assertThat(reloaded.getFailCount()).isZero();
    }

    private void asCurrentUser(SysUser user) {
        TestSecurity.authenticate(user.getId(), user.getUserNo(), user.getName(),
                Set.of("ADMIN"), "ADMIN", seeder.permissionsOf("ADMIN"));
    }

    /** 直接写入 BCrypt 口令（seeder 默认写 {noop} 前缀，无法用于 BCrypt 校验路径）。 */
    private void setPassword(Long userId, String rawPassword) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(rawPassword));
        update.setMustChangePassword(0);
        userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<SysUser>lambdaUpdate().eq(SysUser::getId, userId));
    }
}
