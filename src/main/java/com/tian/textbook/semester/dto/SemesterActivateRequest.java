package com.tian.textbook.semester.dto;

/**
 * 双缓冲原子切换请求（body 带预期 version，乐观锁，W1）。
 *
 * <p>{@code version} 的必填校验在 Service 层（状态门禁之后），**不用 {@code @NotNull}**：
 * Bean Validation 在方法调用前执行，会让「重复激活已在 active 的学期」这类状态错误被
 * 400 参数错误掩盖（联调实测：空 body 调 activate 得到 PARAM_INVALID，而契约规定 409
 * 「该学期已是激活学期」）。顺序调整为「先状态、后参数」后，语义与 API.md 一致。</p>
 */
public record SemesterActivateRequest(
        Integer version) {
}
