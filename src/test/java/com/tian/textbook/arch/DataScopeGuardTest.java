package com.tian.textbook.arch;

import com.tian.textbook.common.annotation.CollegeScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据隔离护栏（S11）：按用户维度查询的 Mapper 方法必须显式声明隔离口径。
 *
 * <p>背景：数据隔离完全依赖 {@code @CollegeScope} 注解，漏标即不隔离，而此前没有任何
 * 编译期或测试期护栏——只靠「这些端点仅授给 ADMIN」的权限码间接兜住。新增接口时若忘记标注，
 * 越权会静默发生。</p>
 *
 * <p>本用例把「按用户维度」定义为：方法参数里出现 {@code userId / teacherId / studentId /
 * applicantId}（经 {@code @Param} 或形参名）。这类方法必须满足其一：</p>
 * <ol>
 *   <li>标注 {@link CollegeScope}（由 DataPermissionInterceptor 追加隔离条件）；</li>
 *   <li>出现在下面的 {@link #DOCUMENTED_EXCEPTIONS} 白名单里——即「已知不隔离，且理由明确」，
 *       新增条目必须在评审中交代原因。</li>
 * </ol>
 *
 * <p>白名单只允许「扩不进去的既有事实」，不允许为了过测试而塞新方法。</p>
 */
class DataScopeGuardTest {

    /** Mapper 源码根目录 */
    private static final Path MAPPER_ROOT = Path.of("src", "main", "java");

    /**
     * 已知不隔离、且理由明确的方法（{@code 类名#方法名}）。
     *
     * <p>共同点：只被 ADMIN 专属端点调用，或调用方已在 Service 层完成归属/范围校验
     * （例如导出中心、看板统计、通知重发等系统上下文），因此不能由请求级
     * DataPermissionInterceptor 统一追加条件。</p>
     */
    private static final Set<String> DOCUMENTED_EXCEPTIONS = Set.of(
            // 调用方（TeacherOrderService/StudentOrderService/ExportServiceImpl）已按角色显式限定范围
            "OrderFormMapper#selectBySemesterAndTeacher",
            "OrderFormMapper#selectByIdSoft",
            "OrderFormMapper#selectCollegeFormsPage",
            "OrderFormMapper#countCollegeForms",
            "OrderFormMapper#selectAllFormsPage",
            "OrderFormMapper#countAllForms",
            "OrderFormMapper#countGroupByCollegeAndStatus",
            "StudentOrderMapper#selectBySemesterAndStudent",
            "StudentOrderMapper#selectByIdSoft",
            "StudentOrderMapper#selectAllPage",
            "StudentOrderMapper#countAll",
            "ChangeRequestMapper#selectByIdSoft",
            "ChangeRequestMapper#selectByBatchNo",
            "ChangeRequestMapper#selectPageByFilter",
            "ChangeRequestMapper#countByFilter",
            // 自读/自写：入参恒为 SecurityUtils.requireCurrentUser().userId()，不存在跨用户读取
            "NoticeRecordMapper#selectConfirmed",
            "NoticeRecordMapper#selectConfirmedByUserAndTasks",
            "NoticeRecordMapper#selectByTaskAndUser",
            "NoticeRecordMapper#selectRound",
            "NoticeRecordMapper#insertConfirmIfAbsent",
            "SysRoleMapper#selectByUserId",
            "SysUserRoleMapper#selectRoleIdsByUser",
            "SysUserRoleMapper#selectByUserAndRole",
            // 认证/会话自管理：仅作用于入参用户自身（登出、改密、过期令牌清理）
            "SysUserMapper#revokeAllTokens",
            "SysUserTokenMapper#revokeAllByUser",
            "SysUserTokenMapper#softDeleteExpiredRevoked",
            // 任课关系：按 (semesterId, teacherId) 精确匹配，teacherId 由 Service 取当前用户；
            // 导入/维护路径为 ADMIN 专属权限码（course:teacher:manage）
            "TeacherCourseMapper#selectByTeacher",
            "TeacherCourseMapper#selectExact",
            "TeacherCourseQueryMapper#selectWithNames",
            // 系统/管理上下文
            "SysUserMapper#selectByUserNo",
            "SysUserMapper#selectByIdSoft",
            "SysUserMapper#selectPageByFilter",
            "SysUserMapper#countByFilter",
            "SysUserMapper#selectActiveByCollegeIds",
            "UserSemesterProfileMapper#selectByUserAndSemester",
            "UserSemesterProfileMapper#selectCollegeId",
            "UserSemesterProfileMapper#selectBySemester",
            // 审计查询：仅 audit:log:view（ADMIN 专属），且按操作者维度过滤属查询条件而非隔离维度
            "AuditLogMapper#selectByFilter",
            "AuditLogMapper#countByFilter");

    @Test
    @DisplayName("按用户维度的 Mapper 查询方法必须标注 @CollegeScope 或在白名单中说明理由")
    void userScopedMapperMethodsDeclareIsolation() throws Exception {
        List<String> undeclared = new ArrayList<>();
        Set<String> discovered = new TreeSet<>();
        for (Class<?> mapper : mapperInterfaces()) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (!isUserScoped(method)) {
                    continue;
                }
                String key = mapper.getSimpleName() + "#" + method.getName();
                discovered.add(key);
                if (method.isAnnotationPresent(CollegeScope.class)) {
                    continue;
                }
                if (DOCUMENTED_EXCEPTIONS.contains(key)) {
                    continue;
                }
                undeclared.add(key);
            }
        }
        assertThat(discovered)
                .as("未扫描到任何按用户维度的 Mapper 方法——护栏失效（包名或判定规则需更新）")
                .isNotEmpty();
        assertThat(undeclared)
                .as("以下 Mapper 方法按用户维度查询但未声明隔离口径："
                        + "请标注 @CollegeScope，或在 DataScopeGuardTest.DOCUMENTED_EXCEPTIONS 中说明理由")
                .isEmpty();
    }

    @Test
    @DisplayName("白名单不得残留已删除或已改名的方法（防止护栏退化为噪音）")
    void documentedExceptionsStillExist() throws Exception {
        Set<String> existing = new LinkedHashSet<>();
        for (Class<?> mapper : mapperInterfaces()) {
            for (Method method : mapper.getDeclaredMethods()) {
                existing.add(mapper.getSimpleName() + "#" + method.getName());
            }
        }
        Set<String> stale = new TreeSet<>(DOCUMENTED_EXCEPTIONS);
        stale.removeAll(existing);
        assertThat(stale)
                .as("白名单里存在已不存在的方法，请同步清理")
                .isEmpty();
    }

    /** 扫描 {@code com.tian.textbook} 下全部 {@code @Mapper} 接口。 */
    private List<Class<?>> mapperInterfaces() throws IOException, ClassNotFoundException {
        List<Class<?>> result = new ArrayList<>();
        Path root = MAPPER_ROOT.resolve("com/tian/textbook");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                String relative = root.relativize(file).toString()
                        .replace(File.separatorChar, '.')
                        .replaceAll("\\.java$", "");
                Class<?> type = Class.forName("com.tian.textbook." + relative);
                if (type.isInterface() && type.isAnnotationPresent(Mapper.class)) {
                    result.add(type);
                }
            }
        }
        return result;
    }

    /** 方法是否按用户维度查询：任一参数（@Param 值或形参名）命中用户维度关键字。 */
    private boolean isUserScoped(Method method) {
        for (Parameter parameter : method.getParameters()) {
            String name = parameter.getName();
            Param param = parameter.getAnnotation(Param.class);
            if (param != null && !param.value().isBlank()) {
                name = param.value();
            }
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("userid") || lower.equals("teacherid") || lower.equals("studentid")
                    || lower.equals("applicantid")) {
                return true;
            }
        }
        return false;
    }
}
