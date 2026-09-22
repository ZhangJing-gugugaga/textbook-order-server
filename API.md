# 教材征订系统 · 服务端 API 手册

> 版本 V1.0.6 · 2026-09-22 · 依据 `SPEC.md` §11 契约基线（95 个端点；V1.0.1–3 联调新增 3 个最小权限只读端点）
> 定位：**前后端联调速查手册**。唯一契约源为 springdoc-openapi 生成的 OpenAPI 3 文档（`GET /v3/api-docs`、`/swagger-ui.html`），本文档与代码同步维护，冲突时以 OpenAPI 为准。
> 配套文档：[README.md](README.md)（环境/账号/测试）、[docs/IMPLEMENTATION-MVP.md](docs/IMPLEMENTATION-MVP.md)（实现范围与裁剪）、[docs/deployment.md](docs/deployment.md)（部署）

---

## 1. 通用约定

### 1.1 基础路径与部署形态

| 项 | 约定 |
|----|------|
| 接口前缀 | `/api/**`（本地 `http://localhost:8080`；试运行/移交经 Nginx 反代 `https://<域名>/api/`） |
| 前端调用 | **一律相对路径**（Web 同域 `/api`；小程序 baseUrl 单点配置） |
| 请求头 | `Authorization: Bearer <accessToken>`；`Content-Type: application/json`（上传为 `multipart/form-data`）；可选 `X-Device-Id`（refresh 轮换的会话标识） |
| 字符集 | UTF-8 |
| 时间格式 | **入参**（body 与 query 一致）：`2026-09-21T09:30:00`（ISO-8601，推荐）与 `2026-09-21 09:30:00`（含缺秒 `09:30`）**都接受**，时区固定 Asia/Shanghai；审计查询的 `startAt/endAt` 另接受纯日期 `2026-09-21`（下界取当日 00:00:00，上界取当日 23:59:59.999999999）。**出参**：ISO-8601 本地日期时间（`2026-09-21T09:30:00`，含微秒时为 `2026-09-21T09:30:00.123456`）——`spring.jackson.date-format` 只作用于 `java.util.Date`，不影响 JSR-310 类型，前端需自行格式化（见 §5.10） |
| CORS | **默认不返回任何 CORS 响应头**（仅同域访问；Web 走 Nginx 同域反代，小程序不受 CORS 约束）。跨域直连需设 `TEXTBOOK_CORS_ORIGINS`（显式 origin 白名单，**严禁 `*`**，配了 `*` 直接启动失败） |

### 1.2 统一响应包络

所有 JSON 接口返回 `{code, message, data}`：

```json
{ "code": "0", "message": null, "data": { } }
```

- 成功：`code = "0"`，`message` 为 null（不输出）
- 失败：`code` 为错误码令牌（见 §1.6），`message` 为面向用户的中文文案，`data` 可为逐字段错误明细
- **null 键省略**（Jackson `NON_NULL`）：成功且无数据时整个 `data` 键不出现（如无单时的 `/api/teacher/order-form`、`/api/student/order`）；`/api/me` 无归属行时 `semesterId/collegeId/classId/collegeName/className` 五键均不出现。前端判空必须用 `== null` / 可选链，不要写 `data === null` 或 `'semesterId' in data`
- HTTP 状态码同时反映语义（400/401/403/404/409/410/429/500），**前端应以 `code` 分流，以 HTTP 状态兜底**

### 1.3 分页

请求：`?page=1&size=20`（**page 从 1 开始**，size 默认 20、上限 200）

响应 `data` 形状（`PageResponse`）：

```json
{ "list": [], "page": 1, "size": 20, "total": 137, "totalPages": 7 }
```

### 1.4 认证与会话

```
POST /api/auth/login  →  { accessToken, refreshToken, expiresIn, mustChangePassword, firstLoginVerified, roles[], currentRole, userNo, name }
```

| 项 | 约定 |
|----|------|
| access token | 15 分钟；放 `Authorization: Bearer`；**不落库** |
| refresh token | 7 天；每次 `POST /api/auth/refresh` 轮换（旧 token 立即失效）；登出/停用/改密/角色变更会撤销全部 refresh |
| 401 三类语义（契约冻结项） | `TOKEN_EXPIRED`：access 过期 → 用 refresh 静默重放<br>`REFRESH_INVALID`：refresh 失效 / 角色版本失效 → 强制登出<br>`ACCOUNT_DISABLED`：账号停用 → 强制登出 |
| 首登拦截 | 初始密码登录后 `mustChangePassword=true`：**业务接口一律 403 `FIRST_LOGIN_REQUIRED`**。放行清单为**显式枚举**：`POST /api/auth/login`、`POST /api/auth/refresh`、`POST /api/auth/first-login/verify`、`POST /api/auth/logout`、`/api/me*`（含 `PUT /api/me/password`）。注意 `POST /api/auth/switch-role` **不在**放行清单内（它会重发令牌）；流程 = 首登校验 → 改密 |
| 多角色 | `roles[]` 为全部角色；`currentRole` 为当前身份；`POST /api/auth/switch-role` 切换身份（**仅改变权限码呈现，数据范围不变**） |
| 前端菜单 | `GET /api/me/permissions` 返回当前身份的权限码集合，用于动态路由/菜单/按钮（`v-perm`） |
| 登录失败 | 失败 5 次锁定 15 分钟（落库，重启不清）；锁定期间返回 401 `ACCOUNT_LOCKED`；同 IP/账号 1 分钟粒度限频（429 `RATE_LIMITED`） |

**refresh 轮换建议实现**：响应拦截器捕获 401 `TOKEN_EXPIRED` → 用 refreshToken 调 `/api/auth/refresh` → 覆盖本地令牌 → 重放原请求；`REFRESH_INVALID`/`ACCOUNT_DISABLED` → 清本地令牌并跳登录页。

### 1.5 权限码（37 条 · M1 冻结）

格式 `模块:业务:操作`。角色默认权限（种子数据，见 `db/data-permission.sql`）：

| 角色 | 权限码 |
|------|--------|
| ADMIN（教材室超管，35 条） | 除 `supplier:*` 外全部 |
| SECRETARY（学院秘书，6 条） | `semester:window:view`、`order:form:view:college`、`export:order:create`、`export:signature:create`、`change:request:submit`、`import:batch:view` |
| TEACHER（任课教师，4 条） | `semester:window:view`、`order:form:submit`、`order:form:view:self`、`change:request:submit` |
| STUDENT（学生，3 条） | `semester:window:view`、`student:order:submit`、`student:order:view:self` |
| SUPPLIER（供货商，2 条） | `supplier:order:view`、`supplier:order:export` |

完整清单（按模块）：`semester:semester:manage|activate`、`semester:window:manage|view`、`user:account:manage|reset`、`org:college|major|class:manage`、`textbook:book:manage|import`、`course:course:manage`、`course:teacher:manage`、`people:student|teacher:import`、`order:form:submit|view:self|view:college|view:all|review`、`student:order:submit|view:self|view:all`、`change:request:submit|review`、`import:batch:view`、`export:order|signature|student|notice:create`、`notice:task:manage|view`、`dashboard:stat:view`、`config:config:manage`、`audit:log:view`、`supplier:order:view|export`。

