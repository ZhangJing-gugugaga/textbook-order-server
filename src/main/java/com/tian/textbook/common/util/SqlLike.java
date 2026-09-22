package com.tian.textbook.common.util;

/**
 * LIKE 关键字转义（防止用户输入的通配符被当作模式语法）。
 *
 * <p>搜索框把用户输入直接拼进 {@code LIKE CONCAT('%', #{kw}, '%')} 时，用户输入的
 * {@code %} 会被当作通配符（输入单个 {@code %} 即匹配全表，退化为全表扫描），
 * {@code _} 同理。这里把 {@code %}/{@code _}/转义符本身转义，配合 SQL 里的
 * {@code ESCAPE '|'} 使用。</p>
 *
 * <p>转义符选 {@code |} 而非反斜杠：反斜杠在 MySQL 字符串字面量里本身是转义符，
 * 写 {@code ESCAPE '\\'} 在 MySQL 与 H2 下语义不一致；{@code |} 在两个方言里都是普通字符。</p>
 */
public final class SqlLike {

    /** 与 SQL 中 {@code ESCAPE '|'} 保持一致的转义符 */
    public static final char ESCAPE_CHAR = '|';

    /** 搜索关键字长度上限（超长关键字只会拖慢查询，无检索价值） */
    private static final int MAX_KEYWORD_LENGTH = 64;

    private SqlLike() {
    }

    /**
     * 转义 LIKE 关键字：{@code |} → {@code ||}，{@code %} → {@code |%}，{@code _} → {@code |_}；
     * 顺带裁剪首尾空白并截断到 {@value #MAX_KEYWORD_LENGTH} 字符。
     *
     * @return 转义后的关键字；入参为空时返回 null（调用方按「无关键字」处理）
     */
    public static String escape(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        }
        StringBuilder sb = new StringBuilder(trimmed.length() + 8);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
