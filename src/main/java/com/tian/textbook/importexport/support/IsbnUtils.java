package com.tian.textbook.importexport.support;

/**
 * ISBN 格式校验（SPEC §8 规则 ISBN_FORMAT：ISBN-10/13 校验位）。
 *
 * <p>容忍常见书写噪声：连字符与空格先剔除；末位 X 视为 10。</p>
 */
public final class IsbnUtils {

    private IsbnUtils() {
    }

    /** 归一化：去连字符/空格并转大写（落库值） */
    public static String normalize(String isbn) {
        if (isbn == null) {
            return "";
        }
        return isbn.replace("-", "").replace(" ", "").trim().toUpperCase();
    }

    /** ISBN-10/13 格式 + 校验位校验 */
    public static boolean valid(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (normalized.length() == 13) {
            return validIsbn13(normalized);
        }
        if (normalized.length() == 10) {
            return validIsbn10(normalized);
        }
        return false;
    }

    private static boolean validIsbn13(String s) {
        if (!s.startsWith("978") && !s.startsWith("979")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c)) {
                return false;
            }
            sum += (c - '0') * (i % 2 == 0 ? 1 : 3);
        }
        char last = s.charAt(12);
        if (!Character.isDigit(last)) {
            return false;
        }
        int check = (10 - sum % 10) % 10;
        return (last - '0') == check;
    }

    private static boolean validIsbn10(String s) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c)) {
                return false;
            }
            sum += (c - '0') * (10 - i);
        }
        char last = s.charAt(9);
        if (last == 'X') {
            sum += 10;
        } else if (Character.isDigit(last)) {
            sum += (last - '0');
        } else {
            return false;
        }
        return sum % 11 == 0;
    }
}