> 无权限码的接口仅需登录（如通知弹窗、导出任务查询）；无权限访问返回 403 `FORBIDDEN`。

### 1.6 错误码表

| code | HTTP | 文案 | 说明 |
|------|------|------|------|
| `PARAM_INVALID` | 400 | 请求参数有误 | `data` 为逐字段错误 `["字段: 原因"]` |
| `FIELD_CHECK_FAILED` | 400 | 存在 N 项问题，请按提示修复后重新提交 | `data` 为逐项 `[{field,rule,message}]`（契约冻结回显格式） |
| `BOOK_DELISTED` | 400 | 部分教材已下架，请核对后重新提交 | 学生选购含下架教材 |
| `PASSWORD_POLICY` | 400 | 新密码需 8 位以上且含字母和数字 | |
| `CONFIG_VALUE_INVALID` | 400 | 配置值不合法 | 配置键白名单/值域 |
| `FILE_TYPE_INVALID` / `FILE_TOO_LARGE` | 400 | 仅支持 .xlsx 文件 / 文件超过大小上限 | 导入上传 |
| `UNAUTHORIZED` / `TOKEN_EXPIRED` / `TOKEN_INVALID` / `REFRESH_INVALID` | 401 | 登录已过期，请重新登录 | 见 §1.4 三类语义 |
| `LOGIN_FAILED` | 401 | 账号或密码不正确 | 防账号枚举 |
| `ACCOUNT_LOCKED` | 401 | 账号已锁定，请稍后再试 | 失败 5 次锁 15 分钟 |
| `ACCOUNT_DISABLED` | 401 | 账号已停用，请联系教材室 | 强制登出 |
| `FIRST_LOGIN_VERIFY_FAILED` | 401 | 校验信息不正确，请联系教材室 | 首登校验 |
| `METHOD_NOT_ALLOWED` | 405 | 请求方法不被支持 | 方法不匹配（此前被兜底成 500） |
| `MEDIA_TYPE_NOT_SUPPORTED` | 415 | 请求内容类型不被支持 | Content-Type 错误（此前被兜底成 500） |
| `FORBIDDEN` / `RESOURCE_FORBIDDEN` | 403 | 无权执行该操作 / 无权访问该资源 | 越权访问写审计 |
| `FIRST_LOGIN_REQUIRED` | 403 | 请先完成首登校验并修改初始密码 | 首登拦截 |
| `NOT_FOUND` | 404 | 资源不存在 | |
| `WINDOW_CLOSED` | 409 | 本期征订已截止 | 提交类接口 |
| `WINDOW_NOT_OPEN` | 409 | 征订尚未开始 | |
| `CORRECTION_EXPIRED` | 409 | 补正窗口已过，请联系教材室 | 被驳回表单补正超期 |
| `STATE_CONFLICT` | 409 | 数据状态已变更，请刷新后重试 | 乐观锁/并发/重复操作 |
| `NOTICE_TASK_EXISTS` | 409 | 本学期已存在进行中的通知任务 | 同学期仅 1 个 active |
| `DOWNLOAD_TOKEN_INVALID` | 410 | 下载链接已失效，请重新导出 | 一次性 token 复用/过期 |
| `RATE_LIMITED` | 429 | 操作过于频繁，请稍后再试 | |
| `SERVER_ERROR` | 500 | 服务开小差了，请稍后重试 | 不泄露堆栈 |

### 1.7 枚举速查（前端状态映射）

| 域 | 取值 |
|----|------|
| 教师征订单 `status` | `draft` 草稿 · `pending_review` 待审核 · `reviewed` 已通过（计入汇总/进学生清单）· `rejected` 已驳回（可补正）· `rejected_auto` 字段审查未过（可修复重提）· `submitted` 已提交（瞬时态） |
| 学生选购单 `status` | `draft` · `submitted` 已提交（重提=整单覆盖） |
| 异动 `status` | `pending_field_check` · `pending_review` 待审批 · `approved` 已通过（立即生效）· `rejected` 已驳回 |
| 学期 `activeStatus` | `draft` 可导入 · `active` 当前 · `archived` 只读归档 |
| 窗口 `windowStatus` | `not_open` 未开始 · `open` 进行中 · `closed` 已截止（延长可回到 open） |
| 导入批次 `status` | `running` · `done` · `failed` |
| 导出任务 `status` | `queued` · `running` · `done` · `failed` · `expired`（文件 24h 过期清理） |
| 通知任务 `status` | `active` · `closed`；`source`：`manual` 手动 / `system_window_change` 窗口变更自动 |
| 发送状态 `sendStatus` | `sent` 已发送 · `unauthorized` 未授权（进线下兜底名单）· `failed` 失败 |
| 账号状态 | `status`：1 正常 / 0 停用；`mustChangePassword`/`firstLoginVerified`：1/0 |

### 1.8 文件上传与下载

| 场景 | 约定 |
|------|------|
| 上传（导入） | `multipart/form-data`，字段名 `file`；仅 `.xlsx`（服务端校验后缀 + 魔数 PK），大小 ≤ `system_config.import.max_file_mb`（默认 10MB） |
| 同步下载（导出 ≤5000 行、模板、错误明细） | 直接返回 xlsx 文件流（`Content-Disposition: attachment`），**不是 JSON 包络**；前端用 `responseType: 'blob'` |
| 异步导出（>5000 行） | 返回 `{taskId, async:true, rowEstimate}` → 轮询 `GET /api/export-task/{id}` → `status=done` 后用 `GET /api/export-task/{id}/download?token=<downloadToken>` 下载 |
| 一次性 token | **单次有效**（首次下载后失效）、默认 10 分钟过期；过期/复用返回 410 `DOWNLOAD_TOKEN_INVALID`（提示重新导出） |

---

## 2. 接口地图（按角色 / 前端路由参考）

```
公共
├─ /api/auth/**                     登录、刷新、登出、首登校验、切换身份
└─ /api/semester/window/status      窗口状态 + 服务器时间（倒计时）

登录用户（/api/me/**、/api/notice/**、/api/export-task/**）
├─ /api/me                          用户信息 + 角色 + 当前身份 + 归属
├─ /api/me/permissions              权限码集合（动态菜单）
├─ /api/me/password                 改密
├─ /api/notice/unconfirmed          未确认通知（阻塞弹窗数据源）
├─ /api/notice/mine                 我的通知（全量含已确认，分页）
├─ /api/notice/{taskId}/confirm     确认（204）
└─ /api/export-task/{id}[/download] 导出任务查询与下载

超管 /api/admin/**（教材室）
├─ semester/**                      学期与窗口引擎（含双缓冲切换）
├─ college|major|class/**           组织三表
├─ textbook|course|teacher-course/** 教材/课程/任课（含导入与模板）
├─ user/**                          账号管理（含名单导入与模板）
├─ order-forms/**                   征订复核工作台（列表/详情/审核）
├─ student-orders                   全院选购
├─ change/**                        异动审批（单条/按批次）
├─ notice/tasks/**                  通知任务
├─ export/**                        四类导出
├─ dashboard                        数据看板
├─ config                           系统配置（8 键）
└─ audit                            审计日志查询

秘书 /api/secretary/**
├─ order-forms                      本院表单（只读）
├─ change、change/import            异动提交（逐条/批量）
└─ export/signature                 本院签字版导出

教师 /api/teacher/**
├─ my-courses、textbook、order-form[/submit]、order-forms   填报与历史
└─ change、change（GET）            异动提交与记录

学生 /api/student/**
├─ book-list                        本班教材清单（必修/下架标识）
├─ order、order/submit、orders      选购单与历史

供货商 /api/supplier/**（物理隔离）
├─ orders                           按学院分组清单
└─ export、export-task/{id}[/download]  一学院一 sheet 导出

通用 /api/batch/{batchId}[/errors]  导入批次进度与错误明细
```

