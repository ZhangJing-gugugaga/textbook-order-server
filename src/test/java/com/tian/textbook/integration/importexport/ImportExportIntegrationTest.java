package com.tian.textbook.integration.importexport;

import com.alibaba.excel.EasyExcel;
import com.tian.textbook.auth.AuthService;
import com.tian.textbook.auth.dto.LoginRequest;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.excel.OrderExportRow;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.order.dto.OrderFormReviewRequest;
import com.tian.textbook.order.dto.OrderFormSubmitItem;
import com.tian.textbook.order.dto.OrderFormSubmitRequest;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.OrderScenarioFactory;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导入导出集成测试（SPEC §10 / Q16 / W14 / W18，H2 承载）。
 *
 * <p>覆盖：学生名单导入建号（初始密码可登录）+ profile 落库 + 班级人数重算、
 * 停用比对仅限本次导入范围（W14）、同步导出可读回、一次性下载 token 单次有效与过期 410。</p>
 */
class ImportExportIntegrationTest extends IntegrationTestBase {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private ImportService importService;
    @Autowired
    private ExportService exportService;
    @Autowired
    private ImportBatchMapper importBatchMapper;
    @Autowired
    private ExportTaskMapper exportTaskMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private UserSemesterProfileMapper profileMapper;
    @Autowired
    private SchoolClassMapper classMapper;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherOrderService teacherOrderService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private OrderScenarioFactory scenarioFactory;
    @Autowired
    private TestDataSeeder seeder;

    private Long semesterId;
    private Long collegeA;
    private Long collegeB;
    private Long classA1;
    private Long classA2;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private void seed() {
        var cA = seeder.college("计算机学院");
        var cB = seeder.college("外语学院");
        var majorA = seeder.major(cA.getId(), "软件工程");
        var majorB = seeder.major(cB.getId(), "英语");
        var clazzA1 = seeder.schoolClass(majorA.getId(), "软工2401", 0);
        var clazzA2 = seeder.schoolClass(majorA.getId(), "软工2402", 0);
        seeder.schoolClass(majorB.getId(), "英语2401", 0);

        Semester semester = seeder.semester("2026-2027-1", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());

        collegeA = cA.getId();
        collegeB = cB.getId();
        classA1 = clazzA1.getId();
        classA2 = clazzA2.getId();
        semesterId = semester.getId();
        SemesterContextHolder.set(semesterId);
    }

    private MockMultipartFile studentFile(String... rows) {
        List<StudentImportRow> data = new java.util.ArrayList<>();
        for (String row : rows) {
            // userNo|name|college|major|class|phone
            String[] parts = row.split("\\|");
            StudentImportRow item = new StudentImportRow();
            item.setUserNo(parts[0]);
            item.setName(parts[1]);
            item.setCollegeName(parts[2]);
            item.setMajorName(parts[3]);
            item.setClassName(parts[4]);
            item.setPhone(parts[5]);
            data.add(item);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, StudentImportRow.class).sheet("学生名单").doWrite(data);
        return new MockMultipartFile("file", "students.xlsx", XLSX, out.toByteArray());
    }

