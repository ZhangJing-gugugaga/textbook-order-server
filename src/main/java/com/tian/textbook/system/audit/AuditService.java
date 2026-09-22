package com.tian.textbook.system.audit;

import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.util.SqlLike;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.util.IpUtils;
import com.tian.textbook.system.entity.AuditLog;
import com.tian.textbook.system.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 审计服务（PRD 模块 9 / 03 §10.2：登录、导出、账号操作、窗口变更、学期切换、审批、复核、
 * 配置变更、供货商导出全记录；只写不改，不含密码/token）。
 *
 * <p>{@link #record} 参与调用方事务（审批/切换/复核等关键动作与业务操作同事务）；
 * {@link #recordIndependent} 用于切面/无事务上下文场景（REQUIRES_NEW 独立提交）。</p>
 */
@Slf4j
@Service
public class AuditService {

    /** 动作令牌（audit_log.action） */
    public static final String LOGIN = "LOGIN";
    public static final String LOGOUT = "LOGOUT";
    public static final String EXPORT = "EXPORT";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String WINDOW = "WINDOW";
    public static final String SEMESTER_SWITCH = "SEMESTER_SWITCH";
    public static final String REVIEW = "REVIEW";
    public static final String CHANGE = "CHANGE";
    public static final String CONFIG = "CONFIG";
    public static final String IMPORT = "IMPORT";
    public static final String NOTICE = "NOTICE";

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 审计查询（W24：按操作者/动作/资源/时间过滤，at DESC），DB 侧分页。
     *
     * <p>Controller 不直连 Mapper（SPEC §2 分层约束），查询收敛在 Service。
     * 分页下推到 SQL：审计表只增不减，全量取出再内存截断会形成内存尖峰。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLog> query(Long userId, String userNo, String action, String resource,
                                        java.time.LocalDateTime startAt, java.time.LocalDateTime endAt,
                                        long page, long size) {
        long safePage = PageResponse.normalizePage(page);
        long safeSize = PageResponse.normalizeSize(size);
        long offset = (safePage - 1) * safeSize;
        // XML 已写 ESCAPE '|'，此处同步转义（否则输入 % 退化为全表扫描）
        String kwUserNo = SqlLike.escape(userNo);
        List<AuditLog> list = auditLogMapper.selectByFilter(
                userId, kwUserNo, action, resource, startAt, endAt, offset, safeSize);
        long total = auditLogMapper.countByFilter(userId, kwUserNo, action, resource, startAt, endAt);
        return PageResponse.of(list, safePage, safeSize, total);
    }

    /**
     * 与业务操作同事务（关键动作：审批/切换/复核/配置/账号/窗口变更）。
     *
     * <p>审计写入失败<b>不再吞掉</b>：调用点（如 TeacherOrderService.review）的契约是
     * 「更新 order_form 与写 audit_log 同事务」，若在此吞异常，审批会照常提交而合规记录
     * 静默缺失——事后无法证明谁批过。异常向上抛出即触发整个业务事务回滚。</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String resource, String resourceId, Map<String, Object> detail) {
        write(action, resource, resourceId, detail, true);
    }

    /**
     * 独立事务（@AuditLog 切面使用：标注方法无外层事务或只读场景）。
     *
     * <p>切面场景下审计是旁路观测，失败只告警不阻断业务调用。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String action, String resource, String resourceId, Map<String, Object> detail) {
        write(action, resource, resourceId, detail, false);
    }

    private void write(String action, String resource, String resourceId, Map<String, Object> detail,
                       boolean failFast) {
        try {
            AuditLog log = new AuditLog();
            var user = SecurityUtils.currentUser();
            if (user != null) {
                log.setUserId(user.userId());
                log.setUserNo(user.userNo());
            }
            log.setAction(action);
            log.setResource(resource);
            log.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
            log.setDetailJson(detail);
            log.setIp(IpUtils.currentIp());
            auditLogMapper.insert(log);
        } catch (RuntimeException e) {
            if (failFast) {
                // 与业务同事务：让异常传播，业务操作一并回滚（宁可拒绝操作，不可无留痕）
                log.error("审计写入失败，业务操作已回滚: action={}, resource={}", action, resource, e);
                throw e;
            }
            log.warn("审计写入失败（旁路，不阻断业务）: action={}, resource={}", action, resource, e);
        }
    }
}