---

## 3. 接口详表

### 3.1 认证与会话（8）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/auth/login` | 公开 | 登录 → access+refresh+角色+首登状态 |
| POST | `/api/auth/refresh` | 凭 refresh | 轮换 refresh，返回新 access + 新 refresh |
| POST | `/api/auth/logout` | 登录 | 撤销本人全部 refresh |
| POST | `/api/auth/first-login/verify` | 待改密 | 首登校验（`{phoneTail}` 手机号后 4 位，或 `{wxCode}` **校验已绑定的 openid**）。**wxCode 不能新建绑定**：只带 wxCode 时仅当该账号已绑定同一 openid 才通过（否则 401 + 计入失败计数）；openid 的绑定只发生在手机号后 4 位通过之后。未填手机号的账号无法自助首登，需教材室补手机号 |
| POST | `/api/auth/switch-role` | 登录 | 切换身份（返回新权限码集合） |
| GET | `/api/me` | 登录 | 用户信息 + 角色列表 + 当前身份 + 授权状态 + active 学期归属 |
| GET | `/api/me/permissions` | 登录 | 权限码列表 |
| PUT | `/api/me/password` | 登录 | 改密（撤销全部 refresh 并重发新令牌） |

**POST /api/auth/login**

```json
// 请求
{ "userNo": "900001", "password": "Admin@123" }
// 响应 data
{
  "accessToken": "eyJ...", "refreshToken": "9f2c...", "expiresIn": 900,
  "mustChangePassword": false, "firstLoginVerified": true,
  "roles": ["ADMIN"], "currentRole": "ADMIN", "userNo": "900001", "name": "张管理"
}
```

**POST /api/auth/first-login/verify**（`mustChangePassword=true` 时先调）

```json
{ "phoneTail": "0001", "wxCode": null }   // 二选一；wxCode 为小程序 wx.login code（换取 openid 绑定）
```

**PUT /api/me/password**

```json
{ "oldPassword": "900002", "newPassword": "Abc12345" }   // 8-64 位且含字母和数字，不得与原密码相同
```

**GET /api/me 响应 data（节选）**

```json
{
  "userId": 6, "userNo": "20230101", "name": "学生甲", "phone": "13700001001",
  "openidBound": false, "roles": ["STUDENT"], "currentRole": "STUDENT",
  "permissions": ["semester:window:view", "student:order:submit", "student:order:view:self"],
  "mustChangePassword": 0, "firstLoginVerified": 1,
  "semesterId": 1, "collegeId": 1, "collegeName": "计算机学院",
  "classId": 1, "className": "软工2023-1",
  "activeSemester": { "id": 1, "name": "2026-2027学年秋季学期", "windowStatus": "open", "channelOpen": 1 }
}
```

### 3.2 学期与窗口（12）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/semester/window/status` | `semester:window:view` | `{semesterId, semesterName, windowStatus, windowStart, windowEnd, channelOpen, serverTime}` — **倒计时以 serverTime 为准，不信任本地时钟** |
| GET | `/api/admin/semester` | `semester:semester:manage` | 学期列表（draft/active/archived） |
| GET | `/api/admin/semester/{id}` | `semester:semester:manage` | 学期详情 |
| POST | `/api/admin/semester` | `semester:semester:manage` | 新建学期（draft） |
| PUT | `/api/admin/semester/{id}` | `semester:semester:manage` | 编辑基本信息（名称/起止日期/窗口起止/auto 开关） |
| POST | `/api/admin/semester/{id}/activate` | `semester:semester:activate` | **双缓冲原子切换**（body 带 `version` 乐观锁；version 不匹配 → 409「存在更新的学期状态，请刷新」）。**不可逆**：切换时旧 active 自动归档，系统不提供回退接口 |
| POST | `/api/admin/semester/{id}/archive` | `semester:semester:activate` | 归档（数据只读保留，可查可导）。**不可逆**：归档后不能再次激活（`archived → active` 无路径，重复激活返回 409「归档不可逆」）；前端确认弹窗须写明「此操作不可撤销」 |
| PUT | `/api/admin/semester/{id}/window` | `semester:window:manage` | 设置窗口起止 + auto 开关（`windowStart < windowEnd`） |
| POST | `/api/admin/semester/{id}/window/open` | `semester:window:manage` | 手动开启 |
| POST | `/api/admin/semester/{id}/window/close` | `semester:window:manage` | 提前截止 |
| POST | `/api/admin/semester/{id}/window/extend` | `semester:window:manage` | 延长（无限次；延长至早于当前时间 → 400；closed 状态延长后自动回到 open） |
| GET | `/api/admin/semester/{id}/window/changes` | `semester:window:manage` | 变更记录（谁/何时/原值→新值，来自审计） |

**POST /api/admin/semester（新建）**

```json
{ "name": "2027-2028学年秋季学期", "startDate": "2027-09-01", "endDate": "2028-01-15",
  "windowStart": "2027-09-10 00:00:00", "windowEnd": "2027-10-31 23:59:59",
  "autoOpen": 1, "autoClose": 1 }
```

**POST /api/admin/semester/{id}/activate**

```json
{ "version": 0 }   // 取 GET /api/admin/semester/{id} 返回的 version 原样回传
```

> 窗口行为：`autoOpen=1` 且到点自动开启；`autoClose=1` 且到点自动截止；置 0 则到点不动（保留手动控制）。每次变更自动创建/合并系统通知任务并写审计。
>
> **学期切换不可逆（前端务必在确认弹窗写明）**：`activate` 会归档旧 active 学期，`archive` 会把当前 active 学期置为只读——两者都没有回退接口，`archived` 学期不能再被激活（返回 409「归档不可逆」）。因此**不要用真实学期的 activate/archive 做冒烟**（把种子学期归档后只能由 DBA 改库恢复）；这类验证请用可重建库的环境（`scripts/e2e-smoke.sh` 自带）。

