package com.tian.textbook.integration.supplier;

import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.entity.OrderFormItem;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.support.OrderScenarioFactory;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.TextbookMapper;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供货商导出契约与物理隔离（真实 HTTP）。
 *
 * <p>回归背景（P0/P1 复核项）：</p>
 * <ol>
 *   <li>异步受理体只有 {@code {taskId}}，而 API.md §3.13 与其余四类导出都是
 *       {@code {taskId, async, rowEstimate}}——前端只能靠 Content-Type 分流才没踩到；</li>
 *   <li>「物理隔离」此前只按 created_by 判定：供货商自己的任务经内部端点
 *       {@code /api/export-task/{id}} 同样可读、可下载（可枚举 id 访问内部任务的风险由
 *       bizType 白名单挡住，但隔离表述与实现不符）。</li>
 * </ol>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SupplierExportContractIntegrationTest {

    private static final int REVIEWED_ITEMS = 101;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private OrderScenarioFactory scenarioFactory;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ExportTaskMapper exportTaskMapper;
    @Autowired
    private OrderFormMapper orderFormMapper;
    @Autowired
    private OrderFormItemMapper orderFormItemMapper;
    @Autowired
    private ConfigService configService;
    @Autowired
    private TextbookMapper textbookMapper;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
    }

    @Test
    @DisplayName("供货商异步导出受理体 = {taskId, async, rowEstimate}（与其余四类导出同形）")
    void supplierExportAsync_returnsSameEnvelopeAsInternalExports() {
        var scenario = scenarioFactory.seed("SPX");
        // 阈值取白名单下界 100，使 101 行明细必然走异步分支（避免为测试插入数千行）
        configService.update(Map.of(ConfigService.EXPORT_SYNC_ROW_THRESHOLD, "100"));
        seedReviewedItems(scenario.semesterId(), scenario.teacherId(),
                scenario.courseId(), scenario.classId(), scenario.textbookId(), REVIEWED_ITEMS);
        String token = supplierToken();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/supplier/export", HttpMethod.POST, jsonEntity("{\"semesterId\":" + scenario.semesterId() + "}", token),
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody() == null ? Map.of() : response.getBody();
        assertThat(body.get("code")).isEqualTo("0");
        assertThat(data(body))
                .as("异步受理体必须与内部导出一致（前端按同一套字段分流）")
                .containsEntry("async", true)
                .containsEntry("rowEstimate", REVIEWED_ITEMS)
                .containsKey("taskId");
    }

    @Test
    @DisplayName("物理隔离：供货商任务只能走 /api/supplier/export-task/**，内部端点对其 404")
    void supplierTask_isNotReachableThroughInternalEndpoints() {
        var scenario = scenarioFactory.seed("SPI");
        SysUser supplier = seeder.user("SUPI", "供货商", "13800002222", null, null, 1, 0, 1, "SUPPLIER");
        SysUser admin = seeder.user("ADMI", "超管", "13800003333", null, null, 1, 0, 1, "ADMIN");
        ExportTask supplierTask = seedTask("supplier", supplier.getId(), "tok-sup");
        ExportTask internalTask = seedTask("order", admin.getId(), "tok-order");
        String supplierToken = token(supplier);

        // 内部进度端点：供货商任务 → 404（隔离）；内部任务 → 404（越权，已有用例覆盖，此处一并锁定）
        assertThat(getStatus("/api/export-task/" + supplierTask.getId(), supplierToken)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getStatus("/api/export-task/" + internalTask.getId(), supplierToken)).isEqualTo(HttpStatus.NOT_FOUND);
        // 内部下载端点同理（token 正确也不放行）
        assertThat(getStatus("/api/export-task/" + supplierTask.getId() + "/download?token=tok-sup", supplierToken))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 供货商自己的端点仍然可用（回归：隔离不得误伤正常链路）
        ResponseEntity<Map<String, Object>> own = restTemplate.exchange(
                "/api/supplier/export-task/" + supplierTask.getId(), HttpMethod.GET,
                bearer(supplierToken), new ParameterizedTypeReference<>() {
                });
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(own.getBody())).containsKey("downloadToken");

        // ADMIN 例外：超管无 supplier:order:export 权限，内部端点是其唯一排障入口
        assertThat(getStatus("/api/export-task/" + supplierTask.getId(), token(admin))).isEqualTo(HttpStatus.OK);
        assertThat(scenario.semesterId()).isNotNull();
    }

    // ============ 私有 ============

    private void seedReviewedItems(Long semesterId, Long teacherId, Long courseId, Long classId,
                                   Long textbookId, int count) {
        OrderForm form = new OrderForm();
        form.setSemesterId(semesterId);
        form.setTeacherId(teacherId);
        form.setStatus("reviewed");
        form.setContentVersion(1);
        form.setCreatedBy(teacherId);
        form.setDeleted(0L);
        orderFormMapper.insert(form);
        // 明细唯一键 = (form, course, class, textbook)：同一教材只能出现一次，
        // 故为每行配一本独立教材（导出按行计数，教材本身无校验要求）
        List<OrderFormItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            OrderFormItem item = new OrderFormItem();
            item.setFormId(form.getId());
            item.setCourseId(courseId);
            item.setClassId(classId);
            item.setTextbookId(i == 0 ? textbookId : seedTextbook(i).getId());
            item.setQuantity(1);
            item.setDeleted(0L);
            items.add(item);
        }
        items.forEach(orderFormItemMapper::insert);
    }

    private Textbook seedTextbook(int index) {
        Textbook textbook = new Textbook();
        textbook.setIsbn(String.format("978000000%04d", index));
        textbook.setTitle("测试教材" + index);
        textbook.setStatus(1);
        textbook.setDeleted(0L);
        textbookMapper.insert(textbook);
        return textbook;
    }

    private ExportTask seedTask(String bizType, Long createdBy, String token) {
        ExportTask task = new ExportTask();
        task.setBizType(bizType);
        task.setParamsJson(Map.of("semesterId", 1));
        task.setRowEstimate(10);
        task.setStatus("done");
        task.setProgressPct(100);
        task.setFilePath("data/export/seed.xlsx");
        task.setDownloadToken(token);
        task.setTokenExpireAt(com.tian.textbook.common.util.AppTime.now().plusMinutes(10));
        task.setCreatedBy(createdBy);
        task.setDeleted(0L);
        exportTaskMapper.insert(task);
        return task;
    }

    private HttpStatusCode getStatus(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, bearer(token),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }).getStatusCode();
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<String> jsonEntity(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    private String supplierToken() {
        SysUser supplier = seeder.user("SUPXP", "供货商", "13800004444", null, null, 1, 0, 1, "SUPPLIER");
        return token(supplier);
    }

    private String token(SysUser user) {
        return jwtService.issueAccessToken(user.getId(), user.getUserNo(), user.getName(),
                Set.of(user.getUserNo().startsWith("9") ? "ADMIN" : "SUPPLIER"),
                user.getUserNo().startsWith("9") ? "ADMIN" : "SUPPLIER", 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> body) {
        Object data = body == null ? null : body.get("data");
        assertThat(data).isInstanceOf(Map.class);
        return (Map<String, Object>) data;
    }
}
