# 架构与接口审查修复记录（2026-09-21）

> 依据：架构与接口设计审查报告（P0 5 项 / P1 25 项 / P2 若干 / P3 建议项）+ 第二轮复验报告（R1–R9）。
> 结果：**208 用例全绿**（首轮修复前 164），`mvnw package` 通过，启动自检四条路径实跑验证，
> 迁移脚本的 ALTER 语句在模拟旧库上实跑通过。
> 变更规模：约 115 个文件。
>
> **两轮修复**：第一轮（§1–§4）为审查报告列出的缺陷；第二轮（§6）为复验发现的残余问题，
> 其中 R1 是致命项、R2 是发布阻塞项。

## 0.1 第二轮复验结论与处理（R1–R9）

复验报告判定「上轮 5 项致命缺陷全部真正闭合，25 项严重缺陷中 22 项闭合」，另发现 1 项
「声称修复但实际未闭合」（S3）与若干一般缺陷。逐项处理如下：

| 编号 | 级别 | 问题 | 处理 |
|------|------|------|------|
| **R1** | 致命 | 登录/首登失败计数与锁定**完全不生效**——计数写在 `@Transactional` 方法内且随后抛 `BizException`（RuntimeException），被默认回滚规则撤销，`max-fail=5` 是死代码 | 新增 `LoginAttemptGuard`（**独立 bean** + `REQUIRES_NEW`）承载计数与锁定；`login`/`firstLoginVerify` 改走它；首登端点补限频。补 5 条回归用例（断言**落库状态**而非异常类型） |
| **R2** | 严重·发布阻塞 | 新增列/唯一键/索引无迁移脚本，存量库拉代码重启即 500 | 新增 `db/migration-2026-09-21.sql`（存储过程式幂等判断 + 存量重复数据清理 + `rejected_auto` 回填）；`MigrationScriptTest` 在模拟旧库上实跑 ALTER 断言新对象建成；`deployment.md` 增 §2.1 上线前置步骤 |
| **S3** | 严重 | content_version 的 CAS 比的是**同一请求内刚读到的值**，跨请求重提（管理员 GET 详情 → 教师重提 → 管理员 POST 通过）拦不住；且 VO/请求体都不含该字段，客户端无法回传 | `OrderFormDetailVO` 暴露 `contentVersion`；`OrderFormReviewRequest` 接收 `contentVersion`；CAS 改为与客户端回传值比对（缺省时降级为请求内比对并文档说明）；补 2 条用例（旧版本号必须 409 + 不传时仍可审）；同步 API.md |
| **R3** | 一般 | 隔离条件合并未给原 where 加括号。实测 jsqlparser 序列化为 `a = 1 OR b = 2 AND (隔离)`，按优先级命中 `a = 1` 的行会绕过隔离（当前注解语句都是 AND 链故未触发，属静默越权地雷） | 合并时改用 `new AndExpression(new Parenthesis(where), condition)`（与 MP 自家 `BaseMultiTableInnerInterceptor` 同做法）；补 2 条单测（OR-where 加括号、AND-where 不破坏） |
| **R4** | 一般 | `TextbookService` 未接入 `SqlLike.escape`，XML 的 `ESCAPE '\|'` 形同虚设 | 四个检索参数接入转义 |
| **R5** | 一般 | `normalizePage` 无上限，`page=Long.MAX_VALUE` 使 offset 溢出为负 → SQL 语法错误 → 500 | 加 `MAX_PAGE = 1_000_000`；补 4 条单测 |
| **R6** | 建议 | 启动补偿无条件回收全库 running/queued，多实例滚动发布误杀旧实例任务 | 加「`updated_at` 早于本进程启动时刻」陈旧阈值 + `textbook.async.recover-on-startup` 开关；`deployment.md` 写明多实例须置 false |
| **R7** | 建议 | API.md 自相矛盾（一处已写 target_roles 定向，另一处仍写「不按 targetRoles 过滤」） | 修正矛盾条目并明确「空队列 = 没有面向本角色的通知，不是故障」 |
| **R8** | 建议 | bcpkix/bcutil-jdk15on 1.70 仍在依赖树 | 一并排除三件套。已用两种方法独立核实 weixin-java-* 4.6.0 的 1079 个 class 对 `org/bouncycastle` **零字节码引用**（`grep` 二进制 + `javap -c` 常量池），排除无运行风险 |
| **R9** | 建议 | 405 缺 `Allow` 头；审计 `userNo` LIKE 未转义；错误明细文件过期后 404 文案与「本来就没错误」无法区分 | 405 回填 `Allow`；审计关键字接入转义；新增 `errorFilePathForDownload` 区分「无明细」与「已过期清理」 |

