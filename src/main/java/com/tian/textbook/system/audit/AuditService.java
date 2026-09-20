package com.tian.textbook.system.audit;

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
     * 审计查询（W24：按操作者/动作/资源/时间过滤，at DESC；分页由调用方截断）。
     *
     * <p>Controller 不直连 Mapper（SPEC §2 分层约束），查询收敛在 Service。</p>
     */
    @Transactional(readOnly = true)
    public List<AuditLog> query(Long userId, String userNo, String action, String resource,
                                java.time.LocalDateTime startAt, java.time.LocalDateTime endAt) {
        return auditLogMapper.selectByFilter(userId, userNo, action, resource, startAt, endAt);
    }

    /** 与业务操作同事务（关键动作：审批/切换/复核/配置/账号/窗口变更）。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String resource, String resourceId, Map<String, Object> detail) {
        write(action, resource, resourceId, detail);
    }

    /** 独立事务（@AuditLog 切面使用：标注方法无外层事务或只读场景）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String action, String resource, String resourceId, Map<String, Object> detail) {
        write(action, resource, resourceId, detail);
    }

    private void write(String action, String resource, String resourceId, Map<String, Object> detail) {
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
        } catch (Exception e) {
            // 审计失败不阻断业务（关键动作已由同事务保证）；此处兜底记录
            log.warn("审计写入失败: action={}, resource={}", action, resource, e);
        }
    }
}
