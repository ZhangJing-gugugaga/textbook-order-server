package com.tian.textbook.integration.semester;

import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.auth.JwtService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 学期归档/激活的二次门禁（B11 / B10 / B15，生产实测缺陷的回归）。
 *
 * <p>回归背景（线上真实事故）：{@code POST /api/admin/semester/{id}/archive} 不读 body、
 * 无任何门禁，一次空 body 调用即把进行中的学期归档——归档后没有 active 学期，
 * 学生选购/教师填报/导出统一报「当前没有激活学期」，业务停摆，恢复只能整库备份还原。
 * 本类锁定修复后的语义：</p>
 * <ul>
 *   <li>归档必须带 {@code version}（乐观锁）；</li>
 *   <li>窗口进行中必须显式 {@code confirmWindowOpen=true}，否则 409 且文案说明影响；</li>
 *   <li>重复 activate → 409（而非被 version 必填的 400 掩盖）；</li>
 *   <li>撤销归档仅在「当前无 active 学期」时可用，且必须 {@code confirm=true}。</li>
 * </ul>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SemesterArchiveGateHttpIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SemesterMapper semesterMapper;

    private String token;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
        SysUser admin = seeder.user("ADMARC", "超管", "13800000021", null, null, 1, 0, 1, "ADMIN");
        token = jwtService.issueAccessToken(admin.getId(), admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", 1);
    }

    @Test
    @DisplayName("B11：窗口进行中的 active 学期——空 body 归档被拒（409），且学期状态不变")
    void archive_activeSemesterWithOpenWindow_requiresExplicitConfirm() {
        Semester semester = activeSemesterWithOpenWindow("2026-2027-归档门禁1");

        // ① 空 body：既没有 version 也没有确认标记 → 拒绝（线上事故的原始调用形态）
        ResponseEntity<Map<String, Object>> empty = post("/api/admin/semester/" + semester.getId() + "/archive",
                null);
        assertThat(empty.getStatusCode()).as("空 body 归档必须被拒：%s", body(empty))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(empty).get("code")).isEqualTo("PARAM_INVALID");
        assertThat(semesterMapper.selectByIdSoft(semester.getId()).getActiveStatus())
                .as("被拒的请求不得改动学期状态").isEqualTo("active");

        // ② 只带 version（前端旧版调用）：窗口进行中 → 409，文案说明「全站停摆」与两条路径
        ResponseEntity<Map<String, Object>> noConfirm = post(
                "/api/admin/semester/" + semester.getId() + "/archive",
                "{\"version\":" + semesterMapper.selectByIdSoft(semester.getId()).getVersion() + "}");
        assertThat(noConfirm.getStatusCode()).as("窗口进行中未确认必须 409：%s", body(noConfirm))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(noConfirm).get("code")).isEqualTo("STATE_CONFLICT");
        assertThat(String.valueOf(body(noConfirm).get("message")))
                .contains("窗口仍在进行中")
                .contains("confirmWindowOpen=true");
        assertThat(semesterMapper.selectByIdSoft(semester.getId()).getActiveStatus())
                .isEqualTo("active");

        // ③ 显式确认后才允许归档
        Semester fresh = semesterMapper.selectByIdSoft(semester.getId());
        ResponseEntity<Map<String, Object>> confirmed = post(
                "/api/admin/semester/" + semester.getId() + "/archive",
                "{\"version\":" + fresh.getVersion() + ",\"confirmWindowOpen\":true}");
        assertThat(confirmed.getStatusCode()).as("显式确认后应可归档：%s", body(confirmed))
                .isEqualTo(HttpStatus.OK);
        Semester archived = semesterMapper.selectByIdSoft(semester.getId());
        assertThat(archived.getActiveStatus()).isEqualTo("archived");
        assertThat(archived.getChannelOpen()).as("归档必须同时关闭窗口").isZero();
        assertThat(archived.getWindowStatus()).isEqualTo("closed");
    }

    @Test
    @DisplayName("B11：窗口未开启的 active 学期——带 version 即可归档；version 过期 409")
    void archive_closedWindow_requiresVersionOnly() {
        Semester semester = seeder.semester("2026-2027-归档门禁2", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), LocalDateTime.now().minusDays(30),
                LocalDateTime.now().minusDays(1), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        int version = semesterMapper.selectByIdSoft(semester.getId()).getVersion();

        // 过期 version → 409（防「读到旧状态后按旧认知归档」）
        ResponseEntity<Map<String, Object>> stale = post(
                "/api/admin/semester/" + semester.getId() + "/archive",
                "{\"version\":" + (version + 99) + "}");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(semesterMapper.selectByIdSoft(semester.getId()).getActiveStatus()).isEqualTo("active");

        // 正确 version：窗口未开启（not_open/closed 且 channel_open=0）→ 无需确认
        ResponseEntity<Map<String, Object>> ok = post(
                "/api/admin/semester/" + semester.getId() + "/archive",
                "{\"version\":" + version + "}");
        assertThat(ok.getStatusCode()).as("窗口未开启时带 version 即可归档：%s", body(ok))
                .isEqualTo(HttpStatus.OK);
        assertThat(semesterMapper.selectByIdSoft(semester.getId()).getActiveStatus()).isEqualTo("archived");
    }

    @Test
    @DisplayName("B10：重复 activate 已在 active 的学期 → 409（空 body 也应是 409，而非 400 参数错误）")
    void activate_alreadyActive_returns409EvenWithoutBody() {
        Semester semester = activeSemesterWithOpenWindow("2026-2027-重复激活");

        ResponseEntity<Map<String, Object>> empty = post("/api/admin/semester/" + semester.getId() + "/activate",
                null);
        assertThat(empty.getStatusCode()).as("重复激活必须是状态冲突：%s", body(empty))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(empty).get("code")).isEqualTo("STATE_CONFLICT");
        assertThat(String.valueOf(body(empty).get("message"))).contains("已是激活学期");

        // draft 学期缺 version → 400 且文案指明怎么取（参数错误只用于真正缺参的场景）
        Semester draft = seeder.semester("2026-2027-缺版本", null, null, null, null, 1, 1);
        ResponseEntity<Map<String, Object>> missing = post("/api/admin/semester/" + draft.getId() + "/activate",
                "{}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(body(missing).get("message"))).contains("version 不能为空");
    }

    @Test
    @DisplayName("B15：撤销归档——无 active 学期时可回滚（窗口保持关闭）；有 active 学期/未确认时被拒")
    void unarchive_restrictedRollback() {
        Semester semester = activeSemesterWithOpenWindow("2026-2027-撤销归档");
        semesterMapper.archiveIfActive(semester.getId());
        Semester archived = semesterMapper.selectByIdSoft(semester.getId());
        assertThat(archived.getActiveStatus()).isEqualTo("archived");

        // ① 未确认 → 400（该操作会把学期恢复为 active，必须显式确认）
        ResponseEntity<Map<String, Object>> noConfirm = post(
                "/api/admin/semester/" + semester.getId() + "/unarchive",
                "{\"version\":" + archived.getVersion() + "}");
        assertThat(noConfirm.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(body(noConfirm).get("message"))).contains("confirm=true");

        // ② 显式确认 + version 匹配 → 恢复 active；窗口保持关闭（不随回滚自动开放填报）
        ResponseEntity<Map<String, Object>> ok = post(
                "/api/admin/semester/" + semester.getId() + "/unarchive",
                "{\"version\":" + archived.getVersion() + ",\"confirm\":true}");
        assertThat(ok.getStatusCode()).as("无 active 学期时应允许撤销归档：%s", body(ok))
                .isEqualTo(HttpStatus.OK);
        Semester restored = semesterMapper.selectByIdSoft(semester.getId());
        assertThat(restored.getActiveStatus()).isEqualTo("active");
        assertThat(restored.getChannelOpen()).as("回滚不自动开放填报通道").isZero();
        assertThat(restored.getWindowStatus()).isEqualTo("closed");

        // ③ 已有 active 学期时不得再回滚（避免两个 active / 静默归档新学期）：
        //    restored 此刻是 active，另造一个 archived 学期尝试回滚 → 409
        Semester anotherArchived = seeder.semester("2026-2027-待回滚", null, null, null, null, 1, 1);
        semesterMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Semester>lambdaUpdate().eq(Semester::getId, anotherArchived.getId())
                .set(Semester::getActiveStatus, "archived"));
        Semester pending = semesterMapper.selectByIdSoft(anotherArchived.getId());
        ResponseEntity<Map<String, Object>> conflict = post(
                "/api/admin/semester/" + pending.getId() + "/unarchive",
                "{\"version\":" + pending.getVersion() + ",\"confirm\":true}");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(body(conflict).get("message"))).contains("已有激活学期");
    }

    // ============ 私有 ============

    /** active 学期 + 窗口进行中（channel_open=1 / window_status=open）——B11 的复现前置条件 */
    private Semester activeSemesterWithOpenWindow(String name) {
        Semester semester = seeder.semester(name, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 15),
                LocalDateTime.now().minusDays(9), LocalDateTime.now().plusDays(38), 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        semesterMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Semester>lambdaUpdate().eq(Semester::getId, semester.getId())
                .set(Semester::getChannelOpen, 1)
                .set(Semester::getWindowStatus, "open"));
        return semesterMapper.selectByIdSoft(semester.getId());
    }

    private ResponseEntity<Map<String, Object>> post(String path, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(jsonBody, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody() == null ? Map.of() : response.getBody();
    }
}
