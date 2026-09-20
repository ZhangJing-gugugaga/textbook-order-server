package com.tian.textbook.system.audit;

import com.tian.textbook.common.annotation.AuditLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计切面（SPEC §2：system 模块审计切面）。
 *
 * <p>仅拦截标注 {@link AuditLog} 的方法，成功后独立事务写审计（失败不写、异常原样抛出）。
 * 与业务同事务的关键动作（审批/切换/复核/配置）由 Service 内显式调用
 * {@link AuditService#record} 完成，不使用本切面。</p>
 */
@Aspect
@Component
public class AuditLogAspect {

    private final AuditService auditService;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        Object result = pjp.proceed();
        String resourceId = resolveResourceId(pjp, auditLog);
        String resource = auditLog.resource().isBlank()
                ? ((MethodSignature) pjp.getSignature()).getMethod().getName()
                : auditLog.resource();
        Map<String, Object> detail = new LinkedHashMap<>();
        if (auditLog.recordArgs()) {
            String[] names = parameterNameDiscoverer.getParameterNames(((MethodSignature) pjp.getSignature()).getMethod());
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    Object arg = args[i];
                    // 不含密码/token：仅记录可 JSON 化的普通参数摘要
                    if (arg != null && !(arg instanceof String s && (s.length() > 200 || s.toLowerCase().contains("password")))) {
                        detail.put(names[i], arg instanceof String || arg instanceof Number || arg instanceof Boolean ? arg : arg.getClass().getSimpleName());
                    }
                }
            }
        }
        auditService.recordIndependent(auditLog.action(), resource, resourceId, detail.isEmpty() ? null : detail);
        return result;
    }

    private String resolveResourceId(ProceedingJoinPoint pjp, AuditLog auditLog) {
        if (auditLog.resourceIdParam().isBlank()) {
            return null;
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] names = parameterNameDiscoverer.getParameterNames(signature.getMethod());
        Object[] args = pjp.getArgs();
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(auditLog.resourceIdParam()) && args[i] != null) {
                return String.valueOf(args[i]);
            }
        }
        return null;
    }
}
