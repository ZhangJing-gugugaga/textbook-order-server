# MVP 实现说明 · textbook-order-server

> 依据：PRD.md V1.1.0 / SPEC.md V1.0.0 / 03-后端开发计划与决策.md v3
> 本文说明本次 MVP 已实现的功能范围、因 MVP 裁剪暂未包含的功能点、以及与文档的落地偏差。
> **前后端联调接口手册见仓库根 [API.md](../API.md)**（95 端点、权限码、错误码、关键流程、联调注意事项）。

## 一、实现范围（对照 PRD 功能列表）

### 1. 认证与账号（PRD 模块 1，P0）— 已实现
- 账号密码登录签发 access（15 分钟）+ refresh（7 天，SHA-256 落库、轮换、撤销）
- 初始密码 = 学号/工号后 6 位；首登强制改密拦截（`must_change_password`/`first_login_verified`，业务接口一律 403，仅 `/api/auth/**`、`/api/me*` 放行）
- 首登校验：手机号后 4 位匹配或小程序 openid 绑定（wx.login code2session）
- 登录锁定落库（失败 5 次锁 15 分钟，重启不清锁）+ 同 IP/账号 1 分钟粒度限频
- 401 三类语义（契约冻结项）：`TOKEN_EXPIRED` / `REFRESH_INVALID` / `ACCOUNT_DISABLED`
- 登出/停用/改密/角色变更 → 撤销该用户全部 refresh；access 携带 role_version 比对
- 切换身份（`POST /api/auth/switch-role`）：仅切换 currentRole 与权限码集合，**不放宽数据范围**（W10）
- 超管建号（含供货商）/停用（即时踢下线）/重置密码；Excel 全量导入即批量建号

### 2. RBAC 与数据隔离（模块 2，P0）— 已实现
- 五表 + 37 条权限码种子（随契约冻结）；`模块:业务:操作` 格式；`@PreAuthorize` 方法级鉴权
- 数据隔离：MyBatis-Plus DataPermissionInterceptor + `@CollegeScope`，**多角色并集**（ADMIN 不过滤 / SECRETARY 学院 / TEACHER·STUDENT 本人）；未标注即不隔离
- 跨表学院范围（秘书查本院表单）由 Service 显式传参 + Mapper XML join `user_semester_profile`（归属真源，W6）实现
- 资源归属二次校验（按 id 取单条时校验归属，防 IDOR，失败 403 + 审计）
- 供货商物理隔离：`/api/supplier/**` 独立前缀 + 自建 Mapper + **ArchUnit 机检**（包内禁 import 学生/教师 Mapper）

### 3. 学期与窗口引擎（模块 3，P0）— 已实现
- 学期生命周期：draft / active / archived；`window_status`（not_open/open/closed）落库为唯一真源（W11）
- 定时扫描每分钟（Asia/Shanghai，仅扫 active 学期）：到点自动开/关；`auto_open/auto_close=false` 时不动；幂等
- 手动开启 / 提前截止 / **无限次延长**（延长早于当前时间拦截）；version 乐观锁 + 进程内锁串行化
- 每次变更写审计（谁/何时/原值→新值）并自动创建/合并系统通知任务
- `GET /api/semester/window/status`（含 serverTime，前端倒计时不信任本地时钟）；`GET /api/admin/semester/{id}/window/changes`
- 错误文案：「本期征订已截止」(409)、「征订尚未开始」、「存在更新的窗口配置，请刷新后重试」

### 4. 双缓冲切换与导入中心（模块 4，P0）— 已实现
- 学期域表（course/teacher_course/order_form(+item)/student_order(+item)/notice_task/notice_record/user_semester_profile/change_request）按 semester_id 隔离；查询恒带 active 学期
- **draft→active 单事务原子切换**：归档旧 active → 激活目标（version 乐观锁 + `uk_semester_active` 唯一约束兜底）→ 由 profile 同步 sys_user 归属冗余列 → 审计；失败回滚 409
- 归档 = 仅置状态，数据只读保留（可查可导，D2-A）
- 在途请求按请求进入时的 active 学期快照完成（ThreadLocal，§5.4）
- 异步导入中心：`.xlsx`（≤10MB、魔数校验）→ 批次落库 → 专用线程池 EasyExcel 流式解析 → 进度轮询；错误行收集不中断 + 错误明细可下载
- 5 张模板（学生/教师/教材/课程任课/异动）列清单按 W13 冻结；严格模式（不自动创建学院/班级）；幂等 upsert（user_no/ISBN/任课关系）
- **停用比对仅限本次导入文件覆盖范围**（按文件内学院 + 角色，W14）；导入为归属权威源（写 profile）
- 导入顺序引导（学院→专业→班级→教师→任课→学生→异动），服务端逐行校验外键

