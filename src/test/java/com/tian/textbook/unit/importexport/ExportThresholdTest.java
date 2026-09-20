package com.tian.textbook.unit.importexport;

import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.importexport.service.ExportAsyncService;
import com.tian.textbook.importexport.service.ExportDataWriter;
import com.tian.textbook.importexport.service.ExportServiceImpl;
import com.tian.textbook.notify.mapper.NoticeRecordMapper;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.StudentOrderItemMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 导出阈值判定单元测试（SPEC §14：预估行数 ≤ 阈值同步、> 阈值异步；阈值来自 ConfigService）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportThresholdTest {

    @Mock
    private ExportTaskMapper exportTaskMapper;
    @Mock
    private ExportAsyncService exportAsyncService;
    @Mock
    private ExportDataWriter dataWriter;
    @Mock
    private OrderFormItemMapper orderFormItemMapper;
    @Mock
    private StudentOrderItemMapper studentOrderItemMapper;
    @Mock
    private NoticeRecordMapper noticeRecordMapper;
    @Mock
    private NoticeTaskMapper noticeTaskMapper;
    @Mock
    private UserSemesterProfileMapper profileMapper;
    @Mock
    private SemesterActiveService activeSemesterService;
    @Mock
    private ConfigService configService;
    @Mock
    private AuditService auditService;
    @Mock
    private AuditLogMapper auditLogMapper;

    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportServiceImpl(exportTaskMapper, exportAsyncService, dataWriter,
                orderFormItemMapper, studentOrderItemMapper, noticeRecordMapper, noticeTaskMapper,
                profileMapper, activeSemesterService, configService, auditService);
    }

    private void threshold(int value) {
        when(configService.getInt(eq(ConfigService.EXPORT_SYNC_ROW_THRESHOLD), anyInt()))
                .thenReturn(value);
    }

    @Nested
    @DisplayName("默认阈值 5000")
    class DefaultThreshold {

        @Test
        void shouldGoAsync_rowsEqualToThreshold_returnsFalse() {
            threshold(5000);
            assertThat(exportService.shouldGoAsync(5000)).isFalse();
        }

        @Test
        void shouldGoAsync_rowsAboveThreshold_returnsTrue() {
            threshold(5000);
            assertThat(exportService.shouldGoAsync(5001)).isTrue();
        }
    }

    @Nested
    @DisplayName("阈值可经 system_config 调整（W8 立即生效）")
    class ConfiguredThreshold {

        @Test
        void shouldGoAsync_thresholdLowered_boundaryFollowsConfig() {
            threshold(100);
            assertThat(exportService.shouldGoAsync(100)).isFalse();
            assertThat(exportService.shouldGoAsync(101)).isTrue();
        }

        @Test
        void shouldGoAsync_thresholdRaised_boundaryFollowsConfig() {
            threshold(100000);
            assertThat(exportService.shouldGoAsync(99999)).isFalse();
            assertThat(exportService.shouldGoAsync(100001)).isTrue();
        }
    }

    @Nested
    @DisplayName("异步任务创建")
    class CreateAsyncTask {

        @Test
        void createAsyncTask_validParams_insertsQueuedTask() {
            threshold(5000);
            ExportTask saved = new ExportTask();
            saved.setId(7L);
            saved.setStatus("queued");
            org.mockito.Mockito.doAnswer(invocation -> {
                ExportTask inserted = invocation.getArgument(0);
                inserted.setId(7L);
                return 1;
            }).when(exportTaskMapper).insert(org.mockito.ArgumentMatchers.<ExportTask>any());
            when(exportTaskMapper.selectByIdSoft(7L)).thenReturn(saved);

            ExportTask task = exportService.createAsyncTask("order",
                    java.util.Map.of("semesterId", 1L), 9000);
            assertThat(task.getId()).isEqualTo(7L);
            assertThat(task.getStatus()).isEqualTo("queued");
        }
    }
}
