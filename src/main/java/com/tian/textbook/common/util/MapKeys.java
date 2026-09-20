package com.tian.textbook.common.util;

import java.util.Locale;
import java.util.Map;

/**
 * Map 列名读取工具（H2/MySQL 列标签大小写差异兼容）。
 *
 * <p>H2（MySQL 模式）对 {@code SELECT col AS alias} 的别名按标识符规则转大写，MySQL 保留别名
 * 原样；{@code resultType=java.util.Map} 的查询用本工具取值，避免一套 SQL 两种库行为不一致。</p>
 */
public final class MapKeys {

    private MapKeys() {
    }

    /** 按列名取值（原样 → 小写 → 大写，兼容 MySQL 保留别名 / H2 DATABASE_TO_LOWER / H2 默认大写）。 */
    public static Object pick(Map<String, Object> row, String column) {
        if (row == null || column == null) {
            return null;
        }
        Object value = row.get(column);
        if (value != null) {
            return value;
        }
        value = row.get(column.toLowerCase(Locale.ROOT));
        return value != null ? value : row.get(column.toUpperCase(Locale.ROOT));
    }
}
