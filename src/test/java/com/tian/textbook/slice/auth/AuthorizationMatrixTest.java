package com.tian.textbook.slice.auth;

import com.tian.textbook.auth.AuthService;
import com.tian.textbook.auth.AuthUserService;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.auth.MeController;
import com.tian.textbook.auth.SecurityConfig;
import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.notify.controller.AdminNoticeController;
import com.tian.textbook.notify.controller.NoticeController;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.order.controller.AdminOrderController;
import com.tian.textbook.order.controller.SecretaryOrderController;
import com.tian.textbook.order.controller.StudentOrderController;
import com.tian.textbook.order.controller.TeacherOrderController;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.service.StudentOrderService;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.controller.SemesterWindowController;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.stats.controller.DashboardController;
import com.tian.textbook.stats.dto.DashboardResponse;
import com.tian.textbook.stats.service.StatsService;
import com.tian.textbook.supplier.SupplierController;
import com.tian.textbook.supplier.SupplierService;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.user.UserController;
import com.tian.textbook.system.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权矩阵切片测试（SPEC §14：5 角色 × 资源 × 操作 → 403；401 三类语义；must_change_password 拦截）。
 *
 * <p>@WebMvcTest 只加载 Controller 切片；安全链真实装配（@Import(SecurityConfig.class)，保留过滤器），
 * token 用真实 JwtService 签发（覆盖 JwtAuthFilter / MustChangePasswordFilter），
 * Service 层 @MockBean 打桩。@WebMvcTest 不加载 MyBatis DataPermissionInterceptor，
 * 数据隔离由集成测试 DataIsolationIntegrationTest 覆盖。</p>
 *
 * <p>注：@Nested 内部类不会继承 @WebMvcTest（@BootstrapWith 不追溯外层类），故用例平铺在本类。
 * Boot 3.3.x 尚无 @MockitoBean（3.4 才引入），此处用等价的 @MockBean。</p>
 */
@WebMvcTest(controllers = {
        AdminOrderController.class, SecretaryOrderController.class, StudentOrderController.class,
        TeacherOrderController.class, UserController.class, SupplierController.class,
        DashboardController.class, SemesterWindowController.class,
        AdminNoticeController.class, NoticeController.class, MeController.class,
        com.tian.textbook.auth.AuthController.class})
@ContextConfiguration(classes = TextbookOrderServerApplication.class)
@Import({SecurityConfig.class, AuthorizationMatrixTest.SliceTestConfig.class})
@ActiveProfiles("test")
class AuthorizationMatrixTest {

    private static final String TEST_SECRET = "slice-test-secret-32-bytes-length-ok!!";

    @MockBean
    private TeacherOrderService teacherOrderService;
    @MockBean
    private StudentOrderService studentOrderService;
    @MockBean
    private UserService userService;
    @MockBean
    private SupplierService supplierService;
    @MockBean
    private StatsService statsService;
    @MockBean
    private SemesterActiveService activeSemesterService;
    @MockBean
    private NotifyService notifyService;
    @MockBean
    private AuthService authService;
    @MockBean
    private AuthUserService authUserService;
    @MockBean
    private com.tian.textbook.system.user.UserProfileService userProfileService;
    @MockBean
    private SysUserMapper sysUserMapper;
    @MockBean
    private UserSemesterProfileMapper userSemesterProfileMapper;
    @MockBean
    private CollegeMapper collegeMapper;
    @MockBean
    private SchoolClassMapper schoolClassMapper;
    @MockBean
    private SemesterMapper semesterMapper;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    /** 真实签 token 所需的最小 bean（JwtService 自持密钥，避免与配置属性 bean 冲突）。 */
    @Configuration
    static class SliceTestConfig {

        @Bean
        JwtService jwtService() {
            TextbookProperties properties = new TextbookProperties();
            properties.getJwt().setSecret(TEST_SECRET);
            properties.getJwt().setAccessMinutes(15);
            return new JwtService(properties);
        }

