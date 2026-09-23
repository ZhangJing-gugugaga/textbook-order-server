package com.tian.textbook.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异动类型枚举（BE-7a，甲方决策「转专业/留级/专升本场景」）。
 *
 * <p>与 {@code change_request.type}（student/teacher，异动**对象**）语义不同：本枚举描述
 * 异动**原因/性质**。历史数据为 NULL，接口按 null 返回、前端展示「未分类」。</p>
 *
 * <p>导入与逐条提交共用同一套解析：接受枚举码或中文，未知值归一为 {@link #OTHER}
 * （不整行失败——历史模板里可能写着「转专业」以外的说法，硬失败会阻断整批导入）。</p>
 */
public final class ChangeTypes {

    public static final String MAJOR_TRANSFER = "MAJOR_TRANSFER";
    public static final String GRADE_REPEAT = "GRADE_REPEAT";
    public static final String UPGRADE = "UPGRADE";
    public static final String OTHER = "OTHER";

    /** 枚举码 → 中文（导出/文档/前端字典共用一处） */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(MAJOR_TRANSFER, "转专业");
        LABELS.put(GRADE_REPEAT, "留级");
        LABELS.put(UPGRADE, "专升本");
        LABELS.put(OTHER, "其他");
    }

    private ChangeTypes() {
    }

    public static Map<String, String> labels() {
        return Map.copyOf(LABELS);
    }

    public static boolean valid(String code) {
        return code != null && LABELS.containsKey(code);
    }

    public static String labelOf(String code) {
        return code == null ? null : LABELS.get(code);
    }

    /**
     * 解析导入/提交入参：接受枚举码（大小写不敏感）或中文；空值/未知值 → {@link #OTHER}。
     *
     * @return 归一后的枚举码（永不为 null）
     */
    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String value = raw.trim();
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (LABELS.containsKey(upper)) {
            return upper;
        }
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        // 兼容常见写法
        return switch (value) {
            case "转专业申请" -> MAJOR_TRANSFER;
            case "留级（降级）", "降级" -> GRADE_REPEAT;
            case "专升本考试" -> UPGRADE;
            default -> OTHER;
        };
    }
}
