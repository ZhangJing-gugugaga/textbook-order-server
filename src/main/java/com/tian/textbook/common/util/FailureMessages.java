package com.tian.textbook.common.util;

import com.tian.textbook.common.error.BizException;

/**
 * 面向用户的失败原因摘要（P2）。
 *
 * <p>异步任务（导入/导出）的失败原因会经接口回传前端。直接把 {@code e.getMessage()} 落库回传
 * 会暴露 SQL 语句、表名、服务器绝对路径等内部细节。此处只对<b>业务异常</b>透传其文案
 * （文案本来就是写给用户看的），其余异常统一替换为可读的兜底描述，细节只进服务端日志。</p>
 */
public final class FailureMessages {

    /** 兜底文案长度上限（与 export_task.error_msg VARCHAR(255) 对齐） */
    private static final int MAX_LENGTH = 200;

    private FailureMessages() {
    }

    /**
     * 生成可安全回传前端的失败原因。
     *
     * @param fallback 非业务异常时使用的兜底文案（如「导出失败」「导入失败」）
     */
    public static String userFacing(Throwable error, String fallback) {
        if (error instanceof BizException biz && biz.getMessage() != null && !biz.getMessage().isBlank()) {
            return truncate(biz.getMessage());
        }
        return fallback;
    }

    private static String truncate(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
