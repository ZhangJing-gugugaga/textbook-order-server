package com.tian.textbook.arch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.order.controller.AdminOrderController;
import com.tian.textbook.order.controller.SecretaryOrderController;
import com.tian.textbook.order.controller.StudentOrderController;
import com.tian.textbook.order.controller.TeacherOrderController;
import com.tian.textbook.supplier.SupplierController;
import com.tian.textbook.system.user.UserController;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.order.service.TeacherOrderService;
import com.tian.textbook.order.service.StudentOrderService;
import com.tian.textbook.notify.service.NotifyService;
import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.importexport.service.ExportServiceImpl;
import com.tian.textbook.importexport.service.ImportServiceImpl;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.stats.service.StatsService;
import com.tian.textbook.supplier.SupplierService;
import com.tian.textbook.auth.AuthService;
import com.tian.textbook.system.user.UserService;
import com.tian.textbook.system.org.OrgService;
import com.tian.textbook.textbook.service.TextbookService;
import com.tian.textbook.textbook.service.CourseService;
import com.tian.textbook.textbook.service.TeacherCourseService;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.order.service.FieldCheckService;
import com.tian.textbook.semester.WindowGuardImpl;
import com.tian.textbook.approval.controller.AdminChangeRequestController;
import com.tian.textbook.approval.controller.ChangeRequestController;
import com.tian.textbook.auth.AuthController;
import com.tian.textbook.auth.MeController;
import com.tian.textbook.importexport.controller.AdminImportController;
import com.tian.textbook.importexport.controller.ExportController;
import com.tian.textbook.importexport.controller.ImportBatchController;
import com.tian.textbook.notify.controller.AdminNoticeController;
import com.tian.textbook.notify.controller.NoticeController;
import com.tian.textbook.stats.controller.DashboardController;
import com.tian.textbook.semester.controller.AdminSemesterController;
import com.tian.textbook.semester.controller.SemesterWindowController;
import com.tian.textbook.system.audit.AuditController;
import com.tian.textbook.system.config.ConfigController;
import com.tian.textbook.system.org.OrgController;
import com.tian.textbook.textbook.controller.AdminCourseController;
import com.tian.textbook.textbook.controller.AdminTeacherCourseController;
import com.tian.textbook.textbook.controller.AdminTextbookController;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 架构机检红线（SPEC §2 / §14 CI 等价物：ArchUnit）。
 *
 * <ol>
 *   <li>supplier 包不得 import order.*Mapper 与 system.*UserMapper（物理隔离）；</li>
 *   <li>common 包不得 import 任何业务 Mapper；</li>
 *   <li>Controller 不得直接注入 Mapper（分层：Controller → Service → Mapper）；</li>
 *   <li>Service 方法不得出现 HttpServletRequest/Response 参数（导出流式场景 OutputStream 除外）。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.tian.textbook", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchUnitTest {

    private static final String SUPPLIER_PACKAGE = "..supplier..";
    private static final String COMMON_PACKAGE = "..common..";

    /** 机检红线 1：供货商模块物理隔离（SPEC §2）。 */
    @ArchTest
    static final ArchRule supplierPackagesMustNotDependOnOrderOrUserMappers =
            noClasses().that().resideInAPackage(SUPPLIER_PACKAGE)
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.tian.textbook.order.mapper.OrderFormMapper")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.tian.textbook.order.mapper.OrderFormItemMapper")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.tian.textbook.order.mapper.StudentOrderMapper")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.tian.textbook.order.mapper.StudentOrderItemMapper")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.tian.textbook.system.mapper.SysUserMapper");

    /** 机检红线 2：common 包不得 import 任何业务 Mapper（含 supplier 自建 Mapper）。 */
    @ArchTest
    static final ArchRule commonMustNotDependOnBusinessMappers =
            noClasses().that().resideInAPackage(COMMON_PACKAGE)
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.tian.textbook..mapper..")
                    .because("SPEC §2 机检红线：common 只定义接口/切面，业务数据访问走 Service");

    /** 机检红线 3：Controller 不得依赖（注入/引用）Mapper（分层约束；MeController/AuditController 已重构走 Service）。 */
    @ArchTest
    static final ArchRule controllersMustNotInjectMappers =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().areAssignableTo(BaseMapper.class)
                    .because("SPEC §2 分层约束：Controller → Service → Mapper");

    /** 机检红线 4：Service 方法不得暴露 HttpServletRequest/Response（导出流式 OutputStream 除外）。 */
    @ArchTest
    static final ArchRule serviceMethodsMustNotTakeServletApi =
            noMethods().that().areDeclaredInClassesThat()
                    .resideInAPackage("..service..")
                    .or().areDeclaredInClassesThat().haveSimpleNameEndingWith("ServiceImpl")
                    .should().haveRawParameterTypes(HttpServletRequest.class.getName())
                    .orShould().haveRawParameterTypes(HttpServletResponse.class.getName())
                    .because("SPEC §2：HTTP 细节不下沉 Service 层（导出流式用 OutputStream）");

    /** 反例存在性自检：上述规则覆盖的类确实被导入了（防止 ArchUnit 扫不到包而假阳性）。 */
    @ArchTest
    static final ArchRule controllerAndServiceSamplesAreAnalyzed =
            classes().that().haveSimpleName("SupplierController")
                    .should().resideInAPackage(SUPPLIER_PACKAGE);
}
