package com.tian.textbook.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段审查引擎（SPEC §8：教师征订两级审查之一级，白名单客观规则集）。
 *
 * <p>输出结构（契约冻结项）：{@code [{"field":"items[2].quantity","rule":"QTY_RANGE","message":"..."}]}，
 * field 命名规范 {@code items[N].xxx}（N 从 0 开始）；任一不过 → 表单 rejected_auto，
 * 教师可修复后窗口内无限次重提（「禁止全驳回」：规则全为教师可控可修复的客观项）。</p>
 *
 * <p>六规则（SPEC §8 表）：REQUIRED / QTY_RANGE / BOOK_ACTIVE / COURSE_OWNER / CLASS_LINK / ISBN_FORMAT。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldCheckService {

    // ============ 规则码（契约冻结，与 SPEC §8 表一致） ============

    public static final String RULE_REQUIRED = "REQUIRED";
    public static final String RULE_QTY_RANGE = "QTY_RANGE";
    public static final String RULE_BOOK_ACTIVE = "BOOK_ACTIVE";
    public static final String RULE_COURSE_OWNER = "COURSE_OWNER";
    public static final String RULE_CLASS_LINK = "CLASS_LINK";
    public static final String RULE_ISBN_FORMAT = "ISBN_FORMAT";

    private final TextbookMapper textbookMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final TeacherCourseMapper teacherCourseMapper;
    private final ConfigService configService;

    /**
     * 教师征订明细字段审查（六规则逐字段执行，返回全部问题项；空列表 = 审查通过）。
     *
     * @param semesterId active 学期 id
     * @param teacherId  当前教师用户 id
     * @param items      提交明细（null/空 → REQUIRED）
     */
    public List<FieldCheckIssue> checkOrderItems(Long semesterId, Long teacherId, List<OrderFormSubmitItem> items) {
        List<FieldCheckIssue> issues = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            issues.add(new FieldCheckIssue("items", RULE_REQUIRED, "请至少添加一条征订明细"));
            return issues;
        }
        // 任课关系预取：courseId → classIds（COURSE_OWNER / CLASS_LINK 判定基础，W17 征订范围即此表）
        Map<Long, Set<Long>> courseClasses = loadCourseClassScope(semesterId, teacherId);
        // 教材批量预取（BOOK_ACTIVE / ISBN_FORMAT）
        Map<Long, Textbook> books = loadTextbooks(items);
        // 班级人数上限缓存（QTY_RANGE，W2）
        Map<Long, Integer> classLimitCache = new HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            OrderFormSubmitItem item = items.get(i);
            String prefix = "第 " + (i + 1) + " 行：";
            if (item == null) {
                issues.add(new FieldCheckIssue("items[" + i + "]", RULE_REQUIRED, prefix + "请填写完整的征订明细"));
                continue;
            }
            boolean courseOk = item.courseId() != null;
            boolean classOk = item.classId() != null;
            boolean bookOk = item.textbookId() != null;
            boolean qtyOk = item.quantity() != null;

            // REQUIRED：必填完整（课程/班级/教材/数量）
            if (!courseOk) {
                issues.add(new FieldCheckIssue("items[" + i + "].courseId", RULE_REQUIRED, prefix + "请选择课程"));
            }
            if (!classOk) {
                issues.add(new FieldCheckIssue("items[" + i + "].classId", RULE_REQUIRED, prefix + "请选择班级"));
            }
            if (!bookOk) {
                issues.add(new FieldCheckIssue("items[" + i + "].textbookId", RULE_REQUIRED, prefix + "请选择教材"));
            }
            if (!qtyOk) {
                issues.add(new FieldCheckIssue("items[" + i + "].quantity", RULE_REQUIRED, prefix + "请填写数量"));
            }

            // BOOK_ACTIVE：教材存在且 status=1；存在才继续校验 ISBN_FORMAT
            if (bookOk) {
                Textbook textbook = books.get(item.textbookId());
                if (textbook == null || !Integer.valueOf(1).equals(textbook.getStatus())) {
                    issues.add(new FieldCheckIssue("items[" + i + "].textbookId", RULE_BOOK_ACTIVE,
                            prefix + "教材已停用，请重新选择"));
                } else if (!isValidIsbn(textbook.getIsbn())) {
                    issues.add(new FieldCheckIssue("items[" + i + "].textbookId", RULE_ISBN_FORMAT,
                            prefix + "ISBN 格式不正确"));
                }
            }

            // QTY_RANGE：1 ≤ quantity ≤ 班级人数（0/null 回退 order.quantity.max_default，W2）
            if (qtyOk) {
                int max = quantityUpperLimit(item.classId(), classLimitCache);
                int quantity = item.quantity();
                if (quantity < 1 || quantity > max) {
                    issues.add(new FieldCheckIssue("items[" + i + "].quantity", RULE_QTY_RANGE,
                            prefix + "数量需在 1-" + max + " 之间"));
                }
            }

            // COURSE_OWNER / CLASS_LINK：任课关系存在性（先查 (学期,教师,课程) 关系集合，
            // 空 → 课程不归本人；有关系但不含此班级 → 课程未关联此班级）
            if (courseOk) {
                Set<Long> linkedClasses = courseClasses.get(item.courseId());
                if (linkedClasses == null || linkedClasses.isEmpty()) {
                    issues.add(new FieldCheckIssue("items[" + i + "].courseId", RULE_COURSE_OWNER,
                            prefix + "该课程不在您的任课范围内"));
                } else if (classOk && !linkedClasses.contains(item.classId())) {
                    issues.add(new FieldCheckIssue("items[" + i + "].classId", RULE_CLASS_LINK,
                            prefix + "该课程未关联此班级"));
                }
            }
        }
        return issues;
    }

    /**
     * 班级数量上限（W2）：school_class.student_count；缺失或为 0 → 回退
     * system_config 的 order.quantity.max_default（默认 999）。
     */
    public int quantityUpperLimit(Long classId) {
        return quantityUpperLimit(classId, new HashMap<>());
    }

    private int quantityUpperLimit(Long classId, Map<Long, Integer> cache) {
        if (classId == null) {
            return defaultQuantityLimit();
        }
        Integer cached = cache.get(classId);
        if (cached != null) {
            return cached;
        }
        int limit = defaultQuantityLimit();
        SchoolClass clazz = schoolClassMapper.selectByIdSoft(classId);
        if (clazz != null && clazz.getStudentCount() != null && clazz.getStudentCount() > 0) {
            limit = clazz.getStudentCount();
        }
        cache.put(classId, limit);
        return limit;
    }

    private int defaultQuantityLimit() {
        return configService.getInt(ConfigService.ORDER_QUANTITY_MAX_DEFAULT, 999);
    }

    /** (active 学期, 当前教师) 任课关系：courseId → classIds */
    private Map<Long, Set<Long>> loadCourseClassScope(Long semesterId, Long teacherId) {
        Map<Long, Set<Long>> courseClasses = new HashMap<>();
        List<TeacherCourse> relations = teacherCourseMapper.selectByTeacher(semesterId, teacherId);
        for (TeacherCourse relation : relations) {
            if (relation.getCourseId() == null || relation.getClassId() == null) {
                continue;
            }
            courseClasses.computeIfAbsent(relation.getCourseId(), k -> new HashSet<>()).add(relation.getClassId());
        }
        return courseClasses;
    }

    /** 明细涉及教材批量预取（deleted=0） */
    private Map<Long, Textbook> loadTextbooks(List<OrderFormSubmitItem> items) {
        Set<Long> bookIds = items.stream()
                .map(OrderFormSubmitItem::textbookId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        return textbookMapper.selectList(Wrappers.<Textbook>lambdaQuery()
                        .in(Textbook::getId, bookIds)
                        .eq(Textbook::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(Textbook::getId, t -> t, (a, b) -> a));
    }

    // ============ ISBN-10/13 校验位纯函数（供单测，SPEC §14） ============

    /**
     * ISBN-10 / ISBN-13 校验位验证（容忍连字符与空格；其余格式一律不合法）。
     *
     * <p>ISBN-10：{@code Σ (10-i)·d_i mod 11 == 0}（末位可为 X/x 表示 10）；
     * ISBN-13：{@code Σ d_i·(1,3 交替) mod 10 == 0}。</p>
     */
    public static boolean isValidIsbn(String isbn) {
        if (isbn == null) {
            return false;
        }
        String digits = isbn.replaceAll("[-\\s]", "");
        if (digits.length() == 10) {
            return isValidIsbn10(digits);
        }
        if (digits.length() == 13) {
            return isValidIsbn13(digits);
        }
        return false;
    }

    private static boolean isValidIsbn10(String s) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(i);
            int d;
            if (i < 9) {
                if (c < '0' || c > '9') {
                    return false;
                }
                d = c - '0';
            } else {
                if (c == 'X' || c == 'x') {
                    d = 10;
                } else if (c >= '0' && c <= '9') {
                    d = c - '0';
                } else {
                    return false;
                }
            }
            sum += (10 - i) * d;
        }
        return sum % 11 == 0;
    }

    private static boolean isValidIsbn13(String s) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (i % 2 == 0 ? 1 : 3) * (c - '0');
        }
        return sum % 10 == 0;
    }
}