### R1 的修复要点（为什么必须是独立 bean + REQUIRES_NEW）

```java
// AuthService.login() 标注 @Transactional，失败路径要抛 BizException(RuntimeException)
// → 默认回滚规则撤销同事务内全部写入 → 计数与锁定写在这里等于没写
loginAttemptGuard.recordFailure(user.getId());   // 独立 bean，REQUIRES_NEW，立即提交
throw new BizException(ErrorCode.LOGIN_FAILED);
```

两个容易踩空的点，都在代码注释里留痕：

1. **必须独立 bean**：同类内自调用不经过 Spring 代理，`REQUIRES_NEW` 不会生效。
2. **锁顺序**：`firstLoginVerify` 成功路径里，`clearFailureState`（独立连接）必须排在
   本事务对 `sys_user` 的任何写入**之前**——否则本事务持有该行 X 锁、独立事务阻塞在它上面、
   本事务又等独立事务返回，形成自死锁。此项由
   `LoginLockIntegrationTest#firstLoginVerify_successClearsCountWithoutDeadlock` 守护：
   实测把顺序改回缺陷版本，该用例即以
   `Timeout trying to lock table "sys_user"` 失败（已做「还原缺陷验证测试有效性」的对照实验）。

### 对复验报告的两处事实修正

1. **R2 中「`import_batch.created_by` 缺失 → 6 个导入上传端点全 500」不成立**：
   该列在初始 DDL 中**本就存在**（`schema.sql` 的 import_batch 定义即含 `created_by`/`updated_by`），
   无需 ALTER。真实影响只是**数据层**：历史行 `created_by` 为 NULL，而新增归属校验对非 ADMIN
   要求 `created_by = 本人`，故历史批次对秘书不可见（404）。已在迁移脚本与 deployment.md 中
   明确说明这是有意的 fail-closed 选择。
2. **R2 中「DATETIME → DATETIME(3) 未迁移」影响面更小**：该精度差异只影响秒以下比较，
   业务无实际影响；迁移脚本中作为**可选**章节给出（`MODIFY COLUMN` 会重建表），
   跳过不产生功能问题。

## 0. 与审查报告结论不一致的三处（按实测/契约修正）

审查报告提出的修复方式有三处按原样实施会引入新问题，已按下述方式落地并在代码注释中留痕：

1. **F3 建议对 `ExportTask.downloadToken` 加 `@JsonIgnore` —— 会切断异步下载链路。**
   token 是在任务完成时才生成的，前端只能从轮询 `GET /api/export-task/{id}` 的响应拿到它；
   加 `@JsonIgnore` 后前端永远拿不到 token，异步导出变成「能导出不能下载」。
   实际漏洞根因是**供货商侧缺归属校验**（`ExportController` 用 `getTaskForUser`，
   供货商侧用裸 `getTask`），已补 `getSupplierTask`/`claimSupplierDownload`（归属 + bizType 白名单，
   失败统一 404 防枚举），token 保持下发但只发给任务所有者。`filePath` 维持 `@JsonIgnore`。

2. **S2「reviewed 表单可被重提覆盖」在测试里被当作特性锁定。**
   `OrderFlowIntegrationTest` 有两个用例依赖「通过后重提再驳回」的流程，而 PRD 状态机
   明确 `reviewed` 的**退出条件为「—」**（终态）。按契约修正实现（拒绝重提），
   并把这部分用例改为走「提交 → 直接驳回」的合法路径，断言（required/delisted 语义）完全保留，
   另加一条「reviewed 重提必须 409」的新不变量断言。

3. **P3 建议排除 xstream/dom4j/httpclient —— 其中 httpclient 不能排除。**
   weixin-java-common 的 `DefaultApacheHttpClientBuilder` 实际使用 Apache HttpClient
   （本次为其配置超时即依赖该类）；xstream/dom4j 属支付/公众号 XML 链路，miniapp 路径未触达，
   但排除后一旦有类被加载即 `NoClassDefFoundError`，且**无法在本机实测微信链路**。
   故本轮只做「升版本」不做「排除」，该项列入待人工确认。

