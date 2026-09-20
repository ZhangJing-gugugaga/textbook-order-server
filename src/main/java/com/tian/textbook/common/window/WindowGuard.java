package com.tian.textbook.common.window;

import com.tian.textbook.common.annotation.WithinWindow;

/**
 * 窗口校验守门（@WithinWindow 切面委托，实现位于 semester 模块，避免 common 依赖业务 Mapper）。
 */
public interface WindowGuard {

    /**
     * 校验当前请求是否在征订窗口内。
     *
     * @param exemption 豁免类型：NONE 必须窗口 open；CORRECTION 对被驳回表单补正重提豁免（W4）
     * @throws com.tian.textbook.common.error.BizException 窗口未开/已截止 → 409
     */
    void assertWithinWindow(WithinWindow.Exemption exemption);
}
