package com.tian.textbook.integration.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.AuditLog;
import com.tian.textbook.system.mapper.AuditLogMapper;
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
 * 数据隔离集成测试（W10 越权矩阵集成级，H2 承载真实 DataPermissionInterceptor）。
 *
 * <p>覆盖：秘书只见本院表单（Service 显式传参）、教师只见本人表单（@CollegeScope 改写 SQL）、
 * 秘书+教师多角色并集不报 SQL 错、秘书访问他院表单详情 403 + 审计落库。</p>
 */
class DataIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TeacherOrderService teacherOrderService;
    @Autowired
    private OrderFormMapper orderFormMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private TestDataSeeder seeder;

    private Long semesterId;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    /** 两学院各一教师 + 一表单；秘书 A（学院A）、多角色用户（学院A 秘书 + 教师A）。 */
    private void seedTwoColleges() {
        var collegeA = seeder.college("计算机学院");
        var majorA = seeder.major(collegeA.getId(), "软件工程");
        var collegeB = seeder.college("外语学院");
        var majorB = seeder.major(collegeB.getId(), "英语");

        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        semesterId = semester.getId();

        var teacherA = seeder.user("TA", "教师A", "13800000001", collegeA.getId(), null,
                1, 0, 1, "TEACHER");
        var teacherB = seeder.user("TB", "教师B", "13800000002", collegeB.getId(), null,
                1, 0, 1, "TEACHER");
        var secretaryA = seeder.user("SA", "秘书A", "13800000003", collegeA.getId(), null,
                1, 0, 1, "SECRETARY");
        var multiRole = seeder.user("MA", "多角色", "13800000004", collegeA.getId(), null,
                1, 0, 1, "SECRETARY", "TEACHER");

        seeder.profile(teacherA.getId(), semesterId, collegeA.getId(), null);
        seeder.profile(teacherB.getId(), semesterId, collegeB.getId(), null);
        seeder.profile(secretaryA.getId(), semesterId, collegeA.getId(), null);
        seeder.profile(multiRole.getId(), semesterId, collegeA.getId(), null);

        insertForm(teacherA.getId(), "pending_review");
        insertForm(teacherB.getId(), "pending_review");
        // 多角色用户自己也提交一单（验证并集时 teacher_id=本人 生效）
        insertForm(multiRole.getId(), "pending_review");
    }

    private OrderForm insertForm(Long teacherId, String status) {
        OrderForm form = new OrderForm();
        form.setSemesterId(semesterId);
        form.setTeacherId(teacherId);
        form.setStatus(status);
        form.setSubmittedAt(LocalDateTime.now());
        form.setDeleted(0L);
        orderFormMapper.insert(form);
        return form;
    }

    private void authenticate(Long userId, String roleCode) {
        TestSecurity.authenticate(userId, "U" + userId, "用户" + userId,
                Set.of(roleCode), roleCode, seeder.permissionsOf(roleCode));
    }

    @Test
    @DisplayName("秘书查本院表单：只见本院（collegeFormsPage 显式传参）")
    void secretaryForms_onlyOwnCollegeVisible() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        authenticate(userIdOf("SA"), "SECRETARY");

        var page = teacherOrderService.collegeFormsPage(null, null, 1, 20);

        // 本院 = 学院A 的教师A + 多角色用户（其 profile 也在学院A）；他院教师B 不可见
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.list()).extracting(com.tian.textbook.order.dto.OrderFormListItem::getTeacherId)
                .containsExactlyInAnyOrder(userIdOf("TA"), userIdOf("MA"));
        assertThat(page.list()).extracting(com.tian.textbook.order.dto.OrderFormListItem::getTeacherId)
                .doesNotContain(userIdOf("TB"));
    }

    @Test
    @DisplayName("教师查本人历史：只见本人（@CollegeScope 改写 SQL）")
    void teacherForms_onlyOwnVisible() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        authenticate(userIdOf("TB"), "TEACHER");

        List<OrderForm> forms = orderFormMapper.selectTeacherForms(userIdOf("TB"));

        assertThat(forms).hasSize(1);
        assertThat(forms).allSatisfy(f -> assertThat(f.getTeacherId()).isEqualTo(userIdOf("TB")));
    }

    @Test
    @DisplayName("多角色（秘书+教师）并集：selectTeacherForms 生成 OR 条件且不报 SQL 错")
    void multiRoleForms_unionWithoutSqlError() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        Long multiRoleId = userIdOf("MA");
        TestSecurity.authenticate(multiRoleId, "MA", "多角色",
                Set.of("SECRETARY", "TEACHER"), "SECRETARY",
                union(seeder.permissionsOf("SECRETARY"), seeder.permissionsOf("TEACHER")));

        List<OrderForm> forms = orderFormMapper.selectTeacherForms(multiRoleId);

        // order_form 无 college_id 列：秘书范围不参与本语句（@CollegeScope 设计，见注解注释），
        // 并集退化为 teacher_id=本人 → 只见自己的表单（不报 SQL 错、不越权）
        assertThat(forms).extracting(OrderForm::getTeacherId)
                .containsExactlyInAnyOrder(multiRoleId);
    }

    @Test
    @DisplayName("秘书访问他院表单详情 → 403 RESOURCE_FORBIDDEN + 审计落库")
    void secretaryAccessOtherCollegeForm_returns403WithAudit() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        Long secretaryA = userIdOf("SA");
        authenticate(secretaryA, "SECRETARY");
        OrderForm otherCollegeForm = formOf(userIdOf("TB"));

        assertThatThrownBy(() -> teacherOrderService.getFormDetail(otherCollegeForm.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_FORBIDDEN));

        List<AuditLog> audits = auditLogMapper.selectByResource("order-form",
                String.valueOf(otherCollegeForm.getId()), 0, 20);
        assertThat(audits).isNotEmpty();
        assertThat(audits).allSatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("REVIEW");
            assertThat(log.getDetailJson()).containsEntry("reason", "越权访问");
        });
    }

    @Test
    @DisplayName("秘书访问本院表单详情 → 放行")
    void secretaryAccessOwnCollegeForm_allowed() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        authenticate(userIdOf("SA"), "SECRETARY");

        var detail = teacherOrderService.getFormDetail(formOf(userIdOf("TA")).getId());

        assertThat(detail.getTeacherId()).isEqualTo(userIdOf("TA"));
    }

    @Test
    @DisplayName("教师访问他人表单详情 → 403 RESOURCE_FORBIDDEN")
    void teacherAccessOtherForm_returns403() {
        seedTwoColleges();
        SemesterContextHolder.set(semesterId);
        authenticate(userIdOf("TA"), "TEACHER");

        assertThatThrownBy(() -> teacherOrderService.getFormDetail(formOf(userIdOf("TB")).getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_FORBIDDEN));
    }

    // ============ 辅助 ============

    private Long userIdOf(String userNo) {
        Long id = seeder.userIdByNo(userNo);
        assertThat(id).as("测试用户 %s 应已由 seedTwoColleges 建好", userNo).isNotNull();
        return id;
    }

    private OrderForm formOf(Long teacherId) {
        return orderFormMapper.selectOne(Wrappers.<OrderForm>lambdaQuery()
                .eq(OrderForm::getTeacherId, teacherId)
                .eq(OrderForm::getSemesterId, semesterId)
                .eq(OrderForm::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> merged = new java.util.HashSet<>(a);
        merged.addAll(b);
        return merged;
    }
}
