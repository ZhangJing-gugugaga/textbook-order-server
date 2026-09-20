package com.tian.textbook.semester;

import com.tian.textbook.semester.entity.Semester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 窗口引擎定时扫描（SPEC §6：每分钟，ZoneId=Asia/Shanghai，仅扫 active 学期；
 * 幂等：状态已变则跳过；auto_open/auto_close=false 时到点不改状态）。
 *
 * <p>test profile 下关闭（测试直接调 SemesterService.applyAutoTransition，保证确定性）。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class WindowEngineScheduler {

    /** 时区固定 Asia/Shanghai（W11，JVM -Duser.timezone 双保险） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SemesterService semesterService;
    private final SemesterActiveService activeSemesterService;

    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    public void scanWindow() {
        Semester active = activeSemesterService.active();
        if (active == null) {
            return;
        }
        // 与切换/手动变更持同一把锁，避免切换中窗口状态错配（R12）
        SemesterService.SEMESTER_LOCK.lock();
        try {
            Semester fresh = activeSemesterService.active();
            if (fresh == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(ZONE);
            // 自动开启：auto_open=1 && channel_open=1 && not_open && now >= window_start
            if (Integer.valueOf(1).equals(fresh.getAutoOpen())
                    && Integer.valueOf(1).equals(fresh.getChannelOpen())
                    && "not_open".equals(fresh.getWindowStatus())
                    && fresh.getWindowStart() != null && !now.isBefore(fresh.getWindowStart())) {
                semesterService.applyAutoTransition(fresh.getId(), "open", true, "到点自动开启征订窗口");
                log.info("窗口自动开启: semester={}", fresh.getId());
                return;
            }
            // 自动截止：auto_close=1 && open && now >= window_end
            if (Integer.valueOf(1).equals(fresh.getAutoClose())
                    && "open".equals(fresh.getWindowStatus())
                    && fresh.getWindowEnd() != null && !now.isBefore(fresh.getWindowEnd())) {
                semesterService.applyAutoTransition(fresh.getId(), "closed", false, "到点自动截止征订窗口");
                log.info("窗口自动截止: semester={}", fresh.getId());
            }
        } finally {
            SemesterService.SEMESTER_LOCK.unlock();
        }
    }
}
