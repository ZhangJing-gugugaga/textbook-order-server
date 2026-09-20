package com.tian.textbook.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.window.WindowGuard;
import com.tian.textbook.order.dto.StudentBookVO;
import com.tian.textbook.order.dto.StudentOrderDetailVO;
import com.tian.textbook.order.dto.StudentOrderItemVO;
import com.tian.textbook.order.dto.StudentOrderListItem;
import com.tian.textbook.order.dto.StudentOrderSubmitItem;
import com.tian.textbook.order.dto.StudentOrderSubmitRequest;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.entity.OrderFormItem;
import com.tian.textbook.order.entity.StudentOrder;
import com.tian.textbook.order.entity.StudentOrderItem;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.order.mapper.StudentOrderItemMapper;
import com.tian.textbook.order.mapper.StudentOrderMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学生选购服务（PRD 模块 5 / 03 §6.2 W3/W18 / SPEC §11.4）。
 *
 * <p>清单生成规则（W3，服务端唯一实现，前端只渲染）：取当前学生在 active 学期的班级 →
 * 该班全部 teacher_course 关系对应教师的表单 → status='reviewed' 的明细教材并集（可选）；
 * required = 出现在任一 submitted_at 非空的教师表单中（有教师提交即标必修）；
 * delisted = 出现在教师表单中但无任何 reviewed 来源（表单后被驳回/仍在审）→ 不可选但保留展示。</p>
 *
 * <p>提交：窗口内（免审，B3）；数量 1-9 且 ≤ 班级人数（0/null 回退 order.quantity.max_default）；
 * 一人一学期一单，重提 = 整单覆盖；记录 submit_snapshot（提交时 college/class 归属，W15）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentOrderService {

    /** 学生选购数量硬上限（PRD 模块 5：1 ≤ qty ≤ 9） */
    private static final int STUDENT_QTY_MAX = 9;

    private final StudentOrderMapper studentOrderMapper;
    private final StudentOrderItemMapper studentOrderItemMapper;
    private final OrderFormMapper orderFormMapper;
    private final OrderFormItemMapper orderFormItemMapper;
    private final TeacherCourseMapper teacherCourseMapper;
    private final UserSemesterProfileMapper userSemesterProfileMapper;
    private final SemesterMapper semesterMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final CollegeMapper collegeMapper;
    private final TextbookMapper textbookMapper;
    private final ConfigService configService;
    private final WindowGuard windowGuard;

    // ============ 9. GET /api/student/book-list ============

    /**
     * 本班可选教材清单（W3）。
     *
     * @return [{textbookId,isbn,title,edition,author,press,price,required,delisted}]，按教材 id 排序；
     * 无班级归属或无关课程返回空列表
     */
    @Transactional(readOnly = true)
    public List<StudentBookVO> bookList() {
        Long semesterId = requireSemester();
        Long studentId = requireCurrentUserId();
        Long classId = classOf(studentId, semesterId);
        if (classId == null) {
            return List.of();
        }
        ClassBookContext context = classBookContext(semesterId, classId);
        List<Long> textbookIds = new ArrayList<>(context.submittedTextbookIds());
        Collections.sort(textbookIds);
        Map<Long, Textbook> textbooks = loadTextbooks(new LinkedHashSet<>(textbookIds));
        List<StudentBookVO> result = new ArrayList<>();
        for (Long textbookId : textbookIds) {
            Textbook textbook = textbooks.get(textbookId);
            if (textbook == null) {
                continue; // 教材已物理不可见（逻辑删除）→ 不出现在清单
            }
            StudentBookVO vo = new StudentBookVO();
            vo.setTextbookId(textbook.getId());
            vo.setIsbn(textbook.getIsbn());
            vo.setTitle(textbook.getTitle());
            vo.setEdition(textbook.getEdition());
            vo.setAuthor(textbook.getAuthor());
            vo.setPress(textbook.getPress());
            vo.setPrice(textbook.getPrice());
            vo.setRequired(context.submittedTextbookIds().contains(textbookId));
            vo.setDelisted(!context.reviewedTextbookIds().contains(textbookId));
            result.add(vo);
        }
        return result;
    }

    // ============ 10. GET /api/student/order ============

    /** 本人选购单 + 明细（含教材信息 + delisted 状态）；无单返回 null */
    @Transactional(readOnly = true)
    public StudentOrderDetailVO getMyOrder() {
        Long semesterId = requireSemester();
        Long studentId = requireCurrentUserId();
        StudentOrder order = studentOrderMapper.selectBySemesterAndStudent(semesterId, studentId);
        if (order == null) {
            return null;
        }
        Long classId = classOf(studentId, semesterId);
        ClassBookContext context = classId == null ? ClassBookContext.EMPTY
                : classBookContext(semesterId, classId);
        List<StudentOrderItem> items = studentOrderItemMapper.selectByOrderId(order.getId());
        return buildOrderVO(order, items, context);
    }

    // ============ 11. POST /api/student/order/submit ============

    /**
     * 提交选购（覆盖语义）。窗口校验：@WithinWindow 注解 + WindowGuard 双保险（无豁免，W3）。
     *
     * <p>校验：每个 textbookId 必须在可选清单内（不在清单或 delisted → 400 BOOK_DELISTED）；
     * 数量 1-9 且 ≤ 班级人数（0/null 回退 order.quantity.max_default）；教材行去重
     * （UNIQUE(order_id, textbook_id, deleted) 兜底）。</p>
     */
    @Transactional
    public StudentOrderDetailVO submit(StudentOrderSubmitRequest request) {
        Long semesterId = requireSemester();
        Long studentId = requireCurrentUserId();
        windowGuard.assertWithinWindow(WithinWindow.Exemption.NONE);

        UserSemesterProfile profile =
                userSemesterProfileMapper.selectByUserAndSemester(studentId, semesterId);
        if (profile == null || profile.getClassId() == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "尚未找到您的学期归属，请联系教材室");
        }
        Long classId = profile.getClassId();
        ClassBookContext context = classBookContext(semesterId, classId);
        List<StudentOrderSubmitItem> items =
                request.items() == null ? List.of() : request.items();

        int upper = Math.min(STUDENT_QTY_MAX, classQuantityLimit(classId));
        Set<Long> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            StudentOrderSubmitItem item = items.get(i);
            String prefix = "第 " + (i + 1) + " 行：";
            if (item == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, prefix + "请填写完整的选购信息");
            }
            if (item.textbookId() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, prefix + "请选择教材");
            }
            if (item.quantity() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, prefix + "请填写数量");
            }
            if (!context.reviewedTextbookIds().contains(item.textbookId())) {
                // 不在清单或已下架（delisted）→ 统一提示（PRD 模块 5 错误文案）
                throw new BizException(ErrorCode.BOOK_DELISTED);
            }
            if (item.quantity() < 1 || item.quantity() > upper) {
                throw new BizException(ErrorCode.PARAM_INVALID, prefix + "数量需在 1-" + upper + " 之间");
            }
            if (!seen.add(item.textbookId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, prefix + "教材重复，请合并数量");
            }
        }

        // upsert（一人一学期一单，W9 唯一约束兜底）
        StudentOrder order = studentOrderMapper.selectBySemesterAndStudent(semesterId, studentId);
        LocalDateTime now = LocalDateTime.now();
        if (order == null) {
            order = new StudentOrder();
            order.setSemesterId(semesterId);
            order.setStudentId(studentId);
            order.setDeleted(0L);
            order.setCreatedBy(studentId);
        }
        order.setStatus("submitted");
        order.setSubmitSnapshot(buildSnapshot(profile));
        order.setSubmittedAt(now);
        order.setUpdatedBy(studentId);
        if (order.getId() == null) {
            studentOrderMapper.insert(order);
        } else {
            studentOrderMapper.updateById(order);
        }

        // 明细整单覆盖：先逻辑删旧再插新（SPEC §12）
        studentOrderItemMapper.softDeleteByOrder(order.getId(), System.currentTimeMillis());
        for (StudentOrderSubmitItem item : items) {
            StudentOrderItem entity = new StudentOrderItem();
            entity.setOrderId(order.getId());
            entity.setTextbookId(item.textbookId());
            entity.setQuantity(item.quantity());
            entity.setCourseId(context.textbookCourseIds().get(item.textbookId()));
            entity.setDeleted(0L);
            entity.setCreatedBy(studentId);
            entity.setUpdatedBy(studentId);
            studentOrderItemMapper.insert(entity);
        }
        log.info("学生选购提交成功: orderId={}, studentId={}, items={}", order.getId(), studentId, items.size());
        List<StudentOrderItem> savedItems = studentOrderItemMapper.selectByOrderId(order.getId());
        return buildOrderVO(order, savedItems, context);
    }

    // ============ 12. GET /api/student/orders ============

    /** 本人历史选购记录（数据隔离由 @CollegeScope student_id 保证；带学期名） */
    @Transactional(readOnly = true)
    public List<StudentOrderListItem> myHistory() {
        CurrentUser user = SecurityUtils.requireCurrentUser();
        List<StudentOrder> orders = studentOrderMapper.selectStudentOrders(user.userId());
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> semesterIds = orders.stream().map(StudentOrder::getSemesterId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> semesterNames = semesterMapper.selectList(Wrappers.<Semester>lambdaQuery()
                        .in(Semester::getId, semesterIds).eq(Semester::getDeleted, 0))
                .stream().collect(Collectors.toMap(Semester::getId, Semester::getName, (a, b) -> a));
        List<Long> orderIds = orders.stream().map(StudentOrder::getId).toList();
        Map<Long, Integer> totals = new java.util.HashMap<>();
        for (StudentOrderItem item : studentOrderItemMapper.selectByOrderIds(orderIds)) {
            totals.merge(item.getOrderId(), item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
        }
        return orders.stream().map(order -> {
            StudentOrderListItem item = new StudentOrderListItem();
            item.setId(order.getId());
            item.setSemesterId(order.getSemesterId());
            item.setSemesterName(semesterNames.get(order.getSemesterId()));
            item.setStudentId(order.getStudentId());
            item.setStudentName(user.name());
            item.setStudentNo(user.userNo());
            item.setStatus(order.getStatus());
            item.setSubmittedAt(order.getSubmittedAt());
            item.setSubmitSnapshot(order.getSubmitSnapshot());
            item.setTotalQuantity(totals.getOrDefault(order.getId(), 0));
            // 归属取提交时快照（异动不影响历史归属，W15）
            Map<String, Object> snapshot = order.getSubmitSnapshot();
            if (snapshot != null) {
                item.setCollegeId(toLong(snapshot.get("collegeId")));
                item.setCollegeName(toStr(snapshot.get("collegeName")));
                item.setClassId(toLong(snapshot.get("classId")));
                item.setClassName(toStr(snapshot.get("className")));
            }
            return item;
        }).toList();
    }

    // ============ 13. GET /api/admin/student-orders ============

    /** 超管全院选购分页（semesterId/collegeId/classId/studentName 过滤） */
    @Transactional(readOnly = true)
    public PageResponse<StudentOrderListItem> allPage(Long semesterId, Long collegeId, Long classId,
                                                      String studentName, long page, long size) {
        long normalizedPage = Math.max(page, 1);
        long normalizedSize = Math.min(Math.max(size, 1), 200);
        long offset = (normalizedPage - 1) * normalizedSize;
        List<StudentOrderListItem> list = studentOrderMapper.selectAllPage(
                semesterId, collegeId, classId, studentName, offset, normalizedSize);
        long total = studentOrderMapper.countAll(semesterId, collegeId, classId, studentName);
        return PageResponse.of(list, normalizedPage, normalizedSize, total);
    }

    // ============ 私有实现 ============

    /**
     * 班级教材上下文（W3 清单规则的判定基础）：
     * 该班 teacher_course 关系对应教师的表单 → submitted 明细教材并集 / reviewed 明细教材并集 /
     * reviewed 来源教材→课程（可追溯）。
     */
    private ClassBookContext classBookContext(Long semesterId, Long classId) {
        List<TeacherCourse> relations = teacherCourseMapper.selectByClass(semesterId, classId);
        Set<Long> teacherIds = relations.stream()
                .map(TeacherCourse::getTeacherId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (teacherIds.isEmpty()) {
            return ClassBookContext.EMPTY;
        }
        List<OrderForm> forms = orderFormMapper.selectList(Wrappers.<OrderForm>lambdaQuery()
                .eq(OrderForm::getSemesterId, semesterId)
                .in(OrderForm::getTeacherId, teacherIds)
                .eq(OrderForm::getDeleted, 0));
        List<Long> submittedFormIds = forms.stream()
                .filter(form -> form.getSubmittedAt() != null)
                .map(OrderForm::getId)
                .toList();
        List<Long> reviewedFormIds = forms.stream()
                .filter(form -> "reviewed".equals(form.getStatus()))
                .map(OrderForm::getId)
                .toList();
        Set<Long> submittedTextbookIds = textbookIdsOfForms(submittedFormIds);
        Set<Long> reviewedTextbookIds = textbookIdsOfForms(reviewedFormIds);
        // reviewed 来源教材 → 课程（学生选购明细可追溯）
        Map<Long, Long> textbookCourseIds = new java.util.HashMap<>();
        if (!reviewedFormIds.isEmpty()) {
            for (OrderFormItem item : orderFormItemMapper.selectByFormIds(reviewedFormIds)) {
                if (item.getTextbookId() != null && item.getCourseId() != null) {
                    textbookCourseIds.putIfAbsent(item.getTextbookId(), item.getCourseId());
                }
            }
        }
        return new ClassBookContext(submittedTextbookIds, reviewedTextbookIds, textbookCourseIds);
    }

    private Set<Long> textbookIdsOfForms(List<Long> formIds) {
        if (formIds.isEmpty()) {
            return Set.of();
        }
        return orderFormItemMapper.selectByFormIds(formIds).stream()
                .map(OrderFormItem::getTextbookId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 学生在 active 学期的班级（profile 真源，W6） */
    private Long classOf(Long studentId, Long semesterId) {
        UserSemesterProfile profile = userSemesterProfileMapper.selectByUserAndSemester(studentId, semesterId);
        return profile == null ? null : profile.getClassId();
    }

    /** 班级数量上限（W2 同源）：school_class.student_count；0/null 回退 order.quantity.max_default */
    private int classQuantityLimit(Long classId) {
        SchoolClass clazz = schoolClassMapper.selectByIdSoft(classId);
        if (clazz != null && clazz.getStudentCount() != null && clazz.getStudentCount() > 0) {
            return clazz.getStudentCount();
        }
        return configService.getInt(ConfigService.ORDER_QUANTITY_MAX_DEFAULT, 999);
    }

    /** 提交时归属快照 {collegeId,collegeName,classId,className}（W15） */
    private Map<String, Object> buildSnapshot(UserSemesterProfile profile) {
        College college = profile.getCollegeId() == null ? null
                : collegeMapper.selectByIdSoft(profile.getCollegeId());
        SchoolClass clazz = profile.getClassId() == null ? null
                : schoolClassMapper.selectByIdSoft(profile.getClassId());
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("collegeId", profile.getCollegeId());
        snapshot.put("collegeName", college == null ? null : college.getName());
        snapshot.put("classId", profile.getClassId());
        snapshot.put("className", clazz == null ? null : clazz.getName());
        return snapshot;
    }

    /** 选购单详情 VO（明细带教材信息 + delisted：当前无 reviewed 来源即不可选，W3） */
    private StudentOrderDetailVO buildOrderVO(StudentOrder order, List<StudentOrderItem> items,
                                              ClassBookContext context) {
        StudentOrderDetailVO vo = new StudentOrderDetailVO();
        vo.setId(order.getId());
        vo.setSemesterId(order.getSemesterId());
        Semester semester = semesterMapper.selectByIdSoft(order.getSemesterId());
        vo.setSemesterName(semester == null ? null : semester.getName());
        vo.setStatus(order.getStatus());
        vo.setSubmitSnapshot(order.getSubmitSnapshot());
        vo.setSubmittedAt(order.getSubmittedAt());

        Map<Long, Textbook> textbooks = loadTextbooks(items.stream()
                .map(StudentOrderItem::getTextbookId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        List<StudentOrderItemVO> itemVOs = new ArrayList<>();
        int totalQuantity = 0;
        for (StudentOrderItem item : items) {
            StudentOrderItemVO itemVO = new StudentOrderItemVO();
            itemVO.setId(item.getId());
            itemVO.setTextbookId(item.getTextbookId());
            Textbook textbook = textbooks.get(item.getTextbookId());
            itemVO.setIsbn(textbook == null ? null : textbook.getIsbn());
            itemVO.setTitle(textbook == null ? null : textbook.getTitle());
            itemVO.setEdition(textbook == null ? null : textbook.getEdition());
            itemVO.setAuthor(textbook == null ? null : textbook.getAuthor());
            itemVO.setPress(textbook == null ? null : textbook.getPress());
            itemVO.setPrice(textbook == null ? null : textbook.getPrice());
            itemVO.setDelisted(!context.reviewedTextbookIds().contains(item.getTextbookId()));
            itemVO.setQuantity(item.getQuantity());
            itemVO.setCourseId(item.getCourseId());
            itemVOs.add(itemVO);
            totalQuantity += item.getQuantity() == null ? 0 : item.getQuantity();
        }
        vo.setItems(itemVOs);
        vo.setTotalQuantity(totalQuantity);
        return vo;
    }

    private Map<Long, Textbook> loadTextbooks(Set<Long> textbookIds) {
        if (textbookIds.isEmpty()) {
            return Map.of();
        }
        return textbookMapper.selectList(Wrappers.<Textbook>lambdaQuery()
                        .in(Textbook::getId, textbookIds).eq(Textbook::getDeleted, 0))
                .stream().collect(Collectors.toMap(Textbook::getId, t -> t, (a, b) -> a));
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String toStr(Object value) {
        return value == null ? null : String.valueOf(value);
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

    // ============ 班级教材上下文（W3 清单规则判定结果） ============

    /**
     * 班级教材上下文（W3）：该班任课关系对应教师的表单明细聚合结果。
     *
     * @param submittedTextbookIds 教师已提交（submitted_at 非空）表单涉及的教材（清单全域，含已下架）
     * @param reviewedTextbookIds  教师已审核通过（reviewed）表单涉及的教材（可选集合）
     * @param textbookCourseIds    reviewed 来源教材 → 课程（选购明细可追溯）
     */
    record ClassBookContext(Set<Long> submittedTextbookIds, Set<Long> reviewedTextbookIds,
                            Map<Long, Long> textbookCourseIds) {

        static final ClassBookContext EMPTY = new ClassBookContext(Set.of(), Set.of(), Map.of());
    }
}
