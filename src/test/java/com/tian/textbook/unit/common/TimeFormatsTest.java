package com.tian.textbook.unit.common;

import com.tian.textbook.common.util.TimeFormats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入参时间解析口径（body 与 query 共用）单元测试。
 *
 * <p>回归背景：{@code spring.mvc.format.date-time} 只作用于 MVC 参数绑定，
 * body 由 Jackson 按 ISO 解析——同一份文档口径下「body 只认 {@code T}、query 只认空格」，
 * 联调必然踩坑。此处锁定「两种都接受」。</p>
 */
class TimeFormatsTest {

    @Test
    @DisplayName("ISO-8601 与空格分隔两种格式都接受（含缺秒、含毫秒）")
    void acceptsBothIsoAndSpaceFormats() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 21, 9, 30, 0);

        assertThat(TimeFormats.parseInput("2026-09-21T09:30:00")).isEqualTo(expected);
        assertThat(TimeFormats.parseInput("2026-09-21 09:30:00")).isEqualTo(expected);
        assertThat(TimeFormats.parseInput("2026-09-21T09:30")).isEqualTo(expected);
        assertThat(TimeFormats.parseInput("2026-09-21 09:30")).isEqualTo(expected);
        assertThat(TimeFormats.parseInput("2026-09-21T09:30:00.123"))
                .isEqualTo(LocalDateTime.of(2026, 9, 21, 9, 30, 0, 123_000_000));
        // 前后空白由前端拼接产生，容忍
        assertThat(TimeFormats.parseInput("  2026-09-21 09:30:00  ")).isEqualTo(expected);
    }

    @Test
    @DisplayName("无法解析的输入抛出 DateTimeParseException（由调用方转 400 并带格式提示）")
    void rejectsUnparsableInput() {
        assertThatThrownBy(() -> TimeFormats.parseInput("2026/09/21 09:30"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TimeFormats.parseInput("2026-09-21T09:30:00Z"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TimeFormats.parseInput(""))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TimeFormats.parseInput(null))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    @DisplayName("纯日期识别：只认 yyyy-MM-dd，日期时间不算纯日期")
    void detectsDateOnly() {
        assertThat(TimeFormats.isDateOnly("2026-09-21")).isTrue();
        assertThat(TimeFormats.isDateOnly(" 2026-09-21 ")).isTrue();
        assertThat(TimeFormats.isDateOnly("2026-09-21T00:00:00")).isFalse();
        assertThat(TimeFormats.isDateOnly("2026-09-21 00:00:00")).isFalse();
        assertThat(TimeFormats.isDateOnly("")).isFalse();
        assertThat(TimeFormats.isDateOnly(null)).isFalse();
    }

    @Test
    @DisplayName("纯日期边界：下界为当日 00:00:00，上界为当日 23:59:59.999999999（含当天整天）")
    void dateOnlyBoundaries() {
        assertThat(TimeFormats.startOfDay("2026-09-21")).isEqualTo(LocalDateTime.of(2026, 9, 21, 0, 0, 0));
        LocalDateTime end = TimeFormats.endOfDay("2026-09-21");
        assertThat(end.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
        assertThat(end.toLocalTime()).isEqualTo(java.time.LocalTime.MAX);
        assertThat(end).isAfter(LocalDateTime.of(2026, 9, 21, 23, 59, 59));
    }
}
