package com.tian.textbook.integration.security;

import com.tian.textbook.approval.dto.ChangeReviewRequest;
import com.tian.textbook.approval.dto.ChangeSubmitRequest;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.importexport.service.ExportServiceImpl;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.OrderScenarioFactory;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0 越权与一致性修复的回归测试（F3 / F4 / F5 / S12 / S23）。
 *
 * <p>每条用例对应审查报告中的一个可利用缺陷，锁定修复后的行为。</p>
 */
class SecurityHardeningIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExportServiceImpl exportService;
    @Autowired
    private ExportTaskMapper exportTaskMapper;
    @Autowired
    private ChangeRequestService changeRequestService;
    @Autowired
    private NotifyService notifyService;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private UserSemesterProfileMapper profileMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private OrderScenarioFactory scenarioFactory;
    /** 使用 Spring 装配的 ObjectMapper（含 JSR310 模块），与接口实际序列化口径一致 */
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private void asSupplier(Long supplierId) {
        TestSecurity.authenticate(supplierId, "SUP1", "供货商", Set.of("SUPPLIER"), "SUPPLIER",
                seeder.permissionsOf("SUPPLIER"));
    }

    private void asAdmin() {
        SysUser admin = seeder.user("ADM", "超管", "13800000099", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "ADM", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
    }

    /** 直接造一条内部导出任务（模拟「他人/其他 bizType」的任务）。 */
    private ExportTask seedExportTask(String bizType, Long createdBy) {
        ExportTask task = new ExportTask();
        task.setBizType(bizType);
        task.setParamsJson(Map.of("semesterId", 1));
        task.setRowEstimate(10);
        task.setStatus("done");
        task.setProgressPct(100);
        task.setFilePath("data/export/seed.xlsx");
        task.setDownloadToken("seed-token-" + bizType);
        task.setTokenExpireAt(com.tian.textbook.common.util.AppTime.now().plusMinutes(10));
        task.setCreatedBy(createdBy);
        task.setDeleted(0L);
        exportTaskMapper.insert(task);
        return task;
    }

    // ============ F3：供货商导出任务 IDOR ============

    @Test
    @DisplayName("F3：供货商读不到内部导出任务（bizType 白名单 + 归属校验），且 token 不下发")
    void supplier_cannotReadInternalExportTask() {
        var scenario = scenarioFactory.seed("F3");
        SysUser supplier = seeder.user("SUP1", "供货商", "13800000001", null, null, 1, 0, 1, "SUPPLIER");
        SysUser admin = seeder.user("ADM", "超管", "13800000002", null, null, 1, 0, 1, "ADMIN");
        ExportTask internal = seedExportTask("order", admin.getId());

        asSupplier(supplier.getId());
        // 枚举 id 读内部任务 → 统一按 404（不通过 403/404 差异泄露任务是否存在）
        assertThatThrownBy(() -> exportService.getSupplierTask(internal.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        // 即便拿到 id + token 也不能下载内部文件
        assertThatThrownBy(() -> exportService.claimSupplierDownload(internal.getId(), "seed-token-order"))
                .isInstanceOf(BizException.class);
        assertThat(scenario.semesterId()).isNotNull();
    }

    @Test
    @DisplayName("F3/B-D13：内部用户 A 查 B 的导出任务 → 404（含下载），ADMIN 例外")
    void internalUser_cannotReadOthersExportTask() {
        // B 的任务（由 ADMIN 账号创建）
        SysUser admin = seeder.user("ADM", "超管", "13800000003", null, null, 1, 0, 1, "ADMIN");
        SysUser teacher = seeder.user("T900", "教师", "13800000004", null, null, 1, 0, 1, "TEACHER");
        ExportTask others = seedExportTask("order", admin.getId());

        // A（教师，非 ADMIN）按 id 直取 → 统一 404（403/404 差异可被用来枚举任务是否存在）
        TestSecurity.authenticate(teacher.getId(), "T900", "教师", Set.of("TEACHER"), "TEACHER",
                seeder.permissionsOf("TEACHER"));
        assertThatThrownBy(() -> exportService.getTaskForUser(others.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        // 即便拿到 id + token 也不能下载他人文件
        assertThatThrownBy(() -> exportService.claimDownloadForUser(others.getId(), "seed-token-order"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        // ADMIN 例外：可读（排障可见性），非业务可达
        TestSecurity.authenticate(admin.getId(), "ADM", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        assertThat(exportService.getTaskForUser(others.getId()).getId()).isEqualTo(others.getId());
    }

    @Test
    @DisplayName("F3：filePath 不下发前端；downloadToken 保留（异步下载链路依赖轮询响应取 token）")
    void exportTask_hidesFilePathButKeepsToken() throws Exception {
        ExportTask task = seedExportTask("supplier", 1L);
        ExportTask loaded = exportTaskMapper.selectByIdSoft(task.getId());

        String json = objectMapper.writeValueAsString(loaded);

        // 服务器内部路径绝不下发
        assertThat(json).doesNotContain("filePath").doesNotContain("data/export/seed.xlsx");
        // token 必须下发：异步导出完成后前端只能从轮询响应拿到它（越权由归属校验兜住）
        assertThat(json).contains("downloadToken").contains("seed-token-supplier");
    }

    // ============ S12：一次性下载 token 原子消费 ============

    @Test
    @DisplayName("S12：token 以 CAS 消费，二次下载（同 token）返回 410")
    void downloadToken_isSingleUse() {
        ExportTask task = seedExportTask("supplier", 1L);

        ExportTask first = exportService.claimDownload(task.getId(), "seed-token-supplier");
        assertThat(first.getDownloadToken()).isNull();

        assertThatThrownBy(() -> exportService.claimDownload(task.getId(), "seed-token-supplier"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DOWNLOAD_TOKEN_INVALID));
    }

    // ============ F4：秘书导出不得跨院 ============

    @Test
    @DisplayName("F4：非 ADMIN 传入 collegeId 被忽略，强制由 profile 归属推导（不能导出他院/全院）")
    void secretaryExport_ignoresRequestedCollegeId() {
        var scenario = scenarioFactory.seed("F4");
        SemesterContextHolder.set(scenario.semesterId());
        SysUser secretary = seeder.user("SEC1", "秘书", "13800000003",
                scenario.collegeId(), null, 1, 0, 1, "SECRETARY");
        seeder.profile(secretary.getId(), scenario.semesterId(), scenario.collegeId(), null);
        TestSecurity.authenticate(secretary.getId(), "SEC1", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));

        // 传他院 collegeId（9999）与不传都应被忽略，实际范围 = 本人归属学院
        var planWithOther = exportService.planOrderExport(scenario.semesterId(), 9999L);
        assertThat(planWithOther.params()).containsEntry("collegeId", scenario.collegeId());
        var planWithout = exportService.planOrderExport(scenario.semesterId(), null);
        assertThat(planWithout.params()).containsEntry("collegeId", scenario.collegeId());
    }

    @Test
    @DisplayName("F4：无学院归属的秘书导出被拒（绝不退化为全院）")
    void secretaryExport_withoutCollege_isRejected() {
        var scenario = scenarioFactory.seed("F4B");
        SemesterContextHolder.set(scenario.semesterId());
        SysUser secretary = seeder.user("SEC2", "秘书", "13800000004", null, null, 1, 0, 1, "SECRETARY");
        TestSecurity.authenticate(secretary.getId(), "SEC2", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));

        assertThatThrownBy(() -> exportService.planOrderExport(scenario.semesterId(), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID));
    }

    // ============ F5：异动审批写对学期、不清空归属 ============

    @Test
    @DisplayName("F5：异动生效写入「异动所属学期」而非当前 active 学期（跨学期审批不污染新学期）")
    void changeApproval_writesOwnSemester() {
        var scenario = scenarioFactory.seed("F5");
        SemesterContextHolder.set(scenario.semesterId());
        // 目标学院必须与现状不同，否则触发 VALUE_CHANGED「变更内容无变化」
        var targetCollege = seeder.college("外语学院F5");
        var targetMajor = seeder.major(targetCollege.getId(), "英语F5");
        var targetClass = seeder.schoolClass(targetMajor.getId(), "英语2401F5", 40);
        SysUser student = seeder.user("ST5", "学生五", "13800000005",
                scenario.collegeId(), scenario.classId(), 1, 0, 1, "STUDENT");
        seeder.profile(student.getId(), scenario.semesterId(), scenario.collegeId(), scenario.classId());
        SysUser secretary = seeder.user("SEC5", "秘书", "13800000006",
                scenario.collegeId(), null, 1, 0, 1, "SECRETARY");
        seeder.profile(secretary.getId(), scenario.semesterId(), scenario.collegeId(), null);

        TestSecurity.authenticate(secretary.getId(), "SEC5", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));
        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST5", targetCollege.getId(), targetClass.getId(), null));
        assertThat(submitted.getStatus()).isEqualTo("pending_review");

        asAdmin();
        var approved = changeRequestService.review(submitted.getId(), new ChangeReviewRequest("pass", null));

        assertThat(approved.getStatus()).isEqualTo("approved");
        // 归属写进「异动所属学期」（= 当前 active 学期），冗余列同步
        UserSemesterProfile profile = profileMapper.selectByUserAndSemester(student.getId(), scenario.semesterId());
        assertThat(profile.getCollegeId()).isEqualTo(targetCollege.getId());
        SysUser reloaded = userMapper.selectByIdSoft(student.getId());
        assertThat(reloaded.getCollegeId()).isEqualTo(targetCollege.getId());
    }

    @Test
    @DisplayName("F5：payload 缺失目标学院时审批中止（不把用户 college_id 置空）")
    void changeApproval_missingTargetCollege_aborts() {
        var scenario = scenarioFactory.seed("F5C");
        SemesterContextHolder.set(scenario.semesterId());
        var targetCollege = seeder.college("外语学院F5C");
        var targetMajor = seeder.major(targetCollege.getId(), "英语F5C");
        var targetClass = seeder.schoolClass(targetMajor.getId(), "英语2401F5C", 40);
        SysUser student = seeder.user("ST6", "学生六", "13800000007",
                scenario.collegeId(), scenario.classId(), 1, 0, 1, "STUDENT");
        seeder.profile(student.getId(), scenario.semesterId(), scenario.collegeId(), scenario.classId());
        SysUser secretary = seeder.user("SEC6", "秘书", "13800000008",
                scenario.collegeId(), null, 1, 0, 1, "SECRETARY");
        seeder.profile(secretary.getId(), scenario.semesterId(), scenario.collegeId(), null);

        TestSecurity.authenticate(secretary.getId(), "SEC6", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));
        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST6", targetCollege.getId(), targetClass.getId(), null));
        assertThat(submitted.getStatus()).isEqualTo("pending_review");

        // 模拟 payload 被破坏（after 段缺失，readBelonging 返回 (null, null)）
        jdbcTemplate.update("UPDATE change_request SET payload_json = ? WHERE id = ?",
                "{\"before\":{\"collegeId\":" + scenario.collegeId() + "}}", submitted.getId());

        asAdmin();
        assertThatThrownBy(() -> changeRequestService.review(submitted.getId(),
                new ChangeReviewRequest("pass", null)))
                .isInstanceOf(BizException.class);

        // 归属未被清空（原缺陷：set(collegeId, null) 会把用户 college_id 置空）
        SysUser reloaded = userMapper.selectByIdSoft(student.getId());
        assertThat(reloaded.getCollegeId()).isEqualTo(scenario.collegeId());
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ============ S23：通知按 target_roles 定向 ============

    @Test
    @DisplayName("S23：供货商读不到面向秘书/教师/学生的内部通知（target_roles 过滤生效）")
    void notice_isFilteredByTargetRoles() {
        var scenario = scenarioFactory.seed("S23");
        SemesterContextHolder.set(scenario.semesterId());
        SysUser supplier = seeder.user("SUP9", "供货商", "13800000010", null, null, 1, 0, 1, "SUPPLIER");
        SysUser student = seeder.user("ST9", "学生九", "13800000011",
                scenario.collegeId(), scenario.classId(), 1, 0, 1, "STUDENT");
        seeder.profile(student.getId(), scenario.semesterId(), scenario.collegeId(), scenario.classId());

        // scenarioFactory 开窗即触发窗口变更通知（target_roles = 秘书/教师/学生），直接复用它
        asAdmin();
        assertThat(notifyService.listTasks()).isNotEmpty();

        asSupplier(supplier.getId());
        assertThat(notifyService.listUnconfirmed()).isEmpty();
        assertThat(notifyService.myNotices(1, 20).list()).isEmpty();

        TestSecurity.authenticate(student.getId(), "ST9", "学生九", Set.of("STUDENT"), "STUDENT",
                seeder.permissionsOf("STUDENT"));
        assertThat(notifyService.listUnconfirmed()).hasSize(1);
    }

    @Test
    @DisplayName("S23：供货商不能确认面向他人的通知（404，不泄露任务存在性）")
    void notice_confirmRejectedForNonTarget() {
        var scenario = scenarioFactory.seed("S23B");
        SemesterContextHolder.set(scenario.semesterId());
        SysUser supplier = seeder.user("SUP8", "供货商", "13800000012", null, null, 1, 0, 1, "SUPPLIER");

        asAdmin();
        var task = notifyService.listTasks().get(0);

        asSupplier(supplier.getId());
        assertThatThrownBy(() -> notifyService.confirm(task.getId(), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ============ S13：异动目标范围 ============

    @Test
    @DisplayName("S13：type 与目标用户角色不匹配 → 落 rejected 行错误（不再放行）")
    void changeSubmit_roleMismatch_rejected() {
        var scenario = scenarioFactory.seed("S13");
        SemesterContextHolder.set(scenario.semesterId());
        // 目标是一个教师，但按 type=student 提交
        SysUser teacher = seeder.user("T13", "教师十三", "13800000013",
                scenario.collegeId(), null, 1, 0, 1, "TEACHER");
        seeder.profile(teacher.getId(), scenario.semesterId(), scenario.collegeId(), null);
        SysUser secretary = seeder.user("SEC13", "秘书", "13800000014",
                scenario.collegeId(), null, 1, 0, 1, "SECRETARY");
        seeder.profile(secretary.getId(), scenario.semesterId(), scenario.collegeId(), null);

        TestSecurity.authenticate(secretary.getId(), "SEC13", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));
        var result = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "T13", scenario.collegeId(), scenario.classId(), null));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getFieldCheckResult())
                .anySatisfy(issue -> assertThat(issue.rule()).isEqualTo("TARGET_ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("S13：跨院目标 → 落 rejected 行错误（TARGET_SCOPE）")
    void changeSubmit_crossCollege_rejected() {
        var scenario = scenarioFactory.seed("S13B");
        SemesterContextHolder.set(scenario.semesterId());
        var otherCollege = seeder.college("外语学院S13B");
        var otherMajor = seeder.major(otherCollege.getId(), "英语S13B");
        var otherClass = seeder.schoolClass(otherMajor.getId(), "英语2401S13B", 40);
        SysUser outsider = seeder.user("ST13", "外院学生", "13800000015",
                otherCollege.getId(), otherClass.getId(), 1, 0, 1, "STUDENT");
        seeder.profile(outsider.getId(), scenario.semesterId(), otherCollege.getId(), otherClass.getId());
        SysUser secretary = seeder.user("SEC14", "秘书", "13800000016",
                scenario.collegeId(), null, 1, 0, 1, "SECRETARY");
        seeder.profile(secretary.getId(), scenario.semesterId(), scenario.collegeId(), null);

        TestSecurity.authenticate(secretary.getId(), "SEC14", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));
        var result = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST13", scenario.collegeId(), otherClass.getId(), null));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getFieldCheckResult())
                .anySatisfy(issue -> assertThat(issue.rule()).isEqualTo("TARGET_SCOPE"));
    }
}
