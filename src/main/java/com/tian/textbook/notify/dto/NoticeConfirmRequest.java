package com.tian.textbook.notify.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 通知确认请求体（POST /api/notice/{taskId}/confirm，SPEC §9：body 可选）。
 *
 * <p>subscribeResult 为订阅授权上报（accepted/rejected）；openid 由 wx.login code2session
 * 静默收集（03 §3.3），accepted 仅作授权信号记录，本服务端不存储该字段
 * （确认落库以 notice_record.confirmed_at 为准）。</p>
 */
public record NoticeConfirmRequest(

        @Pattern(regexp = "accepted|rejected", message = "subscribeResult 仅支持 accepted/rejected")
        String subscribeResult) {
}
