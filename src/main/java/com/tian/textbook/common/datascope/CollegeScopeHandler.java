package com.tian.textbook.common.datascope;

import com.baomidou.mybatisplus.extension.plugins.handler.DataPermissionHandler;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.common.semester.SemesterContextHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据隔离处理器（W10：多角色并集）。
 *
 * <p>仅对标注 {@link CollegeScope} 的 Mapper 方法追加隔离条件（未标注不隔离）：
 * ADMIN 不过滤；SECRETARY 按学院列过滤（学院=当前用户 active 学期归属）；
 * TEACHER/STUDENT 按本人 id 过滤；多角色 OR 并集。</p>
 *
 * <p>注意：DataPermissionInterceptor 会拦截所有语句，本处理器对未标注方法返回 null
 * （不追加条件）。跨表学院范围（秘书查本院表单等）不走本拦截器，由 Service 显式传参。</p>
 */
@Slf4j
@Component
public class CollegeScopeHandler implements DataPermissionHandler {

    /** mappedStatementId → 注解缓存（反射查找每语句仅一次） */
    private final Map<String, CollegeScope> annotationCache = new ConcurrentHashMap<>();

    private final CollegeResolver collegeResolver;

    public CollegeScopeHandler(CollegeResolver collegeResolver) {
        this.collegeResolver = collegeResolver;
    }

    /** 默认拒绝条件：无隔离条件可用时一律生成它，绝不静默放行。 */
    private static final String DENY_ALL = "1 = 0";

    @Override
    public Expression getSqlSegment(Expression where, String mappedStatementId) {
        CollegeScope scope = resolveAnnotation(mappedStatementId);
        if (scope == null) {
            return null; // 未标注 @CollegeScope：不由本拦截器负责（见 S11 的 ArchUnit 护栏）
        }
        CurrentUser user = SecurityUtils.currentUser();
        if (user == null) {
            // fail-closed：未登录（异步线程/定时任务直接调 Mapper）时不再静默放行，
            // 否则隔离条件在无请求上下文时整段消失。定时任务等系统上下文应走不带 @CollegeScope
            // 的专用查询方法，而不是依赖此处放行。
            log.warn("数据隔离：无登录上下文，按默认拒绝处理（未过滤将泄露全量数据）: {}", mappedStatementId);
            return andWith(where, DENY_ALL);
        }
        if (user.isAdmin()) {
            return null; // ADMIN 全部数据
        }
        StringBuilder sb = new StringBuilder();
        if (user.hasRole("SECRETARY") && !scope.secretaryColumn().isBlank()) {
            Long collegeId = SemesterContextHolder.get() == null ? null
                    : collegeResolver.collegeIdOf(user.userId(), SemesterContextHolder.get());
            append(sb, collegeId == null
                    ? DENY_ALL // 无学院归属 → 查不到任何数据
                    : scope.secretaryColumn() + " = " + collegeId);
        }
        if (user.hasRole("TEACHER") && !scope.teacherColumn().isBlank()) {
            append(sb, scope.teacherColumn() + " = " + user.userId());
        }
        if (user.hasRole("STUDENT") && !scope.studentColumn().isBlank()) {
            append(sb, scope.studentColumn() + " = " + user.userId());
        }
        if (!scope.userColumn().isBlank()) {
            append(sb, scope.userColumn() + " = " + user.userId());
        }
        if (sb.length() == 0) {
            // fail-closed：注解列与当前用户角色不匹配（如只配了 teacherColumn 但调用者是学生）
            // 时不再返回 null（等于完全不过滤），改为默认拒绝。
            log.warn("数据隔离：注解列与当前用户角色不匹配，按默认拒绝处理: user={}, roles={}, statement={}",
                    user.userNo(), user.roles(), mappedStatementId);
            return andWith(where, DENY_ALL);
        }
        try {
            Expression condition = CCJSqlParserUtil.parseCondExpression("(" + sb + ")");
            // MP 的 DataPermissionInterceptor 是 setWhere(返回值) 替换语义（非 AND 拼接），
            // 故必须在此把原始 where 一并合并，否则原 WHERE 条件会被整段丢弃。
            return andWith(where, condition);
        } catch (Exception e) {
            // fail-closed：解析失败时不再返回 null（历史上那个「首个条件前缀写成 OR x = y」的
            // bug 正是走这条路静默失效的），改为默认拒绝并告警。
            log.error("构建数据隔离条件失败，按默认拒绝处理: {}", sb, e);
            return andWith(where, DENY_ALL);
        }
    }

    /** 把默认拒绝条件与原始 where 合并（同样遵循 DataPermissionInterceptor 的替换语义）。 */
    private Expression andWith(Expression where, String denyCondition) {
        try {
            Expression condition = CCJSqlParserUtil.parseCondExpression("(" + denyCondition + ")");
            return andWith(where, condition);
        } catch (Exception e) {
            // 理论上不可达（常量表达式）；仍不返回 null，交给上层 500 而不是静默放行
            throw new IllegalStateException("数据隔离默认拒绝条件构建失败", e);
        }
    }

    /**
     * AND 合并原始 where 与隔离条件，<b>左侧必须加括号</b>。
     *
     * <p>SQL 中 AND 优先级高于 OR。若原 where 是 {@code a = 1 OR b = 2}，直接
     * {@code new AndExpression(where, 隔离)} 序列化为 {@code a = 1 OR b = 2 AND (隔离)}，
     * 按优先级等价于 {@code a = 1 OR (b = 2 AND 隔离)}——命中 {@code a = 1} 的行会绕过隔离。
     * MP 自家的 {@code BaseMultiTableInnerInterceptor} 正是为此把左侧包进 Parenthesis。</p>
     *
     * <p>当前带 {@code @CollegeScope} 的语句顶层都是 {@code X = ? AND deleted = 0}（AND 可结合，
     * 语义正确），所以这是尚未被触发的隐患；一旦有人给带 OR 的语句加上注解，隔离就会静默失效。</p>
     */
    private Expression andWith(Expression where, Expression condition) {
        if (where == null) {
            return condition;
        }
        return new AndExpression(new Parenthesis(where), condition);
    }

    /**
     * 追加一个并集条件（多角色 OR 连接）。
     *
     * <p>首个条件不加 " OR " 前缀——此前按调用点传入的 any 作为 first 判定，导致首个条件
     * 被写成 " OR x = y"，{@code parseCondExpression} 必然失败并静默返回 null（隔离条件失效、
     * 每条语句刷 ERROR 日志）。改为按缓冲区是否为空判定前缀。</p>
     */
    private void append(StringBuilder sb, String condition) {
        if (sb.length() > 0) {
            sb.append(" OR ");
        }
        sb.append(condition);
    }

    private CollegeScope resolveAnnotation(String mappedStatementId) {
        return annotationCache.computeIfAbsent(mappedStatementId, id -> {
            int lastDot = id.lastIndexOf('.');
            if (lastDot < 0) {
                return null;
            }
            String className = id.substring(0, lastDot);
            String methodName = id.substring(lastDot + 1);
            try {
                Class<?> mapperType = Class.forName(className);
                for (Method method : mapperType.getDeclaredMethods()) {
                    if (method.getName().equals(methodName) && method.isAnnotationPresent(CollegeScope.class)) {
                        return method.getAnnotation(CollegeScope.class);
                    }
                }
            } catch (ClassNotFoundException e) {
                log.warn("数据隔离注解解析失败（类不存在）: {}", className);
            }
            return null;
        });
    }
}
