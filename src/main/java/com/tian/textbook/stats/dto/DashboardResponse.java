package com.tian.textbook.stats.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据看板（GET /api/admin/dashboard，SPEC §11.5）。
 *
 * <p>R13 降级预案：本接口为单接口聚合；指标口径见各字段注释。</p>
 */
@Data
public class DashboardResponse {

    private Long semesterId;

    /** 窗口状态（semester.window_status 落库真源，W11） */
    private String windowStatus;

    /** 窗口总开关（1/0） */
    private Integer channelOpen;

    /** 服务器时间（Asia/Shanghai，前端倒计时不信任本地时钟，W24） */
    private java.time.ZonedDateTime serverTime;

    /** 各学院教师提交进度 */
    private List<CollegeProgress> colleges;

    /** 待复核总数（全学院 pending_review） */
    private long pendingReviewTotal;

    /** active 通知任务目标用户中未确认人数（去重） */
    private long unconfirmedNoticeTotal;

    /** 学生选购单总数（含草稿，active 学期） */
    private long studentOrderTotal;

    /** 学生已提交选购数（status=submitted，active 学期） */
    private long studentSubmittedTotal;
}
