package com.tian.textbook.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 入参时间格式的唯一解析口径（body 与 query 共用）。
 *
 * <p>背景：{@code spring.mvc.format.date-time} 只作用于 Spring MVC 的参数绑定，
 * 对 Jackson 反序列化的 body 无效（{@code spring.jackson.date-format} 只作用于
 * {@code java.util.Date}，JSR-310 类型仍按 {@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}
 * 解析）。结果是同一份文档口径下，body 只认 ISO 的 {@code T}、query 只认空格分隔，
 * 联调时表现为「body 传 {@code 2026-09-21 09:30:00} 直接 400，且提示只有『请求参数有误』」。</p>
 *
 * <p>此处统一为**两种都接受**（前端已按实测适配 ISO，改实现不能破坏既有调用），
 * 出参仍为 ISO-8601 不变（见 API.md §1.1）：</p>
 *
 * <ul>
 *   <li>{@code 2026-09-21T09:30:00}（ISO-8601，推荐）</li>
 *   <li>{@code 2026-09-21 09:30:00}（历史文档口径）</li>
 *   <li>{@code 2026-09-21 09:30}（容忍缺秒，避免前端格式化差异造成 400）</li>
 * </ul>
 */
public final class TimeFormats {

    /** 解析失败时的用户可读提示（异常文案与文档共用一处，避免多处漂移）。 */
    public static final String INPUT_HINT =
            "时间格式应为 ISO-8601（2026-09-21T09:30:00）或 yyyy-MM-dd HH:mm:ss";

    /** 空格分隔（历史文档口径） */
    private static final DateTimeFormatter SPACE_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 容忍缺秒 */
    private static final DateTimeFormatter SPACE_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 纯日期（审计查询的时间范围允许只给日期） */
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 依次尝试的顺序即错误提示里的推荐顺序（ISO 优先）。 */
    private static final List<DateTimeFormatter> DATE_TIME_INPUTS =
            List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME, SPACE_SECOND, SPACE_MINUTE);

    private TimeFormats() {
    }

    /**
     * 解析入参日期时间（ISO-8601 或 {@code yyyy-MM-dd HH:mm[:ss]}）。
     *
     * @throws DateTimeParseException 两种格式都不匹配时抛出（调用方转 400）
     */
    public static LocalDateTime parseInput(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new DateTimeParseException("时间为空", value, 0);
        }
        for (DateTimeFormatter formatter : DATE_TIME_INPUTS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种
            }
        }
        throw new DateTimeParseException(INPUT_HINT, value, 0);
    }

    /** 是否纯日期（{@code yyyy-MM-dd}，不含时间部分）。 */
    public static boolean isDateOnly(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            LocalDate.parse(value, DATE_ONLY);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 纯日期 → 当日 00:00:00（时间范围下界）。 */
    public static LocalDateTime startOfDay(String dateOnly) {
        return LocalDate.parse(dateOnly.trim(), DATE_ONLY).atStartOfDay();
    }

    /** 纯日期 → 当日 23:59:59.999999999（时间范围上界；用 00:00:00 会把当天整天排除在外）。 */
    public static LocalDateTime endOfDay(String dateOnly) {
        return LocalDate.parse(dateOnly.trim(), DATE_ONLY).atTime(java.time.LocalTime.MAX);
    }
}