### 5. 教师征订两级审查与学生选购（模块 5，P0）— 已实现
- 一人一学期一单（唯一约束 + 覆盖语义：先逻辑删旧明细再插新）
- 字段审查引擎 6 规则（REQUIRED/QTY_RANGE/BOOK_ACTIVE/COURSE_OWNER/CLASS_LINK/ISBN_FORMAT），逐字段回显 `{field,rule,message}`（契约冻结项）；不过 → `rejected_auto` 可修复无限次重提
- 数量上限 = 班级人数，缺失回退 `order.quantity.max_default`（W2）
- 超管内容审核：pass → `reviewed`（计入汇总、进入学生清单）；reject → 理由必填 1-200 字 + `correct_deadline = 关窗 + order.correct_window_days`
- **关窗后补正豁免**（W4）：`@WithinWindow(exemption=CORRECTION)`，仅限本人该表单且状态 ∈ {rejected, rejected_auto}、未过补正截止
- 学生选购：清单 = 本班任课关系下教师 reviewed 教材并集（W3），有教师提交标必修、来源被驳回标「已下架」（提交时剔除并提示）；免审；覆盖语义；提交时学院/班级快照；数量 1-9 且 ≤ 班级人数
- 学生选购汇总作「参考用量」导出（仅教材室，不进供货商清单，W18）

### 6. 异动两级审批（模块 6，P1）— 已实现
- 逐条（教师/秘书）+ Excel 批量（共享 `batch_no`，Q10）
- 字段审查（TARGET_EXISTS/COLLEGE_EXISTS/CLASS_EXISTS/TYPE_VALID/VALUE_CHANGED）→ 超管审批（理由必填）→ 通过才落库
- 教师异动仅可改 `college_id`（W16）；**通过后对 active 学期立即生效**（写 profile，W15），历史按快照
- 支持按批次批量通过/驳回；通过落库与审计同事务

### 7. 通知确认闭环（模块 7，P1）— 已实现
- 通知任务：手动（同学期仅 1 个 active，重复 409）+ 窗口变更自动（合并进同一任务：追加内容 + 重置轮次计数，W18）
- 确认闭环：`GET /api/notice/unconfirmed`（阻塞弹窗数据源，含已停止重发未确认的，Q7）+ `POST /api/notice/{taskId}/confirm` 幂等（首次生效，204）
- 订阅消息重发：每日 09:30（Asia/Shanghai），仅未确认 STUDENT 且有 openid；`send_status ∈ {sent, unauthorized, failed}` 如实落库（不做假「已送达」）
- 轮次上限与间隔以 `system_config` 为唯一真源、改动对未完结任务立即生效（W8）；`notice_task` 两字段为创建时快照
- 弹窗通道不设轮次上限；未授权名单进线下兜底（progress/failures 接口可查，W5/R10）
- 通知汇总导出（学号/工号、姓名、角色、学院、班级、各轮发送时间/状态、确认状态/时间）

### 8. 供货商只读、导出与全局规范（模块 8，P0）— 已实现
- `/api/supplier/**`：按学院分组清单（书名/ISBN/**数量**/教师姓名/学院）+ 一学院一 sheet 导出（异步阈值同导出中心）+ 每次导出审计
- 导出中心：预估行数 ≤ 5000 同步流式；> 5000 走 `export_task` + 轮询 + **一次性下载 token**（单次有效、10 分钟过期、文件保留 24 小时、到点清理）
- 四类导出：教师征订明细（秘书本院/教材室全院）、秘书本院签字版（Excel 签字栏三行占位）、学生选购汇总、通知汇总；错误明细下载
- 全局规范：统一包络 `{code,message,data}`；分页 page/size 默认 1/20 上限 200；400/401/403/409/410/5xx 错误码令牌与文案按模块 8

### 9. 系统配置与审计（模块 9，P1）— 已实现
- 8 个配置键白名单 + 值域校验 + 变更审计；重发参数等不缓存（立即生效）
- 审计：登录/导出/账号操作/窗口变更/学期切换/审批/复核/配置变更全记录；按操作者/动作/资源/时间过滤 + 分页查询；只写不改、不含密码/token
- 看板：各学院提交进度 / 窗口状态 / 待复核数 / 未确认通知数（`GET /api/admin/dashboard`）

