package com.tian.textbook.integration.approval;

import com.alibaba.excel.EasyExcel;
import com.tian.textbook.approval.dto.ChangeBatchReviewRequest;
import com.tian.textbook.approval.dto.ChangeImportRow;
import com.tian.textbook.approval.dto.ChangeReviewRequest;
import com.tian.textbook.approval.dto.ChangeSubmitRequest;
import com.tian.textbook.approval.entity.ChangeRequest;
import com.tian.textbook.approval.mapper.ChangeRequestMapper;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异动两级审批集成测试（SPEC §8 / Q10 / W15/W16，H2 承载）。
 *
 * <p>覆盖：逐条提交 → 审批通过立即生效（active 学期 profile）、字段审查失败落 rejected、
 * 变更内容无变化、批量导入（共享 batchNo，2 对 2 错）按批次批量审批、驳回理由必填。</p>
 */
class ChangeApprovalIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ChangeRequestService changeRequestService;
    @Autowired
    private com.tian.textbook.importexport.ImportService importService;
    @Autowired
    private com.tian.textbook.importexport.mapper.ImportBatchMapper importBatchMapper;
    @Autowired
    private ChangeRequestMapper changeRequestMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private UserSemesterProfileMapper profileMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private TestDataSeeder seeder;

    private Long semesterId;
    private Long collegeA;
    private Long collegeB;
    private Long classA;
    private Long classB;
    private Long student1;
    private Long student2;
    private Long student3;
    private Long secretaryId;

    // ============ BE-7a：异动类型（转专业/留级/专升本/其他） ============

    @Test
    @DisplayName("BE-7a：逐条提交带 changeType → 落库并回显中文；缺省归一为 OTHER")
    void submit_withChangeType() {
        seed();

        var transferred = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST2", collegeB, classB, "MAJOR_TRANSFER"));
        assertThat(transferred.getChangeType()).isEqualTo("MAJOR_TRANSFER");
        assertThat(transferred.getChangeTypeLabel()).isEqualTo("转专业");
        assertThat(changeRequestMapper.selectByIdSoft(transferred.getId()).getChangeType())
                .isEqualTo("MAJOR_TRANSFER");

        // 中文与未知值：中文可解析，未知值归一为 OTHER（不阻断历史客户端）
        var repeated = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST3", collegeB, classB, "留级"));
        assertThat(repeated.getChangeType()).isEqualTo("GRADE_REPEAT");

        var unknown = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST4", collegeB, classB, "说不清的说法"));
        assertThat(unknown.getChangeType()).isEqualTo("OTHER");
        assertThat(unknown.getChangeTypeLabel()).isEqualTo("其他");
    }

    @Test
    @DisplayName("BE-7a：审批列表支持 changeType 筛选，历史数据（null）回显未分类")
    void page_filterByChangeType() {
        seed();
        var one = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST2", collegeB, classB, "MAJOR_TRANSFER"));
        changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST3", collegeB, classB, "UPGRADE"));

        var admin = seeder.user("ADP", "超管", "13800000055", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "ADP", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        var filtered = changeRequestService.page(null, null, null, null, "MAJOR_TRANSFER", 1, 20);
        assertThat(filtered.list()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getId()).isEqualTo(one.getId());
                    assertThat(item.getChangeTypeLabel()).isEqualTo("转专业");
                });
        assertThat(changeRequestService.page(null, null, null, null, null, 1, 20).total())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("BE-7a：导入第 6 列「异动类型」（中文/枚举码），缺列不阻断")
    void import_changeTypeColumn() throws Exception {
        seed();
        List<ChangeImportRow> rows = List.of(
                row6("ST2", "student", "外语学院", "英语2401", "转专业", "MAJOR_TRANSFER"),
                row6("ST3", "student", "外语学院", "英语2401", "留级", "留级"),
                row6("ST1", "student", "外语学院", "英语2401", "专升本", null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, ChangeImportRow.class).sheet("异动").doWrite(rows);
        MockMultipartFile file = new MockMultipartFile("file", "changes6.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());

        Long batchId = importService.startImport("change", null, file);
        var batch = awaitDone(batchId);
        assertThat(batch.getOkCount()).isEqualTo(3);

        List<ChangeRequest> imported = changeRequestMapper.selectByBatchNo(batch.getBatchNo());
        assertThat(imported).extracting(ChangeRequest::getChangeType)
                .containsExactlyInAnyOrder("MAJOR_TRANSFER", "GRADE_REPEAT", "OTHER");
    }

    private ChangeImportRow row6(String userNo, String type, String college, String clazz,
                                 String reason, String changeType) {
        ChangeImportRow row = row(userNo, type, college, clazz, reason);
        row.setChangeType(changeType);
        return row;
    }

    /** 轮询异步导入批次至终态（BE-7b：异动导入改为异步后用例需要等待） */
    private com.tian.textbook.importexport.entity.ImportBatch awaitDone(Long batchId) {
        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(60).toMillis();
        com.tian.textbook.importexport.entity.ImportBatch batch =
                importBatchMapper.selectByIdSoft(batchId);
        while (batch == null || (!"done".equals(batch.getStatus()) && !"failed".equals(batch.getStatus()))) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            batch = importBatchMapper.selectByIdSoft(batchId);
        }
        assertThat(batch).isNotNull();
        assertThat(batch.getStatus()).as("批次应成功结束: %s", batch.getErrorDetail()).isEqualTo("done");
        return batch;
    }

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private void seed() {
        var cA = seeder.college("计算机学院");
        var cB = seeder.college("外语学院");
        var majorA = seeder.major(cA.getId(), "软件工程");
        var majorB = seeder.major(cB.getId(), "英语");
        var clazzA = seeder.schoolClass(majorA.getId(), "软工2401", 50);
        var clazzB = seeder.schoolClass(majorB.getId(), "英语2401", 40);

        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());

        collegeA = cA.getId();
        collegeB = cB.getId();
        classA = clazzA.getId();
        classB = clazzB.getId();
        semesterId = semester.getId();

        student1 = seeder.user("ST1", "学生一", "13800000001", collegeA, classA, 1, 0, 1, "STUDENT").getId();
        student2 = seeder.user("ST2", "学生二", "13800000002", collegeA, classA, 1, 0, 1, "STUDENT").getId();
        student3 = seeder.user("ST3", "学生三", "13800000003", collegeA, classA, 1, 0, 1, "STUDENT").getId();
        var secretary = seeder.user("SE", "秘书", "13800000004", collegeA, null, 1, 0, 1, "SECRETARY");
        secretaryId = secretary.getId();
        seeder.profile(student1, semesterId, collegeA, classA);
        seeder.profile(student2, semesterId, collegeA, classA);
        seeder.profile(student3, semesterId, collegeA, classA);
        seeder.profile(secretaryId, semesterId, collegeA, null);

        SemesterContextHolder.set(semesterId);
        TestSecurity.authenticate(secretaryId, "SE", "秘书", Set.of("SECRETARY"), "SECRETARY",
                seeder.permissionsOf("SECRETARY"));
    }

    private static ErrorCode errorCodeOf(Throwable throwable) {
        return ((BizException) throwable).getErrorCode();
    }

    @Test
    @DisplayName("逐条提交异动 → pending_review → 审批通过 → active 学期归属立即生效（W15）")
    void submitAndApprove_changeTakesEffectImmediately() {
        seed();

        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST1", collegeB, classB, null));
        assertThat(submitted.getStatus()).isEqualTo("pending_review");
        assertThat(submitted.getAfterCollegeId()).isEqualTo(collegeB);
        assertThat(submitted.getAfterClassId()).isEqualTo(classB);

        var admin = seeder.user("AD", "超管", "13800000009", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));

        var approved = changeRequestService.review(submitted.getId(),
                new ChangeReviewRequest("pass", null));

        assertThat(approved.getStatus()).isEqualTo("approved");
        UserSemesterProfile profile = profileMapper.selectByUserAndSemester(student1, semesterId);
        assertThat(profile.getCollegeId()).isEqualTo(collegeB);
        assertThat(profile.getClassId()).isEqualTo(classB);
        // sys_user 冗余列同写（/api/me 与 /api/admin/user 的数据来源，SPEC §7）
        SysUser user = userMapper.selectByIdSoft(student1);
        assertThat(user.getCollegeId()).isEqualTo(collegeB);
        assertThat(user.getClassId()).isEqualTo(classB);
    }

    @Test
    @DisplayName("教师异动审批通过：profile 与 sys_user 冗余列只改学院、不动班级")
    void approveTeacherChange_syncsCollegeOnly() {
        seed();
        var teacher = seeder.user("TH9", "教师九", "13800000099", collegeA, null, 1, 0, 1, "TEACHER");
        seeder.profile(teacher.getId(), semesterId, collegeA, null);

        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "teacher", "TH9", collegeB, null, null));
        assertThat(submitted.getStatus()).isEqualTo("pending_review");

        var admin = seeder.user("AD9", "超管", "13800000098", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD9", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        changeRequestService.review(submitted.getId(), new ChangeReviewRequest("pass", null));

        assertThat(profileMapper.selectByUserAndSemester(teacher.getId(), semesterId).getCollegeId())
                .isEqualTo(collegeB);
        SysUser reloaded = userMapper.selectByIdSoft(teacher.getId());
        assertThat(reloaded.getCollegeId()).isEqualTo(collegeB);
        assertThat(reloaded.getClassId()).isNull();
    }

    @Test
    @DisplayName("org-options：返回学院/班级只读选项（仅 id + 名称 + majorId，不含人数等管理字段）")
    void orgOptions_returnsReadOnlyOptions() {
        seed();

        Map<String, Object> options = changeRequestService.orgOptions();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> colleges = (List<Map<String, Object>>) options.get("colleges");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) options.get("classes");
        assertThat(colleges).hasSize(2);
        assertThat(colleges).allSatisfy(c -> assertThat(c).containsOnlyKeys("id", "name"));
        assertThat(colleges).extracting(c -> c.get("name"))
                .containsExactly("计算机学院", "外语学院");
        assertThat(classes).hasSize(2);
        assertThat(classes).allSatisfy(c -> assertThat(c).containsOnlyKeys("id", "name", "majorId"));
        assertThat(classes).extracting(c -> c.get("id")).containsExactly(classA, classB);
    }

    @Test
    @DisplayName("目标学号不存在 → rejected + field_check_result（TARGET_EXISTS）")
    void submit_targetUserNotExists_rejectedWithFieldCheck() {
        seed();

        var rejected = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "NOSUCH", collegeB, classB, null));

        assertThat(rejected.getStatus()).isEqualTo("rejected");
        assertThat(rejected.getFieldCheckResult()).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.rule()).isEqualTo("TARGET_EXISTS");
                    assertThat(issue.message()).isEqualTo("目标学号不存在");
                });
        // rejected 记录不进审批
        TestSecurity.authenticate(seeder.userIdByNo("SE"), "SE", "秘书", Set.of("SECRETARY"),
                "SECRETARY", seeder.permissionsOf("SECRETARY"));
        assertThat(changeRequestService.myRequests())
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo("rejected"));
    }

    @Test
    @DisplayName("变更内容无变化 → rejected（VALUE_CHANGED）")
    void submit_noActualChange_rejectedWithValueChanged() {
        seed();

        var rejected = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST1", collegeA, classA, null));

        assertThat(rejected.getStatus()).isEqualTo("rejected");
        assertThat(rejected.getFieldCheckResult())
                .extracting(com.tian.textbook.common.FieldCheckIssue::rule)
                .contains("VALUE_CHANGED");
        assertThat(rejected.getReason()).isEqualTo("变更内容无变化");
    }

    @Test
    @DisplayName("批量导入 4 行 2 对 2 错（共享 batchNo）→ 按批次批量审批通过")
    void importAndBatchReview_sharedBatchNo() throws Exception {
        seed();

        List<ChangeImportRow> rows = List.of(
                row("ST2", "student", "外语学院", "英语2401", "转专业"),
                row("ST3", "student", "外语学院", "英语2401", "转专业"),
                row("NOSUCH", "student", "外语学院", "英语2401", "转专业"),
                row("ST2", "student", "不存在的学院", "英语2401", "转专业"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, ChangeImportRow.class).sheet("异动").doWrite(rows);
        MockMultipartFile file = new MockMultipartFile("file", "changes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray());

        // BE-7b：异动导入改为异步批次（每 500 行一事务 + 进度 + 错误明细文件）
        Long batchId = importService.startImport("change", null, file);
        var batchInfo = awaitDone(batchId);

        assertThat(batchInfo.getTotal()).isEqualTo(4);
        assertThat(batchInfo.getOkCount()).isEqualTo(2);
        assertThat(batchInfo.getErrorCount()).isEqualTo(2);
        assertThat(batchInfo.getBatchNo()).startsWith("CHG-");
        // 错误明细逐行可见（供下载核对）
        assertThat(batchInfo.getErrorDetail()).hasSize(2);

        List<ChangeRequest> batch = changeRequestMapper.selectByBatchNo(batchInfo.getBatchNo());
        assertThat(batch).hasSize(4);
        assertThat(batch).allSatisfy(r -> assertThat(r.getBatchNo())
                .isEqualTo(batchInfo.getBatchNo()));
        assertThat(batch).filteredOn(r -> "pending_review".equals(r.getStatus())).hasSize(2);
        assertThat(batch).filteredOn(r -> "rejected".equals(r.getStatus())).hasSize(2);

        // 按批次批量审批（超管）
        var admin = seeder.user("AD2", "超管", "13800000010", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD2", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));

        var result = changeRequestService.batchReview(new ChangeBatchReviewRequest(
                batchInfo.getBatchNo(), "pass", null));

        assertThat(result.getCount()).isEqualTo(2);
        assertThat(result.getAction()).isEqualTo("pass");
        // 生效：两个学生归属均改到外语学院英语2401
        for (Long studentId : List.of(student2, student3)) {
            UserSemesterProfile profile = profileMapper.selectByUserAndSemester(studentId, semesterId);
            assertThat(profile.getCollegeId()).isEqualTo(collegeB);
            assertThat(profile.getClassId()).isEqualTo(classB);
        }
    }

    @Test
    @DisplayName("审批驳回无理由 → 400 PARAM_INVALID")
    void review_rejectWithoutReason_returns400() {
        seed();
        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST1", collegeB, classB, null));

        assertThatThrownBy(() -> changeRequestService.review(submitted.getId(),
                new ChangeReviewRequest("reject", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    @DisplayName("重复审批已处理记录 → 409 STATE_CONFLICT")
    void review_alreadyProcessed_returns409() {
        seed();
        var submitted = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "ST1", collegeB, classB, null));
        var admin = seeder.user("AD3", "超管", "13800000011", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD3", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        changeRequestService.review(submitted.getId(), new ChangeReviewRequest("pass", null));

        assertThatThrownBy(() -> changeRequestService.review(submitted.getId(),
                new ChangeReviewRequest("reject", "重复审批")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));
    }

    @Test
    @DisplayName("教师异动仅支持变更学院：传 targetClassId → 400 PARAM_INVALID")
    void submit_teacherChangeWithClass_returns400() {
        seed();
        var teacher = seeder.user("TH", "教师", "13800000005", collegeA, null, 1, 0, 1, "TEACHER");
        seeder.profile(teacher.getId(), semesterId, collegeA, null);

        assertThatThrownBy(() -> changeRequestService.submit(new ChangeSubmitRequest(
                "teacher", "TH", collegeB, classB, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    private static ChangeImportRow row(String userNo, String type, String collegeName,
                                       String className, String reason) {
        ChangeImportRow row = new ChangeImportRow();
        row.setUserNo(userNo);
        row.setType(type);
        row.setCollegeName(collegeName);
        row.setClassName(className);
        row.setReason(reason);
        return row;
    }
}
