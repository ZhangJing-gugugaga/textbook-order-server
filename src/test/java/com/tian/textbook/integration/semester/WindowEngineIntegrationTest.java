package com.tian.textbook.integration.semester;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.order.dto.StudentOrderSubmitItem;
import com.tian.textbook.order.dto.StudentOrderSubmitRequest;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.order.dto.OrderFormSubmitRequest;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.order.service.StudentOrderService;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.semester.WindowEngineScheduler;
import com.tian.textbook.semester.dto.WindowExtendRequest;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.OrderScenarioFactory;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.AuditLog;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.AuditLogMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 窗口引擎集成测试（SPEC §6 / W11，H2 承载）。
 *
 * <p>覆盖：手动开窗 + /api/semester/window/status（serverTime）、到点自动开启幂等
 * （第二次调用 0 行变化、审计不重复）、到点自动截止、auto_close=0 到点不改状态、
 * 延长（closed→open / 早于 now→400）、窗口关闭后学生提交 409 WINDOW_CLOSED、
 * 被驳回表单补正豁免（W4）与补正过期 409 CORRECTION_EXPIRED。</p>
 */
class WindowEngineIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SemesterService semesterService;
    @Autowired
    private SemesterActiveService activeSemesterService;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private OrderFormMapper orderFormMapper;
    @Autowired
    private TeacherOrderService teacherOrderService;
    @Autowired
    private StudentOrderService studentOrderService;
    @Autowired
    private OrderScenarioFactory scenarioFactory;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private Semester fresh(Long id) {
        return semesterMapper.selectByIdSoft(id);
    }

    private long windowAuditCount(Long semesterId) {
        return auditLogMapper.countByResource("window", String.valueOf(semesterId));
    }

    // ============ 手动开窗 + 窗口状态接口 ============

    @Test
    @DisplayName("setWindow → openWindow → /api/semester/window/status 返回 open + serverTime")
    void windowStatus_afterManualOpen_returnsOpenWithServerTime() throws Exception {
        var scenario = scenarioFactory.seed("WA");
        Semester semester = fresh(scenario.semesterId());

        semesterService.setWindow(scenario.semesterId(), new com.tian.textbook.semester.dto.WindowSetRequest(
                LocalDateTime.now().minusDays(2), LocalDateTime.now().plusDays(5), 1, 1));
        Semester opened = semesterService.openWindow(scenario.semesterId());
        assertThat(opened.getWindowStatus()).isEqualTo("open");
        assertThat(opened.getChannelOpen()).isEqualTo(1);

        // 真实 token 走完整过滤器链（JwtAuthFilter 从库加载角色/权限码）
        SysUser admin = seeder.user("admin", "超管", "13800000000", null, null, 1, 0, 1, "ADMIN");
        String token = jwtService.issueAccessToken(admin.getId(), admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", admin.getRoleVersion());

        mockMvc.perform(get("/api/semester/window/status")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.windowStatus").value("open"))
                .andExpect(jsonPath("$.data.serverTime").exists())
                .andExpect(jsonPath("$.data.semesterId").value(scenario.semesterId().intValue()));

        assertThat(semester.getWindowStatus()).isIn("not_open", "open");
    }

    // ============ 定时扫描：自动开启（幂等） ============

    @Test
    @DisplayName("applyAutoTransition：auto_open 到点自动开启；重复调用幂等（0 行变化、审计不重复）")
    void applyAutoTransition_autoOpen_isIdempotent() {
        var scenario = scenarioFactory.seed("WB", false);
        // 手工置为 not_open + channel_open=1 + window_start 过去（auto_open=1）
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, scenario.semesterId())
                .set(Semester::getWindowStatus, "not_open")
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getWindowStart, LocalDateTime.now().minusHours(1)));

        semesterService.applyAutoTransition(scenario.semesterId(), "open", true, "到点自动开启征订窗口");

        Semester opened = fresh(scenario.semesterId());
        assertThat(opened.getWindowStatus()).isEqualTo("open");
        assertThat(opened.getChannelOpen()).isEqualTo(1);
        long auditsAfterFirst = windowAuditCount(scenario.semesterId());
        assertThat(auditsAfterFirst).isEqualTo(1);

        // 第二次：状态已变 → 幂等跳过（version 不变、审计不重复、无新通知任务副作用）
        int versionBefore = fresh(scenario.semesterId()).getVersion();
        semesterService.applyAutoTransition(scenario.semesterId(), "open", true, "到点自动开启征订窗口");

        Semester again = fresh(scenario.semesterId());
        assertThat(again.getVersion()).isEqualTo(versionBefore);
        assertThat(windowAuditCount(scenario.semesterId())).isEqualTo(auditsAfterFirst);
    }

    @Test
    @DisplayName("WindowEngineScheduler.scanWindow：auto_open=1 到点自动开启（真实调度逻辑）")
    void scanWindow_autoOpenCondition_opensWindow() {
        var scenario = scenarioFactory.seed("WC", false);
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, scenario.semesterId())
                .set(Semester::getWindowStatus, "not_open")
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getAutoOpen, 1)
                .set(Semester::getWindowStart, LocalDateTime.now().minusHours(1)));
        activeSemesterService.evict();

        new WindowEngineScheduler(semesterService, activeSemesterService).scanWindow();

        assertThat(fresh(scenario.semesterId()).getWindowStatus()).isEqualTo("open");
    }

    @Test
    @DisplayName("WindowEngineScheduler.scanWindow：auto_open=0 到点不改状态")
    void scanWindow_autoOpenDisabled_keepsNotOpen() {
        var scenario = scenarioFactory.seed("WD", false);
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, scenario.semesterId())
                .set(Semester::getWindowStatus, "not_open")
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getAutoOpen, 0)
                .set(Semester::getWindowStart, LocalDateTime.now().minusHours(1)));
        activeSemesterService.evict();

        new WindowEngineScheduler(semesterService, activeSemesterService).scanWindow();

        assertThat(fresh(scenario.semesterId()).getWindowStatus()).isEqualTo("not_open");
    }

    // ============ 定时扫描：自动截止 ============

    @Test
    @DisplayName("scanWindow：auto_close=1 且 window_end 过去 → closed")
    void scanWindow_autoCloseDue_closesWindow() {
        var scenario = scenarioFactory.seed("WE");
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, scenario.semesterId())
                .set(Semester::getWindowStatus, "open")
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getAutoClose, 1)
                .set(Semester::getWindowEnd, LocalDateTime.now().minusHours(1)));
        activeSemesterService.evict();

        new WindowEngineScheduler(semesterService, activeSemesterService).scanWindow();

        Semester closed = fresh(scenario.semesterId());
        assertThat(closed.getWindowStatus()).isEqualTo("closed");
        assertThat(closed.getChannelOpen()).isZero();
    }

    @Test
    @DisplayName("scanWindow：auto_close=0 且 window_end 过去 → 状态不变（保留手动控制）")
    void scanWindow_autoCloseDisabled_keepsOpen() {
        var scenario = scenarioFactory.seed("WF");
        semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, scenario.semesterId())
                .set(Semester::getWindowStatus, "open")
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getAutoClose, 0)
                .set(Semester::getWindowEnd, LocalDateTime.now().minusHours(1)));
        activeSemesterService.evict();

        new WindowEngineScheduler(semesterService, activeSemesterService).scanWindow();

        Semester stillOpen = fresh(scenario.semesterId());
        assertThat(stillOpen.getWindowStatus()).isEqualTo("open");
        assertThat(stillOpen.getChannelOpen()).isEqualTo(1);
    }

    // ============ 延长 ============

    @Test
    @DisplayName("extendWindow：closed → open + 新 window_end")
    void extendWindow_closedSemester_reopensWithNewDeadline() {
        var scenario = scenarioFactory.seed("WG");
        semesterService.closeWindow(scenario.semesterId());
        LocalDateTime newEnd = LocalDateTime.now().plusDays(3);

        Semester extended = semesterService.extendWindow(scenario.semesterId(),
                new WindowExtendRequest(newEnd));

        assertThat(extended.getWindowStatus()).isEqualTo("open");
        assertThat(extended.getChannelOpen()).isEqualTo(1);
        // DATETIME 列微秒精度：库内读回值与入参在毫秒粒度一致
        assertThat(extended.getWindowEnd()).isCloseTo(newEnd,
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("extendWindow：延长早于当前时间 → 400 PARAM_INVALID")
    void extendWindow_pastDeadline_returns400() {
        var scenario = scenarioFactory.seed("WH");

        assertThatThrownBy(() -> semesterService.extendWindow(scenario.semesterId(),
                new WindowExtendRequest(LocalDateTime.now().minusMinutes(1))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID));
    }

    // ============ 窗口关闭后提交 → 409；补正豁免（W4） ============

    @Test
    @DisplayName("学生提交：窗口 closed → 409 WINDOW_CLOSED")
    void studentSubmit_windowClosed_returns409() {
        var scenario = scenarioFactory.seed("WI");
        semesterService.closeWindow(scenario.semesterId());

        TestSecurity.authenticate(scenario.studentId(), "S" + "WI", "学生WI",
                Set.of("STUDENT"), "STUDENT", seeder.permissionsOf("STUDENT"));
        SemesterContextHolder.set(scenario.semesterId());

        assertThatThrownBy(() -> studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 1)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WINDOW_CLOSED));
    }

    @Test
    @DisplayName("教师补正：表单 rejected 且 correct_deadline 未过 → 关窗也放行（W4）")
    void teacherSubmit_rejectedFormWithinCorrection_windowClosedStillAllowed() {
        var scenario = scenarioFactory.seed("WJ");
        // 造一条 rejected 表单（correct_deadline 未过）
        OrderForm form = new OrderForm();
        form.setSemesterId(scenario.semesterId());
        form.setTeacherId(scenario.teacherId());
        form.setStatus("rejected");
        form.setCorrectDeadline(LocalDateTime.now().plusDays(3));
        form.setReviewNote("数量有误");
        form.setDeleted(0L);
        orderFormMapper.insert(form);
        semesterService.closeWindow(scenario.semesterId());

        TestSecurity.authenticate(scenario.teacherId(), "TWJ", "教师WJ",
                Set.of("TEACHER"), "TEACHER", seeder.permissionsOf("TEACHER"));
        SemesterContextHolder.set(scenario.semesterId());

        var detail = teacherOrderService.submit(new OrderFormSubmitRequest(List.of(
                new OrderFormSubmitItem(scenario.courseId(), scenario.classId(),
                        scenario.textbookId(), 50))));

        assertThat(detail.getStatus()).isEqualTo("pending_review");
        assertThat(detail.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("教师补正：correct_deadline 已过 → 409 CORRECTION_EXPIRED")
    void teacherSubmit_correctionDeadlinePassed_returns409() {
        var scenario = scenarioFactory.seed("WK");
        OrderForm form = new OrderForm();
        form.setSemesterId(scenario.semesterId());
        form.setTeacherId(scenario.teacherId());
        form.setStatus("rejected");
        form.setCorrectDeadline(LocalDateTime.now().minusMinutes(1));
        form.setReviewNote("数量有误");
        form.setDeleted(0L);
        orderFormMapper.insert(form);
        semesterService.closeWindow(scenario.semesterId());

        TestSecurity.authenticate(scenario.teacherId(), "TWK", "教师WK",
                Set.of("TEACHER"), "TEACHER", seeder.permissionsOf("TEACHER"));
        SemesterContextHolder.set(scenario.semesterId());

        assertThatThrownBy(() -> teacherOrderService.submit(new OrderFormSubmitRequest(List.of(
                new OrderFormSubmitItem(scenario.courseId(), scenario.classId(),
                        scenario.textbookId(), 50)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CORRECTION_EXPIRED));
    }

    @Test
    @DisplayName("窗口未开（not_open）提交 → 409 WINDOW_NOT_OPEN")
    void studentSubmit_windowNotOpen_returns409() {
        var scenario = scenarioFactory.seed("WL", false);

        TestSecurity.authenticate(scenario.studentId(), "SWL", "学生WL",
                Set.of("STUDENT"), "STUDENT", seeder.permissionsOf("STUDENT"));
        SemesterContextHolder.set(scenario.semesterId());

        assertThatThrownBy(() -> studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 1)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WINDOW_NOT_OPEN));
    }

    @Test
    @DisplayName("窗口变更记录可查（谁/何时/原值→新值）")
    void windowChanges_recordsBeforeAndAfter() {
        var scenario = scenarioFactory.seed("WM");
        semesterService.openWindow(scenario.semesterId());

        List<AuditLog> changes = semesterService.windowChanges(scenario.semesterId(), 1, 20);

        assertThat(changes).isNotEmpty();
        assertThat(changes).allSatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("WINDOW");
            assertThat(log.getResource()).isEqualTo("window");
            assertThat(log.getDetailJson()).containsKey("before").containsKey("after");
        });
    }
}
