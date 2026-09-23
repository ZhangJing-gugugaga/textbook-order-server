package com.tian.textbook.unit.notify;

import com.tian.textbook.common.util.AppTime;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.notify.service.NoticeScheduler;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.system.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知重发间隔判定单元测试（SPEC §9 W8：{@code notice.interval_hours} 以 system_config 为唯一真源，
 * 改动对未完结任务生效——B-D4 的回归防护）。
 *
 * <p>判定读配置表当前值而非 {@code notice_task.interval_hours} 创建时快照，因此「改配置 →
 * 下一轮扫描的间隔随之变化」。调度器在 test profile 下不注册为 Bean，这里直接 new 出实例驱动
 * {@code resendUnconfirmed()}，以「是否真的调了 resendTask」作为行为断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoticeSchedulerIntervalTest {

    @Mock
    private NotifyService notifyService;
    @Mock
    private NoticeTaskMapper noticeTaskMapper;
    @Mock
    private SemesterActiveService activeSemesterService;
    @Mock
    private ConfigService configService;

    private NoticeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NoticeScheduler(notifyService, noticeTaskMapper, activeSemesterService, configService);
        Semester active = new Semester();
        active.setId(1L);
        when(activeSemesterService.active()).thenReturn(active);
        when(noticeTaskMapper.selectActiveBySemester(1L)).thenReturn(List.of(activeTask()));
    }

    /** 任务的 interval_hours 快照固定为 24，用以证明判定不看它 */
    private NoticeTask activeTask() {
        NoticeTask task = new NoticeTask();
        task.setId(9L);
        task.setSemesterId(1L);
        task.setStatus("active");
        task.setIntervalHours(24);
        return task;
    }

    private void configInterval(int hours) {
        when(configService.getInt(eq(ConfigService.NOTICE_INTERVAL_HOURS), anyInt())).thenReturn(hours);
    }

    @Test
    @DisplayName("无发送记录：新任务立即发首轮（不等 interval_hours）")
    void firstRound_sentImmediately() {
        configInterval(24);
        when(notifyService.lastSentAt(9L)).thenReturn(null);

        scheduler.resendUnconfirmed();

        verify(notifyService).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));
    }

    @Test
    @DisplayName("距上一轮不足配置间隔：跳过本轮")
    void withinInterval_skipped() {
        configInterval(24);
        when(notifyService.lastSentAt(9L)).thenReturn(AppTime.now().minusHours(1));

        scheduler.resendUnconfirmed();

        verify(notifyService, never()).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));
    }

    @Test
    @DisplayName("改小配置后下一轮间隔随之变化：24h → 1h，1 小时前发过的任务本轮即再发")
    void intervalFollowsConfigChange() {
        when(notifyService.lastSentAt(9L)).thenReturn(AppTime.now().minusHours(1));

        configInterval(24);
        scheduler.resendUnconfirmed();
        verify(notifyService, never()).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));

        // 管理员把 notice.interval_hours 从 24 改为 1（值域 1-168），未完结任务立即受影响
        configInterval(1);
        scheduler.resendUnconfirmed();
        verify(notifyService, times(1)).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));
    }

    @Test
    @DisplayName("改大配置后同样生效：1h → 24h，1 小时前发过的任务本轮被跳过")
    void intervalRaised_skipsAgain() {
        when(notifyService.lastSentAt(9L)).thenReturn(AppTime.now().minusHours(1));

        configInterval(1);
        scheduler.resendUnconfirmed();
        verify(notifyService, times(1)).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));

        configInterval(24);
        scheduler.resendUnconfirmed();
        verify(notifyService, times(1)).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));
    }

    @Test
    @DisplayName("配置缺失/非法：回落到默认 24 小时")
    void missingConfig_fallsBackToDefault() {
        configInterval(0);
        when(notifyService.lastSentAt(9L)).thenReturn(AppTime.now().minusHours(23));

        scheduler.resendUnconfirmed();

        verify(notifyService, never()).resendTask(org.mockito.ArgumentMatchers.any(NoticeTask.class));
    }

    @Test
    @DisplayName("无 active 学期：直接返回，不查任务")
    void noActiveSemester_returnsEarly() {
        when(activeSemesterService.active()).thenReturn(null);

        scheduler.resendUnconfirmed();

        verify(noticeTaskMapper, never()).selectActiveBySemester(org.mockito.ArgumentMatchers.anyLong());
    }
}