## 1. 致命缺陷（P0）

| 编号 | 修复方式 | 关键改动 |
|------|----------|----------|
| F1 | 密钥 fail-fast | `application.yml` 去掉默认值改 `${JWT_SECRET:}`；`JwtService` 构造期校验「空/占位符未解析/过短/命中已知弱值」四类并拒绝启动；trial/school 声明 `${JWT_SECRET}`（无默认） |
| F2 | profile 必填 | `spring.profiles.active` 去掉 `local` 默认；`TextbookOrderServerApplication.main` + `StartupSelfCheck` 双重断言；local 的建库/种子改为「显式选 local 才执行」 |
| F3 | 供货商 IDOR | 新增 `ExportServiceImpl.getSupplierTask/claimSupplierDownload`（归属 + `bizType=supplier` 白名单，两类失败统一 404）；`SupplierService` 改走这两个方法；`filePath` 保持 `@JsonIgnore` |
| F4 | 秘书导出越权 | `planOrderExport` 对非 ADMIN 一律忽略入参 `collegeId`，强制由 `user_semester_profile` 推导；无归属直接拒绝（不退化为全院）。`planSignatureExport` 复用同一 `requireOwnCollegeId` |
| F5 | 异动审批学期/归属 | `applyToProfile` 改用 `changeRequest.getSemesterId()`；`after.collegeId/classId` 为空即抛异常终止审批（不再写 NULL）；仅当异动所属学期 == active 学期时才同步 `sys_user` 冗余列 |
| S12 | token 原子消费 | `claimDownload` 的 UPDATE 增加 `download_token = ?` CAS 谓词，并发同 token 只有一个成功 |
| S24 | CORS 白名单 | `CorsConfig` 改为读取 `textbook.cors.allowed-origins`（默认空 = 不返回 CORS 头，拒绝通配符）；新增 `TEXTBOOK_CORS_ORIGINS` |

## 2. 严重缺陷（P1）

### 并发与事务
- **S1** `WindowGuardImpl`：`correct_deadline` 为空不再视为豁免成立（按已过期处理）；同时补上根因——`TeacherOrderService.submit` 的 `rejected_auto` 分支现在会写 `correct_deadline`。
- **S2** `submit` 拒绝 `reviewed` 表单重提（409 + 明确文案）。
- **S3** 新增 `order_form.content_version` 列 + `bumpContentVersion`；`review` 的 CAS 谓词由「status」扩为「status + content_version」，识别「管理员打开详情后教师又重提」。
- **S4** `applyReview` 的 UPDATE 增加 `status='pending_review'` 谓词，CAS 成功后才生效落库（同事务，失败整体回滚）。
- **S5** `SemesterActiveService.evict()` 在事务内改为注册 `afterCompletion` 回调；`SemesterMapper.archiveIfActive` 归档时同时 `channel_open=0 + window_status='closed'`。
- **S6** `notice_task` 增生成列 `active_flag` + 唯一键 `uk_task_active(semester_id, active_flag, deleted)`，`createTask` 的 `DataIntegrityViolationException` 兜底从死代码变为生效。
- **S7** `resendTask` 去掉 `@Transactional`，拆为「只读快照 + 事务外逐条发送与独立落库」；`WxMaClient` 显式配置建连/读/取连接超时与连接池上限。
- **S8** `SupplierService.writeSync` 去掉 `@Transactional`（不再把 xlsx 生成与网络传输包在事务里）。

### 数据隔离与越权
- **S10** `CollegeScopeHandler` 三处 fail-open 改为 fail-closed（默认 `1 = 0`）：无登录上下文、注解列与角色不匹配、条件解析失败。
- **S11** 新增 `DataScopeGuardTest`：反射扫描全部 `@Mapper`，凡参数含 `userId/teacherId/studentId/applicantId` 的方法必须标 `@CollegeScope` 或在显式白名单中说明理由；另有「白名单不得残留已删除方法」的自检。
- **S13** `ChangeRequestService` 新增 `withScopeCheck`：目标用户角色须与 `type` 匹配（`TARGET_ROLE_MISMATCH`），非 ADMIN 只能对本院用户提交（`TARGET_SCOPE`）；以字段审查错误表达，逐条与批量行为一致。

