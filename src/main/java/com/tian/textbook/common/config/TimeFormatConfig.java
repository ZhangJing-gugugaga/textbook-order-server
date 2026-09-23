package com.tian.textbook.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.tian.textbook.common.util.TimeFormats;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 入参时间格式统一（body 与 query 双格式兼容，见 {@link TimeFormats}）。
 *
 * <p>此前只有 {@code spring.mvc.format.date-time} 一处配置：它只覆盖 MVC 参数绑定，
 * 而 body 由 Jackson 反序列化、JSR-310 类型固定按 ISO 解析——于是「body 传空格格式 400、
 * query 传 ISO 也 400」，且 400 的文案只有「请求参数有误」，联调时无从定位。
 * 这里把两侧都收敛到 {@link TimeFormats} 一个解析口径，**两种格式都接受**（不破坏既有调用），
 * 出参保持 ISO-8601 不变（改出参会同时打断 Web 与小程序两端已适配的解析）。</p>
 */
@Configuration
public class TimeFormatConfig implements WebMvcConfigurer {

    /**
     * body 的 {@code LocalDateTime} 宽容解析。
     *
     * <p>用 {@code postConfigurer} 而不是 {@code deserializerByType}：后者与 JavaTimeModule
     * 的注册顺序取决于 Spring Boot 内部实现，顺序反了就被 JavaTimeModule 的严格 ISO 反序列化器
     * 抢先命中（表现为「配置写了但不生效」）。{@code postConfigurer} 在所有配置完成后注册模块，
     * Jackson 对同类型以后注册者为准，行为确定。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer lenientLocalDateTimeCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            SimpleModule module = new SimpleModule("lenientLocalDateTime");
            module.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
            module.addDeserializer(LocalDate.class, new LenientLocalDateDeserializer());
            mapper.registerModule(module);
        });
    }

    /**
     * query/path 的 {@code LocalDateTime} 宽容解析（注册在属性格式化器之后，按转换器优先级生效）。
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToLocalDateTimeConverter());
        registry.addConverter(new StringToLocalDateConverter());
    }

    /** body：字符串走 {@link TimeFormats}，非字符串（如 ISO 数组形式）交回 Jackson 默认实现。 */
    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        /** 默认实现（ISO_LOCAL_DATE_TIME + 数组形式），仅用于非字符串 token 的兜底。 */
        private static final LocalDateTimeDeserializer DEFAULT =
                new LocalDateTimeDeserializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return DEFAULT.deserialize(p, ctxt);
            }
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return TimeFormats.parseInput(text);
            } catch (DateTimeParseException e) {
                // 抛 InvalidFormatException：由 GlobalExceptionHandler 渲染成
                // 400 PARAM_INVALID + 「字段: 时间格式…」的逐字段明细
                throw InvalidFormatException.from(p, TimeFormats.INPUT_HINT, text, LocalDateTime.class);
            }
        }
    }

    /** query/path：{@code ?from=2026-09-21T09:30:00} 与 {@code ?from=2026-09-21 09:30:00} 等价。 */
    static class StringToLocalDateTimeConverter implements Converter<String, LocalDateTime> {

        @Override
        public LocalDateTime convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            return TimeFormats.parseInput(source);
        }
    }

    /**
     * body：{@code LocalDate} 宽容解析（{@code yyyy-MM-dd}，容忍带时间的写法取日期部分）。
     *
     * <p>与 {@link LenientLocalDateTimeDeserializer} 同一口径：日期字段（学期 startDate/endDate）
     * 在不同调用方手里可能是 {@code 2026-09-01} 或 {@code 2026-09-01 00:00:00}，只为多打了时间
     * 就 400 属于把实现细节当契约。</p>
     */
    static class LenientLocalDateDeserializer extends JsonDeserializer<LocalDate> {

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                throw InvalidFormatException.from(p, TimeFormats.DATE_HINT, p.getText(), LocalDate.class);
            }
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return TimeFormats.parseInputDate(text);
            } catch (DateTimeParseException e) {
                throw InvalidFormatException.from(p, TimeFormats.DATE_HINT, text, LocalDate.class);
            }
        }
    }

    /** query/path 的 {@code LocalDate} 宽容解析（与 body 同口径）。 */
    static class StringToLocalDateConverter implements Converter<String, LocalDate> {

        @Override
        public LocalDate convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            return TimeFormats.parseInputDate(source);
        }
    }
}