        /** SecurityConfig 的 .cors(withDefaults()) 需要 CorsConfigurationSource bean。 */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            CorsConfiguration config = new CorsConfiguration();
            config.addAllowedOriginPattern("*");
            config.addAllowedHeader("*");
            config.addAllowedMethod("*");
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", config);
            return source;
        }
    }

    @BeforeEach
    void setUp() {
        // 管理端接口默认成功桩（越权断言只关心状态码/错误码，不关心业务数据）
        when(teacherOrderService.allFormsPage(any(), any(), any(), any(), any(Long.class), any(Long.class)))
                .thenReturn(PageResponse.of(List.<OrderFormListItem>of(), 1, 20, 0));
        when(teacherOrderService.collegeFormsPage(any(), any(), any(Long.class), any(Long.class)))
                .thenReturn(PageResponse.of(List.<OrderFormListItem>of(), 1, 20, 0));
        when(studentOrderService.getMyOrder()).thenReturn(null);
        when(supplierService.listOrders(any())).thenReturn(List.of());
        when(statsService.dashboard()).thenReturn(new DashboardResponse());
    }

    // ============ 主体装配 ============

    private void givenUser(long userId, String roleCode, boolean mustChangePassword,
                           boolean firstLoginVerified, int status, int roleVersion) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUserNo("U" + userId);
        user.setName("用户" + userId);
        user.setStatus(status);
        user.setMustChangePassword(mustChangePassword ? 1 : 0);
        user.setFirstLoginVerified(firstLoginVerified ? 1 : 0);
        user.setRoleVersion(roleVersion);
        when(authUserService.loadUser(userId)).thenReturn(user);

        SysRole role = new SysRole();
        role.setId(userId);
        role.setRoleCode(roleCode);
        role.setRoleName(roleCode);
        when(authUserService.loadRoles(userId)).thenReturn(List.of(role));
        when(authUserService.permissionsOf(anyList(), eq(roleCode)))
                .thenReturn(permissionsOf(roleCode));
    }

    /** 与 db/data-permission.sql 同源的简化权限集（越权矩阵判定用）。 */
    private static Set<String> permissionsOf(String roleCode) {
        return switch (roleCode) {
            case "ADMIN" -> Set.of("order:form:view:all", "order:form:review", "dashboard:stat:view",
                    "user:account:manage", "semester:semester:manage", "semester:semester:activate",
                    "semester:window:manage", "notice:task:view", "notice:task:manage");
            case "SECRETARY" -> Set.of("order:form:view:college", "semester:window:view",
                    "change:request:submit", "import:batch:view");
            case "TEACHER" -> Set.of("order:form:submit", "order:form:view:self",
                    "semester:window:view", "change:request:submit");
            case "STUDENT" -> Set.of("student:order:submit", "student:order:view:self",
                    "semester:window:view");
            case "SUPPLIER" -> Set.of("supplier:order:view", "supplier:order:export");
            default -> Set.of();
        };
    }

    private String token(long userId, String roleCode) {
        return jwtService.issueAccessToken(userId, "U" + userId, "用户" + userId,
                Set.of(roleCode), roleCode, 1);
    }

    private String expiredToken(long userId, String roleCode) {
        TextbookProperties properties = new TextbookProperties();
        properties.getJwt().setSecret(TEST_SECRET);
        properties.getJwt().setAccessMinutes(-15);
        JwtService expiredIssuer = new JwtService(properties);
        return expiredIssuer.issueAccessToken(userId, "U" + userId, "用户" + userId,
                Set.of(roleCode), roleCode, 1);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ============ 401 三类语义（契约冻结项） ============

    @Test
    @DisplayName("无 token 访问受保护路由 → 401 UNAUTHORIZED")
    void adminOrderForms_withoutToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/order-forms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("篡改 token → 401 TOKEN_INVALID")
    void adminOrderForms_tamperedToken_returns401TokenInvalid() throws Exception {
        givenUser(1L, "ADMIN", false, true, 1, 1);
        String[] parts = token(1L, "ADMIN").split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xy"
                + "." + parts[2];

        mockMvc.perform(get("/api/admin/order-forms").header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("过期 token → 401 TOKEN_EXPIRED")
    void adminOrderForms_expiredToken_returns401TokenExpired() throws Exception {
        givenUser(1L, "ADMIN", false, true, 1, 1);

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(expiredToken(1L, "ADMIN"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("账号停用 → 401 ACCOUNT_DISABLED")
    void adminOrderForms_disabledAccount_returns401AccountDisabled() throws Exception {
        givenUser(1L, "ADMIN", false, true, 0, 1);

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(token(1L, "ADMIN"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    @DisplayName("角色版本失效（role_version 不匹配）→ 401 REFRESH_INVALID")
    void adminOrderForms_roleVersionMismatch_returns401RefreshInvalid() throws Exception {
        givenUser(1L, "ADMIN", false, true, 1, 2); // 库中 roleVersion=2，token 中为 1

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(token(1L, "ADMIN"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    // ============ 越权矩阵：5 角色 × 资源 × 操作 → 403 ============

    @Test
    @DisplayName("STUDENT 访问 /api/admin/order-forms → 403 FORBIDDEN")
    void adminOrderForms_asStudent_returns403Forbidden() throws Exception {
        givenUser(10L, "STUDENT", false, true, 1, 1);

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(token(10L, "STUDENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("TEACHER 调用内容审核 /api/admin/order-forms/{id}/review → 403 FORBIDDEN")
    void orderFormReview_asTeacher_returns403Forbidden() throws Exception {
        givenUser(20L, "TEACHER", false, true, 1, 1);

        mockMvc.perform(post("/api/admin/order-forms/1/review")
                        .header("Authorization", bearer(token(20L, "TEACHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SECRETARY 访问 /api/admin/user → 403 FORBIDDEN")
    void adminUser_asSecretary_returns403Forbidden() throws Exception {
        givenUser(30L, "SECRETARY", false, true, 1, 1);

        mockMvc.perform(get("/api/admin/user")
                        .header("Authorization", bearer(token(30L, "SECRETARY"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SUPPLIER 访问 /api/student/order → 403 FORBIDDEN")
    void studentOrder_asSupplier_returns403Forbidden() throws Exception {
        givenUser(40L, "SUPPLIER", false, true, 1, 1);

        mockMvc.perform(get("/api/student/order")
                        .header("Authorization", bearer(token(40L, "SUPPLIER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("STUDENT 访问 /api/secretary/order-forms → 403 FORBIDDEN")
    void secretaryOrderForms_asStudent_returns403Forbidden() throws Exception {
        givenUser(11L, "STUDENT", false, true, 1, 1);

        mockMvc.perform(get("/api/secretary/order-forms")
                        .header("Authorization", bearer(token(11L, "STUDENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("STUDENT 访问 /api/supplier/orders → 403 FORBIDDEN")
    void supplierOrders_asStudent_returns403Forbidden() throws Exception {
        givenUser(12L, "STUDENT", false, true, 1, 1);

        mockMvc.perform(get("/api/supplier/orders")
                        .header("Authorization", bearer(token(12L, "STUDENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ============ 正向：角色访问授权资源 → 200 ============

    @Test
    @DisplayName("SECRETARY 访问 /api/secretary/order-forms → 200 分页")
    void secretaryOrderForms_asSecretary_returns200Page() throws Exception {
        givenUser(30L, "SECRETARY", false, true, 1, 1);

        mockMvc.perform(get("/api/secretary/order-forms")
                        .header("Authorization", bearer(token(30L, "SECRETARY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    @DisplayName("SUPPLIER 访问 /api/supplier/orders → 200")
    void supplierOrders_asSupplier_returns200() throws Exception {
        givenUser(40L, "SUPPLIER", false, true, 1, 1);

        mockMvc.perform(get("/api/supplier/orders")
                        .header("Authorization", bearer(token(40L, "SUPPLIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    @Test
    @DisplayName("ADMIN 访问 /api/admin/dashboard → 200")
    void dashboard_asAdmin_returns200() throws Exception {
        givenUser(1L, "ADMIN", false, true, 1, 1);

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", bearer(token(1L, "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    @Test
    @DisplayName("ADMIN 访问 /api/admin/order-forms → 200 分页")
    void adminOrderForms_asAdmin_returns200Page() throws Exception {
        givenUser(1L, "ADMIN", false, true, 1, 1);

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(token(1L, "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("STUDENT 访问 /api/student/order → 200")
    void studentOrder_asStudent_returns200() throws Exception {
        givenUser(10L, "STUDENT", false, true, 1, 1);

        mockMvc.perform(get("/api/student/order")
                        .header("Authorization", bearer(token(10L, "STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    @Test
    @DisplayName("STUDENT 访问 /api/semester/window/status → 200 且含 serverTime")
    void windowStatus_asStudent_returns200WithServerTime() throws Exception {
        givenUser(10L, "STUDENT", false, true, 1, 1);
        when(activeSemesterService.active()).thenReturn(null);

        mockMvc.perform(get("/api/semester/window/status")
                        .header("Authorization", bearer(token(10L, "STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.serverTime").exists());
    }

    // ============ 首登拦截（W19） ============

    @Test
    @DisplayName("must_change_password 用户访问业务接口 → 403 FIRST_LOGIN_REQUIRED")
    void businessEndpoint_withMustChangePassword_returns403FirstLoginRequired() throws Exception {
        givenUser(50L, "STUDENT", true, false, 1, 1);

        mockMvc.perform(get("/api/student/book-list")
                        .header("Authorization", bearer(token(50L, "STUDENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FIRST_LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("must_change_password 用户访问 /api/me → 200（放行）")
    void meEndpoint_withMustChangePassword_returns200() throws Exception {
        givenUser(50L, "STUDENT", true, false, 1, 1);
        // MeController 经 AuthUserService.loadUser 取用户（givenUser 已打桩）；
        // 无 active 学期 → 归属块跳过，activeSemester=null
        when(activeSemesterService.active()).thenReturn(null);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", bearer(token(50L, "STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(1));
    }

    @Test
    @DisplayName("first_login_verified=0 用户访问 /api/notice/unconfirmed → 403 FIRST_LOGIN_REQUIRED")
    void noticeUnconfirmed_withFirstLoginNotVerified_returns403FirstLoginRequired() throws Exception {
        givenUser(51L, "STUDENT", false, false, 1, 1);

        mockMvc.perform(get("/api/notice/unconfirmed")
                        .header("Authorization", bearer(token(51L, "STUDENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FIRST_LOGIN_REQUIRED"));
    }

    // ============ 多角色并集（W10：权限码取当前身份） ============

    @Test
    @DisplayName("秘书+教师 双角色，currentRole=SECRETARY 访问超管接口 → 403 FORBIDDEN")
    void adminOrderForms_asSecretaryTeacherMultiRole_returns403Forbidden() throws Exception {
        givenMultiRoleUser(60L, "SECRETARY");

        mockMvc.perform(get("/api/admin/order-forms")
                        .header("Authorization", bearer(jwtService.issueAccessToken(60L, "U60",
                                "用户60", Set.of("SECRETARY", "TEACHER"), "SECRETARY", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("秘书+教师 双角色，currentRole=SECRETARY 访问本院接口 → 200")
    void secretaryOrderForms_asSecretaryTeacherMultiRole_returns200() throws Exception {
        givenMultiRoleUser(61L, "SECRETARY");

        mockMvc.perform(get("/api/secretary/order-forms")
                        .header("Authorization", bearer(jwtService.issueAccessToken(61L, "U61",
                                "用户61", Set.of("SECRETARY", "TEACHER"), "SECRETARY", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    private void givenMultiRoleUser(long userId, String currentRole) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUserNo("U" + userId);
        user.setName("用户" + userId);
        user.setStatus(1);
        user.setMustChangePassword(0);
        user.setFirstLoginVerified(1);
        user.setRoleVersion(1);
        when(authUserService.loadUser(userId)).thenReturn(user);
        SysRole secretary = new SysRole();
        secretary.setId(userId);
        secretary.setRoleCode("SECRETARY");
        SysRole teacher = new SysRole();
        teacher.setId(userId + 1000);
        teacher.setRoleCode("TEACHER");
        when(authUserService.loadRoles(userId)).thenReturn(List.of(secretary, teacher));
        when(authUserService.permissionsOf(anyList(), eq("SECRETARY")))
                .thenReturn(permissionsOf("SECRETARY"));
        when(authUserService.permissionsOf(anyList(), eq("TEACHER")))
                .thenReturn(permissionsOf("TEACHER"));
    }

    // ============ 公开路由（登录） ============

    @Test
    @DisplayName("/api/auth/login 为公开路由：密码错误走业务 401 LOGIN_FAILED 而非安全 401")
    void authLogin_wrongCredentials_isPublicRoute_returns401LoginFailed() throws Exception {
        when(authService.login(any(), anyString(), any()))
                .thenThrow(new com.tian.textbook.common.error.BizException(
                        com.tian.textbook.common.error.ErrorCode.LOGIN_FAILED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userNo\":\"2024001\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("/api/auth/login 登录成功 → 200 且返回 accessToken")
    void authLogin_serviceReturnedResponse_is200() throws Exception {
        when(authService.login(any(), anyString(), any()))
                .thenReturn(new com.tian.textbook.auth.dto.AuthResponse(
                        "access-token", "refresh-token", 900L, false, true,
                        List.of("STUDENT"), "STUDENT", "2024001", "张三"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userNo\":\"2024001\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));
    }
}
