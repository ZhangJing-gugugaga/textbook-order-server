package com.tian.textbook.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时钟（唯一时区口径）。
 *
 * <p>窗口引擎与看板一直显式使用 Asia/Shanghai，但业务代码（窗口判定、提交时间、审核时间、
 * 补正截止、令牌过期）此前用 {@link LocalDateTime#now()} 取 JVM 默认时区。时区保证只剩
 * 启动参数 {@code -Duser.timezone}（仅在 surefire 里配了），生产漏配即让窗口判定差 8 小时，
 * 征订提前或延后开关。此处统一口径，不再依赖 JVM 默认时区。</p>
 *
 * <p>库列均为 DATETIME（无时区），语义即「北京墙上时间」，故用 {@link LocalDateTime} +
 * 固定 ZoneId 而非 Instant。</p>
 */
public final class AppTime {

    /** 业务时区（与 WindowEngineScheduler / StatsService / @Scheduled zone 一致） */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AppTime() {
    }

    /** 当前业务时间（Asia/Shanghai）。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
