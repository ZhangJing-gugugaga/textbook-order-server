package com.tian.textbook.unit.system;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.SystemConfig;
import com.tian.textbook.system.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统配置白名单与值域校验单元测试（SPEC §14：8 键白名单；越界/非数字/未知键 → 400 CONFIG_VALUE_INVALID）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigWhitelistTest {

    @Mock
    private SystemConfigMapper configMapper;
    @Mock
    private AuditService auditService;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configMapper, auditService);
    }

    private static ErrorCode errorCodeOf(Throwable throwable) {
        return ((BizException) throwable).getErrorCode();
    }

    @Nested
    @DisplayName("白名单结构")
    class Whitelist {

        @Test
        void keyWhitelist_containsExactlyEightFrozenKeys() {
            assertThat(ConfigService.KEY_WHITELIST).hasSize(8);
            assertThat(ConfigService.KEY_WHITELIST).containsKeys(
                    "notice.round_limit", "notice.interval_hours", "notice.popup_queue_max",
                    "order.quantity.max_default", "order.correct_window_days",
                    "export.sync_row_threshold", "export.download_token_minutes",
                    "import.max_file_mb");
        }
    }

    @Nested
    @DisplayName("值域校验")
    class ValueValidation {

        @Test
        void update_unknownKey_throwsConfigValueInvalid() {
            assertThatThrownBy(() -> service.update(Map.of("not.a.key", "1")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.CONFIG_VALUE_INVALID));
            verify(configMapper, never()).insert(any(SystemConfig.class));
        }

        @Test
        void update_nonNumericValue_throwsConfigValueInvalid() {
            assertThatThrownBy(() -> service.update(Map.of("notice.round_limit", "abc")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.CONFIG_VALUE_INVALID));
        }

        @Test
        void update_blankValue_throwsConfigValueInvalid() {
            assertThatThrownBy(() -> service.update(Map.of("notice.round_limit", "  ")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.CONFIG_VALUE_INVALID));
        }

        @Test
        void update_valueBelowRange_throwsConfigValueInvalid() {
            assertThatThrownBy(() -> service.update(Map.of("notice.round_limit", "0")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.CONFIG_VALUE_INVALID));
        }

        @Test
        void update_valueAboveRange_throwsConfigValueInvalid() {
            assertThatThrownBy(() -> service.update(Map.of("notice.round_limit", "21")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.CONFIG_VALUE_INVALID));
        }

        @Test
        void update_emptyItems_throwsParamInvalid() {
            assertThatThrownBy(() -> service.update(Map.of()))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
        }

        @Test
        void update_valuesAtRangeBoundaries_acceptedAndAudited() {
            when(configMapper.selectOne(any())).thenReturn(null);
            Map<String, String> items = new LinkedHashMap<>();
            items.put("notice.round_limit", "1");
            items.put("notice.interval_hours", "168");
            items.put("order.quantity.max_default", "99999");

            service.update(items);

            verify(configMapper, times(3)).insert(any(SystemConfig.class));
            verify(auditService).record(eq(AuditService.CONFIG), eq("config"), isNull(), any());
        }

        @Test
        void update_existingKey_updatesInPlaceWithoutInsert() {
            SystemConfig existing = new SystemConfig();
            existing.setId(3L);
            existing.setConfigKey("notice.round_limit");
            existing.setConfigValue("5");
            when(configMapper.selectOne(any())).thenReturn(existing);

            service.update(Map.of("notice.round_limit", "8"));

            verify(configMapper, never()).insert(any(SystemConfig.class));
            verify(configMapper).update(any(SystemConfig.class), any());
        }
    }
}