### 3.3 组织三表（9）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/admin/college` | `org:college:manage` | 学院列表 |
| POST | `/api/admin/college` | `org:college:manage` | `{name, fullName?}` |
| PUT | `/api/admin/college/{id}` | `org:college:manage` | 同上 |
| GET | `/api/admin/major` | `org:major:manage` | `?collegeId=` 专业列表 |
| POST | `/api/admin/major` | `org:major:manage` | `{collegeId, name, fullName?}` |
| PUT | `/api/admin/major/{id}` | `org:major:manage` | 同上 |
| GET | `/api/admin/class` | `org:class:manage` | `?majorId=` 班级列表（含 `studentCount` 班级人数） |
| POST | `/api/admin/class` | `org:class:manage` | `{majorId, name, grade?, studentCount?}` |
| PUT | `/api/admin/class/{id}` | `org:class:manage` | 同上 |

> 严格模式：导入名单不会自动创建学院/班级，须先在此维护。班级人数 `studentCount` 是教师征订数量上限的来源。

### 3.4 教材 / 课程 / 任课（14）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/admin/textbook` | `textbook:book:manage` | 分页检索 `?isbn&title&author&press&status&page&size` |
| POST | `/api/admin/textbook` | `textbook:book:manage` | `{isbn,title,edition?,author?,press?,price?,status?}`（ISBN 重复 → 409） |
| PUT | `/api/admin/textbook/{id}` | `textbook:book:manage` | 同上 |
| POST | `/api/admin/textbook/{id}/status` | `textbook:book:manage` | `{status: 1 在库 / 0 停用}` |
| POST | `/api/admin/textbook/import` | `textbook:book:import` | 教材导入（multipart `file`）→ `{batchId}` |
| GET | `/api/admin/textbook/template` | `textbook:book:import` | 模板下载（xlsx：ISBN/书名/版次/作者/出版社/单价/状态） |
| GET | `/api/admin/course` | `course:course:manage` | `?semesterId=`（默认 active 学期）课程列表 |
| POST | `/api/admin/course` | `course:course:manage` | `{semesterId?, code?, name}`（同学期同 code 重复 → 409） |
| PUT | `/api/admin/course/{id}` | `course:course:manage` | `{code?, name}` |
| GET | `/api/admin/teacher-course` | `course:teacher:manage` | `?semesterId&teacherId&classId` 任课关系（含课程/教师/班级名） |
| POST | `/api/admin/teacher-course` | `course:teacher:manage` | `{semesterId?, teacherId, courseId, classId}`（教师须有 TEACHER 角色） |
| DELETE | `/api/admin/teacher-course/{id}` | `course:teacher:manage` | 逻辑删除 |
| POST | `/api/admin/teacher-course/import` | `course:teacher:manage` | 任课导入（`?semesterId`，multipart `file`）→ `{batchId}` |
| GET | `/api/admin/teacher-course/template` | `course:teacher:manage` | 模板下载（课程代码/课程名/教师工号/班级名称/学期） |

> **征订范围 = 任课关系表**（W17）：某课程本学期不征订 = 不导入该任课关系。

### 3.5 账号管理（6）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/admin/user` | `user:account:manage` | 分页检索 `?roleCode&collegeId&status&keyword&page&size`（`UserListItem` 含角色数组/锁定时间/首登状态） |
| POST | `/api/admin/user` | `user:account:manage` | 建号（含供货商）：`{userNo, name, phone?, collegeId?, classId?, roleCodes:["TEACHER"]}`；初始密码 = 学号/工号后 6 位，首次登录须校验+改密 |
| PUT | `/api/admin/user/{id}/status` | `user:account:manage` | `?status=0|1` 停用/启用（停用即时踢下线） |
| PUT | `/api/admin/user/{id}/reset-password` | `user:account:reset` | 重置为初始密码规则 + 强制改密 |
| POST | `/api/admin/user/import` | `people:student:import` / `people:teacher:import` | 名单导入：`?role=student|teacher&semesterId=`（默认 active 学期）+ multipart `file` → `{batchId}` |
| GET | `/api/admin/user/import/template` | 同上 | `?role=student|teacher` 模板下载（学生：学号/姓名/学院/专业/班级/手机号；教师：工号/姓名/学院/手机号） |

### 3.6 教师征订（教师端 5 + 复核端 4）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/teacher/my-courses` | `order:form:submit` | 本学期任课关系按班级分组：`[{classId, className, courses:[{courseId, courseName}]}]` |
| GET | `/api/teacher/textbook` | `order:form:submit` | **填报选书器**：`?keyword=` 在库教材检索（title/isbn/author/press 模糊匹配），返回**裸数组（非分页端点）** `[{textbookId,isbn,title,edition,author,press,price}]`；只返回 `status=1`，单次封顶 50 条（选择器场景，前端不做翻页） |
| GET | `/api/teacher/order-form` | `order:form:submit` | 当前学期征订单 + 明细（无单时 `data=null`） |
| POST | `/api/teacher/order-form/submit` | `order:form:submit` | 提交/补正（返回字段审查结果；失败 400 `FIELD_CHECK_FAILED` + `data=[{field,rule,message}]`） |
| GET | `/api/teacher/order-forms` | `order:form:view:self` | 历史提交记录（含学期名） |
| GET | `/api/secretary/order-forms` | `order:form:view:college` | 本院表单分页 `?status&teacherName&page&size` |
| GET | `/api/admin/order-forms` | `order:form:view:all` | 全院表单分页 `?semesterId&collegeId&status&teacherName&page&size` |
| GET | `/api/admin/order-forms/{id}` | `order:form:view:all` | 详情（含 `fieldCheckResult`、`contentVersion` 与明细）；教师只能看本人、秘书只能看本院（否则 403 + 审计） |
| POST | `/api/admin/order-forms/{id}/review` | `order:form:review` | `{action:"pass"|"reject", reason, contentVersion}`；reject 理由必填 1-200 字；仅 `pending_review` 可审（否则 409）。**`contentVersion` 必传**：把详情接口读到的值原样回传，服务端以它做 CAS——审核页打开后教师若又重提过（状态仍是 pending_review 但明细已被整单覆盖），版本不一致即 409「表单内容已变更或状态已更新，请刷新后重试」，避免审批结论落在没见过的内容上。不传时只能拦住请求处理窗口内的并发提交（降级行为） |

**POST /api/teacher/order-form/submit**

```json
{
  "items": [
    { "courseId": 1, "classId": 1, "textbookId": 1, "quantity": 45 },
    { "courseId": 1, "classId": 1, "textbookId": 2, "quantity": 45 }
  ]
}
```

字段审查 6 规则（错误逐项回显 `{field:"items[0].quantity", rule:"QTY_RANGE", message:"第 1 行：数量需在 1-50 之间"}`）：
`REQUIRED` 必填完整 · `QTY_RANGE` 数量 1~班级人数（缺失回退配置 999） · `BOOK_ACTIVE` 教材在库 · `COURSE_OWNER` 课程属本人 · `CLASS_LINK` 课程关联该班级 · `ISBN_FORMAT` ISBN 校验位。

