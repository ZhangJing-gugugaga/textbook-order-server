package com.tian.textbook.notify.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.auth.WxMaClient;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.notify.WindowChangeNotifier;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.util.MapKeys;
import com.tian.textbook.notify.dto.NoticeConfirmRequest;
import com.tian.textbook.notify.dto.NoticeFailureItem;
import com.tian.textbook.notify.dto.NoticeProgressResponse;
import com.tian.textbook.notify.dto.NoticeTaskCreateRequest;
import com.tian.textbook.notify.dto.NoticeTaskListItem;
import com.tian.textbook.notify.dto.UnconfirmedNoticeItem;
import com.tian.textbook.notify.entity.NoticeRecord;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeRecordMapper;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知确认闭环（SPEC §9 · PRD 模块 7 · 03 §7）。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>同学期最多 1 个 active 手动任务（W18），重复创建返回 409 NOTICE_TASK_EXISTS；</li>
 *   <li>窗口变更通知合并进同一 active 任务：追加内容 + 刷新标题 + 逻辑删除未确认的
 *       轮次发送记录以重置轮次计数（W18）；</li>
 *   <li>确认幂等：insertConfirmIfAbsent 首次落库 confirmed_at，重复调用仍成功（SPEC §12）；</li>
 *   <li>round_limit/interval_hours 执行依据 = system_config 当前值（W8），
 *       notice_task 两字段仅创建时快照；</li>
 *   <li>触达如实：sent/unauthorized/failed 按实际结果落库，不做假「已送达」（W5/R10）；
 *       弹窗通道不设轮次上限（Q7），达 round_limit 仅停止订阅消息重发。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService implements WindowChangeNotifier {

    /** 窗口变更通知范围 = 秘书+教师+学生全量（G4/03 §4.2） */
    public static final String WINDOW_CHANGE_TARGET_ROLES = "SECRETARY,TEACHER,STUDENT";

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_WINDOW_CHANGE = "system_window_change";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLOSED = "closed";

    /** 订阅消息跳转页面（小程序通知页） */
    private static final String NOTICE_PAGE = "pages/notice/index";

    /** 微信订阅消息 time 类字段格式 */
    private static final DateTimeFormatter WX_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** system_config 缺省时的兜底值（与 03 §10.1 清单一致） */
    private static final int DEFAULT_ROUND_LIMIT = 5;
    private static final int DEFAULT_INTERVAL_HOURS = 24;

    /** 目标用户批量查询分片（避免超长 IN） */
    private static final int USER_CHUNK = 500;

    private final NoticeTaskMapper noticeTaskMapper;
    private final NoticeRecordMapper noticeRecordMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final ConfigService configService;
    private final AuditService auditService;
    private final WxMaClient wxMaClient;
    private final TextbookProperties textbookProperties;

    // ============ 用户侧：未确认队列 + 确认 ============

    /**
     * 当前用户未确认的 active 任务（阻塞弹窗数据源，按 created_at DESC）。
     *
     * <p>含已达 round_limit 停止订阅重发但未确认的任务（Q7 仍返回）。</p>
     */
    @Transactional(readOnly = true)
    public List<UnconfirmedNoticeItem> listUnconfirmed() {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            return List.of();
        }
        List<NoticeTask> tasks = noticeTaskMapper.selectActiveBySemester(semesterId);
        if (tasks.isEmpty()) {
            return List.of();
        }
        int roundLimit = configService.getInt(ConfigService.NOTICE_ROUND_LIMIT, DEFAULT_ROUND_LIMIT);
        List<UnconfirmedNoticeItem> items = new ArrayList<>();
        for (NoticeTask task : tasks) {
            // 已确认（confirmed_at 首次生效）→ 移出队列
            if (noticeRecordMapper.selectConfirmed(task.getId(), current.userId()) != null) {
                continue;
            }
            UnconfirmedNoticeItem item = new UnconfirmedNoticeItem();
            item.setTaskId(task.getId());
            item.setTitle(task.getTitle());
            item.setContent(task.getContent());
            item.setSource(task.getSource());
            item.setCreatedAt(task.getCreatedAt());
            Integer maxRound = noticeRecordMapper.selectMaxRoundNo(task.getId());
            item.setRoundStopped(maxRound != null && maxRound >= roundLimit);
            items.add(item);
        }
        items.sort(Comparator.comparing(UnconfirmedNoticeItem::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return items;
    }

    /**
     * 确认通知（幂等，SPEC §12）：首次写 confirmed_at，重复调用仍成功。
     *
     * <p>任务不存在/不归属当前 active 学期 → 404；任务已关闭 → 409。
     * subscribeResult（accepted/rejected）为订阅授权上报，openid 由 wx.login
     * code2session 静默收集（03 §3.3），本接口仅记录信号不落库该字段。</p>
     */
    @Transactional
    public void confirm(Long taskId, NoticeConfirmRequest request) {
        NoticeTask task = requireTask(taskId);
        Long semesterId = SemesterContextHolder.get();
        if (semesterId != null && !semesterId.equals(task.getSemesterId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "通知任务不存在");
        }
        if (!STATUS_ACTIVE.equals(task.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "通知任务已关闭，无需确认");
        }
        CurrentUser current = SecurityUtils.requireCurrentUser();
        // 幂等：无确认记录才插入；唯一键 uk_notice_round 兜底，重复调用仍 204
        noticeRecordMapper.insertConfirmIfAbsent(taskId, current.userId());
        if (request != null && request.subscribeResult() != null && log.isInfoEnabled()) {
            log.info("订阅授权上报: task={}, user={}, result={}", taskId, current.userId(), request.subscribeResult());
        }
    }

    // ============ 管理侧：任务列表/创建/关闭/进度/失败名单 ============

    /** active 学期任务列表（按 id DESC，含 closed 历史） */
    @Transactional(readOnly = true)
    public List<NoticeTaskListItem> listTasks() {
        Long semesterId = requireActiveSemesterId();
        return noticeTaskMapper.selectBySemester(semesterId).stream()
                .map(this::toListItem)
                .toList();
    }

    /**
     * 手动创建（同学期仅 1 个 active，W18）：已有 active 任务 → 409 NOTICE_TASK_EXISTS。
     * round_limit/interval_hours 从 system_config 快照（W8）。
     */
    @Transactional
    public NoticeTaskListItem createTask(NoticeTaskCreateRequest request) {
        Long semesterId = requireActiveSemesterId();
        if (!noticeTaskMapper.selectActiveBySemester(semesterId).isEmpty()) {
            throw new BizException(ErrorCode.NOTICE_TASK_EXISTS);
        }
        String targetRoles = request.targetRolesOrDefault();
        validateRoleCodes(targetRoles);
        NoticeTask task = new NoticeTask();
        task.setSemesterId(semesterId);
        task.setTitle(request.title().trim());
        task.setContent(request.content().trim());
        task.setTargetRoles(targetRoles);
        task.setRoundLimit(configService.getInt(ConfigService.NOTICE_ROUND_LIMIT, DEFAULT_ROUND_LIMIT));
        task.setIntervalHours(configService.getInt(ConfigService.NOTICE_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS));
        task.setSource(SOURCE_MANUAL);
        task.setStatus(STATUS_ACTIVE);
        task.setDeleted(0L);
        try {
            noticeTaskMapper.insert(task);
        } catch (DataIntegrityViolationException e) {
            // 并发创建兜底：同学期不允许第二个 active 手动任务
            throw new BizException(ErrorCode.NOTICE_TASK_EXISTS);
        }
        auditService.record(AuditService.NOTICE, "notice_task", String.valueOf(task.getId()),
                Map.of("op", "create", "semesterId", String.valueOf(semesterId),
                        "title", task.getTitle(), "targetRoles", targetRoles));
        log.info("通知任务创建: id={}, semester={}, targetRoles={}", task.getId(), semesterId, targetRoles);
        return toListItem(task);
    }

    /** 手动关闭（status='closed' + closed_by/closed_at + 审计） */
    @Transactional
    public NoticeTaskListItem closeTask(Long id) {
        NoticeTask task = requireTask(id);
        if (!STATUS_ACTIVE.equals(task.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "通知任务已关闭");
        }
        CurrentUser current = SecurityUtils.requireCurrentUser();
        NoticeTask update = new NoticeTask();
        update.setId(task.getId());
        update.setStatus(STATUS_CLOSED);
        update.setClosedBy(current.userId());
        update.setClosedAt(LocalDateTime.now());
        int rows = noticeTaskMapper.update(update, Wrappers.<NoticeTask>lambdaUpdate()
                .eq(NoticeTask::getId, task.getId())
                .eq(NoticeTask::getStatus, STATUS_ACTIVE));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的任务状态，请刷新后重试");
        }
        auditService.record(AuditService.NOTICE, "notice_task", String.valueOf(task.getId()),
                Map.of("op", "close", "semesterId", String.valueOf(task.getSemesterId())));
        log.info("通知任务关闭: id={}, by={}", task.getId(), current.userId());
        return toListItem(noticeTaskMapper.selectByIdSoft(task.getId()));
    }

    /** 发送/确认进度（roundLimit = system_config 当前值，W8） */
    @Transactional(readOnly = true)
    public NoticeProgressResponse taskProgress(Long id) {
        NoticeTask task = requireTask(id);
        NoticeProgressResponse response = new NoticeProgressResponse();
        for (Map<String, Object> row : noticeRecordMapper.countProgressByTask(task.getId())) {
            // H2/MySQL 列标签大小写差异兼容（MapKeys）
            String status = Objects.toString(MapKeys.pick(row, "sendStatus"), null);
            long count = toLong(MapKeys.pick(row, "userCount"));
            switch (status == null ? "" : status) {
                case "sent" -> response.setSent(count);
                case "unauthorized" -> response.setUnauthorized(count);
                case "failed" -> response.setFailed(count);
                case "confirmed" -> response.setConfirmed(count);
                default -> log.warn("未知发送状态: task={}, status={}", task.getId(), status);
            }
        }
        response.setRoundLimit(configService.getInt(ConfigService.NOTICE_ROUND_LIMIT, DEFAULT_ROUND_LIMIT));
        return response;
    }

    /** 未授权/失败名单分页（线下兜底，W5/R10） */
    @Transactional(readOnly = true)
    public PageResponse<NoticeFailureItem> taskFailures(Long id, long page, long size) {
        NoticeTask task = requireTask(id);
        long safeSize = Math.min(Math.max(size, 1), 200);
        long safePage = Math.max(page, 1);
        long offset = (safePage - 1) * safeSize;
        // 学院/班级归属按任务所属学期关联（任务恒为 active 学期创建，两者一致）
        Long semesterId = SemesterContextHolder.get() != null ? SemesterContextHolder.get() : task.getSemesterId();
        List<NoticeFailureItem> items = noticeRecordMapper
                .selectFailures(task.getId(), semesterId, offset, safeSize).stream()
                .map(this::toFailureItem)
                .toList();
        long total = noticeRecordMapper.countFailures(task.getId(), semesterId);
        return PageResponse.of(items, safePage, safeSize, total);
    }

    // ============ 窗口变更自动通知（SPEC §6/§9：合并进 active 任务，W18） ============

    /**
     * 窗口变更时调用（由 SemesterService 在同事务内调用）：
     * 无 active 任务 → 创建（source=system_window_change，范围=秘书+教师+学生）；
     * 有 → 追加内容（原内容 + "\n" + 新内容）、标题刷新为入参，
     * 并逻辑删除该任务下 round_no IS NOT NULL 且 confirmed_at IS NULL 的发送记录，
     * 使重发从第 1 轮重新开始（W18）。
     */
    @Override
    @Transactional
    public void onWindowChange(Long semesterId, String title, String content) {
        List<NoticeTask> activeTasks = noticeTaskMapper.selectActiveBySemester(semesterId);
        if (activeTasks.isEmpty()) {
            NoticeTask task = new NoticeTask();
            task.setSemesterId(semesterId);
            task.setTitle(truncate(title, 120));
            task.setContent(truncate(content, 500));
            task.setTargetRoles(WINDOW_CHANGE_TARGET_ROLES);
            task.setRoundLimit(configService.getInt(ConfigService.NOTICE_ROUND_LIMIT, DEFAULT_ROUND_LIMIT));
            task.setIntervalHours(configService.getInt(ConfigService.NOTICE_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS));
            task.setSource(SOURCE_WINDOW_CHANGE);
            task.setStatus(STATUS_ACTIVE);
            task.setDeleted(0L);
            noticeTaskMapper.insert(task);
            log.info("窗口变更通知任务创建: semester={}, task={}", semesterId, task.getId());
            return;
        }
        NoticeTask task = activeTasks.get(0);
        String merged = task.getContent() == null || task.getContent().isBlank()
                ? content
                : task.getContent() + "\n" + content;
        NoticeTask update = new NoticeTask();
        update.setId(task.getId());
        update.setTitle(truncate(title, 120));
        update.setContent(truncate(merged, 500));
        noticeTaskMapper.update(update, Wrappers.<NoticeTask>lambdaUpdate()
                .eq(NoticeTask::getId, task.getId()));
        // 重置轮次计数：逻辑删除未确认的轮次发送记录（deleted=毫秒时间戳，唯一键含 deleted 可重建）
        long reset = noticeRecordMapper.update(null, Wrappers.<NoticeRecord>lambdaUpdate()
                .eq(NoticeRecord::getTaskId, task.getId())
                .isNotNull(NoticeRecord::getRoundNo)
                .isNull(NoticeRecord::getConfirmedAt)
                .set(NoticeRecord::getDeleted, System.currentTimeMillis()));
        log.info("窗口变更通知合并: semester={}, task={}, 重置轮次记录={}", semesterId, task.getId(), reset);
    }

    // ============ 订阅消息重发（NoticeScheduler 调用，SPEC §9） ============

    /**
     * 单任务重发一轮：roundNo = MAX(round_no) + 1；超过 round_limit（system_config 当前值）
     * 跳过（停止订阅消息重发；弹窗不设上限，Q7）。
     *
     * <p>目标用户 = target_roles 角色且在 active 学期有 user_semester_profile 的用户
     * （status=1），排除已确认；仅 STUDENT 且 openid 非空才发订阅消息，每次尝试写
     * notice_record(round_no, sent_at, send_status)；教师/秘书不写发送记录（弹窗为主触达，Q8）。</p>
     */
    @Transactional
    public void resendTask(NoticeTask task) {
        int roundLimit = configService.getInt(ConfigService.NOTICE_ROUND_LIMIT, DEFAULT_ROUND_LIMIT);
        Integer maxRound = noticeRecordMapper.selectMaxRoundNo(task.getId());
        int roundNo = (maxRound == null ? 0 : maxRound) + 1;
        if (roundNo > roundLimit) {
            log.info("通知任务已达重发轮次上限，停止订阅消息重发: task={}, roundLimit={}", task.getId(), roundLimit);
            return;
        }
        // 仅 STUDENT 写订阅消息发送记录；教师/秘书以弹窗为主触达，不写 notice_record（Q8）
        Set<Long> studentIds = userIdsOfRole("STUDENT");
        Set<Long> confirmed = confirmedUserIds(task.getId());
        List<SysUser> targets = resolveTargetUsers(task);
        int sent = 0;
        int unauthorized = 0;
        int failed = 0;
        int skipped = 0;
        for (SysUser user : targets) {
            if (confirmed.contains(user.getId()) || !studentIds.contains(user.getId())) {
                skipped++;
                continue;
            }
            // 插入前查重 + 唯一键 uk_notice_round(task_id,user_id,round_no,deleted) 兜底
            if (noticeRecordMapper.selectRound(task.getId(), user.getId(), roundNo) != null) {
                skipped++;
                continue;
            }
            String status = sendSubscribe(task, user);
            switch (status) {
                case "sent" -> sent++;
                case "unauthorized" -> unauthorized++;
                case "failed" -> failed++;
                default -> {
                }
            }
            insertRoundRecord(task.getId(), user.getId(), roundNo, status);
        }
        log.info("通知重发一轮: task={}, round={}, 目标={}, sent={}, unauthorized={}, failed={}, 跳过={}",
                task.getId(), roundNo, targets.size(), sent, unauthorized, failed, skipped);
    }

    /**
     * 订阅消息发送状态判定（W5/R10 如实落库，仅学生）：
     * openid 为空/模板 id 未配置/客户端未配置 → unauthorized；
     * 发送返回 false（已尝试）→ failed；成功 → sent。
     */
    private String sendSubscribe(NoticeTask task, SysUser user) {
        String openid = user.getOpenid();
        String templateId = textbookProperties.getWeixin().getMiniapp().getSubscribeTemplateId();
        if (openid == null || openid.isBlank() || templateId == null || templateId.isBlank()
                || !wxMaClient.configured()) {
            return "unauthorized";
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("thing1", truncate(task.getTitle(), 20));
        data.put("time2", LocalDateTime.now().format(WX_TIME_FMT));
        data.put("thing3", truncate(task.getContent(), 20));
        boolean ok = wxMaClient.sendSubscribeMessage(openid, templateId, NOTICE_PAGE, data);
        return ok ? "sent" : "failed";
    }

    private void insertRoundRecord(Long taskId, Long userId, int roundNo, String status) {
        try {
            NoticeRecord record = new NoticeRecord();
            record.setTaskId(taskId);
            record.setUserId(userId);
            record.setRoundNo(roundNo);
            record.setSentAt(LocalDateTime.now());
            record.setSendStatus(status);
            record.setDeleted(0L);
            noticeRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.warn("通知发送记录重复（唯一键兜底）: task={}, user={}, round={}", taskId, userId, roundNo);
        }
    }

    // ============ 看板统计（stats 模块复用） ============

    /**
     * active 任务目标用户中未确认人数（去重，跨任务并集）。
     * 目标用户 = target_roles 角色 + active 学期在册 profile（status=1）+ 账号正常。
     */
    @Transactional(readOnly = true)
    public long countUnconfirmedTargetUsers() {
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            return 0L;
        }
        List<NoticeTask> tasks = noticeTaskMapper.selectActiveBySemester(semesterId);
        if (tasks.isEmpty()) {
            return 0L;
        }
        Set<Long> unconfirmed = new HashSet<>();
        for (NoticeTask task : tasks) {
            Set<Long> confirmed = confirmedUserIds(task.getId());
            for (SysUser user : resolveTargetUsers(task)) {
                if (!confirmed.contains(user.getId())) {
                    unconfirmed.add(user.getId());
                }
            }
        }
        return unconfirmed.size();
    }

    /**
     * 目标任务用户：target_roles 角色 ∩ active 学期在册（user_semester_profile.status=1）∩ 账号正常。
     */
    @Transactional(readOnly = true)
    public List<SysUser> resolveTargetUsers(NoticeTask task) {
        List<Long> roleIds = resolveRoleIds(task.getTargetRoles());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<SysUserRole> userRoles = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .in(SysUserRole::getRoleId, roleIds)
                .eq(SysUserRole::getDeleted, 0));
        if (userRoles.isEmpty()) {
            return List.of();
        }
        Set<Long> roleUserIds = userRoles.stream()
                .map(SysUserRole::getUserId)
                .collect(Collectors.toSet());
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            return List.of();
        }
        Set<Long> enrolled = profileMapper.selectBySemester(semesterId).stream()
                .filter(p -> Integer.valueOf(1).equals(p.getStatus()))
                .map(UserSemesterProfile::getUserId)
                .collect(Collectors.toSet());
        List<Long> candidateIds = roleUserIds.stream()
                .filter(enrolled::contains)
                .sorted()
                .toList();
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        List<SysUser> users = new ArrayList<>();
        for (int i = 0; i < candidateIds.size(); i += USER_CHUNK) {
            List<Long> chunk = candidateIds.subList(i, Math.min(i + USER_CHUNK, candidateIds.size()));
            users.addAll(userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                    .in(SysUser::getId, chunk)
                    .eq(SysUser::getStatus, 1)
                    .eq(SysUser::getDeleted, 0)));
        }
        return users;
    }

    // ============ 内部工具 ============

    private Set<Long> confirmedUserIds(Long taskId) {
        return noticeRecordMapper.selectList(Wrappers.<NoticeRecord>lambdaQuery()
                        .eq(NoticeRecord::getTaskId, taskId)
                        .isNotNull(NoticeRecord::getConfirmedAt)
                        .eq(NoticeRecord::getDeleted, 0))
                .stream()
                .map(NoticeRecord::getUserId)
                .collect(Collectors.toSet());
    }

    private List<Long> resolveRoleIds(String targetRoles) {
        List<Long> roleIds = new ArrayList<>();
        if (targetRoles == null || targetRoles.isBlank()) {
            return roleIds;
        }
        for (String roleCode : targetRoles.split(",")) {
            String code = roleCode.trim();
            if (code.isEmpty()) {
                continue;
            }
            SysRole role = roleMapper.selectByCode(code);
            if (role != null) {
                roleIds.add(role.getId());
            }
        }
        return roleIds;
    }

    /** 某角色码的全部用户 id（deleted=0）；角色不存在返回空集合 */
    private Set<Long> userIdsOfRole(String roleCode) {
        SysRole role = roleMapper.selectByCode(roleCode);
        if (role == null) {
            return Set.of();
        }
        return userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getRoleId, role.getId())
                        .eq(SysUserRole::getDeleted, 0))
                .stream()
                .map(SysUserRole::getUserId)
                .collect(Collectors.toSet());
    }

    private void validateRoleCodes(String targetRoles) {
        for (String roleCode : targetRoles.split(",")) {
            String code = roleCode.trim();
            if (code.isEmpty()) {
                continue;
            }
            if (roleMapper.selectByCode(code) == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色不存在: " + code);
            }
        }
    }

    private NoticeTask requireTask(Long id) {
        NoticeTask task = noticeTaskMapper.selectByIdSoft(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通知任务不存在");
        }
        return task;
    }

    private Long requireActiveSemesterId() {
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "当前无激活学期");
        }
        return semesterId;
    }

    private NoticeTaskListItem toListItem(NoticeTask task) {
        NoticeTaskListItem item = new NoticeTaskListItem();
        item.setId(task.getId());
        item.setSemesterId(task.getSemesterId());
        item.setTitle(task.getTitle());
        item.setContent(task.getContent());
        item.setTargetRoles(task.getTargetRoles());
        item.setRoundLimit(task.getRoundLimit());
        item.setIntervalHours(task.getIntervalHours());
        item.setSource(task.getSource());
        item.setStatus(task.getStatus());
        item.setCreatedAt(task.getCreatedAt());
        item.setClosedBy(task.getClosedBy());
        item.setClosedAt(task.getClosedAt());
        return item;
    }

    private NoticeFailureItem toFailureItem(Map<String, Object> row) {
        NoticeFailureItem item = new NoticeFailureItem();
        item.setUserId(toLongObject(row.get("userId")));
        item.setUserNo(Objects.toString(row.get("userNo"), null));
        item.setName(Objects.toString(row.get("name"), null));
        item.setRole(Objects.toString(row.get("role"), null));
        item.setCollegeId(toLongObject(row.get("collegeId")));
        item.setCollegeName(Objects.toString(row.get("collegeName"), null));
        item.setClassId(toLongObject(row.get("classId")));
        item.setClassName(Objects.toString(row.get("className"), null));
        item.setSendStatus(Objects.toString(row.get("sendStatus"), null));
        item.setRoundNo(toIntegerObject(row.get("roundNo")));
        item.setSentAt(toDateTime(row.get("sentAt")));
        return item;
    }

    /** VARCHAR 上限保护（notice_task.title=120 / content=500，合并追加不越界） */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
    }

    private static long toLong(Object value) {
        Long boxed = toLongObject(value);
        return boxed == null ? 0L : boxed;
    }

    private static Long toLongObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Integer toIntegerObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        return null;
    }
}
