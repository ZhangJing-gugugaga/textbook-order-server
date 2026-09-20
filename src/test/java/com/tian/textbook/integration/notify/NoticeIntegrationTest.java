package com.tian.textbook.integration.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.notify.dto.NoticeTaskCreateRequest;
import com.tian.textbook.notify.entity.NoticeRecord;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeRecordMapper;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知确认闭环集成测试（SPEC §9 / W7/W8/W18，H2 承载）。
 *
 * <p>覆盖：同学期仅 1 个 active 手动任务（409）、窗口变更合并（同任务、内容追加、
 * 轮次记录逻辑删除重置）、confirm 幂等（notice_record 仅一条 confirmed_at 非空）、
 * unconfirmed 队列（含 roundStopped）、重发如实落库（unauthorized / 非 STUDENT 不写记录）。</p>
 */
class NoticeIntegrationTest extends IntegrationTestBase {

    @Autowired
    private NotifyService notifyService;
    @Autowired
    private NoticeTaskMapper noticeTaskMapper;
    @Autowired
    private NoticeRecordMapper noticeRecordMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private TestDataSeeder seeder;

    private Long semesterId;
    private Long studentId;
    private Long teacherId;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private void seed() {
        var college = seeder.college("计算机学院");
        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        semesterId = semester.getId();

        var student = seeder.user("ST1", "学生一", "13800000001", college.getId(), null,
                1, 0, 1, "STUDENT");
        var teacher = seeder.user("TH1", "教师一", "13800000002", college.getId(), null,
                1, 0, 1, "TEACHER");
        studentId = student.getId();
        teacherId = teacher.getId();
        seeder.profile(studentId, semesterId, college.getId(), null);
        seeder.profile(teacherId, semesterId, college.getId(), null);

        SemesterContextHolder.set(semesterId);
    }

