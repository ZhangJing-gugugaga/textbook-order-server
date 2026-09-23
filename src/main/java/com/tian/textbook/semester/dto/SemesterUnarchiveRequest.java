package com.tian.textbook.semester.dto;

/**
 * 撤销归档请求（受限回滚，B15：归档误操作不再只能改库恢复）。
 *
 * <p>归档不可逆的设计不变（{@code archived → draft} 无路径、激活接口不接受 archived），
 * 但**误归档**在生产环境真实发生过，且恢复只能由 DBA 改库。此处提供一个受限回滚：
 * 仅当系统当前**没有任何 active 学期**（即业务已停摆、正是误归档的现场）时才允许，
 * 且必须显式 {@code confirm=true} + version 乐观锁。</p>
 *
 * <p>若当前已有 active 学期，说明归档是双缓冲切换的正常结果（新学期的 activate 会自动归档旧学期），
 * 此时回滚会造成「两个 active」或静默归档新学期，因此拒绝——需要回退请先归档当前 active 学期。</p>
 *
 * <p>回滚只恢复 {@code active_status=active}；归档时被强制关闭的窗口（{@code channel_open=0 /
 * window_status=closed}）保持关闭，须由管理员显式重新开启（避免回滚即恢复对外填报）。</p>
 */
public record SemesterUnarchiveRequest(
        Integer version,
        Boolean confirm) {
}