### 10. 验证目标（PRD 十三）对应测试（`./mvnw test`：238 用例 0 失败，12 跳过；加 `-Dmysql.local.enabled=true` 为 241 / 3 跳过）

| 验证项 | 测试类 | 结果 |
|--------|--------|------|
| 越权矩阵（5 角色 × 资源 × 操作 → 403/404 + 审计） | `slice/auth/AuthorizationMatrixTest`（30 例：401 三类语义、首登拦截、多角色并集、公开路由、联调新增端点权限） | 通过 |
| 窗口引擎（自动开关/延长/提前截止 + 通知自动创建 + serverTime + 变更记录） | `integration/semester/WindowEngineIntegrationTest`（13 例，含关窗 409 与补正豁免/过期） | 通过 |
| 双缓冲（原子切换 + version 冲突回滚 + 归属正确 + 同刻仅一个 active） | `integration/semester/SemesterDoubleBufferIntegrationTest`（4 例） | 通过 |
| 教师征订 + 学生选购（字段审查逐字段回显、两级审核、清单 required/delisted、覆盖语义） | `integration/order/OrderFlowIntegrationTest`（16 例） | 通过 |
| 数据隔离（秘书本院/教师本人/多角色并集/越权 403+审计） | `integration/order/DataIsolationIntegrationTest`（6 例） | 通过 |
| 异动审批（逐条 + 批量 + 立即生效 + 驳回理由必填） | `integration/approval/ChangeApprovalIntegrationTest`（7 例） | 通过 |
| 通知闭环（单任务 409、窗口变更合并 + 轮次重置、confirm 幂等、roundStopped、unauthorized 落库） | `integration/notify/NoticeIntegrationTest`（8 例） | 通过 |
| 导入幂等与范围（建号可登录、停用比对不越界、重复导入幂等、错误行、同步导出读回、一次性 token 410） | `integration/importexport/ImportExportIntegrationTest`（6 例） | 通过 |
| 字段审查 6 规则 × 边界（含数量上限回退、ISBN 校验位） | `unit/order/FieldCheckServiceTest`（18 例） | 通过 |
| 配置白名单/值域、导出阈值、窗口状态机 | `unit/system/ConfigWhitelistTest`、`unit/importexport/ExportThresholdTest`、`unit/semester/WindowStateMachineTest` | 通过 |
| 机检红线（supplier 禁 import 学生/教师 Mapper、common 禁 import 业务 Mapper、Controller 禁直连 Mapper） | `arch/ArchUnitTest`（5 例） | 通过 |
| 性能（万行导入 ≤5 分钟） | `integration/perf/TenThousandRowImportPerfTest`（@Disabled，实测 10k 行 169s） | 默认禁用 |
| Testcontainers(MySQL) 等价集成 | `integration/testcontainers/MySqlContainerIntegrationTest`（`-Drun.mysql.tests=true` 门控） | 无 Docker 跳过 |

> 测试过程中发现并修复 7 个主代码缺陷（均已在提交信息说明）：窗口变更审计因 MP 默认 ObjectMapper 缺 JSR310 静默丢失、自定义 @Select 读不回 JSON 列、W14 停用比对对新建用户失效、万行导入 BCrypt 串行超时（改批内并行哈希）、CORRECTION_EXPIRED 契约缺口、双缓冲同步语句的 MySQL 专有语法、MeController/AuditController 分层违规。

## 二、MVP 裁剪：暂未包含的功能点

