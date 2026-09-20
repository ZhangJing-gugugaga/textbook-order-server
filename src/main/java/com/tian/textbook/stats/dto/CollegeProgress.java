package com.tian.textbook.stats.dto;

import lombok.Data;

/**
 * 学院提交进度（看板行）。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>teacherTotal = active 学期在册（user_semester_profile.status=1）且带 TEACHER 角色、
 *       账号正常的用户数（真源 profile，W6）；</li>
 *   <li>submitted = 已提交表单数（非草稿：pending_review + reviewed + rejected + rejected_auto）；</li>
 *   <li>pendingReview = 待复核（pending_review）；reviewed = 已通过（reviewed）；
 *       rejected = 已驳回（rejected + rejected_auto）。</li>
 * </ul>
 */
@Data
public class CollegeProgress {

    private Long collegeId;

    private String collegeName;

    private long teacherTotal;

    private long submitted;

    private long pendingReview;

    private long reviewed;

    private long rejected;
}