> 补正规则（W4）：被驳回（`rejected`/`rejected_auto`）的表单**关窗后仍可在 `correctDeadline` 前补正重提**；超期返回 409 `CORRECTION_EXPIRED`。
>
> **`reviewed` 是终态**（PRD 状态机退出条件为「—」）：教师再提交返回 409「已通过审核，为终态不可修改（系统不提供撤销审核）」；教材室对 `reviewed` 表单再审核同样 409（「不能再次审核」）——**系统内没有撤销审核的路径**，不要引导用户「联系教材室驳回后补正」（该路径不存在）。前端在 `status=reviewed` 时应隐藏/禁用提交与审核按钮。

### 3.7 学生选购（学生端 4 + 超管 1）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/student/book-list` | `student:order:submit` | 本班教材清单：`[{textbookId,isbn,title,edition,author,press,price,required,delisted}]`（清单 = 本班任课关系下教师已审核通过教材并集；`delisted=true` 不可选，仅用于提示） |
| GET | `/api/student/order` | `student:order:submit` | 本人选购单 + 明细（含教材信息与下架标记） |
| POST | `/api/student/order/submit` | `student:order:submit` | 提交（**覆盖语义**：重提 = 整单替换） |
| GET | `/api/student/orders` | `student:order:view:self` | 历史选购记录（跨学期摘要：`id,semesterId,semesterName,status,submittedAt,itemCount,totalQuantity` + 提交时归属快照） |
| GET | `/api/admin/student-orders` | `student:order:view:all` | 全院选购分页 `?semesterId&collegeId&classId&studentName&page&size` |

> **已知缺口**：历史学期只有摘要端点，**无历史明细端点**（`GET /api/student/order` 仅返回 active 学期且无 `?semesterId` 参数）。小程序端按「历史仅摘要、明细限当前学期」降级展示。

**POST /api/student/order/submit**

```json
{ "items": [ { "textbookId": 1, "quantity": 1 }, { "textbookId": 3, "quantity": 2 } ] }
```

> 校验：窗口内（`windowStatus=open` 且 `channelOpen=1`，否则 409 `WINDOW_CLOSED`）；数量 1-9 且 ≤ 班级人数；含下架教材 → 400 `BOOK_DELISTED`。提交时记录学院/班级快照（后续异动不影响历史归属）。

### 3.8 异动审批（提交端 4 + 审批端 4）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/teacher/change` | `change:request:submit` | 逐条提交（教师/秘书同构） |
| POST | `/api/secretary/change` | `change:request:submit` | 逐条提交 |
| POST | `/api/secretary/change/import` | `change:request:submit` | Excel 批量（列：学号/工号、变更类型、目标学院、目标班级、原因）→ `{batchId, batchNo, total, okCount, errorCount}` |
| GET | `/api/teacher/change` | `change:request:submit` | 我的提交记录（教师/秘书均可调，返回当前用户本人记录） |
| GET | `/api/change/org-options` | `change:request:submit` | **提交端目标归属选项**：`{colleges:[{id,name}], classes:[{id,name,majorId}]}`（只读，不含人数等管理字段；组织维护接口仍为超管专属） |
| GET | `/api/admin/change` | `change:request:review` | 审批列表分页 `?semesterId&status&batchNo&type&page&size` |
| POST | `/api/admin/change/{id}/review` | `change:request:review` | `{action:"pass"|"reject", reason}`（reject 理由必填；通过后对 active 学期**立即生效**） |
| POST | `/api/admin/change/batch/review` | `change:request:review` | `{batchNo, action, reason}` 按批次批量处理（**仅支持按 batchNo，无按 ids 多选形态**；非导入产生的单条记录无 batchNo，需逐条审批） |

**POST /api/teacher/change**

```json
{ "type": "student", "targetUserNo": "20230102", "targetCollegeId": 2, "targetClassId": 5 }
// type=teacher 时只允许改学院：传 targetClassId 会 400「教师异动仅支持变更学院」（W16）
```

> 字段审查（目标学号存在/学院存在/学生需班级存在/类型合法/前后值不同）失败 → 记录直接落 `rejected` 并回显 `fieldCheckResult`，不抛 400。

### 3.9 导入中心（6 + 批次 2）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/admin/textbook/import` | `textbook:book:import` | 教材导入 → `{batchId}` |
| GET | `/api/admin/textbook/template` | `textbook:book:import` | 教材模板（xlsx） |
| POST | `/api/admin/teacher-course/import` | `course:teacher:manage` | 任课导入 → `{batchId}` |
| GET | `/api/admin/teacher-course/template` | `course:teacher:manage` | 任课模板（xlsx） |
| POST | `/api/admin/user/import` | `people:*:import` | 名单导入 → `{batchId}` |
| GET | `/api/admin/user/import/template` | `people:*:import` | 名单模板（xlsx） |
| GET | `/api/batch/{batchId}` | `import:batch:view` | 批次进度：`{id,bizType,total,okCount,errorCount,progressPct,status,batchNo,errorDetail}`。**归属校验**：非 ADMIN 只能读自己发起的批次，否则 404 |
| GET | `/api/batch/{batchId}/errors` | `import:batch:view` | 错误明细下载（xlsx；无错误行 → 404） |

**前端轮询建议**：上传 → 得 `batchId` → 每 1-2s 调 `GET /api/batch/{batchId}` 直到 `status ∈ {done, failed}`；`errorCount > 0` 时提供「下载错误明细」。**导入顺序**（外键依赖，前端按此引导）：学院 → 专业 → 班级 → 教师 → 课程任课 → 学生 → 异动。

> 停用比对（W14）：名单导入只比对**本次文件覆盖范围**（文件内学院 + 对应角色）内缺席的账号并停用，范围外账号不受影响；结果摘要（停用数量等）写入审计。
>
> **班级人数（W2）以名单为准**：学生名单导入会把文件内每个班级的 `school_class.studentCount` 重算为「该班在文件中的**去重学生数**」（同一学号多行只算 1 人），它是教师征订数量上限的来源。因此**局部名单会把上限改小**（文件里只放 1 行 → 上限变 1，教师填报会被 `QTY_RANGE` 拦住）。导入摘要的 `classSizeUpdates`/`classSizeShrinks` 会记录重算与下调规模（下调另记 WARN 日志），必要时用 `PUT /api/admin/class/{id}` 的 `studentCount` 手工修正。

### 3.10 导出中心（4 + 任务 2）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/admin/export/orders` | `export:order:create` | 教师征订明细（body 可选 `{semesterId, collegeId}`；秘书传本院 collegeId 即本院导出） |
| POST | `/api/secretary/export/signature` | `export:signature:create` | 本院签字版（含签字栏三行；学院范围取当前用户 active 学期归属） |
| POST | `/api/admin/export/students` | `export:student:create` | 学生选购汇总（参考用量：学院/班级/ISBN/书名/学生数/数量合计） |
| POST | `/api/admin/export/notice` | `export:notice:create` | 通知汇总（body `{taskId}`；含各轮发送时间/状态、确认状态/时间） |
| GET | `/api/export-task/{id}` | 登录（非 ADMIN 仅本人任务，否则 **404**） | 任务进度：`{id,bizType,rowEstimate,status,progressPct,downloadToken,tokenExpireAt,expiresAt,errorMsg}`。`downloadToken` 仅在**任务所有者**的响应中出现（越权读取由归属校验 + 统一 404 兜住）；`filePath`（服务器内部路径）永不下发。**`bizType=supplier` 的任务对非 ADMIN 一律 404**（供货商任务只经 `/api/supplier/export-task/{id}` 访问，物理隔离；ADMIN 例外以便排障） |
| GET | `/api/export-task/{id}/download` | 同上 | 必填 query `?token=<downloadToken>`；一次性下载（xlsx；复用/过期/超出文件保留期 → 410）。token 以 CAS 原子消费：并发同 token 请求只有一个能成功 |

