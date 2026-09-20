package com.tian.textbook.stats.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.order.entity.StudentOrder;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.order.mapper.StudentOrderMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.stats.dto.CollegeProgress;
import com.tian.textbook.stats.dto.DashboardResponse;
import com.tian.textbook.stats.mapper.StatsMapper;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.mapper.CollegeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板聚合（SPEC §11.5：GET /api/admin/dashboard）。
 *
 * <p>数据源：OrderFormMapper.countGroupByCollegeAndStatus（教师表单按学院×状态）、
 * StatsMapper.countTeachersByCollege（在册教师数）、StudentOrderMapper（学生选购）、
 * NotifyService.countUnconfirmedTargetUsers（未确认通知）。R13 降级预案 = 本单接口聚合。</p>
 *
 * <p>时区固定 Asia/Shanghai（W11/W24），serverTime 供前端倒计时。</p>
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /**  Asia/Shanghai（与窗口引擎/调度一致，W11） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SemesterActiveService activeSemesterService;
    private final OrderFormMapper orderFormMapper;
    private final StudentOrderMapper studentOrderMapper;
    private final CollegeMapper collegeMapper;
    private final StatsMapper statsMapper;
    private final NotifyService notifyService;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        DashboardResponse response = new DashboardResponse();
        response.setServerTime(ZonedDateTime.now(ZONE));
        Semester active = activeSemesterService.active();
        if (active == null) {
            // 无激活学期：窗口与进度指标为空，学生/通知计数为 0
            response.setColleges(List.of());
            return response;
        }
        response.setSemesterId(active.getId());
        response.setWindowStatus(active.getWindowStatus());
        response.setChannelOpen(active.getChannelOpen());

        // 学院提交进度（以全部学院为行，含暂无数据的学院）
        Map<Long, CollegeProgress> progressByCollege = new LinkedHashMap<>();
        for (College college : collegeMapper.selectList(Wrappers.<College>lambdaQuery()
                .eq(College::getDeleted, 0)
                .orderByAsc(College::getId))) {
            CollegeProgress progress = new CollegeProgress();
            progress.setCollegeId(college.getId());
            progress.setCollegeName(college.getName());
            progressByCollege.put(college.getId(), progress);
        }
        // 教师表单按学院 × 状态聚合（reviewed 等状态计数）
        for (Map<String, Object> row : orderFormMapper.countGroupByCollegeAndStatus(active.getId())) {
            Long collegeId = toLong(row.get("collegeId"));
            if (collegeId == null) {
                continue; // 无学院归属的表单不计入学院行（隔离外数据）
            }
            CollegeProgress progress = progressByCollege.computeIfAbsent(collegeId, key -> {
                CollegeProgress created = new CollegeProgress();
                created.setCollegeId(collegeId);
                created.setCollegeName(String.valueOf(row.get("collegeName")));
                return created;
            });
            long cnt = toLong(row.get("cnt"));
            String status = String.valueOf(row.get("status"));
            switch (status) {
                case "pending_review" -> {
                    progress.setPendingReview(cnt);
                    progress.setSubmitted(progress.getSubmitted() + cnt);
                }
                case "reviewed" -> {
                    progress.setReviewed(cnt);
                    progress.setSubmitted(progress.getSubmitted() + cnt);
                }
                case "rejected", "rejected_auto" -> {
                    progress.setRejected(progress.getRejected() + cnt);
                    progress.setSubmitted(progress.getSubmitted() + cnt);
                }
                default -> {
                    // draft/submitted 等不计入看板口径
                }
            }
        }
        // 在册教师数（active 学期 profile + TEACHER 角色）
        for (Map<String, Object> row : statsMapper.countTeachersByCollege(active.getId())) {
            Long collegeId = toLong(row.get("collegeId"));
            if (collegeId == null) {
                continue;
            }
            progressByCollege.computeIfPresent(collegeId, (key, progress) -> {
                progress.setTeacherTotal(toLong(row.get("teacherTotal")));
                return progress;
            });
        }
        List<CollegeProgress> colleges = List.copyOf(progressByCollege.values());
        response.setColleges(colleges);

        // 待复核总数
        long pendingReviewTotal = colleges.stream().mapToLong(CollegeProgress::getPendingReview).sum();
        response.setPendingReviewTotal(pendingReviewTotal);

        // 学生选购（active 学期）
        response.setStudentOrderTotal(studentOrderMapper.selectCount(Wrappers.<StudentOrder>lambdaQuery()
                .eq(StudentOrder::getSemesterId, active.getId())
                .eq(StudentOrder::getDeleted, 0)));
        response.setStudentSubmittedTotal(studentOrderMapper.selectCount(Wrappers.<StudentOrder>lambdaQuery()
                .eq(StudentOrder::getSemesterId, active.getId())
                .eq(StudentOrder::getStatus, "submitted")
                .eq(StudentOrder::getDeleted, 0)));

        // 未确认通知（active 任务目标用户 - 已确认，去重）
        response.setUnconfirmedNoticeTotal(notifyService.countUnconfirmedTargetUsers());
        return response;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
