package com.tian.textbook.notify.service;

import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.util.AppTime;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.system.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知订阅消息重发调度（SPEC §9；BE-5c：每小时扫描 + {@code notice.interval_hours} 间隔判定）。
 *
 * <p>扫 active 学期 active 任务 → 未确认且已授权用户 → 订阅消息（仅 STUDENT）；
 * 每轮尝试写 notice_record；达 round_limit（system_config 唯一真源，W8）停止订阅重发，
 * 弹窗通道不设轮次上限（Q7）。</p>
 *
 * <p><b>为什么从「每日 09:30 固定」改为「每小时扫描 + 间隔判定」</b>：固定时刻有两个问题——
 * ① 任务创建后最长要等 24 小时才发首轮（管理员点「创建并发送」，实际什么也没发出去）；
 * ② {@code notice.interval_hours} 是死配置（只在创建时快照，从不被读取）。现在：无发送记录
 * （新任务）→ 立即发首轮；距最近一轮不足 interval_hours → 跳过；否则发下一轮。
 * 窗口非开放时 {@link NotifyService#resendTask} 自行跳过（BE-5a）。</p>
 *
 * <p><b>间隔判定读 system_config 当前值</b>（而非 {@code notice_task.interval_hours} 快照）：
 * 与 {@code round_limit} 语义统一——两者都是「配置表唯一真源、改动对未完结任务生效」（W8）；
 * 表上同名列仅为创建时快照，供列表展示与追溯。改动最迟在下一小时的扫描轮生效。</p>
 *
 * <p>test profile 下关闭（测试直接调 NotifyService 方法，保证确定性）。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class NoticeScheduler {

    /** interval_hours 缺失/非法时的兜底（与 system_config 默认值一致） */
    private static final int DEFAULT_INTERVAL_HOURS = 24;

    private final NotifyService notifyService;
    private final NoticeTaskMapper noticeTaskMapper;
    private final SemesterActiveService activeSemesterService;
    private final ConfigService configService;

    @Scheduled(cron = "0 5 * * * ?", zone = "Asia/Shanghai")
    public void resendUnconfirmed() {
        Semester active = activeSemesterService.active();
        if (active == null) {
            return;
        }
        // 定时任务不经拦截器，自行 set/clear 学期上下文快照（SPEC §5.4）
        SemesterContextHolder.set(active.getId());
        try {
            List<NoticeTask> tasks = noticeTaskMapper.selectActiveBySemester(active.getId());
            if (tasks.isEmpty()) {
                return;
            }
            for (NoticeTask task : tasks) {
                try {
                    if (!intervalElapsed(task)) {
                        log.info("距上一轮发送不足 interval_hours，跳过本轮: task={}, intervalHours={}",
                                task.getId(), currentIntervalHours());
                        continue;
                    }
                    notifyService.resendTask(task);
                } catch (Exception e) {
                    // 单任务失败不影响其他任务；异常如实记录，下一轮扫描自然重试
                    log.warn("通知重发失败: task={}, err={}", task.getId(), e.getMessage());
                }
            }
        } finally {
            SemesterContextHolder.clear();
        }
    }

    /** 当前生效的间隔（system_config 实时读取，W8；无缓存，见 {@link ConfigService}） */
    private int currentIntervalHours() {
        int configured = configService.getInt(ConfigService.NOTICE_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS);
        return configured <= 0 ? DEFAULT_INTERVAL_HOURS : configured;
    }

    /**
     * 是否已到下一轮发送时间：无发送记录 → 立即发（新任务首轮不必等下一个周期）；
     * 否则要求距最近一轮 ≥ {@code notice.interval_hours}（**system_config 当前值**，W8）。
     */
    private boolean intervalElapsed(NoticeTask task) {
        java.time.LocalDateTime lastSentAt = notifyService.lastSentAt(task.getId());
        if (lastSentAt == null) {
            return true;
        }
        return !AppTime.now().isBefore(lastSentAt.plusHours(currentIntervalHours()));
    }
}