**导出调用形态（四类一致）**

```
预估行数 ≤ export.sync_row_threshold(5000) → HTTP 200 + xlsx 文件流（前端 responseType:'blob'）
预估行数 > 5000                          → {code:"0", data:{taskId, async:true, rowEstimate}}
                                             → 轮询 GET /api/export-task/{taskId} 至 done
                                             → GET /api/export-task/{taskId}/download?token=<downloadToken>
```

### 3.11 通知确认闭环（3 + 管理端 5）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/notice/unconfirmed` | 登录 | 未确认任务队列（**阻塞弹窗数据源**）：`[{taskId,title,content,source,createdAt,roundStopped}]`，按创建时间倒序；**按 `target_roles` 定向**（仅面向本人角色的任务，ADMIN 全量）；前端弹窗队列上限 `notice.popup_queue_max`（默认 5，保留最新 5 条），全量在「我的」页可查（见下行 `/mine`） |
| GET | `/api/notice/mine` | 登录 | **我的通知（「我的」页数据源）**：`?page=1&size=20` → `PageResponse`，含**已确认与已关闭**任务：`[{taskId,title,content,source,status,createdAt,confirmedAt}]`；`confirmedAt=null` 表示待确认；与 `/unconfirmed` 同口径（**按 `target_roles` 定向**：仅返回面向本人角色的任务，ADMIN 全量可见） |
| POST | `/api/notice/{taskId}/confirm` | 登录 | 确认「收到」→ **HTTP 204 无 body**；幂等（重复调用仍 204）；body 可带 `{subscribeResult:"accepted"|"rejected"}`（小程序订阅授权上报） |
| GET | `/api/admin/notice/tasks` | `notice:task:view` | 本学期任务列表 |
| POST | `/api/admin/notice/tasks` | `notice:task:manage` | 手动创建 `{title,content,targetRoles?}`（默认 `STUDENT`；同学期已有 active → 409 `NOTICE_TASK_EXISTS`） |
| POST | `/api/admin/notice/tasks/{id}/close` | `notice:task:manage` | 手动关闭 |
| GET | `/api/admin/notice/tasks/{id}/progress` | `notice:task:view` | `{sent, unauthorized, failed, confirmed, roundLimit}` |
| GET | `/api/admin/notice/tasks/{id}/failures` | `notice:task:view` | 未授权/失败名单（线下兜底）：`[{userNo,name,role,collegeName,className,sendStatus,roundNo,sentAt}]` |

> 触达机制（W5/R10）：微信一次性订阅「一次授权一条」，**未确认者≈未授权者**，重发对其基本无效；弹窗为主触达（不设轮次上限），订阅消息为已授权用户的额外提醒；`unauthorized` 如实落库并进失败名单。教师/秘书仅弹窗触达（打开 Web 时拉取）。

### 3.12 系统配置 / 审计 / 看板（4）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/admin/config` | `config:config:manage` | 配置列表（8 键） |
| PUT | `/api/admin/config` | `config:config:manage` | 批量更新（body 为 `{键: 值}` 映射，如 `{"notice.round_limit":"5"}`；键白名单 + 值域校验，越界/未知键 → 400） |
| GET | `/api/admin/audit` | `audit:log:view` | 审计查询 `?userId&userNo&action&resource&startAt&endAt&page&size`（时间接受 ISO-8601 与 `yyyy-MM-dd HH:mm:ss`，另接受纯日期 `yyyy-MM-dd`：`startAt` 取当日 00:00:00、`endAt` 取当日 23:59:59.999999999 含当天整天；开始晚于结束 → 400） |
| GET | `/api/admin/dashboard` | `dashboard:stat:view` | 看板：`{semesterId, windowStatus, channelOpen, serverTime, colleges:[{collegeId,collegeName,teacherTotal,submitted,pendingReview,reviewed,rejected}], pendingReviewTotal, unconfirmedNoticeTotal, studentOrderTotal, studentSubmittedTotal}` |

**配置键清单（8）**：`notice.round_limit`(5) · `notice.interval_hours`(24) · `notice.popup_queue_max`(5) · `order.quantity.max_default`(999) · `order.correct_window_days`(7) · `export.sync_row_threshold`(5000) · `export.download_token_minutes`(10) · `import.max_file_mb`(10)。变更立即对未完结通知任务生效——**例外**：`notice.interval_hours` 目前只作为任务快照展示，重发由每日 09:30 的定时任务驱动，改大/改小不会改变实际发送节奏（待实现，见 .project-state.md 待确认项）。

### 3.13 供货商只读（4，物理隔离）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/supplier/orders` | `supplier:order:view` | 按学院分组清单 `?semesterId=`（默认 active）：`[{collegeId,collegeName,items:[{teacherName,isbn,title,quantity}]}]` — 字段白名单：**书名/ISBN/数量/教师姓名/学院** |
| POST | `/api/supplier/export` | `supplier:order:export` | 一学院一 sheet 导出（同步流 / `{taskId, async:true, rowEstimate}`——受理体与其余四类导出**同形**，前端可按同一套字段分流） |
| GET | `/api/supplier/export-task/{id}` | `supplier:order:export` | 任务进度。**仅限本人创建的 `bizType=supplier` 任务**，否则 404（内部导出任务不可枚举、不可下载） |
| GET | `/api/supplier/export-task/{id}/download` | `supplier:order:export` | 必填 query `?token=<downloadToken>`；一次性下载（复用/过期 → 410） |

> 红线：`/api/supplier/**` 为独立模块物理隔离，**不提供任何学生字段路径**；每次导出写审计（谁/何时/范围）。
>
> 隔离是双向的：供货商任务只能经 `/api/supplier/export-task/**` 访问，内部端点 `/api/export-task/{id}` 对非 ADMIN 返回 404（ADMIN 例外以便排障）；供货商经内部端点访问内部任务同样 404。

---

## 4. 关键业务流程

### 4.1 登录 → 首登校验 → 改密

```
POST /api/auth/login
  ├─ 成功且 mustChangePassword=false → 进入业务界面（GET /api/me、/api/me/permissions）
  └─ 成功且 mustChangePassword=true  → 首登引导页
       ├─ POST /api/auth/first-login/verify  {phoneTail:"0001"}   （wxCode 仅用于绑定 openid / 校验已绑定）
       └─ PUT /api/me/password {oldPassword, newPassword}
            → 返回新 access+refresh（旧 refresh 全部撤销）→ 进入业务界面
```

