package com.tian.textbook.unit.datascope;

import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.common.datascope.CollegeResolver;
import com.tian.textbook.common.datascope.CollegeScopeHandler;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.support.TestSecurity;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据隔离条件构建单元测试（W10 多角色并集）。
 *
 * <p>回归背景：{@code append} 的首个条件前缀判定反了（按调用点传入的 any 判定），首个条件被写成
 * {@code " OR x = y"}，{@code parseCondExpression} 必然失败 → 返回 null → **隔离条件静默失效**
 * （同时每条语句刷 ERROR 日志）。本用例锁定「条件可解析且首个条件无 OR 前缀」。</p>
 */
class CollegeScopeHandlerTest {

    private static final long USER_ID = 7L;
    private static final long COLLEGE_ID = 3L;

    /** 真实 Mapper 方法（走 Class.forName 注解解析路径）。 */
    private static final String STUDENT_STMT =
            "com.tian.textbook.order.mapper.StudentOrderMapper.selectStudentOrders";
    private static final String TEACHER_STMT =
            "com.tian.textbook.order.mapper.OrderFormMapper.selectTeacherForms";
    private static final String UNANNOTATED_STMT =
            "com.tian.textbook.order.mapper.StudentOrderMapper.selectByIdSoft";

    private final CollegeResolver resolver = (userId, semesterId) -> COLLEGE_ID;
    private final CollegeScopeHandler handler = new CollegeScopeHandler(resolver);

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
        SemesterContextHolder.clear();
    }

    /** 测试用假 Mapper：承载代码库中当前没有的 secretaryColumn 组合（多角色并集场景）。 */
    static class FakeMapper {

        @CollegeScope(secretaryColumn = "college_id")
        void secretaryOnly() {
        }

        @CollegeScope(secretaryColumn = "college_id", teacherColumn = "teacher_id")
        void secretaryTeacher() {
        }

        @CollegeScope(secretaryColumn = "college_id", studentColumn = "student_id",
                teacherColumn = "teacher_id", userColumn = "applicant_id")
        void allScopes() {
        }
    }

    private void authenticate(String... roleCodes) {
        TestSecurity.authenticate(USER_ID, "U7", "用户7", Set.of(roleCodes), roleCodes[0], Set.of());
    }

    private static String segment(String statementId) {
        Expression expression = new CollegeScopeHandler((u, s) -> COLLEGE_ID)
                .getSqlSegment(null, statementId);
        return expression == null ? null : expression.toString();
    }

    @Test
    @DisplayName("原有 where 非空：合并而非替换（MP 拦截器 setWhere 是替换语义）")
    void existingWhere_isMergedNotReplaced() throws Exception {
        authenticate("STUDENT");
        Expression where = CCJSqlParserUtil.parseCondExpression("student_id = ? AND deleted = 0");

        Expression segment = new CollegeScopeHandler((u, s) -> COLLEGE_ID)
                .getSqlSegment(where, STUDENT_STMT);

        assertThat(segment).isNotNull();
        assertThat(segment.toString())
                .contains("student_id = ?")
                .contains("deleted = 0")
                .contains("(student_id = 7)");
    }

    @Test
    @DisplayName("STUDENT 单角色：条件可解析且首个条件无 OR 前缀（原缺陷：\" OR student_id = 7\" 解析失败）")
    void studentScope_buildsParsableCondition() {
        authenticate("STUDENT");

        String segment = segment(STUDENT_STMT);

        assertThat(segment).isEqualTo("(student_id = 7)");
    }

    @Test
    @DisplayName("TEACHER 单角色：teacher_id 条件生效")
    void teacherScope_buildsParsableCondition() {
        authenticate("TEACHER");

        assertThat(segment(TEACHER_STMT)).isEqualTo("(teacher_id = 7)");
    }

    @Test
    @DisplayName("秘书+教师 多角色：OR 并集可解析（学院条件在前，无多余前缀）")
    void multiRole_buildsOrUnion() {
        SemesterContextHolder.set(1L);
        authenticate("SECRETARY", "TEACHER");

        String segment = segment(FakeMapper.class.getName() + ".secretaryTeacher");

        assertThat(segment).isEqualTo("(college_id = 3 OR teacher_id = 7)");
    }

    @Test
    @DisplayName("秘书无学院归属：退化为 1 = 0（查不到任何数据，不越院）")
    void secretaryWithoutCollege_deniesAll() {
        SemesterContextHolder.set(1L);
        authenticate("SECRETARY");
        CollegeScopeHandler denying = new CollegeScopeHandler((userId, semesterId) -> null);

        Expression expression = denying.getSqlSegment(null, FakeMapper.class.getName() + ".secretaryOnly");

        assertThat(expression).isNotNull();
        assertThat(expression.toString()).isEqualTo("(1 = 0)");
    }

    @Test
    @DisplayName("四角色维度齐备时全量并集可解析")
    void allScopes_buildsFullUnion() {
        SemesterContextHolder.set(1L);
        authenticate("SECRETARY", "TEACHER", "STUDENT");

        String segment = segment(FakeMapper.class.getName() + ".allScopes");

        assertThat(segment).isEqualTo(
                "(college_id = 3 OR teacher_id = 7 OR student_id = 7 OR applicant_id = 7)");
    }

    @Test
    @DisplayName("ADMIN 不过滤；未标注 @CollegeScope 的语句不追加条件")
    void adminAndUnannotated_returnNull() {
        authenticate("ADMIN");
        assertThat(segment(STUDENT_STMT)).isNull();

        authenticate("STUDENT");
        assertThat(segment(UNANNOTATED_STMT)).isNull();
    }

    // ============ fail-closed（S10：三处静默放行改为默认拒绝） ============

    @Test
    @DisplayName("无登录上下文：默认拒绝 1 = 0（原实现返回 null 即不过滤，异步线程/定时任务可读全量）")
    void noSecurityContext_deniesAll() {
        // 不调用 authenticate：SecurityContext 为空
        String segment = segment(STUDENT_STMT);

        assertThat(segment).isNotNull();
        assertThat(segment).contains("1 = 0");
    }

    @Test
    @DisplayName("无登录上下文且已有 where：默认拒绝与原始 where 合并（不丢原条件）")
    void noSecurityContext_mergesDenyWithExistingWhere() throws Exception {
        Expression where = CCJSqlParserUtil.parseCondExpression("deleted = 0");

        Expression expression = handler.getSqlSegment(where, STUDENT_STMT);

        assertThat(expression).isNotNull();
        assertThat(expression.toString()).contains("deleted = 0").contains("1 = 0");
    }

    @Test
    @DisplayName("原 where 含 OR：合并时必须给左侧加括号（AND 优先级高于 OR，否则隔离被绕过）")
    void existingWhereWithOr_isParenthesized() throws Exception {
        authenticate("STUDENT");
        // 未加括号会序列化为 a = 1 OR b = 2 AND (student_id = 7)，
        // 按优先级等价于 a = 1 OR (b = 2 AND 隔离) —— 命中 a = 1 的行绕过隔离
        Expression where = CCJSqlParserUtil.parseCondExpression("a = 1 OR b = 2");

        Expression segment = handler.getSqlSegment(where, STUDENT_STMT);

        assertThat(segment).isNotNull();
        String sql = segment.toString();
        assertThat(sql).contains("(a = 1 OR b = 2)");
        assertThat(sql).contains("(student_id = 7)");
    }

    @Test
    @DisplayName("原 where 为 AND 链：仍正常合并（加括号不破坏既有语义）")
    void existingWhereWithAnd_stillMerged() throws Exception {
        authenticate("TEACHER");
        Expression where = CCJSqlParserUtil.parseCondExpression("teacher_id = 7 AND deleted = 0");

        Expression segment = handler.getSqlSegment(where, TEACHER_STMT);

        assertThat(segment).isNotNull();
        assertThat(segment.toString()).contains("teacher_id = 7").contains("deleted = 0");
    }

    @Test
    @DisplayName("注解列与角色不匹配：默认拒绝 1 = 0（原实现 sb 为空即返回 null，完全不过滤）")
    void roleWithoutMatchingColumn_deniesAll() {
        SemesterContextHolder.set(1L);
        // 学生调用只配了 teacherColumn 的语句：学生维度无可用列
        authenticate("STUDENT");

        String segment = segment(TEACHER_STMT);

        assertThat(segment).isNotNull();
        assertThat(segment).contains("1 = 0");
    }
}
