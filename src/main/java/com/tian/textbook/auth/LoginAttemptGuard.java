package com.tian.textbook.auth;

import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.util.AppTime;
import com.tian.textbook.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 登录/首登失败计数与锁定（**独立事务**）。
 *
 * <p>为什么必须是独立事务：调用方（{@link AuthService#login}）本身在 {@code @Transactional} 内，
 * 失败路径会抛 {@link com.tian.textbook.common.error.BizException}（RuntimeException）→
 * 默认回滚规则撤销整个事务的所有写入。<b>计数与锁定若与抛异常同事务，写多少都会被回滚掉</b>，
 * {@code max-fail} 锁定沦为死代码——攻击者可无限次尝试口令。</p>
 *
 * <p>本类的方法以 {@code REQUIRES_NEW} 提交，与外层事务解耦：
 * 外层无论回滚与否，失败计数都已落库。注意必须是<b>独立 bean</b>——
 * 同类内自调用不经过 Spring 代理，{@code REQUIRES_NEW} 不会生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    private final SysUserMapper userMapper;
    private final TextbookProperties properties;

    /**
     * 记一次失败：计数 +1，达阈值则同时置锁定时间。独立事务提交。
     *
     * <p>计数用单条 {@code SET fail_count = fail_count + 1} 由数据库行锁串行化，
     * 并发失败不会互相覆盖（此前的「先 SELECT 读初值、再 UPDATE 写初值+1」会丢失更新）。</p>
     *
     * @return 自增后的失败次数（已含本次）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long userId) {
        userMapper.incrFailCount(userId);
        int failCount = userMapper.selectFailCount(userId);
        int maxFail = properties.getSecurity().getLogin().getMaxFail();
        if (failCount >= maxFail) {
            LocalDateTime lockUntil = AppTime.now()
                    .plusMinutes(properties.getSecurity().getLogin().getLockMinutes());
            userMapper.resetFailCountAndLock(userId, lockUntil);
            log.warn("账号连续失败次数达上限，已锁定: userId={}, failCount={}, lockUntil={}",
                    userId, failCount, lockUntil);
        }
        return failCount;
    }

    /** 口令校验通过即清零失败计数与锁定（独立事务提交，不受后续流程成败影响）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearFailureState(Long userId) {
        userMapper.clearFailState(userId);
    }
}
