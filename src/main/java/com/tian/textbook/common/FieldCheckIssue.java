package com.tian.textbook.common;

/**
 * 字段审查错误项（契约冻结回显格式，SPEC §8）：{field, rule, message}。
 */
public record FieldCheckIssue(String field, String rule, String message) {
}
