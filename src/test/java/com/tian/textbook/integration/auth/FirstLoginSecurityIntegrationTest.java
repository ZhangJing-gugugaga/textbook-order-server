package com.tian.textbook.integration.auth;

import com.tian.textbook.auth.AuthService;
import com.tian.textbook.auth.WxMaClient;
import com.tian.textbook.auth.dto.ChangePasswordRequest;
import com.tian.textbook.auth.dto.FirstLoginVerifyRequest;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 首登校验与改密闸门的安全回归（P1）。
 *
 * <p>两条攻击链（都建立在「初始口令 = 学号/工号后 6 位，学号可枚举」之上）：</p>
 * <ol>
 *   <li><b>wxCode 分支可接管账号</b>：原实现把 wxCode 换来的 openid 直接写入并置
 *       {@code first_login_verified=1}，与账号已有 openid 无任何比对 → 任何人
 *       「初始口令登录 → 用自己的微信过首登 → 改密」即可接管未首登账号，
 *       手机号后 4 位这一唯一补偿控制被整段跳过（也与 schema 注释
 *       「openid 仅订阅消息推送用，不作认证」相悖）；</li>
 *   <li><b>重置密码不清验证标记</b>：管理员重置口令后 {@code first_login_verified} 保留 1，
 *       改密闸门（mustChangePassword=1 且 firstLoginVerified≠1）不再成立 →
 *       知道学号即可登录后直接改密接管。</li>
 * </ol>
 */
class FirstLoginSecurityIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private SysUserMapper userMapper;

    /** 微信客户端打桩：真实实现需要 appid/secret 与微信服务，测试里只关心 openid 的比对语义。 */
    @MockBean
    private WxMaClient wxMaClient;

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    private SysUser givenPendingFirstLogin(String userNo, String phone, String openid) {
        SysUser user = seeder.user(userNo, "首登测试", phone, null, null, 1, 1, 0, "STUDENT");
        if (openid != null) {
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setOpenid(openid);
            userMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<SysUser>lambdaUpdate().eq(SysUser::getId, user.getId()));
        }
        TestSecurity.authenticate(user.getId(), userNo, "首登测试", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
        return userMapper.selectByIdSoft(user.getId());
    }

    @Test
    @DisplayName("wxCode 不能新建 openid 绑定：未绑定的账号用自己的微信过首登必须被拒（防账号接管）")
    void firstLogin_wxCodeCannotBindNewOpenid() {
        SysUser user = givenPendingFirstLogin("FL1", "13800000001", null);
        when(wxMaClient.code2Openid(eq("attacker-code"))).thenReturn("openid-attacker");

        assertThatThrownBy(() -> authService.firstLoginVerify(new FirstLoginVerifyRequest(null, "attacker-code")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FIRST_LOGIN_VERIFY_FAILED));

        SysUser reloaded = userMapper.selectByIdSoft(user.getId());
        assertThat(reloaded.getFirstLoginVerified()).as("首登不得通过").isZero();
        assertThat(reloaded.getOpenid()).as("不得绑定攻击者的 openid").isNull();
        assertThat(reloaded.getFailCount()).as("失败计数落库（与手机号错共用一把锁）").isEqualTo(1);
    }

    @Test
    @DisplayName("wxCode 只能校验已绑定的 openid：绑定一致时通过，不一致时拒绝")
    void firstLogin_wxCodeVerifiesBoundOpenidOnly() {
        SysUser user = givenPendingFirstLogin("FL2", "13800000002", "openid-owner");
        when(wxMaClient.code2Openid(eq("owner-code"))).thenReturn("openid-owner");
        when(wxMaClient.code2Openid(eq("other-code"))).thenReturn("openid-other");

        assertThatThrownBy(() -> authService.firstLoginVerify(new FirstLoginVerifyRequest(null, "other-code")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未绑定");

        authService.firstLoginVerify(new FirstLoginVerifyRequest(null, "owner-code"));
        assertThat(userMapper.selectByIdSoft(user.getId()).getFirstLoginVerified()).isEqualTo(1);
    }

    @Test
    @DisplayName("手机号后 4 位通过后才绑定 openid；wxCode 换 openid 失败不阻断校验")
    void firstLogin_phoneTailBindsOpenidAfterVerification() {
        SysUser user = givenPendingFirstLogin("FL3", "13800000003", null);
        when(wxMaClient.code2Openid(eq("ok-code"))).thenReturn("openid-bound");
        when(wxMaClient.code2Openid(eq("bad-code"))).thenReturn(null);

        authService.firstLoginVerify(new FirstLoginVerifyRequest("0003", "ok-code"));
        SysUser verified = userMapper.selectByIdSoft(user.getId());
        assertThat(verified.getFirstLoginVerified()).isEqualTo(1);
        assertThat(verified.getOpenid()).isEqualTo("openid-bound");

        // 微信侧失败：主因子已过，校验仍通过（订阅消息通道降级为 unauthorized，弹窗不受影响）
        SysUser another = givenPendingFirstLogin("FL4", "13800000004", null);
        TestSecurity.authenticate(another.getId(), "FL4", "首登测试", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
        authService.firstLoginVerify(new FirstLoginVerifyRequest("0004", "bad-code"));
        SysUser after = userMapper.selectByIdSoft(another.getId());
        assertThat(after.getFirstLoginVerified()).isEqualTo(1);
        assertThat(after.getOpenid()).isNull();
    }

    @Test
    @DisplayName("重置密码必须清 first_login_verified：否则知道学号即可直接改密接管")
    void resetPassword_clearsFirstLoginVerified() {
        SysUser user = seeder.user("FL5", "首登测试", "13800000005", null, null, 1, 0, 1, "STUDENT");
        assertThat(user.getFirstLoginVerified()).isEqualTo(1);

        userService.resetPassword(user.getId());

        SysUser reloaded = userMapper.selectByIdSoft(user.getId());
        assertThat(reloaded.getMustChangePassword()).isEqualTo(1);
        assertThat(reloaded.getFirstLoginVerified())
                .as("重置后必须重新走首登校验，否则初始口令（学号后 6 位）+ 直接改密 = 账号接管")
                .isZero();

        // 闸门生效：未完成首登校验时改密被拒
        TestSecurity.authenticate(user.getId(), "FL5", "首登测试", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
        assertThatThrownBy(() -> authService.changePassword(
                new ChangePasswordRequest("FL5", "NewPass123"), "dev"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FIRST_LOGIN_VERIFY_FAILED));
    }
}
