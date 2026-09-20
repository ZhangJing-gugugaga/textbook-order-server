package com.tian.textbook.common.notify;

/**
 * 窗口变更通知（SPEC §6：每次窗口变更自动创建/合并系统通知任务）。
 *
 * <p>接口定义在 common，实现位于 notify 模块（ semester 的 WindowService 调用，
 * 避免模块间直接依赖实现）。</p>
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
}
