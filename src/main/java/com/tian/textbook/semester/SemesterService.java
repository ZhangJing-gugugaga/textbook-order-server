package com.tian.textbook.semester;

import com.tian.textbook.common.util.AppTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.notify.WindowChangeNotifier;
import com.tian.textbook.semester.dto.SemesterActivateRequest;
import com.tian.textbook.semester.dto.SemesterCreateRequest;
import com.tian.textbook.semester.dto.SemesterUpdateRequest;
import com.tian.textbook.semester.dto.WindowExtendRequest;
import com.tian.textbook.semester.dto.WindowSetRequest;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.AuditLog;
import com.tian.textbook.system.mapper.AuditLogMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 学期与窗口引擎（PRD 模块 3/4 · SPEC §6/§7）。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>window_status 落库为唯一真源（W11），定时扫描与手动操作都写它；</li>
 *   <li>窗口变更与双缓冲切换共用同一把进程内 ReentrantLock 串行化（R12）；</li>
 *   <li>双缓冲切换 = 单事务两条 UPDATE + version 乐观锁 + DB 唯一约束兜底（uk_semester_active）；</li>
 *   <li>每次窗口变更写审计并自动创建/合并系统通知任务。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemesterService {

    /** 窗口变更与学期切换串行化（SPEC §6/§7：进程内锁 + version 乐观校验） */
    public static final ReentrantLock SEMESTER_LOCK = new ReentrantLock();

    private final SemesterMapper semesterMapper;
    private final SysUserMapper userMapper;
    private final AuditService auditService;
    private final AuditLogMapper auditLogMapper;
    private final WindowChangeNotifier windowChangeNotifier;
    private final SemesterActiveService activeSemesterService;

    // ============ 学期基本管理 ============

    @Transactional
    public Semester create(SemesterCreateRequest request) {
        validateWindowRange(request.windowStart(), request.windowEnd());
        Semester semester = new Semester();
        semester.setName(request.name().trim());
        semester.setStartDate(request.startDate());
        semester.setEndDate(request.endDate());
        semester.setWindowStart(request.windowStart());
        semester.setWindowEnd(request.windowEnd());
        semester.setAutoOpen(request.autoOpen() == null ? 1 : request.autoOpen());
        semester.setAutoClose(request.autoClose() == null ? 1 : request.autoClose());
        semester.setChannelOpen(0);
        semester.setWindowStatus("not_open");
        semester.setActiveStatus("draft");
        semester.setVersion(0);
        semester.setDeleted(0L);
        try {
            semesterMapper.insert(semester);
        } catch (Exception e) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "学期名称已存在");
        }
        auditService.record(AuditService.SEMESTER_SWITCH, "semester", String.valueOf(semester.getId()),
                Map.of("op", "create", "name", semester.getName()));
        return semester;
    }

    @Transactional(readOnly = true)
    public List<Semester> list() {
        return semesterMapper.selectList(Wrappers.<Semester>lambdaQuery()
                .eq(Semester::getDeleted, 0)
                .orderByDesc(Semester::getId));
    }

    @Transactional(readOnly = true)
    public Semester get(Long id) {
        Semester semester = semesterMapper.selectByIdSoft(id);
        if (semester == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
        }
        return semester;
    }

    @Transactional
    public Semester updateBasic(Long id, SemesterUpdateRequest request) {
        Semester semester = get(id);
        if (request.windowStart() != null || request.windowEnd() != null) {
            validateWindowRange(request.windowStart() != null ? request.windowStart() : semester.getWindowStart(),
                    request.windowEnd() != null ? request.windowEnd() : semester.getWindowEnd());
        }
        if (request.name() != null && !request.name().isBlank()) {
            semester.setName(request.name().trim());
        }
        if (request.startDate() != null) {
            semester.setStartDate(request.startDate());
        }
        if (request.endDate() != null) {
            semester.setEndDate(request.endDate());
        }
        if (request.windowStart() != null) {
            semester.setWindowStart(request.windowStart());
        }
        if (request.windowEnd() != null) {
            semester.setWindowEnd(request.windowEnd());
        }
        if (request.autoOpen() != null) {
            semester.setAutoOpen(request.autoOpen());
        }
        if (request.autoClose() != null) {
            semester.setAutoClose(request.autoClose());
        }
        semesterMapper.updateById(semester);
        return semester;
    }

    // ============ 双缓冲原子切换（W1/W6，SPEC §7） ============

    /**
     * draft → active 原子切换：单事务内 归档旧 active → 激活目标（version 乐观锁）→
     * 由 user_semester_profile 同步 sys_user 归属冗余列 → 写审计。
     *
     * <p>失败（version 冲突 / uk_semester_active 唯一约束命中）→ 回滚 + 409。</p>
     */
    @Transactional
    public Semester activate(Long id, SemesterActivateRequest request) {
        SEMESTER_LOCK.lock();
        try {
            Semester target = semesterMapper.selectByIdSoft(id);
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
            }
            if (!"draft".equals(target.getActiveStatus())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "仅 draft 学期可激活");
            }
            if (!target.getVersion().equals(request.version())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            Semester old = semesterMapper.selectActive();
            if (old != null) {
                if (old.getId().equals(target.getId())) {
                    throw new BizException(ErrorCode.STATE_CONFLICT, "该学期已是激活学期");
                }
                int archived = semesterMapper.archiveIfActive(old.getId());
                if (archived == 0) {
                    throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
                }
            }
            int activated = semesterMapper.activateIfDraft(target.getId(), target.getVersion());
            if (activated == 0) {
                // version 冲突或 uk_semester_active 唯一约束兜底
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            // ⑤ 由 profile 同步 sys_user 归属冗余列（新学期无 profile 的用户置空）
            userMapper.syncCollegeClassFromProfile(target.getId());
            // ⑥ 审计
            auditService.record(AuditService.SEMESTER_SWITCH, "semester", String.valueOf(target.getId()),
                    Map.of("op", "activate", "name", target.getName(),
                            "archivedSemesterId", old == null ? "" : String.valueOf(old.getId())));
            activeSemesterService.evict();
            log.info("双缓冲切换完成: {} (draft→active), 旧学期归档: {}", target.getName(),
                    old == null ? "无" : old.getId());
            return semesterMapper.selectByIdSoft(target.getId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // uk_semester_active 唯一约束命中等
            log.warn("学期切换失败: {}", e.getMessage());
            throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
        } finally {
            SEMESTER_LOCK.unlock();
        }
    }

    /** 手动归档（仅 active 学期；归档后数据只读保留，可查可导，D2-A） */
    @Transactional
    public void archive(Long id) {
        SEMESTER_LOCK.lock();
        try {
            Semester semester = get(id);
            if (!"active".equals(semester.getActiveStatus())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "仅 active 学期可归档");
            }
            int archived = semesterMapper.archiveIfActive(id);
            if (archived == 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            auditService.record(AuditService.SEMESTER_SWITCH, "semester", String.valueOf(id),
                    Map.of("op", "archive", "name", semester.getName()));
            activeSemesterService.evict();
        } finally {
            SEMESTER_LOCK.unlock();
        }
    }

    // ============ 窗口引擎（W11，SPEC §6） ============

    /** 设置起止 + auto 开关 */
    @Transactional
    public Semester setWindow(Long id, WindowSetRequest request) {
        validateWindowRange(request.windowStart(), request.windowEnd());
        return mutateWindow(id, semester -> {
            Map<String, Object> before = windowSnapshot(semester);
            semester.setWindowStart(request.windowStart());
            semester.setWindowEnd(request.windowEnd());
            if (request.autoOpen() != null) {
                semester.setAutoOpen(request.autoOpen());
            }
            if (request.autoClose() != null) {
                semester.setAutoClose(request.autoClose());
            }
            return before;
        }, "设置窗口起止时间");
    }

    /** 手动开启（channel_open=1 + window_status=open） */
    @Transactional
    public Semester openWindow(Long id) {
        return mutateWindow(id, semester -> {
            Map<String, Object> before = windowSnapshot(semester);
            if (semester.getWindowStart() == null || semester.getWindowEnd() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请先设置窗口起止时间");
            }
            semester.setChannelOpen(1);
            semester.setWindowStatus("open");
            return before;
        }, "手动开启窗口");
    }

    /** 提前截止（channel_open=0 + window_status=closed） */
    @Transactional
    public Semester closeWindow(Long id) {
        return mutateWindow(id, semester -> {
            Map<String, Object> before = windowSnapshot(semester);
            semester.setChannelOpen(0);
            semester.setWindowStatus("closed");
            return before;
        }, "提前截止");
    }

    /**
     * 延长（无限次）：更新 window_end；若已 closed 则同时 channel_open=1 + window_status=open。
     * 延长早于当前时间被校验拦截。
     */
    @Transactional
    public Semester extendWindow(Long id, WindowExtendRequest request) {
        LocalDateTime now = AppTime.now();
        if (request.windowEnd() == null || !request.windowEnd().isAfter(now)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "延长后的截止时间必须晚于当前时间");
        }
        return mutateWindow(id, semester -> {
            Map<String, Object> before = windowSnapshot(semester);
            if (semester.getWindowStart() != null && !request.windowEnd().isAfter(semester.getWindowStart())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "截止时间必须晚于开始时间");
            }
            semester.setWindowEnd(request.windowEnd());
            if ("closed".equals(semester.getWindowStatus())) {
                semester.setChannelOpen(1);
                semester.setWindowStatus("open");
            }
            return before;
        }, "延长征订窗口至 " + request.windowEnd());
    }

    /**
     * 窗口变更统一入口：持进程内锁 → version 乐观校验更新 → 审计 → 自动通知。
     * 返回更新后的学期。
     */
    private Semester mutateWindow(Long id, java.util.function.Function<Semester, Map<String, Object>> mutator,
                                  String changeDesc) {
        SEMESTER_LOCK.lock();
        try {
            Semester semester = semesterMapper.selectByIdSoft(id);
            if (semester == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
            }
            int expectedVersion = semester.getVersion();
            Map<String, Object> before = mutator.apply(semester);
            Map<String, Object> after = windowSnapshot(semester);
            int rows = semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                    .eq(Semester::getId, id)
                    .eq(Semester::getVersion, expectedVersion)
                    .set(Semester::getWindowStart, semester.getWindowStart())
                    .set(Semester::getWindowEnd, semester.getWindowEnd())
                    .set(Semester::getChannelOpen, semester.getChannelOpen())
                    .set(Semester::getAutoOpen, semester.getAutoOpen())
                    .set(Semester::getAutoClose, semester.getAutoClose())
                    .set(Semester::getWindowStatus, semester.getWindowStatus())
                    .set(Semester::getVersion, expectedVersion + 1));
            if (rows == 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的窗口配置，请刷新后重试");
            }
            // 审计（谁/何时/原值→新值，PRD 模块 3）
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("semesterId", id);
            detail.put("change", changeDesc);
            detail.put("before", before);
            detail.put("after", after);
            auditService.record(AuditService.WINDOW, "window", String.valueOf(id), detail);
            // 自动创建/合并系统通知任务（SPEC §6）
            windowChangeNotifier.onWindowChange(id, "征订窗口变更通知",
                    changeDesc + "。新的窗口截止时间：" + semester.getWindowEnd() + "，请尽快提交。");
            activeSemesterService.evict();
            return semesterMapper.selectByIdSoft(id);
        } finally {
            SEMESTER_LOCK.unlock();
        }
    }

    /** 定时任务自动开/关（幂等：状态已变则跳过；auto_* 关闭时不动） */
    @Transactional
    public void applyAutoTransition(Long id, String targetStatus, boolean channelOpen, String changeDesc) {
        Semester semester = semesterMapper.selectByIdSoft(id);
        if (semester == null) {
            return;
        }
        int expectedVersion = semester.getVersion();
        int rows = semesterMapper.update(null, Wrappers.<Semester>lambdaUpdate()
                .eq(Semester::getId, id)
                .eq(Semester::getVersion, expectedVersion)
                .eq(Semester::getWindowStatus, "open".equals(targetStatus) ? "not_open" : "open")
                .set(Semester::getWindowStatus, targetStatus)
                .set(Semester::getChannelOpen, channelOpen ? 1 : 0)
                .set(Semester::getVersion, expectedVersion + 1));
        if (rows == 0) {
            return; // 状态已变 → 幂等跳过
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("semesterId", id);
        detail.put("change", changeDesc);
        detail.put("after", windowSnapshot(semesterMapper.selectByIdSoft(id)));
        auditService.record(AuditService.WINDOW, "window", String.valueOf(id), detail);
        Semester updated = semesterMapper.selectByIdSoft(id);
        windowChangeNotifier.onWindowChange(id, "征订窗口变更通知",
                changeDesc + "。新的窗口截止时间：" + updated.getWindowEnd() + "，请尽快提交。");
        activeSemesterService.evict();
    }

    /**
     * 窗口变更记录分页（谁/何时/原值→新值，W24）。
     *
     * <p>分页参数经 {@link PageResponse} 归一化：此前只做上限、未做下限，
     * {@code size<=0} 会生成 {@code LIMIT 0} 或负值（后者直接 SQL 异常 → 500）。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLog> windowChanges(Long id, long page, long size) {
        long safePage = PageResponse.normalizePage(page);
        long safeSize = PageResponse.normalizeSize(size);
        long offset = (safePage - 1) * safeSize;
        List<AuditLog> list = auditLogMapper.selectByResource("window", String.valueOf(id), offset, safeSize);
        long total = auditLogMapper.countByResource("window", String.valueOf(id));
        return PageResponse.of(list, safePage, safeSize, total);
    }

    private Map<String, Object> windowSnapshot(Semester s) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("windowStart", s.getWindowStart());
        snapshot.put("windowEnd", s.getWindowEnd());
        snapshot.put("channelOpen", s.getChannelOpen());
        snapshot.put("autoOpen", s.getAutoOpen());
        snapshot.put("autoClose", s.getAutoClose());
        snapshot.put("windowStatus", s.getWindowStatus());
        return snapshot;
    }

    private void validateWindowRange(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "窗口开始时间必须早于截止时间");
        }
    }
}