| # | 裁剪项 | 依据 | 说明 |
|---|--------|------|------|
| 1 | 支付 | PRD 概念版「本版本不做」 | 无支付场景（校内免费） |
| 2 | 微服务拆分 / 消息队列 | 同上 + 03 §1「明确不做」 | 单机单体；定时任务多实例部署时换 xxl-job/分布式锁（R3，已在文档标注） |
| 3 | 教学班模型 | PRD 需求 14 | 仅行政班（`school_class`），任课关系即征订范围（W17） |
| 4 | AI 功能 | PRD 九~十二节 | 无 AI；字段审查/自动开关窗/重发轮次均为确定性白名单规则 |
| 5 | 短信 / 企业微信催办渠道 | PRD 模块 3「不包含」+ W5 | 弹窗为主触达 + 订阅消息为已授权用户额外提醒；真催办留 V1.1 评估 |
| 6 | 前端页面实现 | PRD 需求范围 | 本文档为服务端；Web/小程序前端见各自仓库 |
| 7 | 5 轮订阅消息重发的试运行验收 | PRD 十三（本地不可验） | 需 moonzj.com HTTPS 试运行环境 + 已申请订阅模板；本地/CI 只验逻辑 |
| 8 | 万行导入 ≤5 分钟、P95<500ms 性能实测 | PRD 十三 / W22 | 逻辑已就绪（异步批次 + 流式 + 批内并行哈希，本地实测 10k 行 169s 达标）；P95 指标需 MySQL 生产等价环境压测 |
| 9 | 签字版导出模板由田老师样张替换 | SPEC §10 | 代码已实现模板驱动（`templates/secretary-signature.xlsx` 占位三行），样张到位后替换文件即可，代码不改 |
| 10 | `import_batch` 摘要列（停用数量/覆盖异动数） | W14 要求提示「覆盖 N 条异动结果」 | 已写入审计 detail；批次面板展示需加列（DDL 冻结，留 V1.1） |
| 11 | Playwright / 小程序端到端 | SPEC §14 M5 | 前端主责；后端侧以接口级集成测试覆盖 |
| 12 | Redis / 多实例分布式锁 | 03 §1 | Caffeine 进程内缓存 + 进程内锁；多实例部署时引入（R3） |

## 三、与文档的落地偏差（均有依据）

1. **MyBatis-Plus 锁定 3.5.7**：SPEC §1 锁「3.5.x」，但 3.5.8+ 重构了分页/数据权限插件 API（`PaginationInnerInterceptor`、`DataPermissionHandler` 被移除），与 SPEC §5 记载的实现方式不符，故取同区间内最后一个保留该 API 的版本 3.5.7。
2. **集成测试用 H2（MySQL 模式）**：本机无 Docker，Testcontainers(MySQL) 用例以 `@EnabledIfSystemProperty(run.mysql.tests)` 门控、Docker 可用时启用；schema 由 `db/schema.sql` 经测试期转换器生成（生产 DDL 单一来源不变）。
3. **`@CollegeScope` 学院列默认收敛为空**：防止为无 `college_id` 列的方法误配学院列，导致多角色（秘书+教师）并集时生成错误 SQL；跨表学院范围统一走显式 join。
4. **软删除为显式实现**：W9 要求「删除写当前时间戳」，MP 逻辑删除只能写固定值，故全部查询显式带 `deleted=0`、删除写毫秒时间戳（规避唯一索引冲突的目的不变）。
5. **首登校验的 openid 绑定经 `wxCode` 换取**：契约（SPEC §11.1「手机号后 4 位 / openid 绑定」）未定义 code2session 端点，实现在 `POST /api/auth/first-login/verify` 内接收 `wxCode` 换取 openid 绑定。
6. **未配置微信密钥时订阅消息记 `unauthorized`**：与 W5/R10「未授权者≈未授权名单、如实落库」的口径一致；配置 `WX_MINIAPP_APPID/SECRET/WX_SUBSCRIBE_TEMPLATE_ID` 后自动生效。
7. **审计写入分两种事务语义**：关键动作（审批/切换/复核/配置/账号/窗口）与业务操作同事务（REQUIRED）；登录/导出等无业务事务场景由 `@AuditLog` 切面独立事务写入（失败不影响业务）。
8. **前端路径以 OpenAPI 为准**：web 端现用 `/export-tasks/{id}`，服务端按 SPEC §11 契约 `/api/export-task/{id}`（02 号文档注明前端路径为提议值，以冻结契约为准）。
9. **导出异步返回统一为 `{taskId, async:true, rowEstimate}`**：导出中心与供货商导出两条链路字段一致（此前导出中心复用 `{batchId}` 字段承载 taskId，易与导入批次混淆，已在联调前统一）。
10. **不下发服务器文件路径**：`export_task.file_path`、`import_batch.file_path/error_file_path` 标记 `@JsonIgnore`，响应仅给出 `downloadToken`/批次 id 等前端所需信息。

## 四、验证结果

`./mvnw test -Dmysql.local.enabled=true`（完整非过滤）：**241 用例，0 失败，0 错误，3 跳过**（性能用例默认禁用 + Testcontainers 无 Docker 门控跳过；不加该参数时本地 MySQL 用例一并跳过，共 12 跳过），BUILD SUCCESS。测试清单见第十节表格。
