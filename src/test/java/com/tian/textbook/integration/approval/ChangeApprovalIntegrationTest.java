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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
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
    private ChangeRequestMapper changeRequestMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private UserSemesterProfileMapper profileMapper;
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
                "student", "ST1", collegeB, classB));
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
    }

    @Test
    @DisplayName("目标学号不存在 → rejected + field_check_result（TARGET_EXISTS）")
    void submit_targetUserNotExists_rejectedWithFieldCheck() {
        seed();

        var rejected = changeRequestService.submit(new ChangeSubmitRequest(
                "student", "NOSUCH", collegeB, classB));

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
                "student", "ST1", collegeA, classA));

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

        var importResult = changeRequestService.importRows(file);

        assertThat(importResult.getTotal()).isEqualTo(4);
        assertThat(importResult.getOkCount()).isEqualTo(2);
        assertThat(importResult.getErrorCount()).isEqualTo(2);
        assertThat(importResult.getBatchNo()).startsWith("CHG-");

        List<ChangeRequest> batch = changeRequestMapper.selectByBatchNo(importResult.getBatchNo());
        assertThat(batch).hasSize(4);
        assertThat(batch).allSatisfy(r -> assertThat(r.getBatchNo())
                .isEqualTo(importResult.getBatchNo()));
        assertThat(batch).filteredOn(r -> "pending_review".equals(r.getStatus())).hasSize(2);
        assertThat(batch).filteredOn(r -> "rejected".equals(r.getStatus())).hasSize(2);

        // 按批次批量审批（超管）
        var admin = seeder.user("AD2", "超管", "13800000010", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD2", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));

        var result = changeRequestService.batchReview(new ChangeBatchReviewRequest(
                importResult.getBatchNo(), "pass", null));

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
                "student", "ST1", collegeB, classB));

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
                "student", "ST1", collegeB, classB));
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
                "teacher", "TH", collegeB, classB)))
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
