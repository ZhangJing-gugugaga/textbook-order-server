package com.tian.textbook.integration.perf;

import com.alibaba.excel.EasyExcel;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 性能/规模用例（SPEC §14 / PRD 十三：万行导入 ≤ 5 分钟）。
 *
 * <p>默认禁用（@Tag("perf") + @Disabled）：本地按需开启
 * {@code mvn test -Dtest=TenThousandRowImportPerfTest} 或去掉 @Disabled。</p>
 *
 * <p>实测（本机 8 核 + H2，2026-09-21）：10,000 行导入 169s（total=10000, ok=10000），
 * 满足「万行导入 ≤5 分钟」。前提：ImportRowWriter 对初始密码 BCrypt 做批内并行哈希
 * （单线程串行约 78ms/次 → 万行 10+ 分钟，见 ImportRowWriter#hashInitialPasswords）。</p>
 *
 * <p>程序生成 1 万行学生导入样本（无需外部文件），断言：
 * ① 批次跑完（done）且全部成功；② 总耗时 ≤ 5 分钟（SPEC §14 量化验收）。</p>
 */
@Tag("perf")
@org.junit.jupiter.api.Disabled("本地按需开启：默认不跑以保持 mvn test 快速（实测 10k 行约 2.8 分钟）")
class TenThousandRowImportPerfTest extends IntegrationTestBase {

    private static final int ROWS = 10_000;
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private ImportService importService;
    @Autowired
    private ImportBatchMapper importBatchMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private TestDataSeeder seeder;

    @Test
    @DisplayName("万行学生导入 ≤ 5 分钟（PRD 十三 / SPEC §14）")
    void importTenThousandRows_withinFiveMinutes() throws Exception {
        var college = seeder.college("计算机学院");
        var major = seeder.major(college.getId(), "软件工程");
        var clazz = seeder.schoolClass(major.getId(), "软工2401", 0);
        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());

        List<StudentImportRow> rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            StudentImportRow row = new StudentImportRow();
            row.setUserNo(String.format("2024%06d", i));
            row.setName("学生" + i);
            row.setCollegeName("计算机学院");
            row.setMajorName("软件工程");
            row.setClassName("软工2401");
            row.setPhone("1380000" + String.format("%04d", i));
            rows.add(row);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, StudentImportRow.class).sheet("学生名单").doWrite(rows);
        MockMultipartFile file = new MockMultipartFile("file", "students-10k.xlsx", XLSX,
                out.toByteArray());

        long start = System.nanoTime();
        Long batchId = importService.startImport("student", semester.getId(), file);

        ImportBatch batch = null;
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6);
        while (System.currentTimeMillis() < deadline) {
            batch = importBatchMapper.selectByIdSoft(batchId);
            if (batch != null && ("done".equals(batch.getStatus()) || "failed".equals(batch.getStatus()))) {
                break;
            }
            Thread.sleep(500);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(batch).isNotNull();
        assertThat(batch.getStatus()).isEqualTo("done");
        assertThat(batch.getTotal()).isEqualTo(ROWS);
        assertThat(batch.getOkCount()).isEqualTo(ROWS);
        assertThat(batch.getProgressPct()).isEqualTo(100);
        assertThat(Duration.ofMillis(elapsedMs).toMinutes()).isLessThanOrEqualTo(5);
        System.out.printf("PERF 万行导入耗时: %d ms (total=%d, ok=%d)%n",
                elapsedMs, batch.getTotal(), batch.getOkCount());
    }
}
