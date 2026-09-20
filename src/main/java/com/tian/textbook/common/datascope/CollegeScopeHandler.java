package com.tian.textbook.common.datascope;

import com.baomidou.mybatisplus.extension.plugins.handler.DataPermissionHandler;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.common.semester.SemesterContextHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
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

    @Override
    public Expression getSqlSegment(Expression where, String mappedStatementId) {
        CollegeScope scope = resolveAnnotation(mappedStatementId);
        if (scope == null) {
            return null;
        }
        CurrentUser user = SecurityUtils.currentUser();
        if (user == null) {
            // 未登录（如定时任务直接调 Mapper 的场景）不追加条件，由 Service 层保证
            return null;
        }
        if (user.isAdmin()) {
            return null; // ADMIN 全部数据
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (user.hasRole("SECRETARY") && !scope.secretaryColumn().isBlank()) {
            Long collegeId = SemesterContextHolder.get() == null ? null
                    : collegeResolver.collegeIdOf(user.userId(), SemesterContextHolder.get());
            any = true;
            append(sb, any, collegeId == null
                    ? "1 = 0" // 无学院归属 → 查不到任何数据
                    : scope.secretaryColumn() + " = " + collegeId);
        }
        if (user.hasRole("TEACHER") && !scope.teacherColumn().isBlank()) {
            any = append(sb, any, scope.teacherColumn() + " = " + user.userId());
        }
        if (user.hasRole("STUDENT") && !scope.studentColumn().isBlank()) {
            any = append(sb, any, scope.studentColumn() + " = " + user.userId());
        }
        if (!scope.userColumn().isBlank()) {
            any = append(sb, any, scope.userColumn() + " = " + user.userId());
        }
        if (!any) {
            return null;
        }
        try {
            return CCJSqlParserUtil.parseCondExpression("(" + sb + ")");
        } catch (Exception e) {
            log.error("构建数据隔离条件失败: {}", sb, e);
            return null;
        }
    }

    private boolean append(StringBuilder sb, boolean first, String condition) {
        if (!first) {
            sb.append(" OR ");
        }
        sb.append(condition);
        return true;
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
