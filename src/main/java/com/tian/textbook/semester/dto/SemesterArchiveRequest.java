package com.tian.textbook.semester.dto;

/**
 * 归档请求（二次门禁，B11 生产缺陷修复）。
 *
 * <p>背景：归档是**不可逆**且会立刻停止全站征订业务的写操作（归档后没有 active 学期，
 * 学生选购/教师填报/导出一律报「当前没有激活学期」）。原实现 {@code POST /archive} 不读 body，
 * 空 body 一次调用即可把进行中的学期归档——线上实测发生过，只能靠整库备份恢复。</p>
 *
 * <p>因此归档必须带两个显式确认项：</p>
 * <ul>
 *   <li>{@code version}（必填）：乐观锁。调用方须先读学期最新状态（{@code GET /api/admin/semester/{id}}），
 *       防止「读到旧状态 → 期间窗口已变更 → 按旧认知归档」；不匹配返回 409。</li>
 *   <li>{@code confirmWindowOpen}（窗口进行中时必填 true）：学期窗口仍处于进行中
 *       （{@code window_status=open} 或 {@code channel_open=1}）时，归档会当场中断正在填报的
 *       教师/学生。未显式确认时返回 409 并说明影响，由前端弹「强确认」后带 true 重试。</li>
 * </ul>
 *
 * <p>非空校验放在 Service（而非 {@code @NotNull}）：Bean Validation 在方法调用前执行，
 * 会让「重复归档」这类状态错误被参数错误掩盖（详见 B10 的同类问题）。</p>
 */
public record SemesterArchiveRequest(
        Integer version,
        Boolean confirmWindowOpen) {
}
