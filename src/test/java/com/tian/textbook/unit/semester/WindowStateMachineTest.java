package com.tian.textbook.unit.semester;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.notify.WindowChangeNotifier;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.semester.dto.SemesterActivateRequest;
import com.tian.textbook.semester.dto.SemesterCreateRequest;
import com.tian.textbook.semester.dto.WindowExtendRequest;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.mapper.AuditLogMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 窗口状态机单元测试（SPEC §14：状态流转；Service 依赖仓储较多，此处覆盖纯逻辑与守卫分支，
 * 端到端流转由集成测试 WindowEngineIntegrationTest 覆盖）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WindowStateMachineTest {

    @Mock
    private SemesterMapper semesterMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private AuditService auditService;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private WindowChangeNotifier windowChangeNotifier;
    @Mock
    private SemesterActiveService activeSemesterService;

    private SemesterService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set() 需 TableInfo 缓存（Spring 上下文中由 MP 初始化；单测手工补）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Semester.class);
        service = new SemesterService(semesterMapper, userMapper, auditService, auditLogMapper,
                windowChangeNotifier, activeSemesterService);
    }

    private Semester draftSemester(Long id, int version) {
        Semester semester = new Semester();
        semester.setId(id);
        semester.setName("2026-2027-1");
        semester.setStartDate(LocalDate.of(2026, 9, 1));
        semester.setEndDate(LocalDate.of(2027, 1, 15));
        semester.setWindowStart(LocalDateTime.now().minusDays(1));
        semester.setWindowEnd(LocalDateTime.now().plusDays(7));
        semester.setChannelOpen(0);
        semester.setAutoOpen(1);
        semester.setAutoClose(1);
        semester.setWindowStatus("not_open");
        semester.setActiveStatus("draft");
        semester.setVersion(version);
        semester.setDeleted(0L);
        return semester;
    }

    private SemesterCreateRequest createRequest(LocalDateTime start, LocalDateTime end) {
        return new SemesterCreateRequest("2026-2027-1", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), start, end, 1, 1);
    }

    private static ErrorCode errorCodeOf(Throwable throwable) {
        return ((BizException) throwable).getErrorCode();
    }

    @Nested
    @DisplayName("create：窗口起止校验 + draft 初值")
    class Create {

        @Test
        void create_windowStartAfterEnd_throwsParamInvalidWithoutInsert() {
            assertThatThrownBy(() -> service.create(createRequest(
                    LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(1))))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
            verify(semesterMapper, never()).insert(any(Semester.class));
        }

        @Test
        void create_windowStartEqualsEnd_throwsParamInvalid() {
            LocalDateTime same = LocalDateTime.now().plusDays(1);
            assertThatThrownBy(() -> service.create(createRequest(same, same)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
        }

        @Test
        void create_validRange_insertsDraftSemester() {
            Semester created = service.create(createRequest(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7)));

            verify(semesterMapper).insert(any(Semester.class));
            assertThat(created.getActiveStatus()).isEqualTo("draft");
            assertThat(created.getWindowStatus()).isEqualTo("not_open");
            assertThat(created.getChannelOpen()).isZero();
            assertThat(created.getVersion()).isZero();
            verify(auditService).record(eq(AuditService.SEMESTER_SWITCH), eq("semester"), any(), any());
        }
    }

    @Nested
    @DisplayName("activate：双缓冲切换守卫")
    class Activate {

        @Test
        void activate_nonDraftSemester_throwsStateConflict() {
            Semester active = draftSemester(2L, 0);
            active.setActiveStatus("active");
            when(semesterMapper.selectByIdSoft(2L)).thenReturn(active);

            assertThatThrownBy(() -> service.activate(2L, new SemesterActivateRequest(0)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));
            verify(semesterMapper, never()).activateIfDraft(anyLong(), anyInt());
        }

        @Test
        void activate_versionMismatch_throwsStateConflictAndRollsBack() {
            when(semesterMapper.selectByIdSoft(2L)).thenReturn(draftSemester(2L, 5));

            assertThatThrownBy(() -> service.activate(2L, new SemesterActivateRequest(4)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));
            verify(semesterMapper, never()).activateIfDraft(anyLong(), anyInt());
            verify(userMapper, never()).syncCollegeClassFromProfile(anyLong());
        }

        @Test
        void activate_oldActiveArchiveFails_throwsStateConflict() {
            when(semesterMapper.selectByIdSoft(2L)).thenReturn(draftSemester(2L, 0));
            Semester old = draftSemester(1L, 3);
            old.setActiveStatus("active");
            when(semesterMapper.selectActive()).thenReturn(old);
            when(semesterMapper.archiveIfActive(1L)).thenReturn(0);

            assertThatThrownBy(() -> service.activate(2L, new SemesterActivateRequest(0)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STATE_CONFLICT));
            verify(semesterMapper, never()).activateIfDraft(anyLong(), anyInt());
        }

        @Test
        void activate_firstActiveSemester_syncsProfileAndAudits() {
            Semester target = draftSemester(2L, 0);
            when(semesterMapper.selectByIdSoft(2L)).thenReturn(target);
            when(semesterMapper.selectActive()).thenReturn(null);
            when(semesterMapper.activateIfDraft(2L, 0)).thenReturn(1);
            Semester activated = draftSemester(2L, 1);
            activated.setActiveStatus("active");
            when(semesterMapper.selectByIdSoft(2L)).thenReturn(target, activated);

            Semester result = service.activate(2L, new SemesterActivateRequest(0));

            verify(userMapper).syncCollegeClassFromProfile(2L);
            verify(auditService).record(eq(AuditService.SEMESTER_SWITCH), eq("semester"),
                    eq("2"), any());
            assertThat(result.getActiveStatus()).isEqualTo("active");
        }
    }

    @Nested
    @DisplayName("窗口手动操作")
    class ManualWindow {

        @Test
        void openWindow_withoutWindowRange_throwsParamInvalid() {
            Semester semester = draftSemester(1L, 0);
            semester.setWindowStart(null);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);

            assertThatThrownBy(() -> service.openWindow(1L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
        }

        @Test
        void openWindow_valid_mutatesToOpenWithChannel() {
            Semester semester = draftSemester(1L, 0);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);
            when(semesterMapper.update(any(), any())).thenReturn(1);

            service.openWindow(1L);

            assertThat(semester.getChannelOpen()).isEqualTo(1);
            assertThat(semester.getWindowStatus()).isEqualTo("open");
            verify(windowChangeNotifier).onWindowChange(eq(1L), anyString(), anyString());
        }

        @Test
        void closeWindow_open_mutatesToClosed() {
            Semester semester = draftSemester(1L, 0);
            semester.setWindowStatus("open");
            semester.setChannelOpen(1);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);
            when(semesterMapper.update(any(), any())).thenReturn(1);

            service.closeWindow(1L);

            assertThat(semester.getChannelOpen()).isZero();
            assertThat(semester.getWindowStatus()).isEqualTo("closed");
        }

        @Test
        void extendWindow_pastDeadline_throwsParamInvalid() {
            assertThatThrownBy(() -> service.extendWindow(1L,
                    new WindowExtendRequest(LocalDateTime.now().minusMinutes(1))))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
            verify(semesterMapper, never()).update(any(), any());
        }

        @Test
        void extendWindow_closedSemester_reopensWithNewDeadline() {
            Semester semester = draftSemester(1L, 0);
            semester.setWindowStatus("closed");
            semester.setChannelOpen(0);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);
            when(semesterMapper.update(any(), any())).thenReturn(1);
            LocalDateTime newEnd = LocalDateTime.now().plusDays(3);

            service.extendWindow(1L, new WindowExtendRequest(newEnd));

            assertThat(semester.getWindowEnd()).isEqualTo(newEnd);
            assertThat(semester.getWindowStatus()).isEqualTo("open");
            assertThat(semester.getChannelOpen()).isEqualTo(1);
        }

        @Test
        void extendWindow_beforeWindowStart_throwsParamInvalid() {
            Semester semester = draftSemester(1L, 0);
            semester.setWindowStart(LocalDateTime.now().plusDays(5));
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);

            assertThatThrownBy(() -> service.extendWindow(1L,
                    new WindowExtendRequest(LocalDateTime.now().plusDays(1))))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.PARAM_INVALID));
        }
    }

    @Nested
    @DisplayName("applyAutoTransition：定时任务幂等")
    class AutoTransition {

        @Test
        void applyAutoTransition_stateAlreadyChanged_skipsAuditAndNotify() {
            Semester semester = draftSemester(1L, 0);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester);
            when(semesterMapper.update(any(), any())).thenReturn(0);

            service.applyAutoTransition(1L, "open", true, "到点自动开启征订窗口");

            verify(auditService, never()).record(anyString(), anyString(), any(), any());
            verify(windowChangeNotifier, never()).onWindowChange(anyLong(), anyString(), anyString());
        }

        @Test
        void applyAutoTransition_transitionApplied_recordsAuditAndNotify() {
            Semester semester = draftSemester(1L, 0);
            Semester updated = draftSemester(1L, 1);
            updated.setWindowStatus("open");
            updated.setChannelOpen(1);
            when(semesterMapper.selectByIdSoft(1L)).thenReturn(semester, updated);
            when(semesterMapper.update(any(), any())).thenReturn(1);

            service.applyAutoTransition(1L, "open", true, "到点自动开启征订窗口");

            verify(auditService).record(eq(AuditService.WINDOW), eq("window"), eq("1"), any());
            verify(windowChangeNotifier).onWindowChange(eq(1L), anyString(), anyString());
            verify(activeSemesterService).evict();
        }
    }
}