### 4.2 教师填报 → 两级审查 → 补正

```
GET  /api/teacher/my-courses            任课范围（按班级分组）
GET  /api/teacher/order-form            回显已存草稿/表单
POST /api/teacher/order-form/submit     提交
  ├─ 字段审查不过 → 400 FIELD_CHECK_FAILED + data=[{field,rule,message}]（表单落 rejected_auto，可无限次修复重提）
  └─ 通过 → status=pending_review
GET  /api/admin/order-forms?status=pending_review   超管复核工作台
POST /api/admin/order-forms/{id}/review {action:"reject", reason:"数量与班级人数不符", contentVersion:3}
  → status=rejected，correctDeadline=关窗+7 天
  → 教师端显示驳回理由；关窗后仍可在 correctDeadline 前补正重提（超期 409 CORRECTION_EXPIRED）
```

### 4.3 学生选购

```
GET  /api/semester/window/status        窗口是否 open（倒计时用 serverTime）
GET  /api/student/book-list             本班清单（required 必修 / delisted 已下架不可选）
POST /api/student/order/submit          提交（覆盖语义，重提=整单替换）
GET  /api/student/order                 回显本人选购单
```

### 4.4 异步导入轮询

```
POST /api/admin/user/import?role=student&semesterId=2  (multipart file)
  → {code:"0", data:{batchId: 12}}
GET  /api/batch/12   → {total:1200, okCount:1195, errorCount:5, progressPct:100, status:"done"}
GET  /api/batch/12/errors → xlsx 错误明细（第 N 行 + 原因）
```

### 4.5 异步导出 + 一次性下载

```
POST /api/admin/export/orders  {"semesterId":1}
  ├─ 行数 ≤5000 → 直接 xlsx 流（blob 下载）
  └─ 行数 >5000 → {data:{taskId:7, async:true, rowEstimate:12000}}
       GET /api/export-task/7                     → status: queued→running→done
       GET /api/export-task/7/download?token=xxx  → xlsx 流（token 单次有效、10 分钟过期）
```

### 4.6 通知弹窗与确认

```
（登录后）GET /api/notice/unconfirmed → 弹窗队列（前端按 popup_queue_max 截断展示）
用户点「收到」→ POST /api/notice/{taskId}/confirm  → 204（幂等）
小程序可在同一请求带 {"subscribeResult":"accepted"} 上报订阅授权（仅学生收订阅消息）
「我的」页历史 → GET /api/notice/mine?page=1&size=20（含已确认，confirmedAt 非空即已确认）
```

---

## 5. 前端联调注意事项

1. **401 分流**：`TOKEN_EXPIRED` 静默 refresh 重放；`REFRESH_INVALID`/`ACCOUNT_DISABLED` 清令牌跳登录；`FIRST_LOGIN_REQUIRED` 跳首登引导页（不要跳登录页）。
2. **409 提示刷新**：`STATE_CONFLICT`/`WINDOW_CLOSED` 等由后端状态机裁决，前端只提示并刷新数据（如「存在更新的表单状态，请刷新后重试」）。
3. **窗口判定不信任本地时钟**：倒计时基于 `GET /api/semester/window/status` 的 `serverTime` 与 `windowEnd` 差值。
4. **提交类接口的重复点击**：重提为覆盖语义、confirm 幂等、导出下载 token 单次有效——前端仍需防抖/按钮 loading。
5. **文件下载统一 `responseType: 'blob'`**：模板、同步导出、错误明细、一次性下载都是文件流；异步路径才返回 JSON（用 `taskId` 区分）。
6. **菜单与按钮权限**：以 `GET /api/me/permissions` 渲染；多角色用户切换身份后需重新拉取权限码（数据范围不变，仍按角色并集隔离）。
7. **错误展示**：优先展示 `message`（已按 PRD 文案规范）；字段审查类错误按 `data[{field,rule,message}]` 定位到具体行/字段。
8. **分页从 1 开始**：`page=1` 为第一页；`size` 上限 200。
9. **路径以本手册与 OpenAPI 为准**：如导出任务为 `/api/export-task/{id}`（单数，SPEC §11 契约基线）。
10. **时间格式**：**入参** body 与 query 都接受 ISO-8601（`2026-09-21T09:30:00`，推荐）与 `2026-09-21 09:30:00`（空格分隔，含缺秒），前端发哪种都行；**出参**恒为 ISO-8601（`2026-09-21T09:30:00`，部分字段带微秒 `2026-09-21T09:30:00.123456`）。前端统一在响应拦截器里格式化，不要假设出参与入参同格式。时间格式错误返回 400 `PARAM_INVALID`，`data` 会指明字段与可接受格式（如 `["windowStart: 时间格式应为 ISO-8601（2026-09-21T09:30:00）或 yyyy-MM-dd HH:mm:ss"]`）。
11. **null 键省略**：成功且 `data` 为 null 时整个 `data` 键不出现；`/api/me` 无归属行时归属五键不出现。判空用 `== null` / 可选链，不要用 `'key' in obj` 或 `=== null` 判定「接口未返回」。
12. **通知两个端点的分工**：`/api/notice/unconfirmed` 只给「未确认」（弹窗阻塞队列，含 `roundStopped`）；`/api/notice/mine` 给全量（含已确认与已关闭，分页）。**两者都按 `target_roles` 定向**（仅返回面向本人角色的任务，ADMIN 全量可见），前端应据此假设角色定向——空队列表示「没有面向本角色的通知」，不是故障。
13. **联调环境限频**：登录限频默认 10 次/分钟（`textbook.security.login.rate-per-minute`，按 IP+账号），多角色反复走查很快会撞 429 `RATE_LIMITED`；联调/压测环境建议调高。

---

