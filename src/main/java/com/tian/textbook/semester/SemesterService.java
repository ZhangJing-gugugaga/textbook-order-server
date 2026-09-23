package com.tian.textbook.semester;

import com.tian.textbook.common.util.AppTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.notify.WindowChangeNotifier;
import com.tian.textbook.semester.dto.SemesterActivateRequest;
import com.tian.textbook.semester.dto.SemesterArchiveRequest;
import com.tian.textbook.semester.dto.SemesterCreateRequest;
import com.tian.textbook.semester.dto.SemesterUnarchiveRequest;
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

    /** 窗口状态：已截止（与 SemesterMapper 的 window_status 取值一致） */
    private static final String CLOSED = "closed";

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
        // 只写请求里出现的字段（定向 UPDATE），不用 updateById 回写整实体：
        // 实体是「读出来的旧快照」，整实体回写会把 window_status / channel_open / active_status /
        // version 一并按旧值写回。两个真实后果：① 管理员编辑基本信息期间窗口到点自动截止 →
        // 提交后窗口被静默重新打开且 version 回退；② 编辑 draft 学期期间它被激活 → 提交后
        // 写回 draft，系统变成「无 active 学期」，全站业务接口报「尚未激活任何学期」。
        var update = Wrappers.<Semester>lambdaUpdate().eq(Semester::getId, id);
        if (request.name() != null && !request.name().isBlank()) {
            update.set(Semester::getName, request.name().trim());
        }
        if (request.startDate() != null) {
            update.set(Semester::getStartDate, request.startDate());
        }
        if (request.endDate() != null) {
            update.set(Semester::getEndDate, request.endDate());
        }
        if (request.windowStart() != null) {
            update.set(Semester::getWindowStart, request.windowStart());
        }
        if (request.windowEnd() != null) {
            update.set(Semester::getWindowEnd, request.windowEnd());
        }
        if (request.autoOpen() != null) {
            update.set(Semester::getAutoOpen, request.autoOpen());
        }
        if (request.autoClose() != null) {
            update.set(Semester::getAutoClose, request.autoClose());
        }
        semesterMapper.update(null, update);
        return semesterMapper.selectByIdSoft(id);
    }

    // ============ 双缓冲原子切换（W1/W6，SPEC §7） ============

    /**
     * draft → active 原子切换：单事务内 归档旧 active → 激活目标（version 乐观锁）→
     * 由 user_semester_profile 同步 sys_user 归属冗余列 → 写审计。
     *
     * <p>失败（version 冲突 / uk_semester_active 唯一约束命中）→ 回滚 + 409。</p>
     *
     * <p>校验顺序为「先状态、后参数」：Bean Validation 在方法调用前执行，若 version 用
     * {@code @NotNull} 声明，则「重复 activate 已在 active 的学期」会返回 400 参数错误，
     * 而契约（API.md §3.2）规定该场景是 409 状态冲突——联调据此判为偏差。此处按状态门禁
     * → version 必填 → version 匹配的顺序判定，保证「状态冲突优先于参数错误」。</p>
     */
    @Transactional
    public Semester activate(Long id, SemesterActivateRequest request) {
        SEMESTER_LOCK.lock();
        try {
            Semester target = semesterMapper.selectByIdSoft(id);
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
            }
            if ("archived".equals(target.getActiveStatus())) {
                // 归档不可逆：没有「取消归档 / 回退切换」接口，归档学期不能直接回到 active。
                // 唯一的回退路径是受限的 unarchive（要求当前无 active 学期），文案一并说明。
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "该学期已归档，不能再次激活；如需回退请使用「撤销归档」（要求当前没有激活学期）");
            }
            if (!"draft".equals(target.getActiveStatus())) {
                // active：重复激活 → 409（此前落到 version 校验，空 body 时被 400 掩盖）
                throw new BizException(ErrorCode.STATE_CONFLICT, "该学期已是激活学期，无需重复激活");
            }
            if (request == null || request.version() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "version 不能为空：请先读取学期最新状态（GET /api/admin/semester/{id}）后重试");
            }
            if (!target.getVersion().equals(request.version())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            Semester old = semesterMapper.selectActive();
            if (old != null) {
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

    /**
     * 手动归档（仅 active 学期；归档后数据只读保留，可查可导，D2-A）。
     *
     * <p>**二次门禁（B11 生产缺陷修复）**：归档会立刻把全站征订业务停下（没有 active 学期后，
     * 学生选购/教师填报/导出统一报「当前没有激活学期，请先创建并激活学期」），且不可逆。
     * 原实现不读 body、无任何确认，线上一次空 body 调用即把进行中的学期归档，只能整库恢复。
     * 现在要求：</p>
     * <ol>
     *   <li>{@code version} 必填（乐观锁）：拒绝「读到旧状态后按旧认知归档」；</li>
     *   <li>窗口进行中（{@code window_status=open} 或 {@code channel_open=1}）时必须
     *       {@code confirmWindowOpen=true}：否则 409 并说明影响，由前端强确认后重试。</li>
     * </ol>
     *
     * <p>校验顺序为「先状态、后参数、再窗口确认」：重复归档/归档非 active 学期的语义是
     * 409 状态冲突，不能被 400 参数错误掩盖。</p>
     */
    @Transactional
    public void archive(Long id, SemesterArchiveRequest request) {
        SEMESTER_LOCK.lock();
        try {
            Semester semester = get(id);
            if (!"active".equals(semester.getActiveStatus())) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "仅 active 学期可归档；当前状态：" + semester.getActiveStatus()
                                + "（归档不可逆，撤销归档仅对误归档的 active 学期开放）");
            }
            Integer version = request == null ? null : request.version();
            if (version == null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "version 不能为空：请先读取学期最新状态（GET /api/admin/semester/{id}）后重试");
            }
            if (!semester.getVersion().equals(version)) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            if (windowLive(semester) && !Boolean.TRUE.equals(request.confirmWindowOpen())) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "该学期征订窗口仍在进行中（" + windowDescription(semester) + "）：归档会立即停止"
                                + "全站征订业务（学生选购、教师填报、导出将统一报「当前没有激活学期」），"
                                + "且归档不可逆。请先关闭窗口再归档，或在前端强确认后带 "
                                + "confirmWindowOpen=true 重新提交");
            }
            int archived = semesterMapper.archiveIfActive(id);
            if (archived == 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("op", "archive");
            detail.put("name", semester.getName());
            detail.put("windowStatus", semester.getWindowStatus());
            detail.put("channelOpen", semester.getChannelOpen());
            detail.put("windowStart", semester.getWindowStart());
            detail.put("windowEnd", semester.getWindowEnd());
            detail.put("confirmWindowOpen", Boolean.TRUE.equals(request.confirmWindowOpen()));
            auditService.record(AuditService.SEMESTER_SWITCH, "semester", String.valueOf(id), detail);
            if (windowLive(semester)) {
                log.warn("进行中窗口的学期被显式确认归档: id={}, name={}, 窗口={} ~ {}",
                        id, semester.getName(), semester.getWindowStart(), semester.getWindowEnd());
            }
            // BE-5d：通知记录迁入历史表（独立事务、分批；失败不影响归档结果，可重跑）
            archiveNoticeRecordsQuietly(id);
            activeSemesterService.evict();
        } finally {
            SEMESTER_LOCK.unlock();
        }
    }

    /**
     * 撤销归档（受限回滚，B15）：把误归档的学期恢复为 active。
     *
     * <p>只在「当前没有任何 active 学期」时允许——这正是误归档（或误操作）后的现场；
     * 若已有 active 学期，说明归档是双缓冲切换的正常结果，回滚会造成两个 active 或静默归档
     * 新学期，因此拒绝并提示先归档当前 active 学期。</p>
     *
     * <p>回滚不恢复窗口：归档时窗口已被强制关闭（{@code channel_open=0 / window_status=closed}），
     * 恢复后保持关闭，由管理员显式重新开启，避免一次回滚就把对外填报通道打开。</p>
     */
    @Transactional
    public Semester unarchive(Long id, SemesterUnarchiveRequest request) {
        SEMESTER_LOCK.lock();
        try {
            Semester semester = get(id);
            if (!"archived".equals(semester.getActiveStatus())) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "仅已归档（archived）学期可撤销归档；当前状态：" + semester.getActiveStatus());
            }
            Semester active = semesterMapper.selectActive();
            if (active != null) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "当前已有激活学期（" + active.getName() + "）：请先归档它，再撤销归档本学期的归档状态"
                                + "（同一时刻仅允许一个 active 学期）");
            }
            if (request == null || !Boolean.TRUE.equals(request.confirm())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "撤销归档需显式确认：请在请求体传 confirm=true（该操作会把学期恢复为 active，"
                                + "全站业务随即恢复可用）");
            }
            if (request.version() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "version 不能为空：请先读取学期最新状态（GET /api/admin/semester/{id}）后重试");
            }
            if (!semester.getVersion().equals(request.version())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            int restored = semesterMapper.unarchiveIfArchived(id, semester.getVersion());
            if (restored == 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的学期状态，请刷新");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("op", "unarchive");
            detail.put("name", semester.getName());
            detail.put("windowStatus", semester.getWindowStatus());
            detail.put("channelOpen", semester.getChannelOpen());
            detail.put("note", "撤销归档（受限回滚）；窗口保持关闭，需手动重新开启");
            auditService.record(AuditService.SEMESTER_SWITCH, "semester", String.valueOf(id), detail);
            activeSemesterService.evict();
            log.warn("学期撤销归档（受限回滚）: id={}, name={}；窗口仍为 closed，需手动重新开启",
                    id, semester.getName());
            return semesterMapper.selectByIdSoft(id);
        } finally {
            SEMESTER_LOCK.unlock();
        }
    }

    /**
     * 归档后把该学期的通知记录迁入历史表（BE-5d）。
     *
     * <p>独立事务（NotifyService 侧 REQUIRES_NEW）且吞异常：迁移失败只告警，不回滚归档
     * （归档是状态切换，数据迁移可事后重跑，幂等靠 {@code uk_history_record}）。</p>
     */
    private void archiveNoticeRecordsQuietly(Long semesterId) {
        try {
            long rows = windowChangeNotifier.archiveSemesterRecords(semesterId);
            log.info("学期归档：通知记录迁移 {} 行（semesterId={}）", rows, semesterId);
        } catch (Exception e) {
            log.warn("学期归档：通知记录迁移失败（可重跑，不影响归档结果）semesterId={}, err={}",
                    semesterId, e.getMessage());
        }
    }

    /** 窗口是否处于「进行中」：window_status=open 或 channel_open=1（任一为真即视为对外通道开启）。 */
    private boolean windowLive(Semester semester) {
        return Integer.valueOf(1).equals(semester.getChannelOpen())
                || "open".equals(semester.getWindowStatus());
    }

    private String windowDescription(Semester semester) {
        return "window_status=" + semester.getWindowStatus() + "，channel_open=" + semester.getChannelOpen()
                + "，窗口 " + semester.getWindowStart() + " ~ " + semester.getWindowEnd();
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
            // 窗口关闭 → 关闭该学期 active 通知任务（BE-5a：征订结束即不再要求确认，也不再重发）；
            // 其余变更（开启/延长/设置）→ 自动创建/合并系统通知任务（SPEC §6）
            if (CLOSED.equals(semester.getWindowStatus())) {
                windowChangeNotifier.onWindowClosed(id, changeDesc);
            } else {
                windowChangeNotifier.onWindowChange(id, "征订窗口变更通知",
                        changeDesc + "。新的窗口截止时间：" + semester.getWindowEnd() + "，请尽快提交。");
            }
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
        // 自动截止 → 关闭 active 任务；自动开启 → 合并/创建通知任务（BE-5a）
        if (CLOSED.equals(targetStatus)) {
            windowChangeNotifier.onWindowClosed(id, changeDesc);
        } else {
            windowChangeNotifier.onWindowChange(id, "征订窗口变更通知",
                    changeDesc + "。新的窗口截止时间：" + updated.getWindowEnd() + "，请尽快提交。");
        }
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
