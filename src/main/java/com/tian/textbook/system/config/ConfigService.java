package com.tian.textbook.system.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.SystemConfig;
import com.tian.textbook.system.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置（PRD 模块 9 / 03 §10.1：键白名单 + 值域校验；重发参数唯一真源，W8）。
 *
 * <p>不缓存：配置更新对未完结通知任务立即生效（SPEC §12）。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    /** 配置键白名单（M1 冻结）+ 值域 [min, max] */
    public static final Map<String, int[]> KEY_WHITELIST = new LinkedHashMap<>();

    static {
        KEY_WHITELIST.put("notice.round_limit", new int[]{1, 20});
        KEY_WHITELIST.put("notice.interval_hours", new int[]{1, 168});
        KEY_WHITELIST.put("notice.popup_queue_max", new int[]{1, 20});
        KEY_WHITELIST.put("order.quantity.max_default", new int[]{1, 99999});
        KEY_WHITELIST.put("order.correct_window_days", new int[]{1, 90});
        KEY_WHITELIST.put("export.sync_row_threshold", new int[]{100, 100000});
        KEY_WHITELIST.put("export.download_token_minutes", new int[]{1, 60});
        KEY_WHITELIST.put("import.max_file_mb", new int[]{1, 100});
    }

    public static final String NOTICE_ROUND_LIMIT = "notice.round_limit";
    public static final String NOTICE_INTERVAL_HOURS = "notice.interval_hours";
    public static final String NOTICE_POPUP_QUEUE_MAX = "notice.popup_queue_max";
    public static final String ORDER_QUANTITY_MAX_DEFAULT = "order.quantity.max_default";
    public static final String ORDER_CORRECT_WINDOW_DAYS = "order.correct_window_days";
    public static final String EXPORT_SYNC_ROW_THRESHOLD = "export.sync_row_threshold";
    public static final String EXPORT_DOWNLOAD_TOKEN_MINUTES = "export.download_token_minutes";
    public static final String IMPORT_MAX_FILE_MB = "import.max_file_mb";

    private final SystemConfigMapper configMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<SystemConfig> list() {
        return configMapper.selectList(Wrappers.<SystemConfig>lambdaQuery()
                .eq(SystemConfig::getDeleted, 0)
                .orderByAsc(SystemConfig::getId));
    }

    /** 批量更新（键白名单 + 值域校验 + 审计） */
    @Transactional
    public void update(Map<String, String> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "没有要更新的配置项");
        }
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : items.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            int[] range = KEY_WHITELIST.get(key);
            if (range == null) {
                throw new BizException(ErrorCode.CONFIG_VALUE_INVALID, "配置项不存在: " + key);
            }
            if (value == null || value.isBlank()) {
                throw new BizException(ErrorCode.CONFIG_VALUE_INVALID, "配置值不能为空: " + key);
            }
            long numeric;
            try {
                numeric = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.CONFIG_VALUE_INVALID, "配置值不合法: " + key);
            }
            if (numeric < range[0] || numeric > range[1]) {
                throw new BizException(ErrorCode.CONFIG_VALUE_INVALID,
                        String.format("配置值越界: %s 需在 %d-%d 之间", key, range[0], range[1]));
            }
            SystemConfig existing = configMapper.selectOne(Wrappers.<SystemConfig>lambdaQuery()
                    .eq(SystemConfig::getConfigKey, key)
                    .eq(SystemConfig::getDeleted, 0)
                    .last("LIMIT 1"));
            if (existing == null) {
                SystemConfig config = new SystemConfig();
                config.setConfigKey(key);
                config.setConfigValue(value.trim());
                config.setDeleted(0L);
                configMapper.insert(config);
            } else {
                SystemConfig update = new SystemConfig();
                update.setId(existing.getId());
                update.setConfigValue(value.trim());
                configMapper.update(update, Wrappers.<SystemConfig>lambdaUpdate()
                        .eq(SystemConfig::getId, existing.getId()));
            }
            changed.put(key, value.trim());
        }
        auditService.record(AuditService.CONFIG, "config", null, Map.of("changed", changed));
    }

    public String get(String key) {
        return configMapper.selectValueByKey(key);
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
