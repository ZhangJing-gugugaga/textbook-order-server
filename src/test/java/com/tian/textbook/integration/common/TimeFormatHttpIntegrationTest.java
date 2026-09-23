package com.tian.textbook.integration.common;

import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.system.entity.AuditLog;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间入参格式的真实 HTTP 回归测试（body 与 query 双格式）。
 *
 * <p>回归背景（联调实测）：{@code API.md §1.1} 声明入参统一 {@code yyyy-MM-dd HH:mm:ss}，
 * 但 {@code spring.mvc.format.date-time} 只作用于 MVC 参数绑定——body 由 Jackson 按
 * {@code ISO_LOCAL_DATE_TIME} 解析，于是 body 传空格格式直接 400，且文案只有「请求参数有误」；
 * 审计查询（query）反过来只认空格格式，传 ISO 也 400。本类锁定「两种格式在两侧都可用」，
 * 并锁定出参仍为 ISO-8601（前端已按此适配，不能改）。</p>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimeFormatHttpIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AuditLogMapper auditLogMapper;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
    }

    @Test
    @DisplayName("body 时间入参：空格格式与 ISO 都接受（此前空格格式 400 PARAM_INVALID）")
    void windowSet_acceptsBothBodyFormats() {
        Semester semester = seeder.semester("2026-2027-时间格式", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        String token = adminToken();

        // ① 历史文档口径：空格分隔
        Map<String, Object> space = data(putWindow(semester.getId(), token,
                "{\"windowStart\":\"2026-09-21 09:30:00\",\"windowEnd\":\"2026-10-31 23:59:59\"}"));
        assertThat(space.get("windowStart")).isEqualTo("2026-09-21T09:30:00");

        // ② ISO-8601（前端实际发送的形态）
        Map<String, Object> iso = data(putWindow(semester.getId(), token,
                "{\"windowStart\":\"2026-09-22T08:00:00\",\"windowEnd\":\"2026-11-01T23:59:59\"}"));
        assertThat(iso.get("windowStart")).isEqualTo("2026-09-22T08:00:00");
        // 出参恒为 ISO-8601（不因入参格式而变）
        assertThat(iso.get("windowEnd")).isEqualTo("2026-11-01T23:59:59");
    }

    @Test
    @DisplayName("body 时间格式非法：400 PARAM_INVALID 且明细指明字段与可接受格式")
    void windowSet_invalidFormat_returnsFieldHint() {
        Semester semester = seeder.semester("2026-2027-时间格式-非法", null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1, 1);
        String token = adminToken();

        ResponseEntity<Map<String, Object>> response = putWindow(semester.getId(), token,
                "{\"windowStart\":\"2026/09/21 09:30\",\"windowEnd\":\"2026-10-31 23:59:59\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).get("code")).isEqualTo("PARAM_INVALID");
        assertThat(String.valueOf(body(response).get("data")))
                .contains("windowStart")
                .contains("ISO-8601");
    }

    @Test
    @DisplayName("B12：PUT /admin/semester/{id} 与窗口接口同口径——body 空格与 ISO 都接受，出参恒为 ISO")
    void updateSemester_acceptsBothBodyFormats() {
        Semester semester = seeder.semester("2026-2027-编辑时间格式", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(7), 1, 1);
        String token = adminToken();

        // 线上实测（后端测试报告 B12）：本接口接受空格格式并真实写库。开发团队联调报告记的
        // 「body 空格格式一律 400」是 TimeFormatConfig 上线前的旧行为——两个接口口径不一致的
        // 观感来自文档过期，而非实现分叉。此处把「两侧同口径」锁定为契约。
        ResponseEntity<Map<String, Object>> space = putSemester(semester.getId(), token,
                "{\"windowStart\":\"2026-10-01 00:00:00\",\"windowEnd\":\"2026-10-31 23:59:59\"}");
        assertThat(space.getStatusCode()).as("空格格式应与窗口接口一致被接受：%s", body(space))
                .isEqualTo(HttpStatus.OK);
        assertThat(data(space).get("windowStart")).isEqualTo("2026-10-01T00:00:00");

        // ISO-8601（前端实际发送形态）同样接受，出参不因入参格式而变
        ResponseEntity<Map<String, Object>> iso = putSemester(semester.getId(), token,
                "{\"windowStart\":\"2026-10-02T08:00:00\",\"windowEnd\":\"2026-11-01T23:59:59\"}");
        assertThat(iso.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(iso).get("windowStart")).isEqualTo("2026-10-02T08:00:00");

        // LocalDate 字段（startDate/endDate）同口径：纯日期与「多打了时间」的写法都接受
        ResponseEntity<Map<String, Object>> dates = putSemester(semester.getId(), token,
                "{\"startDate\":\"2026-09-02 00:00:00\",\"endDate\":\"2027-01-16\"}");
        assertThat(dates.getStatusCode()).as("日期字段应容忍带时间写法：%s", body(dates))
                .isEqualTo(HttpStatus.OK);
        assertThat(data(dates).get("startDate")).isEqualTo("2026-09-02");
        assertThat(data(dates).get("endDate")).isEqualTo("2027-01-16");

        // 真正的非法值仍然 400，且提示指明日期格式（宽容不等于放过错误输入）
        ResponseEntity<Map<String, Object>> bad = putSemester(semester.getId(), token,
                "{\"startDate\":\"2026/09/02\"}");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(body(bad).get("data"))).contains("startDate").contains("yyyy-MM-dd");
    }

    @Test
    @DisplayName("query 时间入参：审计查询接受 ISO-8601，且纯日期 endAt 含当天整天")
    void auditQuery_acceptsIsoAndDateOnlyEndOfDay() {
        SysUser admin = seeder.user("ADMTF", "超管", "13800000011", null, null, 1, 0, 1, "ADMIN");
        String token = jwtService.issueAccessToken(admin.getId(), admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", 1);

        // 当天 10:00 的审计记录
        LocalDate today = LocalDate.now();
        AuditLog log = new AuditLog();
        log.setAction("LOGIN");
        log.setResource("sys_user");
        log.setResourceId("1");
        log.setAt(LocalDateTime.of(today, LocalTime.of(10, 0)));
        auditLogMapper.insert(log);

        // ① ISO-8601 query（此前只认空格格式 → 400）
        ResponseEntity<Map<String, Object>> iso = audit(token,
                "?startAt=" + today + "T00:00:00&endAt=" + today + "T23:59:59");
        assertThat(iso.getStatusCode()).as("ISO query body=%s", body(iso)).isEqualTo(HttpStatus.OK);
        assertThat(total(iso)).isEqualTo(1);

        // ② 纯日期 endAt：按当日结束处理（此前是当日 00:00:00，会把当天记录整体排除）
        ResponseEntity<Map<String, Object>> dateOnly = audit(token, "?startAt=" + today + "&endAt=" + today);
        assertThat(dateOnly.getStatusCode()).as("dateOnly query body=%s", body(dateOnly)).isEqualTo(HttpStatus.OK);
        assertThat(total(dateOnly)).isEqualTo(1);

        // ③ 空格格式仍可用（向后兼容）。query 里的空格用 + 传输：
        // TestRestTemplate 会把 URL 当模板再编码一次，写 %20 会被双重编码成 %2520。
        ResponseEntity<Map<String, Object>> space = audit(token,
                "?startAt=" + today + "+00:00:00&endAt=" + today + "+23:59:59");
        assertThat(space.getStatusCode()).as("space query body=%s", body(space)).isEqualTo(HttpStatus.OK);
        assertThat(total(space)).isEqualTo(1);
    }

    // ============ 私有 ============

    private ResponseEntity<Map<String, Object>> putWindow(Long semesterId, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/admin/semester/" + semesterId + "/window", HttpMethod.PUT,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    /** 编辑学期基本信息（B12：与窗口接口同口径的时间入参）。 */
    private ResponseEntity<Map<String, Object>> putSemester(Long semesterId, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/admin/semester/" + semesterId, HttpMethod.PUT,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Map<String, Object>> audit(String token, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/admin/audit" + query, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
    }

    private String adminToken() {
        SysUser admin = seeder.user("ADMTFW", "超管", "13800000012", null, null, 1, 0, 1, "ADMIN");
        return jwtService.issueAccessToken(admin.getId(), admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", 1);
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

    private static int total(ResponseEntity<Map<String, Object>> response) {
        assertThat(body(response).get("code")).isEqualTo("0");
        Object data = body(response).get("data");
        assertThat(data).isInstanceOf(Map.class);
        Object total = ((Map<?, ?>) data).get("total");
        assertThat(total).as("审计查询返回体应含 total").isInstanceOf(Number.class);
        return ((Number) total).intValue();
    }

    /** 断言用：审计查询返回体中的 list 不为空（辅助排查失败原因）。 */
    @SuppressWarnings("unused")
    private static List<?> list(ResponseEntity<Map<String, Object>> response) {
        Object data = body(response).get("data");
        return data instanceof Map<?, ?> map && map.get("list") instanceof List<?> rows ? rows : List.of();
    }
}