    private void asAdmin() {
        var admin = seeder.user("AD", "超管", "13800000009", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
    }

    private void asStudent() {
        TestSecurity.authenticate(studentId, "ST1", "学生一", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
    }

    private NoticeTaskCreateRequest createRequest(String title, String content) {
        return new NoticeTaskCreateRequest(title, content, "STUDENT");
    }

    private List<NoticeTask> activeTasks() {
        return noticeTaskMapper.selectActiveBySemester(semesterId);
    }

    @Test
    @DisplayName("手动创建通知任务：同学期第二个 → 409 NOTICE_TASK_EXISTS")
    void createTask_secondActiveTask_returns409() {
        seed();
        asAdmin();

        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        assertThat(task.getStatus()).isEqualTo("active");
        assertThat(task.getSource()).isEqualTo("manual");
        assertThat(activeTasks()).hasSize(1);

        assertThatThrownBy(() -> notifyService.createTask(
                createRequest("再次创建", "不应成功")))
                .isInstanceOf(com.tian.textbook.common.error.BizException.class)
                .satisfies(e -> assertThat(((com.tian.textbook.common.error.BizException) e).getErrorCode())
                        .isEqualTo(com.tian.textbook.common.error.ErrorCode.NOTICE_TASK_EXISTS));
        assertThat(activeTasks()).hasSize(1);
    }

    @Test
    @DisplayName("窗口变更合并：两次 onWindowChange → 同一任务、内容追加、轮次记录重置")
    void onWindowChange_twice_mergesIntoSameTaskAndResetsRounds() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("征订窗口通知", "初始内容"));

        // 先发一轮（未授权 → unauthorized 记录，round_no=1）
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        List<NoticeRecord> afterResend = noticeRecordMapper.selectList(Wrappers
                .<NoticeRecord>lambdaQuery().eq(NoticeRecord::getTaskId, task.getId()));
        assertThat(afterResend).singleElement()
                .satisfies(r -> {
                    assertThat(r.getSendStatus()).isEqualTo("unauthorized");
                    assertThat(r.getRoundNo()).isEqualTo(1);
                });

        // 第一次窗口变更：合并 + 追加内容 + 逻辑删除未确认轮次记录
        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口已延长至 2026-10-01");
        NoticeTask afterFirst = noticeTaskMapper.selectByIdSoft(task.getId());
        assertThat(afterFirst.getContent()).isEqualTo("初始内容\n窗口已延长至 2026-10-01");
        assertThat(afterFirst.getTitle()).isEqualTo("征订窗口变更通知");
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId())
                .eq(NoticeRecord::getDeleted, 0))).isEmpty();
        // 逻辑删除的记录仍在库（唯一键含 deleted 可重建）
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId()))).hasSize(1);

        // 第二次窗口变更：继续合并进同一任务
        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口再次延长至 2026-10-08");
        NoticeTask afterSecond = noticeTaskMapper.selectByIdSoft(task.getId());
        assertThat(afterSecond.getId()).isEqualTo(task.getId());
        assertThat(afterSecond.getContent())
                .isEqualTo("初始内容\n窗口已延长至 2026-10-01\n窗口再次延长至 2026-10-08");
        assertThat(activeTasks()).hasSize(1);
    }

    @Test
    @DisplayName("onWindowChange：无 active 任务时自动创建（source=system_window_change）")
    void onWindowChange_withoutActiveTask_createsSystemTask() {
        seed();
        asAdmin();

        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口已开启");

        List<NoticeTask> tasks = activeTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getSource()).isEqualTo("system_window_change");
        assertThat(tasks.get(0).getTargetRoles()).isEqualTo(NotifyService.WINDOW_CHANGE_TARGET_ROLES);
    }

    @Test
    @DisplayName("confirm 幂等：两次确认均成功，notice_record 仅一条 confirmed_at 非空")
    void confirm_twice_isIdempotent() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        asStudent();

        notifyService.confirm(task.getId(), null);
        notifyService.confirm(task.getId(), null); // 重复确认仍成功（204 语义）

        List<NoticeRecord> records = noticeRecordMapper.selectList(Wrappers
                .<NoticeRecord>lambdaQuery().eq(NoticeRecord::getTaskId, task.getId()));
        assertThat(records).singleElement()
                .satisfies(r -> {
                    assertThat(r.getUserId()).isEqualTo(studentId);
                    assertThat(r.getConfirmedAt()).isNotNull();
                    assertThat(r.getRoundNo()).isNull();
                });
        // 确认后移出未确认队列
        assertThat(notifyService.listUnconfirmed()).isEmpty();
    }

    @Test
    @DisplayName("unconfirmed：未确认任务入队且带 roundStopped（达 round_limit 仍返回，Q7）")
    void unconfirmed_returnsTaskWithRoundStoppedFlag() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        asStudent();

        var unconfirmed = notifyService.listUnconfirmed();
        assertThat(unconfirmed).singleElement()
                .satisfies(item -> {
                    assertThat(item.getTaskId()).isEqualTo(task.getId());
                    assertThat(item.isRoundStopped()).isFalse();
                    assertThat(item.getContent()).isEqualTo("请尽快提交征订");
                });

        // 发 5 轮（默认 round_limit=5）→ roundStopped=true 但仍返回（弹窗通道不设上限）
        for (int i = 0; i < 5; i++) {
            notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        }
        var afterLimit = notifyService.listUnconfirmed();
        assertThat(afterLimit).singleElement()
                .satisfies(item -> assertThat(item.isRoundStopped()).isTrue());
    }

    @Test
    @DisplayName("重发：未配置微信 → STUDENT 记 unauthorized；非 STUDENT 不写发送记录")
    void resendTask_recordsUnauthorizedForStudentOnly() {
        seed();
        asAdmin();
        // 窗口变更任务：目标角色 = 秘书+教师+学生
        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口已开启");
        NoticeTask task = activeTasks().get(0);

        notifyService.resendTask(task);

        List<NoticeRecord> records = noticeRecordMapper.selectList(Wrappers
                .<NoticeRecord>lambdaQuery().eq(NoticeRecord::getTaskId, task.getId()));
        assertThat(records).singleElement()
                .satisfies(r -> {
                    assertThat(r.getUserId()).isEqualTo(studentId);
                    assertThat(r.getSendStatus()).isEqualTo("unauthorized");
                    assertThat(r.getRoundNo()).isEqualTo(1);
                });
        // 教师/秘书不写发送记录（弹窗为主触达，Q8）
        assertThat(records).noneSatisfy(r -> assertThat(r.getUserId()).isEqualTo(teacherId));
    }

    @Test
    @DisplayName("进度统计：sent/unauthorized/confirmed 分类计数")
    void taskProgress_countsBySendStatus() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        asStudent();
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        notifyService.confirm(task.getId(), null);

        var progress = notifyService.taskProgress(task.getId());

        assertThat(progress.getUnauthorized()).isEqualTo(1);
        assertThat(progress.getConfirmed()).isEqualTo(1);
        assertThat(progress.getRoundLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("关闭任务：status=closed + closed_by/closed_at；重复关闭 → 409")
    void closeTask_marksClosedAndRejectsDoubleClose() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));

        var closed = notifyService.closeTask(task.getId());
        assertThat(closed.getStatus()).isEqualTo("closed");
        assertThat(closed.getClosedAt()).isNotNull();

        assertThatThrownBy(() -> notifyService.closeTask(task.getId()))
                .isInstanceOf(com.tian.textbook.common.error.BizException.class)
                .satisfies(e -> assertThat(((com.tian.textbook.common.error.BizException) e).getErrorCode())
                        .isEqualTo(com.tian.textbook.common.error.ErrorCode.STATE_CONFLICT));
    }
}