### 认证与会话
- **S14** 改密与登出均追加 `incrRoleVersion` + 缓存失效，旧 access token 立即失效。
- **S15** 首登校验失败按账号计数并锁定（与登录失败共用 `fail_count/lock_until`），通过时清零。
- **S16** `IpUtils` 改为可信代理白名单模型（`textbook.security.login.trusted-proxies`），从 XFF 右向左取第一个非可信地址；未配置则完全不采信 XFF。

### 健壮性与性能
- **S17** 新增 `AppTime`（固定 Asia/Shanghai），16 处 `LocalDateTime.now()` 全部替换。
- **S18** `GlobalExceptionHandler` 补 405 / 415 / 参数类型不匹配 / 缺请求头 / Servlet 绑定 / 静态资源 404 六类处理器；`ErrorCode` 增 `METHOD_NOT_ALLOWED`、`MEDIA_TYPE_NOT_SUPPORTED`。
- **S19** 补索引：`order_form(teacher_id,id)`、`student_order(student_id,id)`、`change_request(applicant_id,id)`、`audit_log(resource,resource_id,at)`；删除两个唯一键前缀冗余索引。
- **S20** 审计查询下推 SQL 分页（`selectByFilter` + `countByFilter`），`AuditService.query` 返回 `PageResponse`。
- **S21** 新增三个 COUNT 查询（`countReviewedItems` / `countSummaryRows` / `countTaskSummaryRows`），导出预估不再 `.size()` 物化全量。
- **S22** `import_batch.created_by` 落库 + `getBatchForUser` 归属校验（失败 404），批次两个端点改走它。
- **S9** `ImportUploadValidator` 增加解压体积（512MB）/单条目（128MB）/条目数（2000）上限；`ImportReadListener` 错误明细上限 500 条，超出只计数（`ImportRunSummary` 增 `truncatedErrors`，批次 `errorCount` 取「明细 + 截断计数」）。
- **S23** 通知三端点补 `@PreAuthorize("isAuthenticated()")`，并按 `target_roles` 过滤（`targetsUser`，ADMIN 全量）；`confirm` 同步校验。
- **S25** 登录失败计数改「单条自增 + 读回」；`notice_record` 增生成列 `confirm_flag` + 唯一键 `uk_notice_confirm`，并发确认由唯一键裁决并按幂等成功返回。

## 3. 一般缺陷（P2）

- schema：全部 `CREATE TABLE IF NOT EXISTS`；显式 `COLLATE=utf8mb4_general_ci`；`DATETIME` → `DATETIME(3)`（与 `NOW(3)` 对齐）；`sys_user_token` 唯一键补 `deleted`。
- 接口契约：`PageResponse` 新增 `normalizePage/normalizeSize/offsetOf`，8 处分散的 `Math.min(Math.max(...))` 收敛（并修掉 `size<=0` 生成 `LIMIT 0`/负值的问题）；`TextbookSaveRequest`/`CourseSaveRequest`/`ChangeSubmitRequest`/`ChangeBatchReviewRequest` 补 `@Size`（与 DDL 列宽一致）；`OrderFormSubmitRequest` 补条数上限 500；`TeacherOrderService` 增加同单重复明细检测（`ITEM_DUPLICATED`，替代误导性的 409）；批量审批单次上限 500。
- LIKE 通配符：新增 `SqlLike.escape`（转义 `%`/`_`/转义符，转义符选 `|` 以兼容 MySQL 与 H2），XML 补 `ESCAPE '|'`，4 个 Service 调用点接入。
- 异步与可运维：异步导出改为**事务提交后**派发（消除「永远 queued」）；新增 `AsyncTaskRecoveryListener`（启动把遗留 running/queued 置 failed）；`ExportCleanupScheduler` 增加孤儿文件清理。
- 审计：`AuditService.record` 不再吞异常（同事务场景审计失败即回滚业务），`recordIndependent` 保留告警不阻断；`AuditLogAspect` 参数脱敏扩展到 phone/token/secret/openid/idcard 等。
- 其他：`FailureMessages` 统一异步失败文案（只透传业务异常文案，不泄露 SQL/路径）；导出完成日志不再打印绝对路径；`SupplierOrderMapper.selectReviewedRows` 加行数上限；`NotifyService.taskFailures`/`resolveTargetUsers` 改用任务所属学期（修失败名单学期错配）；`truncate` 语义统一为取头部 + 省略号。

