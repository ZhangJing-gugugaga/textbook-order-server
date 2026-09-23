package com.tian.textbook.common.notify;

/**
 * 学期生命周期通知端口（SPEC §6/§9：窗口变更自动创建/合并通知任务；BE-5a/5d 扩展）。
 *
 * <p>接口定义在 common，实现位于 notify 模块（semester 模块通过它调用，避免模块间直接
 * 依赖实现）。覆盖三类事件：窗口变更（开启/延长/设置）→ 合并通知任务；窗口关闭
 * （提前截止/自动截止）→ 关闭 active 任务；学期归档 → 通知记录迁入历史表。</p>
 */
public interface WindowChangeNotifier {

    /**
     * 窗口变更时调用：合并进本学期 active 任务（无则创建，source=system_window_change）。
     *
     * @param semesterId 学期 id
     * @param title      任务标题（窗口变更时刷新）
     * @param content    本次变更说明（含新截止时间）
     */
    void onWindowChange(Long semesterId, String title, String content);

    /**
     * 窗口关闭时调用（BE-5a）：把该学期 active 通知任务置 closed。
     *
     * <p>语义：征订结束即不再要求确认。此前关闭窗口只往任务里追加「请尽快提交」文案，
     * 任务永远 active、每天继续给未确认学生发订阅消息。</p>
     *
     * @param semesterId 学期 id
     * @param reason     关闭原因（提前截止 / 自动截止，写入审计）
     */
    void onWindowClosed(Long semesterId, String reason);

    /**
     * 学期归档时调用（BE-5d）：把该学期的通知记录迁入 {@code notice_record_history}。
     *
     * <p>独立事务、分批迁移；失败不影响归档结果，可重跑（幂等）。</p>
     *
     * @param semesterId 学期 id
     * @return 迁移行数
     */
    long archiveSemesterRecords(Long semesterId);
}
