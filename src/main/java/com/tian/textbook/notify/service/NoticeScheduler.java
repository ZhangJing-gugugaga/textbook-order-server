package com.tian.textbook.notify.service;

import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知订阅消息重发调度（SPEC §9：每日 09:30，Asia/Shanghai；单机进程内，多实例换 xxl-job）。
 *
 * <p>扫 active 学期 active 任务 → 未确认且已授权用户 → 订阅消息（仅 STUDENT）；
 * 每轮尝试写 notice_record；达 round_limit（system_config 唯一真源，W8）停止订阅重发，
 * 弹窗通道不设轮次上限（Q7）。</p>
 *
 * <p>test profile 下关闭（测试直接调 NotifyService 方法，保证确定性）。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class NoticeScheduler {

    private final NotifyService notifyService;
    private final NoticeTaskMapper noticeTaskMapper;
    private final SemesterActiveService activeSemesterService;

    @Scheduled(cron = "0 30 9 * * ?", zone = "Asia/Shanghai")
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
}