## 4. 建议项（P3）

- 依赖收口：POI 4.1.2 → 5.2.5、commons-io 2.11.0 → 2.16.1、commons-compress → 1.27.1（POI 5.x 必需）、bcprov-jdk15on → bcprov-jdk18on 1.78.1；排除 easyexcel 带入的 `poi-ooxml-schemas` 4.1.2（与 POI 5.x 的 `poi-ooxml-lite` 重复）。**已用 Excel 读写集成用例实测验证**。
- springdoc 默认关闭（仅 local 显式开启）；删除 `logging.pattern.console` 死配置；systemd 模板补 `WorkingDirectory`；新增 `TEXTBOOK_LOG_DIR`。
- 启动自检 `StartupSelfCheck`：profile 必填、导出目录可写、微信配置缺失告警、注入可信代理白名单。
- 可观测性：`spring-boot-starter-actuator`（仅暴露 health，含 db 与磁盘）；优雅停机（`server.shutdown=graceful` + 30s 宽限）；`TraceIdFilter`（MDC + `X-Request-Id`，logback pattern 带 `%X{traceId}`）。
- 安全加固：弱口令黑名单 + 禁「与学号/工号（含后 6 位）相同」；`MustChangePasswordFilter` 白名单由前缀通配收敛为显式枚举（`switch-role` 不再放行）；下载校验 `expires_at`；`/api/export-task/{id}` 归属失败统一 404；种子 18 个账号改为各自独立 BCrypt salt；建号手机号按号段校验。
- 文档：README（快速开始/环境变量表/启动自检/测试规模）、API.md（首登白名单、通知 target_roles、导出任务与批次归属、405/415 错误码）、docs/deployment.md（必填项与启动自检表、systemd 模板、运维要点、移交检查单）。

## 5. 验证方式

- `./mvnw test`：**208 用例通过 / 3 跳过**（Testcontainers 需 Docker）。
- 新增回归用例：
  - `unit/auth/JwtServiceSecretTest`（5）：密钥四类非法值 + 往返签发解析。
  - `unit/common/RequestUtilTest`（6）：可信代理 XFF 解析 4 场景 + LIKE 转义。
  - `unit/datascope/CollegeScopeHandlerTest`（+3）：无登录上下文/角色列不匹配的 fail-closed。
  - `integration/security/SecurityHardeningIntegrationTest`（11）：F3 IDOR、token 单次有效、F4 跨院导出、F5 学期与归属、S13 目标范围、S23 通知定向。
  - `arch/DataScopeGuardTest`（2）：隔离口径护栏 + 白名单自检。
  - `slice/auth/AuthorizationMatrixTest`（+2）：首登拦截白名单收敛。
  - `integration/auth/LoginLockIntegrationTest`（5）：**R1 回归** —— 失败计数落库、连续失败锁定、
    锁定后正确口令也拒绝、成功登录清零、首登校验失败计数与成功路径无自死锁。
  - `integration/migration/MigrationScriptTest`（2）：**R2 回归** —— 在模拟旧库上实跑迁移脚本的
    ALTER 语句并断言新列/索引建成；迁移脚本与 schema.sql 的新增对象集合一致（防命名漂移）。
  - `integration/order/OrderFlowIntegrationTest`（+2）：**S3 回归** —— 用审核页读到的旧版本号审核
    必须 409，刷新后成功；未回传版本号时降级路径仍可审。
  - `unit/datascope/CollegeScopeHandlerTest`（+2）：**R3 回归** —— OR-where 合并加括号、AND-where 不破坏。
  - `unit/common/PageResponseTest`（4）：**R5 回归** —— page 上限与 offset 不溢出。
- 启动自检实跑（`java -jar`）：无 profile → 明确中止；trial 无 JWT_SECRET → 明确中止；弱密钥 → 明确中止；合规密钥 → 通过自检并进入 DB 连接（本机无 MySQL，符合预期）。
