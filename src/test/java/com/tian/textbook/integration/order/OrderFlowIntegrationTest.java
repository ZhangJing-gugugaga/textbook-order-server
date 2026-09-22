package com.tian.textbook.integration.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.order.dto.OrderFormReviewRequest;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.order.dto.OrderFormSubmitRequest;
import com.tian.textbook.order.dto.StudentBookVO;
import com.tian.textbook.order.dto.StudentOrderSubmitItem;
import com.tian.textbook.order.dto.StudentOrderSubmitRequest;
import com.tian.textbook.order.dto.TeacherTextbookOptionVO;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.entity.OrderFormItem;
import com.tian.textbook.order.entity.StudentOrder;
import com.tian.textbook.order.entity.StudentOrderItem;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.order.mapper.StudentOrderItemMapper;
import com.tian.textbook.order.mapper.StudentOrderMapper;
import com.tian.textbook.order.service.StudentOrderService;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.OrderScenarioFactory;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 教师征订 + 学生选购全流程集成测试（SPEC §8 / W3/W4/W9，H2 承载）。
 *
 * <p>覆盖：字段审查六规则落库可读回（autoResultMap JSON）、审核通过/驳回（理由必填 +
 * correct_deadline）、学生清单 required/delisted 语义、提交覆盖语义与 submit_snapshot。</p>
 */
class OrderFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TeacherOrderService teacherOrderService;
    @Autowired
    private StudentOrderService studentOrderService;
    @Autowired
    private OrderFormMapper orderFormMapper;
    @Autowired
    private OrderFormItemMapper orderFormItemMapper;
    @Autowired
    private StudentOrderMapper studentOrderMapper;
    @Autowired
    private StudentOrderItemMapper studentOrderItemMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private OrderScenarioFactory scenarioFactory;
    @Autowired
    private TestDataSeeder seeder;

    private OrderScenarioFactory.Scenario scenario;
    private Long adminId;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private void seedScenario(String suffix) {
        scenario = scenarioFactory.seed(suffix);
        SemesterContextHolder.set(scenario.semesterId());
    }

    private void asTeacher() {
        TestSecurity.authenticate(scenario.teacherId(), "T" + scenario.semesterId(), "教师",
                Set.of("TEACHER"), "TEACHER", seeder.permissionsOf("TEACHER"));
    }

    private void asStudent() {
        TestSecurity.authenticate(scenario.studentId(), "S" + scenario.semesterId(), "学生",
                Set.of("STUDENT"), "STUDENT", seeder.permissionsOf("STUDENT"));
    }

    private void asAdmin() {
        if (adminId == null) {
            var admin = seeder.user("admin", "超管", "13800000000", null, null, 1, 0, 1, "ADMIN");
            adminId = admin.getId();
        }
        TestSecurity.authenticate(adminId, "admin", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
    }

    private OrderFormSubmitRequest validItems() {
        return new OrderFormSubmitRequest(List.of(new OrderFormSubmitItem(
                scenario.courseId(), scenario.classId(), scenario.textbookId(), 50)));
    }

    private static ErrorCode errorCodeOf(Throwable throwable) {
        return ((BizException) throwable).getErrorCode();
    }

    // ============ 字段审查（一级） ============

    @Test
    @DisplayName("教师提交：数量超班级人数 → rejected_auto + field_check_result 逐字段落库可读回")
    void teacherSubmit_quantityAboveClassCount_rejectedAutoWithFieldCheckResult() {
        seedScenario("OA");
        asTeacher();

        assertThatThrownBy(() -> teacherOrderService.submit(new OrderFormSubmitRequest(
                List.of(new OrderFormSubmitItem(scenario.courseId(), scenario.classId(),
                        scenario.textbookId(), 51)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getErrorCode()).isEqualTo(ErrorCode.FIELD_CHECK_FAILED);
                    assertThat((List<?>) biz.getData()).singleElement()
                            .satisfies(issue -> {
                                FieldCheckIssue i = (FieldCheckIssue) issue;
                                assertThat(i.rule()).isEqualTo("QTY_RANGE");
                                assertThat(i.field()).isEqualTo("items[0].quantity");
                                assertThat(i.message()).contains("1-50");
                            });
                });

        // rejected_auto 状态 + JSON 落库可读回（autoResultMap + JacksonTypeHandler）
        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(scenario.semesterId(),
                scenario.teacherId());
        assertThat(form.getStatus()).isEqualTo("rejected_auto");
        assertThat(form.getFieldCheckResult()).singleElement()
                .satisfies(i -> assertThat(i.rule()).isEqualTo("QTY_RANGE"));
    }

    @Test
    @DisplayName("教师提交：课程不归本人 → COURSE_OWNER（逐字段）")
    void teacherSubmit_courseNotOwned_returnsCourseOwnerIssue() {
        seedScenario("OB");
        asTeacher();
        var otherCourse = seeder.course(scenario.semesterId(), "CS-OB", "他人课程");

        assertThatThrownBy(() -> teacherOrderService.submit(new OrderFormSubmitRequest(
                List.of(new OrderFormSubmitItem(otherCourse.getId(), scenario.classId(),
                        scenario.textbookId(), 10)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((List<?>) ((BizException) e).getData()))
                        .extracting(issue -> ((FieldCheckIssue) issue).rule())
                        .containsExactly("COURSE_OWNER"));

        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(scenario.semesterId(),
                scenario.teacherId());
        assertThat(form.getStatus()).isEqualTo("rejected_auto");
        assertThat(form.getFieldCheckResult()).singleElement()
                .satisfies(i -> assertThat(i.rule()).isEqualTo("COURSE_OWNER"));
    }

    @Test
    @DisplayName("教师提交：全过 → pending_review + 明细落库")
    void teacherSubmit_allRulesPass_pendingReview() {
        seedScenario("OC");
        asTeacher();

        var detail = teacherOrderService.submit(validItems());

        assertThat(detail.getStatus()).isEqualTo("pending_review");
        assertThat(detail.getItems()).hasSize(1);
        assertThat(detail.getTotalQuantity()).isEqualTo(50);
        List<OrderFormItem> items = orderFormItemMapper.selectByFormId(detail.getId());
        assertThat(items).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(50));
    }

    @Test
    @DisplayName("教师重提：整单覆盖（旧明细逻辑删、新明细插入）")
    void teacherResubmit_overwritesPreviousItems() {
        seedScenario("OD");
        asTeacher();
        var first = teacherOrderService.submit(validItems());

        var second = teacherOrderService.submit(new OrderFormSubmitRequest(List.of(
                new OrderFormSubmitItem(scenario.courseId(), scenario.classId(),
                        scenario.textbookId(), 10))));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(orderFormItemMapper.selectByFormId(first.getId())).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(10));
        // 旧明细被逻辑删除（deleted != 0），唯一键不冲突
        List<OrderFormItem> all = orderFormItemMapper.selectList(Wrappers
                .<OrderFormItem>lambdaQuery().eq(OrderFormItem::getFormId, first.getId()));
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(i -> i.getDeleted() != 0).hasSize(1);
    }

    // ============ 内容审核（二级） ============

    @Test
    @DisplayName("超管审核 pass → reviewed")
    void adminReview_pass_marksReviewed() {
        seedScenario("OE");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();

        var reviewed = teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("pass", null));

        assertThat(reviewed.getStatus()).isEqualTo("reviewed");
        assertThat(reviewed.getReviewAt()).isNotNull();
    }

    @Test
    @DisplayName("超管审核 reject 无理由 → 400 PARAM_INVALID")
    void adminReview_rejectWithoutReason_returns400() {
        seedScenario("OF");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();

        assertThatThrownBy(() -> teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("reject", "  ")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));

        assertThat(orderFormMapper.selectByIdSoft(submitted.getId()).getStatus())
                .isEqualTo("pending_review");
    }

    @Test
    @DisplayName("超管审核 reject（有理由）→ rejected + correct_deadline = window_end + 7 天")
    void adminReview_rejectWithReason_setsCorrectDeadline() {
        seedScenario("OG");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        LocalDateTime windowEnd = semesterMapper.selectByIdSoft(scenario.semesterId()).getWindowEnd();

        var rejected = teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("reject", "数量超出班级实际人数"));

        assertThat(rejected.getStatus()).isEqualTo("rejected");
        assertThat(rejected.getReviewNote()).isEqualTo("数量超出班级实际人数");
        assertThat(rejected.getCorrectDeadline()).isEqualTo(windowEnd.plusDays(7));

        OrderForm reloaded = orderFormMapper.selectByIdSoft(submitted.getId());
        assertThat(reloaded.getStatus()).isEqualTo("rejected");
        assertThat(reloaded.getCorrectDeadline()).isEqualTo(windowEnd.plusDays(7));
    }

    @Test
    @DisplayName("超管审核：非 pending_review 状态 → 409 STATE_CONFLICT")
    void adminReview_alreadyReviewed_returns409() {
        seedScenario("OH");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));

        assertThatThrownBy(() -> teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("reject", "重复审核")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));
    }

    // ============ 学生选购清单（W3） ============

    @Test
    @DisplayName("学生 book-list：reviewed 后教材入清单 required=true；表单被驳回后 delisted=true")
    void studentBookList_reflectsReviewAndRejection() {
        seedScenario("OI");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        var reviewed = teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("pass", null));
        assertThat(reviewed.getStatus()).isEqualTo("reviewed");
        asStudent();

        List<StudentBookVO> afterPass = studentOrderService.bookList();
        assertThat(afterPass).singleElement()
                .satisfies(book -> {
                    assertThat(book.getTextbookId()).isEqualTo(scenario.textbookId());
                    assertThat(book.isRequired()).isTrue();
                    assertThat(book.isDelisted()).isFalse();
                });

        // reviewed 为终态（PRD 状态机「退出条件 = —」）：教师重提必须被拒，
        // 否则审批结论会被静默撤销（学生清单与采购导出随之变化且无留痕）
        asTeacher();
        assertThatThrownBy(() -> teacherOrderService.submit(validItems()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));

        // 驳回路径的 delisted 语义见 studentSubmit_delistedTextbook_returns400
        // （rejected 表单无 reviewed 来源 → 清单标「已下架」）
        asStudent();
        List<StudentBookVO> stillReviewed = studentOrderService.bookList();
        assertThat(stillReviewed).singleElement()
                .satisfies(book -> assertThat(book.isDelisted()).isFalse());
    }

    @Test
    @DisplayName("S3：审核页打开后教师重提 → 用旧版本号审核必须 409（防审核对象漂移）")
    void review_staleContentVersion_conflicts() {
        seedScenario("OS3");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        assertThat(submitted.getStatus()).isEqualTo("pending_review");
        int versionSeenByReviewer = submitted.getContentVersion();
        assertThat(versionSeenByReviewer).as("详情必须暴露 contentVersion 供审核端回传").isPositive();

        // 审核员打开详情页之后，教师又重提了一次（状态仍是 pending_review，明细已被整单覆盖）
        asTeacher();
        // 数量须在 1..班级人数(50) 内，取 30 以区别于首次提交的 50
        var resubmitted = teacherOrderService.submit(new OrderFormSubmitRequest(List.of(
                new OrderFormSubmitItem(scenario.courseId(), scenario.classId(), scenario.textbookId(), 30))));
        assertThat(resubmitted.getContentVersion())
                .as("整单覆盖后内容版本必须递增")
                .isGreaterThan(versionSeenByReviewer);

        // 审核员用打开页面时看到的版本号提交 → 必须被拒（否则审批结论落在没见过的内容上）
        asAdmin();
        assertThatThrownBy(() -> teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("pass", null, versionSeenByReviewer)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));

        // 刷新详情拿到新版本号后审核成功
        var fresh = teacherOrderService.getFormDetail(submitted.getId());
        var approved = teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("pass", null, fresh.getContentVersion()));
        assertThat(approved.getStatus()).isEqualTo("reviewed");
    }

    @Test
    @DisplayName("S3：未回传 contentVersion 时降级为请求内比对，审核仍可正常通过")
    void review_withoutContentVersion_stillWorks() {
        seedScenario("OS3B");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());

        asAdmin();
        // 两参构造（兼容旧调用）→ contentVersion=null → 降级路径
        var approved = teacherOrderService.review(submitted.getId(),
                new OrderFormReviewRequest("pass", null));

        assertThat(approved.getStatus()).isEqualTo("reviewed");
    }

    @Test
    @DisplayName("学生提交：含 delisted 教材 → 400 BOOK_DELISTED")
    void studentSubmit_delistedTextbook_returns400() {
        seedScenario("OJ");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        // 直接驳回（而非「通过后再重提驳回」——reviewed 是终态，不可重提）：
        // 该表单已提交但无 reviewed 来源，教材在清单中标「已下架」
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("reject", "暂缓"));
        asStudent();

        assertThatThrownBy(() -> studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 1)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.BOOK_DELISTED));
    }

    @Test
    @DisplayName("学生提交：正常 → submitted + submit_snapshot；重提覆盖（旧明细逻辑删）")
    void studentSubmit_normal_thenResubmitOverwrites() {
        seedScenario("OK");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));
        asStudent();

        var order = studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 2))));

        assertThat(order.getStatus()).isEqualTo("submitted");
        assertThat(order.getSubmittedAt()).isNotNull();
        assertThat(order.getSubmitSnapshot()).containsEntry("collegeId", scenario.collegeId())
                .containsEntry("classId", scenario.classId())
                .containsEntry("className", "软工2401OK");
        assertThat(order.getTotalQuantity()).isEqualTo(2);
        assertThat(studentOrderItemMapper.selectByOrderId(order.getId())).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(2));

        // 重提：整单覆盖
        var resubmitted = studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 5))));
        assertThat(resubmitted.getId()).isEqualTo(order.getId());
        assertThat(resubmitted.getTotalQuantity()).isEqualTo(5);
        List<StudentOrderItem> all = studentOrderItemMapper.selectList(Wrappers
                .<StudentOrderItem>lambdaQuery().eq(StudentOrderItem::getOrderId, order.getId()));
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(i -> i.getDeleted() != 0).hasSize(1);
    }

    @Test
    @DisplayName("学生提交：数量超班级人数上限 → 400 PARAM_INVALID")
    void studentSubmit_quantityAboveClassCount_returns400() {
        seedScenario("OL");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));
        asStudent();

        assertThatThrownBy(() -> studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 51)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID);
                    assertThat(e.getMessage()).contains("1-9"); // 学生选购硬上限 min(9, 班级人数)
                });
    }

    @Test
    @DisplayName("学生提交：教材重复 → 400 PARAM_INVALID（唯一键兜底前先行拦截）")
    void studentSubmit_duplicateTextbook_returns400() {
        seedScenario("OM");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));
        asStudent();

        assertThatThrownBy(() -> studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 1),
                        new StudentOrderSubmitItem(scenario.textbookId(), 2)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("教材重复"));
    }

    @Test
    @DisplayName("一人一学期一单：重复提交同一教师不建第二条（唯一键兜底）")
    void teacherSubmit_duplicateForm_upsertsSameRow() {
        seedScenario("ON");
        asTeacher();
        teacherOrderService.submit(validItems());
        teacherOrderService.submit(validItems());

        List<OrderForm> forms = orderFormMapper.selectList(Wrappers.<OrderForm>lambdaQuery()
                .eq(OrderForm::getSemesterId, scenario.semesterId())
                .eq(OrderForm::getTeacherId, scenario.teacherId())
                .eq(OrderForm::getDeleted, 0));
        assertThat(forms).hasSize(1);

        List<StudentOrder> studentOrders = studentOrderMapper.selectList(Wrappers
                .<StudentOrder>lambdaQuery().eq(StudentOrder::getSemesterId, scenario.semesterId()));
        assertThat(studentOrders).isEmpty();
    }

    @Test
    @DisplayName("看板口径：各学院提交进度按 profile 归属聚合")
    void dashboardCount_aggregatesByCollegeProfile() {
        seedScenario("OO");
        asTeacher();
        teacherOrderService.submit(validItems());

        List<Map<String, Object>> rows = orderFormMapper.countGroupByCollegeAndStatus(
                scenario.semesterId());
        assertThat(rows).singleElement()
                .satisfies(row -> {
                    // H2 列标签大写 / MySQL 保留别名：大小写兼容取值
                    assertThat(com.tian.textbook.common.util.MapKeys.pick(row, "collegeId"))
                            .isEqualTo(scenario.collegeId());
                    assertThat(com.tian.textbook.common.util.MapKeys.pick(row, "status"))
                            .isEqualTo("pending_review");
                });
    }

    @Test
    @DisplayName("学期域隔离：order_form 只归属自己的学期")
    void semesterDomain_formsBelongToOwnSemester() {
        seedScenario("OP");
        asTeacher();
        teacherOrderService.submit(validItems());

        List<OrderForm> forms = orderFormMapper.selectList(Wrappers.<OrderForm>lambdaQuery()
                .eq(OrderForm::getSemesterId, scenario.semesterId()));
        assertThat(forms).hasSize(1);
        assertThat(semesterMapper.selectByIdSoft(scenario.semesterId()).getActiveStatus())
                .isEqualTo("active");
        assertThat(scenario.windowEnd()).isAfter(LocalDateTime.now());
    }

    // ============ 学生历史（GET /api/student/orders） ============

    @Test
    @DisplayName("学生历史：itemCount/totalQuantity 与提交时归属快照齐备（跨学期摘要）")
    void studentHistory_carriesItemCountAndSubmitSnapshot() {
        seedScenario("OS");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));
        asStudent();
        studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 2))));

        List<com.tian.textbook.order.dto.StudentOrderListItem> history = studentOrderService.myHistory();

        assertThat(history).singleElement()
                .satisfies(item -> {
                    assertThat(item.getItemCount()).isEqualTo(1);
                    assertThat(item.getTotalQuantity()).isEqualTo(2);
                    assertThat(item.getSemesterName()).isEqualTo("2026-2027-OS");
                    // 归属取提交时快照（异动不影响历史归属，W15）
                    assertThat(item.getSubmitSnapshot()).isNotNull();
                    assertThat(item.getCollegeId()).isEqualTo(scenario.collegeId());
                    assertThat(item.getClassName()).isEqualTo("软工2401OS");
                });
    }

    @Test
    @DisplayName("超管全院选购分页：itemCount/totalQuantity 由 SQL 聚合带出")
    void adminStudentOrdersPage_aggregatesItemCountAndQuantity() {
        seedScenario("OT");
        asTeacher();
        var submitted = teacherOrderService.submit(validItems());
        asAdmin();
        teacherOrderService.review(submitted.getId(), new OrderFormReviewRequest("pass", null));
        asStudent();
        studentOrderService.submit(new StudentOrderSubmitRequest(
                List.of(new StudentOrderSubmitItem(scenario.textbookId(), 3))));

        asAdmin();
        var page = studentOrderService.allPage(scenario.semesterId(), null, null, null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getItemCount()).isEqualTo(1);
                    assertThat(item.getTotalQuantity()).isEqualTo(3);
                    assertThat(item.getStudentNo()).isEqualTo("SOT");
                });
    }

    // ============ 填报选书器（GET /api/teacher/textbook） ============

    @Test
    @DisplayName("选书器：只返回在库教材（status=1），关键词命中书名/ISBN/作者/出版社")
    void teacherTextbookOptions_onlyActiveTextbooksAndKeywordMatch() {
        seedScenario("OQ");
        asTeacher();
        var stopped = seeder.textbook("978-7-111-40702-7", "已停用教材", 0);

        // 无关键词：在库教材全量，停用教材不出现（与字段审查 BOOK_ACTIVE 同口径）
        assertThat(teacherOrderService.searchTextbooks(null))
                .extracting(TeacherTextbookOptionVO::getTextbookId)
                .contains(scenario.textbookId())
                .doesNotContain(stopped.getId());
        assertThat(teacherOrderService.searchTextbooks("已停用教材")).isEmpty();

        // 关键词：书名 / ISBN / 作者 / 出版社 四路模糊匹配
        assertThat(teacherOrderService.searchTextbooks("高等数学OQ"))
                .extracting(TeacherTextbookOptionVO::getTextbookId)
                .containsExactly(scenario.textbookId());
        assertThat(teacherOrderService.searchTextbooks("978-0-306-40615-7"))
                .extracting(TeacherTextbookOptionVO::getTextbookId)
                .containsExactly(scenario.textbookId());
        assertThat(teacherOrderService.searchTextbooks("测试作者"))
                .extracting(TeacherTextbookOptionVO::getTextbookId)
                .contains(scenario.textbookId());
        assertThat(teacherOrderService.searchTextbooks("测试出版社"))
                .extracting(TeacherTextbookOptionVO::getTextbookId)
                .contains(scenario.textbookId());

        // 字段白名单：选书器所需字段齐备（不含审计列）
        var option = teacherOrderService.searchTextbooks("高等数学OQ").get(0);
        assertThat(option.getTextbookId()).isEqualTo(scenario.textbookId());
        assertThat(option.getIsbn()).isEqualTo("978-0-306-40615-7");
        assertThat(option.getTitle()).isEqualTo("高等数学OQ");
        assertThat(option.getEdition()).isEqualTo("第1版");
        assertThat(option.getAuthor()).isEqualTo("测试作者");
        assertThat(option.getPress()).isEqualTo("测试出版社");
        assertThat(option.getPrice()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("选书器：单次封顶 50 条（选择器场景不分页）")
    void teacherTextbookOptions_cappedAtFifty() {
        seedScenario("OR");
        asTeacher();
        for (int i = 0; i < 55; i++) {
            seeder.textbook("978-7-0000-" + String.format("%04d", i) + "-0", "批量教材" + i, 1);
        }

        assertThat(teacherOrderService.searchTextbooks("批量教材")).hasSize(50);
    }
}