    private ImportBatch awaitDone(Long batchId) {
        Duration timeout = Duration.ofSeconds(60);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        ImportBatch batch = importBatchMapper.selectByIdSoft(batchId);
        while (batch == null || (!"done".equals(batch.getStatus()) && !"failed".equals(batch.getStatus()))) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            batch = importBatchMapper.selectByIdSoft(batchId);
        }
        return batch;
    }

    /** 轮询异步导出任务到终态（@Async(exportExecutor) 真实异步，SPEC §10）。 */
    private ExportTask awaitExportDone(Long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        ExportTask task = exportService.getTask(taskId);
        while (!"done".equals(task.getStatus()) && !"failed".equals(task.getStatus())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            task = exportService.getTask(taskId);
        }
        assertThat(task.getStatus()).isEqualTo("done");
        return task;
    }

    @Test
    @DisplayName("学生名单导入：建号（初始密码可登录）+ profile 落库 + 班级人数重算")
    void importStudents_createsUsersAndProfiles() {
        seed();

        Long batchId = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024002|李四|计算机学院|软件工程|软工2401|13800000002",
                "2024003|王五|计算机学院|软件工程|软工2402|13800000003"));
        ImportBatch batch = awaitDone(batchId);

        assertThat(batch.getStatus()).isEqualTo("done");
        assertThat(batch.getTotal()).isEqualTo(3);
        assertThat(batch.getOkCount()).isEqualTo(3);
        assertThat(batch.getErrorCount()).isZero();

        // 账号建成：初始密码 = 学号后 6 位，可登录
        SysUser user = userMapper.selectByUserNo("2024001");
        assertThat(user).isNotNull();
        assertThat(user.getMustChangePassword()).isEqualTo(1);
        assertThat(user.getFirstLoginVerified()).isZero();
        var loginResponse = authService.login(
                new LoginRequest("2024001", UserService.initialPassword("2024001")),
                "127.0.0.1", "test-device");
        assertThat(loginResponse.accessToken()).isNotBlank();
        assertThat(loginResponse.mustChangePassword()).isTrue();

        // 学期归属落库（W6 真源）
        var profile = profileMapper.selectByUserAndSemester(user.getId(), semesterId);
        assertThat(profile).isNotNull();
        assertThat(profile.getCollegeId()).isEqualTo(collegeA);
        assertThat(profile.getClassId()).isEqualTo(classA1);

        // 班级人数 = 文件内该班**去重**学生数（W2，以名单为准）
        assertThat(classMapper.selectByIdSoft(classA1).getStudentCount()).isEqualTo(2);
        assertThat(classMapper.selectByIdSoft(classA2).getStudentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("draft 学期名单导入：只写 profile，不得全局停用 active 学期在册账号")
    void importIntoDraftSemester_doesNotDisableActiveAccounts() {
        seed();
        // 先在 active 学期导入两名学生（建立「在册」基线）
        Long first = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024002|李四|计算机学院|软件工程|软工2401|13800000002"));
        assertThat(awaitDone(first).getStatus()).isEqualTo("done");

        // 新建 draft 学期（非 active），只导入其中一人
        Semester draft = seeder.semester("2027-2028-1", null, null, null, null, 1, 1);
        Long second = importService.startImport("student", draft.getId(), studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001"));
        assertThat(awaitDone(second).getStatus()).isEqualTo("done");

        // 停用比对只在「目标学期 = active 学期」时生效：draft 导入不得停用 active 在册账号
        // （sys_user.college_id 是 active 学期归属冗余列，拿它当 draft 的比对基准会错位，
        //   把当前在册学生/教师全部全局停用 + 撤销 refresh 直接踢下线）
        SysUser kept = userMapper.selectByUserNo("2024002");
        assertThat(kept.getStatus()).as("active 学期在册学生不得被 draft 导入停用").isEqualTo(1);
        var keptProfile = profileMapper.selectByUserAndSemester(kept.getId(), semesterId);
        assertThat(keptProfile.getStatus()).isEqualTo(1);
        // draft 学期的 profile 照常写入（导入为权威源）
        assertThat(profileMapper.selectByUserAndSemester(userMapper.selectByUserNo("2024001").getId(),
                draft.getId())).isNotNull();
    }

    @Test
    @DisplayName("班级人数（W2）：按去重学号统计（重复行不放大上限），下调写入审计摘要")
    void importStudents_classSizeCountsDistinctUserNos() {
        seed();
        // 预置「已维护的真实人数」50：局部名单会把上限改小，必须留痕（否则教师填报莫名被卡）
        classMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.tian.textbook.system.entity.SchoolClass>lambdaUpdate()
                .eq(com.tian.textbook.system.entity.SchoolClass::getId, classA1)
                .set(com.tian.textbook.system.entity.SchoolClass::getStudentCount, 50));

        Long batchId = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024002|李四|计算机学院|软件工程|软工2401|13800000002"));
        ImportBatch batch = awaitDone(batchId);
        assertThat(batch.getStatus()).isEqualTo("done");

        // 3 行但只有 2 个学号 → 2 人（此前按行数计数会把上限算成 3）
        assertThat(classMapper.selectByIdSoft(classA1).getStudentCount()).isEqualTo(2);

        // 审计摘要记录班级人数更新与下调数（供管理员追溯「上限为何变小」）
        var audit = auditService.query(null, null, AuditService.IMPORT, "import_batch", null, null, 1, 20);
        assertThat(audit.list()).isNotEmpty();
        assertThat(audit.list().get(0).getDetailJson())
                .containsEntry("classSizeUpdates", 1)
                .containsEntry("classSizeShrinks", 1);
    }

    @Test
    @DisplayName("停用比对（W14）：再导入少了某学生 → 该学生 status=0；范围外学院学生不受影响")
    void reimportFewerStudents_disablesOnlyInScope() {
        seed();

        // 第一次：学院A 两人；学院B 一人（另一次导入）
        Long first = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024002|李四|计算机学院|软件工程|软工2401|13800000002"));
        assertThat(awaitDone(first).getStatus()).isEqualTo("done");
        Long other = importService.startImport("student", semesterId, studentFile(
                "2024101|Alice|外语学院|英语|英语2401|13800000011"));
        assertThat(awaitDone(other).getStatus()).isEqualTo("done");

        // 第二次：学院A 只剩一人
        Long second = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001"));
        ImportBatch batch = awaitDone(second);
        assertThat(batch.getStatus()).isEqualTo("done");

        SysUser dropped = userMapper.selectByUserNo("2024002");
        assertThat(dropped.getStatus()).isZero(); // 文件内学院 + 角色中不在文件内 → 停用
        var droppedProfile = profileMapper.selectByUserAndSemester(dropped.getId(), semesterId);
        assertThat(droppedProfile.getStatus()).isZero();

        SysUser kept = userMapper.selectByUserNo("2024001");
        assertThat(kept.getStatus()).isEqualTo(1);
        SysUser outOfScope = userMapper.selectByUserNo("2024101");
        assertThat(outOfScope.getStatus()).isEqualTo(1); // 范围外学院不动
    }

    @Test
    @DisplayName("同文件重复导入：幂等 upsert，不产生重复账号")
    void reimportSameFile_isIdempotent() {
        seed();

        Long first = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001"));
        assertThat(awaitDone(first).getOkCount()).isEqualTo(1);
        Long second = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001"));
        assertThat(awaitDone(second).getOkCount()).isEqualTo(1);

        List<SysUser> users = userMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<SysUser>lambdaQuery().eq(SysUser::getUserNo, "2024001"));
        assertThat(users).hasSize(1);
    }

    @Test
    @DisplayName("同步导出（预估 ≤ 阈值）：writeSync 写出的 xlsx 可被 EasyExcel 读回且有数据行")
    void writeSync_orderExport_readableWithDataRows() {
        var scenario = scenarioFactory.seed("IE");
        SemesterContextHolder.set(scenario.semesterId());
        TestSecurity.authenticate(scenario.teacherId(), "T", "教师", Set.of("TEACHER"), "TEACHER",
                seeder.permissionsOf("TEACHER"));
        teacherOrderService.submit(new OrderFormSubmitRequest(List.of(new OrderFormSubmitItem(
                scenario.courseId(), scenario.classId(), scenario.textbookId(), 50))));
        var submittedForm = teacherOrderService.getMyForm();
        assertThat(submittedForm).isNotNull();
        var admin = seeder.user("AD", "超管", "13800000009", null, null, 1, 0, 1, "ADMIN");
        TestSecurity.authenticate(admin.getId(), "AD", "超管", Set.of("ADMIN"), "ADMIN",
                seeder.permissionsOf("ADMIN"));
        teacherOrderService.review(submittedForm.getId(), new OrderFormReviewRequest("pass", null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeSync("order", Map.of("semesterId", scenario.semesterId()), out);

        List<OrderExportRow> rows = EasyExcel.read(new ByteArrayInputStream(out.toByteArray()))
                .head(OrderExportRow.class).sheet().doReadSync();
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getIsbn()).isEqualTo("978-0-306-40615-7");
            assertThat(r.getQuantity()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("一次性下载 token：首次成功、第二次 410；token 过期 410")
    void downloadToken_singleUseAndExpiry() throws Exception {
        var scenario = scenarioFactory.seed("IT");
        SemesterContextHolder.set(scenario.semesterId());

        ExportTask task = exportService.createAsyncTask("order",
                Map.of("semesterId", scenario.semesterId()), 1);
        // @Async(exportExecutor) 真实异步执行：轮询到 done（SPEC §10）
        ExportTask done = awaitExportDone(task.getId());
        String token = done.getDownloadToken();
        assertThat(token).isNotBlank();

        ExportTask claimed = exportService.claimDownload(task.getId(), token);
        assertThat(claimed.getDownloadToken()).isNull();

        assertThatThrownBy(() -> exportService.claimDownload(task.getId(), token))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DOWNLOAD_TOKEN_INVALID));

        // token 过期 → 410
        ExportTask expired = awaitExportDone(exportService.createAsyncTask("order",
                Map.of("semesterId", scenario.semesterId()), 1).getId());
        exportTaskMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ExportTask>lambdaUpdate().eq(ExportTask::getId, expired.getId())
                .set(ExportTask::getTokenExpireAt, LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> exportService.claimDownload(expired.getId(),
                        expired.getDownloadToken()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DOWNLOAD_TOKEN_INVALID));
    }

    @Test
    @DisplayName("导入错误行：外键不存在逐行报错且不中断（错误明细可下载）")
    void importStudents_invalidRows_collectedAsErrors() {
        seed();

        Long batchId = importService.startImport("student", semesterId, studentFile(
                "2024001|张三|计算机学院|软件工程|软工2401|13800000001",
                "2024002|李四|不存在的学院|软件工程|软工2401|13800000002",
                "2024003||计算机学院|软件工程|软工2401|13800000003"));
        ImportBatch batch = awaitDone(batchId);

        assertThat(batch.getStatus()).isEqualTo("done");
        assertThat(batch.getOkCount()).isEqualTo(1);
        assertThat(batch.getErrorCount()).isEqualTo(2);
        assertThat(batch.getErrorFilePath()).isNotBlank();
        assertThat(userMapper.selectByUserNo("2024001")).isNotNull();
        assertThat(userMapper.selectByUserNo("2024002")).isNull();
        assertThat(userMapper.selectByUserNo("2024003")).isNull();
    }
}
