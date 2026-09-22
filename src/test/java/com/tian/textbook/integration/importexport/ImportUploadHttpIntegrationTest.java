package com.tian.textbook.integration.importexport;

import com.alibaba.excel.EasyExcel;
import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.system.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入接口「真实 HTTP multipart 上传」回归测试。
 *
 * <p>背景缺陷：{@code textbook.export.tmp-dir} 默认是相对路径（test profile =
 * {@code ./data/test-export}），而 {@code MultipartFile.transferTo(File)} 在 servlet 容器下
 * 委托 {@code Part.write}，相对路径以 multipart location 为基准解析，未配置 location 时抛
 * IOException → 5 个导入接口全部返回 400「文件保存失败」。既有集成测试用 MockMultipartFile
 * （其 transferTo 走内存拷贝，不经 Part.write），因此漏检该缺陷。</p>
 *
 * <p>本类以 {@code RANDOM_PORT} 启动真实 Tomcat + TestRestTemplate 发起真实 multipart 上传，
 * 锁定「相对 tmp-dir 下导入仍成功」不再回归（对应实现：ImportServiceImpl#saveUpload 绝对化 + transferTo(Path)）。</p>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ImportUploadHttpIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SemesterMapper semesterMapper;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
    }

    @Test
    @DisplayName("真实 multipart 上传：相对 tmp-dir 下导入成功（200 + 批次 done，非 400 文件保存失败）")
    void realHttpMultipartUpload_succeeds() {
        Semester semester = seedOrgAndSemester();
        String token = adminToken();

        ResponseEntity<Map<String, Object>> upload = restTemplate.exchange(
                "/api/admin/user/import?role=student&semesterId=" + semester.getId(),
                HttpMethod.POST,
                multipartEntity(studentXlsx(
                        "2025001|张三|计算机学院|软件工程|软工2401|13800000001"), "students.xlsx", token),
                new ParameterizedTypeReference<>() {
                });

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(upload).get("code")).isEqualTo("0");
        long batchId = ((Number) data(upload).get("batchId")).longValue();

        // 批次跑完：证明上传的 xlsx 真被解析落库，而非仅「上传没报错」
        Map<String, Object> batch = awaitBatchDone(batchId, token);
        assertThat(batch.get("status")).isEqualTo("done");
        assertThat(((Number) batch.get("okCount")).intValue()).isEqualTo(1);
        assertThat(((Number) batch.get("errorCount")).intValue()).isZero();
        assertThat(seeder.userIdByNo("2025001")).isNotNull();
    }

    @Test
    @DisplayName("真实 multipart 上传：内容非 xlsx → 400 FILE_TYPE_INVALID（容器层放行后业务校验仍生效）")
    void realHttpMultipartUpload_rejectsNonXlsx() {
        Semester semester = seedOrgAndSemester();
        String token = adminToken();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/admin/user/import?role=student&semesterId=" + semester.getId(),
                HttpMethod.POST,
                multipartEntity("not-a-zip".getBytes(StandardCharsets.UTF_8), "students.xlsx", token),
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).get("code")).isEqualTo("FILE_TYPE_INVALID");
    }

    // ============ 私有 ============

    private Semester seedOrgAndSemester() {
        var college = seeder.college("计算机学院");
        var major = seeder.major(college.getId(), "软件工程");
        seeder.schoolClass(major.getId(), "软工2401", 0);
        Semester semester = seeder.semester("2026-2027-HTTP", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        return semester;
    }

    /** 真实签名 token（走 JwtAuthFilter 全链路：用户存在 + role_version 匹配 + 非首登待改密）。 */
    private String adminToken() {
        SysUser admin = seeder.user("ADMINHTTP", "超管", "13800000009", null, null, 1, 0, 1, "ADMIN");
        return jwtService.issueAccessToken(admin.getId(), admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", 1);
    }

    /** 学生名单 xlsx 字节（列：学号|姓名|学院|专业|班级|手机号）。 */
    private static byte[] studentXlsx(String row) {
        String[] parts = row.split("\\|");
        StudentImportRow item = new StudentImportRow();
        item.setUserNo(parts[0]);
        item.setName(parts[1]);
        item.setCollegeName(parts[2]);
        item.setMajorName(parts[3]);
        item.setClassName(parts[4]);
        item.setPhone(parts[5]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, StudentImportRow.class).sheet("学生名单").doWrite(List.of(item));
        return out.toByteArray();
    }

    private static HttpEntity<MultiValueMap<String, Object>> multipartEntity(byte[] content,
                                                                             String fileName, String token) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> awaitBatchDone(long batchId, String token) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        Map<String, Object> batch = fetchBatch(batchId, token);
        while (!"done".equals(batch.get("status")) && !"failed".equals(batch.get("status"))) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            batch = fetchBatch(batchId, token);
        }
        return batch;
    }

    private Map<String, Object> fetchBatch(long batchId, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/batch/" + batchId, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return data(response);
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        Object data = body(response).get("data");
        assertThat(data).isInstanceOf(Map.class);
        return (Map<String, Object>) data;
    }
}