## 6. 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-09-21 | 首版（V1.0.0） | 对齐 SPEC §11 契约基线（92 端点）；统一导出异步返回为 `{taskId, async, rowEstimate}`；导出/导入响应不下发服务器文件路径（`@JsonIgnore`） |
| 2026-09-21 | 新增 1 端点（V1.0.1） | `GET /api/teacher/textbook`（`order:form:submit`）：联调发现教师端缺少选书器数据源——教师无 `textbook:book:manage`，`/api/admin/textbook` 对其 403，而 PRD「填报教材页」要求「教材库搜索（书名/ISBN）选中后入明细」。补最小权限只读端点（仅 `status=1` 在库教材，字段白名单不含审计列，单次封顶 50 条）。 |
| 2026-09-21 | 新增 1 端点（V1.0.2） | `GET /api/change/org-options`（`change:request:submit`）：联调发现教师/秘书提交异动时无目标学院/班级数据源（组织三表接口均为 `org:*:manage`，对其 403），前端表单无法选择目标归属。补最小权限只读选项端点（仅 id + 名称）。 |
| 2026-09-21 | 缺陷修复（V1.0.2） | ① 导入落盘路径绝对化：`textbook.export.tmp-dir` 为相对路径（默认 `./data/export`）时 `MultipartFile.transferTo` 失败，5 个导入接口全部返回 400 `BIZ_ERROR 文件保存失败`；② 异动审批生效时同步 `sys_user.college_id/class_id` 冗余列（原仅写 `user_semester_profile`，导致 `/api/me` 与 `/api/admin/user` 在审批通过后仍回显异动前归属）。 |
| 2026-09-21 | 新增 1 端点（V1.0.3） | `GET /api/notice/mine`（仅需登录，分页）：`03 §7` 与本文档 §3.11 承诺「未确认队列之外的全量通知在『我的』页可查」，但此前只有 `/unconfirmed`（仅未确认）与 `/{taskId}/confirm`，承诺无端点支撑；补分页端点，含已确认与已关闭任务并回显 `confirmedAt`。 |
| 2026-09-21 | 契约补注（V1.0.3） | ① 明确**时间格式入参/出参不对称**（入参 `yyyy-MM-dd HH:mm:ss`，出参 ISO-8601 且可能带微秒——`spring.jackson.date-format` 只作用于 `java.util.Date`，不作用于 JSR-310）；② 明确 **null 键省略**语义（成功无数据时 `data` 键不出现；`/api/me` 无归属行时归属五键不出现）；③ 记录两处已知缺口：学生历史学期明细无端点（§3.7）、异动批量审批仅支持按 batchNo（§3.8）；④ 补联调限频说明（§5.13，默认 10 次/分钟）。 |
| 2026-09-21 | 缺陷修复（V1.0.4） | ① **JSON 列漏挂 MP resultMap**（自定义 `@Select` 不自动套用 `autoResultMap`，JSON 列被静默丢弃）：`GET /api/student/orders` 的 `submitSnapshot` 及由其派生的 `collegeId/collegeName/classId/className` 全为 null；`GET /api/admin/audit` 的 `detail` 恒为 null。同族漏挂共 5 处（另含 `OrderFormMapper.selectTeacherForms`、`ExportTaskMapper.selectByToken/selectExpired`，当前无可见影响）已一并修复。② **`@CollegeScope` 数据隔离条件静默失效**：首个条件被拼成 `" OR x = y"` 导致解析必失败并返回 null（拦截器整段跳过），且 MP `setWhere` 是**替换**语义而 handler 未合并原 where——修复后隔离条件按预期生效（3 个标注语句另有 SQL 层显式过滤，此前未造成越权）。③ `GET /api/student/orders` 补 `itemCount`（明细行数，与教师侧 `order-forms` 口径一致）。 |
| 2026-09-21 | 回归防护（V1.0.4） | 新增**真实 HTTP multipart 上传**集成用例（`RANDOM_PORT` + TestRestTemplate + 相对 `tmp-dir`）：回退 V1.0.2 的落盘修复后该用例即返回 400（与线上现象一致），锁定导入缺陷不再回归；另补数据隔离条件构建单元用例、审计明细读回用例、学生历史/选书器/org-options 用例。测试总数 140 → 164。 |
| 2026-09-22 | 契约修复（V1.0.5） | ① **时间入参两侧口径统一为「两种格式都接受」**：此前 `spring.mvc.format.date-time` 只管 MVC 参数绑定，body 由 Jackson 按 ISO 解析——联调实测「body 传 `2026-09-21 09:30:00` 直接 400、query 传 ISO 也 400」，且 400 文案只有「请求参数有误」。现由 `TimeFormatConfig`/`TimeFormats` 统一解析（ISO 与空格都接受，出参仍 ISO），时间格式错误回 400 + 逐字段提示（`["windowStart: 时间格式应为 …"]`）。② 审计查询的纯日期 `endAt` 由「当日 00:00:00」改为「当日结束」（原语义会把当天记录整体排除）。 |
| 2026-09-22 | 契约修复（V1.0.5） | ③ **供货商导出异步受理体补齐 `async`/`rowEstimate`**（此前只有 `{taskId}`，与 §3.10 其余四类不一致，前端只能靠 Content-Type 分流）。④ **供货商任务物理隔离补齐**：内部端点 `/api/export-task/{id}` 对 `bizType=supplier` 任务返回 404（非 ADMIN），隔离变为双向（ADMIN 例外以便排障）。⑤ `reviewed` 终态文案修正：教师重提不再引导「联系教材室驳回后补正」（该路径不存在——教材室对 `reviewed` 再审核同样 409），改为如实说明终态；管理员重复审核文案由「请刷新后重试」改为「已通过审核（终态），不能再次审核」。⑥ 学期 `activate`/`archive` 明确不可逆（归档后不能再次激活，文案含「归档不可逆」）。 |
| 2026-09-22 | 语义明确（V1.0.5） | ⑦ **班级人数（W2）以名单为准**：学生名单导入把 `school_class.studentCount` 重算为文件内该班**去重**学生数（此前按行数计数，同学号重复行会放大上限）；导入摘要新增 `classSizeUpdates`/`classSizeShrinks` 并下调时记 WARN——局部名单会把教师数量上限改小，需人工确认是否用 `PUT /api/admin/class/{id}` 修正。 |
| 2026-09-22 | 上线前复审修复（V1.0.6） | ① **窗口变更重置轮次撞唯一键**：`onWindowChange` 的逻辑删除语句漏 `deleted=0`，第二次窗口变更（重发之后）会撞 `uk_notice_round` → 整个变更事务回滚，自动截止每分钟重试每分钟失败，**窗口再也关不上**（延长/提前截止同样失败）。② **教师提交与审核并发**：提交侧只有「读后判断」，与审核并发时会把 `reviewed` 静默改回 `pending_review`（审批结论被撤销，audit_log 却留着已审核）——提交事务内改为对该表单行加锁并重读状态（`selectByIdForUpdate`）。③ **编辑学期基本信息回写整实体**：会把 `window_status/channel_open/version/active_status` 按旧快照写回（窗口被静默重开、version 回退；极端情况把已激活学期写回 draft → 全站无 active 学期），改为只写请求字段。④ **首登 wxCode 分支可接管账号**：wxCode 换来的 openid 原直接绑定并置已验证，未与已有 openid 比对——改为「只校验已绑定 openid，绑定仅发生在手机号后 4 位通过之后」。⑤ **重置密码不清 `first_login_verified`**：重置后（口令回到学号后 6 位）可跳过首登校验直接改密，已一并清零。⑥ **draft 学期名单导入全局停用账号**：停用比对候选集来自 `sys_user.college_id`（active 学期冗余列），对 draft 学期导入会把 active 在册的人误停用；现仅在「目标学期 = active 学期」时执行。⑦ 可信代理配置绑定修复（`TEXTBOOK_TRUSTED_PROXIES` 此前无占位符，配了不生效，审计 IP 恒为 127.0.0.1）。⑧ 通知任务合并时 `target_roles` 取并集（此前合并进手动 STUDENT-only 任务后，教师/秘书收不到窗口变更通知）。⑨ `local` profile 连非本机库直接拒绝启动；定时任务线程池 1 → 3（通知重发不再阻塞窗口引擎）。 |
