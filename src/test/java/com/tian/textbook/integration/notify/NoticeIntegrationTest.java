package com.tian.textbook.integration.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.excel.EasyExcel;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.excel.NoticeSummaryExportRow;
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
    @Autowired
    private com.tian.textbook.semester.SemesterService semesterService;
    @Autowired
    private com.tian.textbook.importexport.service.ExportDataWriter exportDataWriter;

    private Long semesterId;
    private Long studentId;
    private Long teacherId;
    /** 超管账号 id（用例内复用：asAdmin 多次调用不重复建号，user_no 唯一键约束） */
    private Long adminId;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
        adminId = null;
    }

    private void seed() {
        var college = seeder.college("计算机学院");
        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        // BE-5a 起重发前校验窗口（非 open 不发送、不写记录）→ 本类多数用例聚焦重发/轮次语义，
        // 统一把窗口置为进行中；窗口门禁本身由 windowClosed_* 用例覆盖。
        openWindow(semester.getId());
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
        if (adminId == null) {
            var admin = seeder.user("AD", "超管", "13800000009", null, null, 1, 0, 1, "ADMIN");
            adminId = admin.getId();
        }
        TestSecurity.authenticate(adminId, "AD", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
    }

    private void asStudent() {
        TestSecurity.authenticate(studentId, "ST1", "学生一", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
    }

    private NoticeTaskCreateRequest createRequest(String title, String content) {
        return new NoticeTaskCreateRequest(title, content, "STUDENT");
    }

    /** 把学期窗口置为「进行中」（channel_open=1 + window_status=open） */
    private void openWindow(Long id) {
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, id)
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getWindowStatus, "open"));
    }

    /** 把学期窗口置为「已截止」（channel_open=0 + window_status=closed） */
    private void closeWindow(Long id) {
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, id)
                .set(Semester::getChannelOpen, 0)
                .set(Semester::getWindowStatus, "closed"));
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
    @DisplayName("窗口变更合并后重发再变更：轮次重置不得撞 uk_notice_round（否则窗口永远关不上）")
    void onWindowChange_afterResend_doesNotCollideWithSoftDeletedRounds() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("征订窗口通知", "初始内容"));

        // 复现序列（正常运维即可发生）：
        // ① 发一轮（round_no=1，deleted=0）→ ② 变更①重置（该轮记录软删为 t1）
        // → ③ 再发一轮（轮次从 1 重开，新建 round_no=1、deleted=0）→ ④ 变更②重置。
        // 若重置语句漏了 deleted=0 谓词，第 ④ 步会把「软删的旧记录」与「本轮新建记录」改成
        // 同一个 deleted 时间戳，撞 uk_notice_round 抛 1062 → 整个窗口变更事务回滚；而自动截止
        // 由每分钟定时任务驱动，于是每分钟重试、每分钟失败，窗口再也关不上（延长/提前截止同样失败）。
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口已延长至 2026-10-01");
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId())
                .eq(NoticeRecord::getDeleted, 0))).isNotEmpty();

        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口再次延长至 2026-10-08");

        NoticeTask after = noticeTaskMapper.selectByIdSoft(task.getId());
        assertThat(after.getContent()).isEqualTo("初始内容\n窗口已延长至 2026-10-01\n窗口再次延长至 2026-10-08");
        // 重置后：未确认轮次记录全部软删（deleted != 0），且旧记录保持各自的时间戳
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId())
                .eq(NoticeRecord::getDeleted, 0))).isEmpty();
    }

    @Test
    @DisplayName("窗口变更合并进手动任务：target_roles 取并集（教师/秘书也必须收到）")
    void onWindowChange_mergingManualTask_expandsTargetRoles() {
        seed();
        asAdmin();
        // 手动任务默认只面向 STUDENT
        var task = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        assertThat(task.getTargetRoles()).isEqualTo("STUDENT");

        notifyService.onWindowChange(semesterId, "征订窗口变更通知", "窗口已延长至 2026-10-01");

        // 只追加内容不改 target_roles 的话，延期信息只有学生看得到（同学期只允许一个 active
        // 任务，管理员也无法补发第二条给教师）
        NoticeTask merged = noticeTaskMapper.selectByIdSoft(task.getId());
        assertThat(merged.getTargetRoles().split(","))
                .containsExactlyInAnyOrder("STUDENT", "SECRETARY", "TEACHER");

        // 教师视角确认可见（按 target_roles 定向过滤）
        TestSecurity.authenticate(teacherId, "TH1", "教师一", Set.of("TEACHER"), "TEACHER",
                seeder.permissionsOf("TEACHER"));
        assertThat(notifyService.listUnconfirmed()).extracting("taskId").contains(task.getId());
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

    @Test
    @DisplayName("我的通知：含已确认与已关闭任务、回显 confirmedAt，created_at DESC 分页")
    void myNotices_includesConfirmedAndClosedWithPagination() {
        seed();
        asAdmin();
        var first = notifyService.createTask(createRequest("教材征订提醒", "请尽快提交征订"));
        asStudent();
        notifyService.confirm(first.getId(), null);
        asAdmin();
        notifyService.closeTask(first.getId());
        var second = notifyService.createTask(createRequest("窗口变更通知", "窗口已延长至 2026-10-01"));

        asStudent();
        var page1 = notifyService.myNotices(1, 1);

        assertThat(page1.total()).isEqualTo(2);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.list()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getTaskId()).isEqualTo(second.getId());
                    assertThat(item.getStatus()).isEqualTo("active");
                    assertThat(item.getConfirmedAt()).isNull();
                    assertThat(item.getContent()).isEqualTo("窗口已延长至 2026-10-01");
                });
        var page2 = notifyService.myNotices(2, 1);
        assertThat(page2.list()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getTaskId()).isEqualTo(first.getId());
                    assertThat(item.getStatus()).isEqualTo("closed");
                    assertThat(item.getSource()).isEqualTo("manual");
                    assertThat(item.getConfirmedAt()).isNotNull();
                });
    }

    // ============ BE-5：窗口联动 / 立即发送 / 间隔 / 归档 / 渠道 / 配置 / 入口确认 ============

    @Test
    @DisplayName("BE-5a：窗口关闭后重发不产生任何记录（此前会继续给未确认学生发订阅消息）")
    void windowClosed_resendWritesNoRecords() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("窗口关闭后重发", "不应发送"));

        closeWindow(semesterId);

        var stats = notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        assertThat(stats.skippedReason()).isNotNull();
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId()))).isEmpty();
    }

    @Test
    @DisplayName("BE-5a：窗口提前截止 → 该学期 active 通知任务自动关闭（closed_by 为空 = 系统关闭）")
    void windowClosed_autoClosesActiveTask() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("自动关闭", "内容"));

        semesterService.closeWindow(semesterId);

        NoticeTask closed = noticeTaskMapper.selectByIdSoft(task.getId());
        assertThat(closed.getStatus()).isEqualTo("closed");
        assertThat(closed.getClosedBy()).as("系统关闭留空").isNull();
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("BE-5b：立即发送返回本轮统计；任务已关闭 → 409；窗口未开放 → 409 WINDOW_CLOSED")
    void sendNow_statsAndGuards() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("立即发送", "内容"));

        var stats = notifyService.sendNow(task.getId());
        assertThat(stats.roundNo()).isEqualTo(1);
        assertThat(stats.total()).isEqualTo(1); // 目标 = 1 名学生
        assertThat(stats.unauthorized()).isEqualTo(1); // 未配置微信 → unauthorized
        assertThat(stats.skippedReason()).isNull();

        notifyService.closeTask(task.getId());
        assertThatThrownBy(() -> notifyService.sendNow(task.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("通知任务已关闭");

        var second = notifyService.createTask(createRequest("窗口校验", "内容"));
        closeWindow(semesterId);
        assertThatThrownBy(() -> notifyService.sendNow(second.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WINDOW_CLOSED));
    }

    @Test
    @DisplayName("BE-5c：间隔判定依据 lastSentAt —— 新任务无记录（立即发首轮），发过一轮后有值")
    void intervalJudgement_usesLastSentAt() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("间隔判定", "内容"));

        assertThat(notifyService.lastSentAt(task.getId()))
                .as("新任务无发送记录 → 调度立即发首轮（不再等 24h）").isNull();

        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));

        assertThat(notifyService.lastSentAt(task.getId()))
                .as("发过一轮 → 后续按 interval_hours 判定").isNotNull();
    }

    @Test
    @DisplayName("BE-5d：学期归档迁移通知记录到历史表，主表清空，进度仍可读到（UNION）")
    void archive_migratesRecordsToHistory() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("归档迁移", "内容"));
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        long before = noticeRecordMapper.countBySemester(semesterId);
        assertThat(before).isPositive();

        long migrated = notifyService.archiveSemesterRecords(semesterId);

        assertThat(migrated).isEqualTo(before);
        assertThat(noticeRecordMapper.countBySemester(semesterId)).as("主表已清空").isZero();
        assertThat(noticeRecordMapper.countHistoryBySemester(semesterId)).isEqualTo(before);
        // 进度查询走 UNION：归档后仍能统计到历史记录（否则管理端进度会突然归零）
        assertThat(notifyService.taskProgress(task.getId()).getUnauthorized()).isEqualTo(before);
        // 幂等：重复归档不报错、不重复迁移
        assertThat(notifyService.archiveSemesterRecords(semesterId)).isZero();
        assertThat(noticeRecordMapper.countHistoryBySemester(semesterId)).isEqualTo(before);
    }

    @Test
    @DisplayName("BE-5f：通知汇总导出的「渠道」列取值（订阅消息+弹窗 / 仅弹窗（未授权））")
    void noticeExport_channelColumn() throws Exception {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("渠道列", "内容"));
        // 学生：unauthorized 轮次（未配置微信）→ 仅弹窗（未授权）
        notifyService.resendTask(noticeTaskMapper.selectByIdSoft(task.getId()));
        // 教师：手工补一条 sent 记录 → 订阅消息+弹窗（教师本不在 STUDENT 发送范围）
        NoticeRecord sent = new NoticeRecord();
        sent.setTaskId(task.getId());
        sent.setUserId(teacherId);
        sent.setSemesterId(semesterId);
        sent.setRoundNo(1);
        sent.setSentAt(LocalDateTime.now());
        sent.setSendStatus("sent");
        sent.setDeleted(0L);
        noticeRecordMapper.insert(sent);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        exportDataWriter.writeNotice(out, task.getId(), semesterId);
        List<NoticeSummaryExportRow> rows = EasyExcel
                .read(new java.io.ByteArrayInputStream(out.toByteArray()))
                .head(NoticeSummaryExportRow.class)
                .sheet().doReadSync();

        java.util.Map<String, String> channelByUserNo = new java.util.HashMap<>();
        for (NoticeSummaryExportRow row : rows) {
            channelByUserNo.put(row.getUserNo(), row.getChannel());
        }
        assertThat(channelByUserNo).containsEntry("ST1", "仅弹窗（未授权）");
        assertThat(channelByUserNo).containsEntry("TH1", "订阅消息+弹窗");
        assertThat(rows).as("渠道列非空").allSatisfy(row -> assertThat(row.getChannel()).isNotBlank());
    }

    @Test
    @DisplayName("BE-5e：subscribe-config 下发模板 id（未配置 → null）与弹窗队列上限")
    void subscribeConfig_unconfiguredTemplateReturnsNull() {
        seed();
        asStudent();

        java.util.Map<String, Object> config = notifyService.subscribeConfig();

        assertThat(config).containsEntry("subscribeTemplateId", null);
        assertThat(config.get("popupQueueMax")).isEqualTo(5);
    }

    @Test
    @DisplayName("BE-5g：进入选书页即确认（confirmed_by_entry、幂等、已有确认不动）")
    void confirmByEntry_idempotent() {
        seed();
        asAdmin();
        var task = notifyService.createTask(createRequest("入口确认", "内容"));

        asStudent();
        assertThat(notifyService.confirmByEntry()).isEqualTo(1);

        assertThat(notifyService.listUnconfirmed()).as("入口确认后移出未确认队列").isEmpty();
        List<NoticeRecord> confirmed = noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId())
                .eq(NoticeRecord::getUserId, studentId)
                .isNotNull(NoticeRecord::getConfirmedAt));
        assertThat(confirmed).singleElement()
                .satisfies(r -> assertThat(r.getSendStatus()).isEqualTo("confirmed_by_entry"));

        // 幂等：再次调用不新增确认记录
        assertThat(notifyService.confirmByEntry()).isZero();
        assertThat(noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                .eq(NoticeRecord::getTaskId, task.getId())
                .eq(NoticeRecord::getUserId, studentId)
                .isNotNull(NoticeRecord::getConfirmedAt))).hasSize(1);
    }

    @Test
    @DisplayName("BE-5d：任务列表可按学期查询（缺省 = 当前 active 学期）")
    void listTasks_bySemester() {
        seed();
        asAdmin();
        notifyService.createTask(createRequest("列表查询", "内容"));

        assertThat(notifyService.listTasks()).hasSize(1);
        assertThat(notifyService.listTasks(semesterId)).hasSize(1);
        assertThat(notifyService.listTasks(999999L)).isEmpty();
    }

    @Test
    @DisplayName("我的通知：无 active 学期 → 空页（与 unconfirmed 同口径）")
    void myNotices_withoutActiveSemester_returnsEmptyPage() {
        seed();
        asStudent();
        SemesterContextHolder.clear();

        var page = notifyService.myNotices(1, 20);

        assertThat(page.list()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
