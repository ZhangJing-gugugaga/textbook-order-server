package com.tian.textbook.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.window.WindowGuard;
import com.tian.textbook.order.dto.CourseOptionVO;
import com.tian.textbook.order.dto.OrderFormDetailVO;
import com.tian.textbook.order.dto.OrderFormItemVO;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.dto.OrderFormReviewRequest;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.order.dto.OrderFormSubmitRequest;
import com.tian.textbook.order.dto.TeacherCourseGroupVO;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.entity.OrderFormItem;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.CourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教师征订服务（PRD 模块 5 / SPEC §8 / §11.4）。
 *
 * <p>审查链：系统字段审查（{@link FieldCheckService}，自动白名单）→ 超管内容审核（{@link #review}）。
 * 关键不变量：</p>
 * <ul>
 *   <li>一人一学期一单（UNIQUE(semester_id, teacher_id, deleted)，W9）；重提 = 整单覆盖
 *       （先逻辑删旧明细再插新，SPEC §12）；</li>
 *   <li>字段审查失败 → rejected_auto + field_check_result 落库后抛 400（noRollbackFor 保证落库不被回滚）；</li>
 *   <li>补正重提豁免窗口校验（W4）：仅本人该表单、状态 ∈ {rejected, rejected_auto} 且未过 correct_deadline。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherOrderService {

    private final OrderFormMapper orderFormMapper;
    private final OrderFormItemMapper orderFormItemMapper;
    private final FieldCheckService fieldCheckService;
    private final TeacherCourseMapper teacherCourseMapper;
    private final CourseMapper courseMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final CollegeMapper collegeMapper;
    private final TextbookMapper textbookMapper;
    private final SemesterMapper semesterMapper;
    private final UserSemesterProfileMapper userSemesterProfileMapper;
    private final ConfigService configService;
    private final AuditService auditService;
    private final WindowGuard windowGuard;

    // ============ 1. GET /api/teacher/my-courses ============

    /**
     * active 学期当前教师的任课关系（按班级分组，W17：征订范围 = 已导入的 teacher_course）。
     *
     * @return [{classId, className, courses:[{courseId, courseName}]}]，按班级/课程 id 排序
     */
    @Transactional(readOnly = true)
    public List<TeacherCourseGroupVO> myCourses() {
        Long semesterId = requireSemester();
        Long teacherId = requireCurrentUserId();
        List<TeacherCourse> relations = teacherCourseMapper.selectByTeacher(semesterId, teacherId);
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<Long> classIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        for (TeacherCourse relation : relations) {
            if (relation.getClassId() != null) {
                classIds.add(relation.getClassId());
            }
            if (relation.getCourseId() != null) {
                courseIds.add(relation.getCourseId());
            }
        }
        Map<Long, SchoolClass> classes = schoolClassMapper.selectList(Wrappers.<SchoolClass>lambdaQuery()
                        .in(SchoolClass::getId, classIds).eq(SchoolClass::getDeleted, 0))
                .stream().collect(Collectors.toMap(SchoolClass::getId, c -> c, (a, b) -> a));
        Map<Long, Course> courses = courseMapper.selectList(Wrappers.<Course>lambdaQuery()
                        .in(Course::getId, courseIds).eq(Course::getDeleted, 0))
                .stream().collect(Collectors.toMap(Course::getId, c -> c, (a, b) -> a));

        // 班级 → 课程（去重、排序）
        Map<Long, Set<Long>> classCourses = new LinkedHashMap<>();
        for (TeacherCourse relation : relations) {
            if (relation.getClassId() == null || relation.getCourseId() == null) {
                continue;
            }
            classCourses.computeIfAbsent(relation.getClassId(), k -> new HashSet<>()).add(relation.getCourseId());
        }
        List<Long> sortedClassIds = new ArrayList<>(classCourses.keySet());
        Collections.sort(sortedClassIds);
        List<TeacherCourseGroupVO> result = new ArrayList<>();
        for (Long classId : sortedClassIds) {
            TeacherCourseGroupVO group = new TeacherCourseGroupVO();
            group.setClassId(classId);
            SchoolClass clazz = classes.get(classId);
            group.setClassName(clazz == null ? null : clazz.getName());
            List<Long> sortedCourseIds = new ArrayList<>(classCourses.get(classId));
            Collections.sort(sortedCourseIds);
            List<CourseOptionVO> courseOptions = new ArrayList<>();
            for (Long courseId : sortedCourseIds) {
                Course course = courses.get(courseId);
                courseOptions.add(new CourseOptionVO(courseId, course == null ? null : course.getName()));
            }
            group.setCourses(courseOptions);
            result.add(group);
        }
        return result;
    }

    // ============ 2. GET /api/teacher/order-form ============

    /** 当前学期征订单 + 明细（无单返回 null） */
    @Transactional(readOnly = true)
    public OrderFormDetailVO getMyForm() {
        Long semesterId = requireSemester();
        Long teacherId = requireCurrentUserId();
        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(semesterId, teacherId);
        return form == null ? null : buildDetailVO(form);
    }

    // ============ 3. POST /api/teacher/order-form/submit ============

    /**
     * 提交/补正（覆盖语义）。窗口校验：@WithinWindow(CORRECTION) 注解 + WindowGuard 双保险
     * （补正豁免：本人该表单、状态 ∈ {rejected, rejected_auto} 且未过 correct_deadline，W4）。
     *
     * <p>字段审查任一不过 → rejected_auto + field_check_result 落库后抛
     * {@link BizException#fieldCheckFailed}（400 + data=逐项错误）；全过 → pending_review + 明细覆盖。</p>
     *
     * <p>noRollbackFor：失败路径的 rejected_auto 状态必须落库（否则回滚会丢失审查结果），
     * 故对 BizException 不做回滚；成功路径无异常，正常提交。</p>
     */
    @Transactional(noRollbackFor = BizException.class)
    public OrderFormDetailVO submit(OrderFormSubmitRequest request) {
        Long semesterId = requireSemester();
        Long teacherId = requireCurrentUserId();
        windowGuard.assertWithinWindow(WithinWindow.Exemption.CORRECTION);

        List<FieldCheckIssue> issues =
                fieldCheckService.checkOrderItems(semesterId, teacherId, request.items());
        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(semesterId, teacherId);
        LocalDateTime now = LocalDateTime.now();
        if (form == null) {
            form = new OrderForm();
            form.setSemesterId(semesterId);
            form.setTeacherId(teacherId);
            form.setDeleted(0L);
            form.setCreatedBy(teacherId);
        }

        if (!issues.isEmpty()) {
            // 字段审查失败：rejected_auto + 逐字段错误落库（明细不覆盖，保留上次有效提交）
            form.setStatus("rejected_auto");
            form.setFieldCheckResult(issues);
            form.setUpdatedBy(teacherId);
            upsertForm(form);
            log.info("教师征订字段审查未通过: teacherId={}, semesterId={}, issues={}", teacherId, semesterId, issues.size());
            throw BizException.fieldCheckFailed(issues.size(), issues);
        }

        // 字段审查全过：pending_review + submitted_at + 清空审查/审核历史 + 明细整单覆盖
        form.setStatus("pending_review");
        form.setSubmittedAt(now);
        form.setFieldCheckResult(null);
        form.setReviewBy(null);
        form.setReviewAt(null);
        form.setReviewNote(null);
        form.setCorrectDeadline(null);
        form.setUpdatedBy(teacherId);
        boolean isNewForm = form.getId() == null;
        upsertForm(form);
        if (!isNewForm) {
            // updateById 跳过 null 字段，需显式清理旧审查/审核历史（JSON 置 null 不涉及 typeHandler）
            clearFormReviewState(form.getId());
        }
        overwriteItems(form.getId(), request.items(), teacherId);
        log.info("教师征订提交成功: formId={}, teacherId={}, items={}", form.getId(), teacherId, request.items().size());
        return buildDetailVO(form);
    }

    // ============ 4. GET /api/teacher/order-forms ============

    /** 本人历史提交记录（跨学期，带学期名；数据隔离由 @CollegeScope teacher_id 保证） */
    @Transactional(readOnly = true)
    public List<OrderFormListItem> myHistory() {
        CurrentUser user = SecurityUtils.requireCurrentUser();
        List<OrderForm> forms = orderFormMapper.selectTeacherForms(user.userId());
        if (forms.isEmpty()) {
            return List.of();
        }
        // 学期名（跨学期历史）
        Set<Long> semesterIds = forms.stream().map(OrderForm::getSemesterId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> semesterNames = semesterMapper.selectList(Wrappers.<Semester>lambdaQuery()
                        .in(Semester::getId, semesterIds).eq(Semester::getDeleted, 0))
                .stream().collect(Collectors.toMap(Semester::getId, Semester::getName, (a, b) -> a));
        // 明细统计（行数 / 数量合计）
        List<Long> formIds = forms.stream().map(OrderForm::getId).toList();
        Map<Long, long[]> stat = new HashMap<>();
        for (OrderFormItem item : orderFormItemMapper.selectByFormIds(formIds)) {
            long[] acc = stat.computeIfAbsent(item.getFormId(), k -> new long[2]);
            acc[0] += 1;
            acc[1] += item.getQuantity() == null ? 0 : item.getQuantity();
        }
        // 各学期学院归属（profile 真源，W6）
        Map<Long, UserSemesterProfile> profiles = userSemesterProfileMapper.selectList(
                        Wrappers.<UserSemesterProfile>lambdaQuery()
                                .eq(UserSemesterProfile::getUserId, user.userId())
                                .in(UserSemesterProfile::getSemesterId, semesterIds)
                                .eq(UserSemesterProfile::getDeleted, 0))
                .stream().collect(Collectors.toMap(UserSemesterProfile::getSemesterId, p -> p, (a, b) -> a));
        Map<Long, String> collegeNames = loadCollegeNames(profiles.values().stream()
                .map(UserSemesterProfile::getCollegeId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<OrderFormListItem> result = new ArrayList<>();
        for (OrderForm form : forms) {
            OrderFormListItem item = new OrderFormListItem();
            item.setId(form.getId());
            item.setSemesterId(form.getSemesterId());
            item.setSemesterName(semesterNames.get(form.getSemesterId()));
            item.setTeacherId(form.getTeacherId());
            item.setTeacherName(user.name());
            item.setTeacherNo(user.userNo());
            UserSemesterProfile profile = profiles.get(form.getSemesterId());
            if (profile != null && profile.getCollegeId() != null) {
                item.setCollegeId(profile.getCollegeId());
                item.setCollegeName(collegeNames.get(profile.getCollegeId()));
            }
            item.setStatus(form.getStatus());
            item.setSubmittedAt(form.getSubmittedAt());
            item.setReviewAt(form.getReviewAt());
            item.setReviewNote(form.getReviewNote());
            item.setCorrectDeadline(form.getCorrectDeadline());
            long[] acc = stat.get(form.getId());
            item.setItemCount(acc == null ? 0 : (int) acc[0]);
            item.setTotalQuantity(acc == null ? 0 : (int) acc[1]);
            result.add(item);
        }
        return result;
    }

    // ============ 5. GET /api/secretary/order-forms ============

    /**
     * 秘书本院表单分页（collegeId 从当前用户在 active 学期的归属取，W6；
     * 无归属 → 空页，绝不越院）。
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderFormListItem> collegeFormsPage(String status, String teacherName,
                                                            long page, long size) {
        Long semesterId = SemesterContextHolder.get();
        Long collegeId = semesterId == null ? null
                : userSemesterProfileMapper.selectCollegeId(requireCurrentUserId(), semesterId);
        long normalizedPage = Math.max(page, 1);
        long normalizedSize = normalizeSize(size);
        if (semesterId == null || collegeId == null) {
            return PageResponse.of(List.of(), normalizedPage, normalizedSize, 0);
        }
        long offset = (normalizedPage - 1) * normalizedSize;
        List<OrderFormListItem> list = orderFormMapper.selectCollegeFormsPage(
                collegeId, semesterId, status, teacherName, offset, normalizedSize);
        long total = orderFormMapper.countCollegeForms(collegeId, semesterId, status, teacherName);
        return PageResponse.of(list, normalizedPage, normalizedSize, total);
    }

    // ============ 6. GET /api/admin/order-forms ============

    /** 超管全院表单分页（复核工作台；semesterId 可空 = 全学期） */
    @Transactional(readOnly = true)
    public PageResponse<OrderFormListItem> allFormsPage(Long semesterId, Long collegeId, String status,
                                                        String teacherName, long page, long size) {
        long normalizedPage = Math.max(page, 1);
        long normalizedSize = normalizeSize(size);
        long offset = (normalizedPage - 1) * normalizedSize;
        List<OrderFormListItem> list = orderFormMapper.selectAllFormsPage(
                semesterId, collegeId, status, teacherName, offset, normalizedSize);
        long total = orderFormMapper.countAllForms(semesterId, collegeId, status, teacherName);
        return PageResponse.of(list, normalizedPage, normalizedSize, total);
    }

    // ============ 7. GET /api/admin/order-forms/{id} ============

    /**
     * 征订单详情（表单 + field_check_result + 明细）。
     *
     * <p>资源归属二次校验（SPEC §5，防 IDOR）：教师仅本人；秘书限本院（按表单所属学期 profile 比对）；
     * 超管放行。越权 → 403 + 审计。</p>
     */
    public OrderFormDetailVO getFormDetail(Long id) {
        OrderForm form = orderFormMapper.selectByIdSoft(id);
        if (form == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "征订单不存在");
        }
        if (!canAccessForm(SecurityUtils.requireCurrentUser(), form)) {
            auditService.record(AuditService.REVIEW, "order-form", String.valueOf(id),
                    Map.of("reason", "越权访问"));
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN);
        }
        return buildDetailVO(form);
    }

    // ============ 8. POST /api/admin/order-forms/{id}/review ============

    /**
     * 内容审核（两级审查第二级）：pass → reviewed；reject → rejected + 理由 1-200 字必填 +
     * correct_deadline = 窗口截止(window_end，null 则 now) + order.correct_window_days 天（W4）。
     *
     * <p>状态机保护：仅 pending_review 可审（并发以乐观 UPDATE 兜底 → 409）；
     * 更新 order_form 与写 audit_log 同事务（SPEC §12）。</p>
     */
    @Transactional
    public OrderFormDetailVO review(Long id, OrderFormReviewRequest request) {
        OrderForm form = orderFormMapper.selectByIdSoft(id);
        if (form == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "征订单不存在");
        }
        if (!"pending_review".equals(form.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的表单状态，请刷新后重试");
        }
        String action = request.action() == null ? "" : request.action().trim();
        Long reviewerId = requireCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        String nextStatus;
        String reason = request.reason() == null ? "" : request.reason().trim();

        if ("reject".equals(action)) {
            if (reason.isEmpty() || reason.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请填写驳回理由");
            }
            nextStatus = "rejected";
            // 补正截止：关窗时间 + order.correct_window_days（window_end 缺失则以当前时间为基准）
            Semester semester = semesterMapper.selectByIdSoft(form.getSemesterId());
            LocalDateTime base = semester != null && semester.getWindowEnd() != null
                    ? semester.getWindowEnd() : now;
            int days = configService.getInt(ConfigService.ORDER_CORRECT_WINDOW_DAYS, 7);
            form.setCorrectDeadline(base.plusDays(days));
            form.setReviewNote(reason);
        } else if ("pass".equals(action)) {
            if (reason.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "审核备注不能超过 200 字");
            }
            nextStatus = "reviewed";
            form.setReviewNote(reason.isEmpty() ? null : reason);
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核结论仅支持 pass / reject");
        }

        form.setStatus(nextStatus);
        form.setReviewBy(reviewerId);
        form.setReviewAt(now);
        form.setUpdatedBy(reviewerId);

        // 乐观并发保护：仅 pending_review 可被推进（并发复核以后到者 409 裁决）
        int rows = orderFormMapper.update(null, Wrappers.<OrderForm>lambdaUpdate()
                .eq(OrderForm::getId, id)
                .eq(OrderForm::getStatus, "pending_review")
                .set(OrderForm::getStatus, nextStatus)
                .set(OrderForm::getReviewBy, reviewerId)
                .set(OrderForm::getReviewAt, now)
                .set(OrderForm::getReviewNote, form.getReviewNote())
                .set(OrderForm::getCorrectDeadline, form.getCorrectDeadline())
                .set(OrderForm::getUpdatedBy, reviewerId));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的表单状态，请刷新后重试");
        }
        auditService.record(AuditService.REVIEW, "order-form", String.valueOf(id),
                Map.of("action", action, "reason", reason));
        log.info("征订单审核完成: id={}, action={}, reviewer={}", id, action, reviewerId);
        return buildDetailVO(orderFormMapper.selectByIdSoft(id));
    }

    // ============ 私有实现 ============

    /** upsert：无 id 插入，有 id 更新（updateById 对 JSON 字段应用 typeHandler，null 字段跳过由显式清理补齐） */
    private void upsertForm(OrderForm form) {
        if (form.getId() == null) {
            orderFormMapper.insert(form);
        } else {
            orderFormMapper.updateById(form);
        }
    }

    /**
     * 显式清空审查/审核历史（updateById 跳过 null 字段）。
     * field_check_result 置 null 走原生列（不涉及 typeHandler）；SET 子句中 {@code col = ?} 传 null 即置 NULL。
     */
    private void clearFormReviewState(Long formId) {
        orderFormMapper.update(null, Wrappers.<OrderForm>lambdaUpdate()
                .eq(OrderForm::getId, formId)
                .set(OrderForm::getFieldCheckResult, null)
                .set(OrderForm::getReviewBy, null)
                .set(OrderForm::getReviewAt, null)
                .set(OrderForm::getReviewNote, null)
                .set(OrderForm::getCorrectDeadline, null));
    }

    /** 明细整单覆盖：先逻辑删旧（deleted=当前毫秒）再插新（SPEC §12） */
    private void overwriteItems(Long formId, List<OrderFormSubmitItem> items, Long operatorId) {
        orderFormItemMapper.softDeleteByForm(formId, System.currentTimeMillis());
        for (OrderFormSubmitItem item : items) {
            OrderFormItem entity = new OrderFormItem();
            entity.setFormId(formId);
            entity.setCourseId(item.courseId());
            entity.setClassId(item.classId());
            entity.setTextbookId(item.textbookId());
            entity.setQuantity(item.quantity());
            entity.setDeleted(0L);
            entity.setCreatedBy(operatorId);
            entity.setUpdatedBy(operatorId);
            orderFormItemMapper.insert(entity);
        }
    }

    /** 详情 VO：表单 + field_check_result + 明细（课程名/班级名/教材名/ISBN 批量带出） */
    private OrderFormDetailVO buildDetailVO(OrderForm form) {
        OrderFormDetailVO vo = new OrderFormDetailVO();
        vo.setId(form.getId());
        vo.setSemesterId(form.getSemesterId());
        Semester semester = semesterMapper.selectByIdSoft(form.getSemesterId());
        vo.setSemesterName(semester == null ? null : semester.getName());
        vo.setTeacherId(form.getTeacherId());
        vo.setStatus(form.getStatus());
        vo.setFieldCheckResult(form.getFieldCheckResult());
        vo.setSubmittedAt(form.getSubmittedAt());
        vo.setReviewAt(form.getReviewAt());
        vo.setReviewBy(form.getReviewBy());
        vo.setReviewNote(form.getReviewNote());
        vo.setCorrectDeadline(form.getCorrectDeadline());

        List<OrderFormItem> items = orderFormItemMapper.selectByFormId(form.getId());
        Map<Long, String> courseNames = loadCourseNames(items.stream()
                .map(OrderFormItem::getCourseId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> classNames = loadClassNames(items.stream()
                .map(OrderFormItem::getClassId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Textbook> textbooks = loadTextbooks(items.stream()
                .map(OrderFormItem::getTextbookId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<OrderFormItemVO> itemVOs = new ArrayList<>();
        int totalQuantity = 0;
        for (OrderFormItem item : items) {
            OrderFormItemVO itemVO = new OrderFormItemVO();
            itemVO.setId(item.getId());
            itemVO.setCourseId(item.getCourseId());
            itemVO.setCourseName(courseNames.get(item.getCourseId()));
            itemVO.setClassId(item.getClassId());
            itemVO.setClassName(classNames.get(item.getClassId()));
            itemVO.setTextbookId(item.getTextbookId());
            Textbook textbook = textbooks.get(item.getTextbookId());
            itemVO.setTextbookTitle(textbook == null ? null : textbook.getTitle());
            itemVO.setIsbn(textbook == null ? null : textbook.getIsbn());
            itemVO.setQuantity(item.getQuantity());
            itemVOs.add(itemVO);
            totalQuantity += item.getQuantity() == null ? 0 : item.getQuantity();
        }
        vo.setItems(itemVOs);
        vo.setItemCount(itemVOs.size());
        vo.setTotalQuantity(totalQuantity);
        return vo;
    }

    private Map<Long, String> loadCourseNames(Set<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        return courseMapper.selectList(Wrappers.<Course>lambdaQuery()
                        .in(Course::getId, courseIds).eq(Course::getDeleted, 0))
                .stream().collect(Collectors.toMap(Course::getId, Course::getName, (a, b) -> a));
    }

    private Map<Long, String> loadClassNames(Set<Long> classIds) {
        if (classIds.isEmpty()) {
            return Map.of();
        }
        return schoolClassMapper.selectList(Wrappers.<SchoolClass>lambdaQuery()
                        .in(SchoolClass::getId, classIds).eq(SchoolClass::getDeleted, 0))
                .stream().collect(Collectors.toMap(SchoolClass::getId, SchoolClass::getName, (a, b) -> a));
    }

    private Map<Long, Textbook> loadTextbooks(Set<Long> textbookIds) {
        if (textbookIds.isEmpty()) {
            return Map.of();
        }
        return textbookMapper.selectList(Wrappers.<Textbook>lambdaQuery()
                        .in(Textbook::getId, textbookIds).eq(Textbook::getDeleted, 0))
                .stream().collect(Collectors.toMap(Textbook::getId, t -> t, (a, b) -> a));
    }

    private Map<Long, String> loadCollegeNames(Set<Long> collegeIds) {
        if (collegeIds.isEmpty()) {
            return Map.of();
        }
        return collegeMapper.selectList(Wrappers.<College>lambdaQuery()
                        .in(College::getId, collegeIds).eq(College::getDeleted, 0))
                .stream().collect(Collectors.toMap(College::getId, College::getName, (a, b) -> a));
    }

    /**
     * 资源归属校验（SPEC §5：按 id 取单条资源时校验归属，防 IDOR）：
     * 超管放行；教师限本人；秘书限本院（按表单所属学期的 profile 学院比对）。
     */
    private boolean canAccessForm(CurrentUser user, OrderForm form) {
        if (user.isAdmin()) {
            return true;
        }
        if (user.hasRole("TEACHER") && user.userId().equals(form.getTeacherId())) {
            return true;
        }
        if (user.hasRole("SECRETARY")) {
            Long myCollege = userSemesterProfileMapper.selectCollegeId(user.userId(), form.getSemesterId());
            Long formCollege = userSemesterProfileMapper.selectCollegeId(form.getTeacherId(), form.getSemesterId());
            return myCollege != null && myCollege.equals(formCollege);
        }
        return false;
    }

    private Long requireSemester() {
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "尚未激活任何学期，请联系教材室");
        }
        return semesterId;
    }

    private Long requireCurrentUserId() {
        return SecurityUtils.requireCurrentUser().userId();
    }

    /** size 上限 200（SPEC §11） */
    private long normalizeSize(long size) {
        return Math.min(Math.max(size, 1), 200);
    }
}
