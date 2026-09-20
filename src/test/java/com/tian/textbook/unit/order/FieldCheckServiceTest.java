package com.tian.textbook.unit.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.order.service.FieldCheckService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 字段审查引擎单元测试（SPEC §14：6 规则 × 边界）。
 *
 * <p>覆盖 REQUIRED / QTY_RANGE（含班级人数 0 回退 order.quantity.max_default）/ BOOK_ACTIVE /
 * COURSE_OWNER / CLASS_LINK / ISBN_FORMAT 与 isValidIsbn 纯函数。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FieldCheckServiceTest {

    private static final long SEMESTER_ID = 10L;
    private static final long TEACHER_ID = 100L;
    private static final long CLASS_ID = 1000L;
    private static final long COURSE_ID = 2000L;
    private static final long TEXTBOOK_ID = 3000L;

    @Mock
    private TextbookMapper textbookMapper;
    @Mock
    private SchoolClassMapper schoolClassMapper;
    @Mock
    private TeacherCourseMapper teacherCourseMapper;
    @Mock
    private ConfigService configService;

    private FieldCheckService service;

    @BeforeEach
    void setUp() {
        service = new FieldCheckService(textbookMapper, schoolClassMapper, teacherCourseMapper,
                configService);
        // 默认：班级 50 人、回退上限 999、课程-班级关联存在、教材在库
        SchoolClass clazz = new SchoolClass();
        clazz.setId(CLASS_ID);
        clazz.setStudentCount(50);
        when(schoolClassMapper.selectByIdSoft(CLASS_ID)).thenReturn(clazz);
        when(configService.getInt(eq(ConfigService.ORDER_QUANTITY_MAX_DEFAULT), anyInt())).thenReturn(999);
        TeacherCourse relation = new TeacherCourse();
        relation.setSemesterId(SEMESTER_ID);
        relation.setTeacherId(TEACHER_ID);
        relation.setCourseId(COURSE_ID);
        relation.setClassId(CLASS_ID);
        when(teacherCourseMapper.selectByTeacher(SEMESTER_ID, TEACHER_ID)).thenReturn(List.of(relation));
        Textbook book = new Textbook();
        book.setId(TEXTBOOK_ID);
        book.setIsbn("978-0-306-40615-7");
        book.setStatus(1);
        when(textbookMapper.selectList(any(Wrapper.class))).thenReturn(List.of(book));
    }

    private OrderFormSubmitItem validItem() {
        return new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 1);
    }

    private List<FieldCheckIssue> check(List<OrderFormSubmitItem> items) {
        return service.checkOrderItems(SEMESTER_ID, TEACHER_ID, items);
    }

    private List<String> rulesOf(List<FieldCheckIssue> issues) {
        return issues.stream().map(FieldCheckIssue::rule).toList();
    }

    @Nested
    @DisplayName("REQUIRED：必填完整")
    class RequiredRule {

        @Test
        void checkOrderItems_nullItems_returnsSingleRequiredIssue() {
            List<FieldCheckIssue> issues = check(null);
            assertThat(issues).singleElement()
                    .satisfies(i -> assertThat(i.rule()).isEqualTo(FieldCheckService.RULE_REQUIRED));
        }

        @Test
        void checkOrderItems_emptyItems_returnsSingleRequiredIssue() {
            assertThat(rulesOf(check(List.of())))
                    .containsExactly(FieldCheckService.RULE_REQUIRED);
        }

        @Test
        void checkOrderItems_blankFields_returnsRequiredIssuePerField() {
            List<FieldCheckIssue> issues = check(List.of(new OrderFormSubmitItem(null, null, null, null)));
            assertThat(rulesOf(issues)).containsExactlyInAnyOrder(
                    FieldCheckService.RULE_REQUIRED, FieldCheckService.RULE_REQUIRED,
                    FieldCheckService.RULE_REQUIRED, FieldCheckService.RULE_REQUIRED);
            assertThat(issues).extracting(FieldCheckIssue::field)
                    .containsExactlyInAnyOrder("items[0].courseId", "items[0].classId",
                            "items[0].textbookId", "items[0].quantity");
        }
    }

    @Nested
    @DisplayName("QTY_RANGE：1 ≤ quantity ≤ 班级人数（0 回退配置默认值）")
    class QtyRangeRule {

        @Test
        void checkOrderItems_quantityEqualsClassCount_passes() {
            assertThat(check(List.of(new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 50))))
                    .isEmpty();
        }

        @Test
        void checkOrderItems_quantityAboveClassCount_returnsQtyRangeIssue() {
            List<FieldCheckIssue> issues =
                    check(List.of(new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 51)));
            assertThat(rulesOf(issues)).containsExactly(FieldCheckService.RULE_QTY_RANGE);
            assertThat(issues.get(0).message()).contains("1-50");
        }

        @Test
        void checkOrderItems_zeroQuantity_returnsQtyRangeIssue() {
            assertThat(rulesOf(check(List.of(new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 0)))))
                    .contains(FieldCheckService.RULE_QTY_RANGE);
        }

        @Test
        void checkOrderItems_classCountZero_fallsBackToConfiguredDefault() {
            SchoolClass empty = new SchoolClass();
            empty.setId(CLASS_ID);
            empty.setStudentCount(0);
            when(schoolClassMapper.selectByIdSoft(CLASS_ID)).thenReturn(empty);

            assertThat(check(List.of(new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 999))))
                    .isEmpty();
            List<FieldCheckIssue> issues =
                    check(List.of(new OrderFormSubmitItem(COURSE_ID, CLASS_ID, TEXTBOOK_ID, 1000)));
            assertThat(rulesOf(issues)).containsExactly(FieldCheckService.RULE_QTY_RANGE);
            assertThat(issues.get(0).message()).contains("1-999");
        }

        @Test
        void quantityUpperLimit_nullClassId_returnsConfiguredDefault() {
            assertThat(service.quantityUpperLimit(null)).isEqualTo(999);
        }

        @Test
        void quantityUpperLimit_existingClass_returnsStudentCount() {
            assertThat(service.quantityUpperLimit(CLASS_ID)).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("BOOK_ACTIVE / ISBN_FORMAT：教材在库且格式合法")
    class BookRule {

        @Test
        void checkOrderItems_textbookNotExists_returnsBookActiveIssue() {
            when(textbookMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
            assertThat(rulesOf(check(List.of(validItem()))))
                    .containsExactly(FieldCheckService.RULE_BOOK_ACTIVE);
        }

        @Test
        void checkOrderItems_textbookDisabled_returnsBookActiveIssue() {
            Textbook disabled = new Textbook();
            disabled.setId(TEXTBOOK_ID);
            disabled.setIsbn("978-7-04-056616-6");
            disabled.setStatus(0);
            when(textbookMapper.selectList(any(Wrapper.class))).thenReturn(List.of(disabled));
            assertThat(rulesOf(check(List.of(validItem()))))
                    .containsExactly(FieldCheckService.RULE_BOOK_ACTIVE);
        }

        @Test
        void checkOrderItems_activeBookWithInvalidIsbn_returnsIsbnFormatIssue() {
            Textbook badIsbn = new Textbook();
            badIsbn.setId(TEXTBOOK_ID);
            badIsbn.setIsbn("978-0-306-40615-8");
            badIsbn.setStatus(1);
            when(textbookMapper.selectList(any(Wrapper.class))).thenReturn(List.of(badIsbn));
            assertThat(rulesOf(check(List.of(validItem()))))
                    .containsExactly(FieldCheckService.RULE_ISBN_FORMAT);
        }
    }

    @Nested
    @DisplayName("COURSE_OWNER / CLASS_LINK：任课关系存在性")
    class OwnershipRule {

        @Test
        void checkOrderItems_courseNotTaughtByUser_returnsCourseOwnerIssue() {
            when(teacherCourseMapper.selectByTeacher(SEMESTER_ID, TEACHER_ID)).thenReturn(List.of());
            assertThat(rulesOf(check(List.of(validItem()))))
                    .containsExactly(FieldCheckService.RULE_COURSE_OWNER);
        }

        @Test
        void checkOrderItems_courseOwnedButClassNotLinked_returnsClassLinkIssue() {
            long otherClass = 1001L;
            List<FieldCheckIssue> issues = check(List.of(
                    new OrderFormSubmitItem(COURSE_ID, otherClass, TEXTBOOK_ID, 1)));
            assertThat(rulesOf(issues)).containsExactly(FieldCheckService.RULE_CLASS_LINK);
            assertThat(issues.get(0).message()).contains("该课程未关联此班级");
        }

        @Test
        void checkOrderItems_courseAndClassLinked_passes() {
            assertThat(check(List.of(validItem()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("ISBN_FORMAT：isValidIsbn 纯函数")
    class IsbnFormat {

        @Test
        void isValidIsbn_validIsbn10And13_returnsTrue() {
            assertThat(FieldCheckService.isValidIsbn("978-0-306-40615-7")).isTrue();
            assertThat(FieldCheckService.isValidIsbn("080442957X")).isTrue();
            assertThat(FieldCheckService.isValidIsbn("080442957x")).isTrue();
            assertThat(FieldCheckService.isValidIsbn("978 0 306 40615 7")).isTrue();
        }

        @Test
        void isValidIsbn_invalidCheckDigitOrLength_returnsFalse() {
            assertThat(FieldCheckService.isValidIsbn("978-0-306-40615-8")).isFalse(); // 校验位错
            assertThat(FieldCheckService.isValidIsbn("0804429571")).isFalse();         // 校验位错
            assertThat(FieldCheckService.isValidIsbn("123456789")).isFalse();          // 长度不足
            assertThat(FieldCheckService.isValidIsbn("12345678901234")).isFalse();     // 超长
            assertThat(FieldCheckService.isValidIsbn("978-7-04-05661X-6")).isFalse();  // 13 位含字母
            assertThat(FieldCheckService.isValidIsbn("")).isFalse();
            assertThat(FieldCheckService.isValidIsbn(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("组合：逐字段返回全部问题（契约冻结项）")
    class MultipleIssues {

        @Test
        void checkOrderItems_multipleProblems_returnsAllIssuesPerField() {
            long otherCourse = 2001L;
            List<FieldCheckIssue> issues = check(List.of(
                    new OrderFormSubmitItem(otherCourse, CLASS_ID, TEXTBOOK_ID, 51)));
            assertThat(rulesOf(issues)).containsExactlyInAnyOrder(
                    FieldCheckService.RULE_COURSE_OWNER, FieldCheckService.RULE_QTY_RANGE);
            assertThat(issues).extracting(FieldCheckIssue::field)
                    .containsExactlyInAnyOrder("items[0].courseId", "items[0].quantity");
        }
    }
}
