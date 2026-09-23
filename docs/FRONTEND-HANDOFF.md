# 前端联调交接（2026-09-22）

> 本轮后端做了两轮安全/一致性修复，**端点数量与路径没有变化**（95 个端点，与 [API.md](../API.md) 完全一致），
> 但有若干**契约行为变更**，前端不改会在联调中出问题。下面 A 节是必须处理的，B 节是建议接入的，
> C 节是环境信息，D 节是已知限制。
>
> 完整字段级契约见 [API.md](../API.md)（92+ 端点手册）；本文只列**变化点与联调要点**。

---

## A. 必须处理（不改会出问题）

### A1. 审核接口必须回传 `contentVersion`（防"审核对象漂移"）

**背景**：管理员打开审核详情页 → 教师又重提了一次（表单状态仍是 `pending_review`，但明细已被整单覆盖）
→ 管理员点"通过"。服务端只比对状态是发现不了的，审批结论会落在他没见过的内容上。

**做法**：

1. `GET /api/admin/order-forms/{id}` 的 `data.contentVersion`（整数）是审核页"看到的版本"；
2. 提交审核时把它原样带回：

```http
POST /api/admin/order-forms/{id}/review
{ "action": "pass", "reason": "", "contentVersion": 2 }
```

3. 版本不一致 → **409 `STATE_CONFLICT`**，提示"表单内容已变更或状态已更新，请刷新后重试"，
   前端应**重新拉取详情**再让管理员确认（不要自动重试，否则等于替他确认了没看过的内容）。

**不传 `contentVersion` 会怎样**：服务端降级为"只拦请求处理窗口内的并发提交"，
**拦不住跨请求的重提**——即 A1 描述的场景会真实发生。所以请务必回传。

### A2. 首登待完成时**不能**调 `switch-role`

首登待完成（`mustChangePassword=true` 或 `firstLoginVerified=false`）时的放行清单现在是**显式枚举**：

| 放行 | 端点 |
|------|------|
| ✅ | `POST /api/auth/login` |
| ✅ | `POST /api/auth/refresh` |
| ✅ | `POST /api/auth/first-login/verify` |
| ✅ | `POST /api/auth/logout` |
| ✅ | `/api/me`、`/api/me/permissions`、`PUT /api/me/password` |
| ❌ | 其余全部（含 `POST /api/auth/switch-role`）→ 403 `FIRST_LOGIN_REQUIRED` |

**首登校验的两种方式（2026-09-22 收紧）**：`{phoneTail}`（手机号后 4 位）通过后才把 `wxCode` 换来的
openid 绑到账号；**只带 `{wxCode}` 时只能校验「已绑定的 openid」**，未绑定的账号一律 401 并计入失败计数。
原因：原实现把 wxCode 换来的 openid 直接绑定并置已验证，配合「初始口令 = 学号后 6 位（学号可枚举）」
可被任何人接管账号。小程序端若在首登页只发 wxCode，请改为「手机号后 4 位必填 + 可选 wxCode」；
**未填手机号的账号（导入模板手机号可空）无法自助首登，需教材室补手机号**。

**变更点**：此前 `/api/auth/switch-role` 被 `/api/auth/` 前缀通配放行（它会重发 access/refresh，
等于让未完成首登的会话拿到新的长效令牌），现已拦截。

**前端流程**：登录 → 若 `mustChangePassword=true` → 引导首登校验 → 改密 → 才进业务界面。
**多角色用户在此阶段不要调 switch-role**（会被 403），等改密完成后再切身份。

### A3. 通知按 `target_roles` 定向（空队列是正常的）

`GET /api/notice/unconfirmed`、`GET /api/notice/mine`、`POST /api/notice/{taskId}/confirm`
现在都按 `notice_task.target_roles` 过滤：

- 只返回**面向本人角色**的通知（ADMIN 全量可见）；
- **空队列 = 没有面向本角色的通知，不是故障**。前端若有"空则报错/重试"逻辑请去掉；
- 供货商不在窗口变更通知的 `target_roles`（秘书/教师/学生）内 → 供货商永远看到空队列，属预期；
- 不在定向范围内的任务，`confirm` 返回 **404**（不泄露任务是否存在）。

### A4. 教师端 `status=reviewed` 时必须禁用提交

`reviewed` 是**终态**（PRD 状态机退出条件为"—"）。此前教师重提会把已通过审核的表单覆盖回
`pending_review` 并清空审核记录（静默撤销审批结论），现已拦截：**再提交返回 409**。

**前端**：`status=reviewed` 时隐藏/禁用"提交/修改"按钮。**文案不要写"如需修改请联系教材室驳回后补正"**——`reviewed` 是终态，审核接口只接受 `pending_review`，教材室在系统内同样驳不回（对 `reviewed` 再审核返回 409「已通过审核（终态），不能再次审核」）。建议改为"已通过审核，如需变更请联系教材室线下处理"。

### A5. 归属失败的语义统一为 **404**（不再是 403）

| 端点 | 场景 | 现在 |
|------|------|------|
| `GET /api/export-task/{id}` | 非本人任务 | **404** `NOT_FOUND` |
| `GET /api/export-task/{id}/download` | 非本人任务 | **404** |
| `GET /api/export-task/{id}` | `bizType=supplier` 的任务（即使**本人**创建） | **404**（2026-09-22 起：供货商任务只走 `/api/supplier/export-task/**`，隔离是双向的；ADMIN 例外以便排障） |
| `GET /api/supplier/export-task/{id}` | 非本人 或 非 `bizType=supplier` | **404** |
| `GET /api/batch/{batchId}`、`/errors` | 非本人批次 | **404** |

**原因**：403/404 的差异本身可用于探测"该 id 是否存在"（枚举内部任务）。
前端不要把 404 当"服务异常"重试；也确实不存在"有权限但没找到"与"没权限"的区分了。

### A6. 导出/下载流程的硬约束

```
POST /api/admin/export/orders  (或 /students、/notice、/secretary/export/signature、/supplier/export)
  ├─ 预估行数 ≤ 阈值(5000)：直接返回 xlsx 二进制流（Content-Type=...spreadsheetml.sheet）
  └─ > 阈值：返回 { taskId, async: true, rowEstimate }
        → 轮询 GET /api/export-task/{id}（或 /api/supplier/export-task/{id}）
        → status=done 时响应里才有 downloadToken
        → GET /api/export-task/{id}/download?token=<downloadToken>
```

前端必须注意：

1. **按 `Content-Type` 分流**，不能假设一定返回 JSON（同步路径返回的是 xlsx 二进制）；
2. `downloadToken` **只在任务所有者的轮询响应里**出现，任务完成前没有；
3. token **单次有效**，且在**下载开始时即被消费**——下载中途失败、或文件已被清理，
   token 就没了，必须**重新导出**（不要原地重试同一个 token）；
4. 失效情形都返回 **410 `DOWNLOAD_TOKEN_INVALID`**：token 复用、token 过期（默认 10 分钟）、
   文件超出保留期（默认 24 小时）；
5. `filePath`（服务器内部路径）**永不下发**；`paramsJson` 会下发（含 semesterId/collegeId）；
6. **轮询端点按角色固定**：内部用户（超管/秘书）用 `/api/export-task/{id}`，供货商**必须**用
   `/api/supplier/export-task/{id}`——2026-09-22 起内部端点对 `bizType=supplier` 的任务返回 404
   （双向物理隔离）。`/api/supplier/export` 的异步受理体现在也是 `{taskId, async, rowEstimate}`，
   与其余四类同形，可统一处理（不再需要靠 Content-Type 猜测）。

### A7. 时间入参：两种格式都接受（不再有 body/query 分叉）

此前 `spring.mvc.format.date-time` 只作用于 MVC 参数绑定：**body 只认 ISO 的 `T`、query 只认空格格式**，
传错格式一律 400，且文案只有"请求参数有误"。现在两侧统一（`TimeFormats`）：

| 位置 | 接受 |
|------|------|
| body（如窗口设置 `windowStart/windowEnd`、`PUT /api/admin/semester/{id}`） | `2026-09-21T09:30:00`、`2026-09-21 09:30:00`、缺秒 `09:30` |
| body 日期字段（`LocalDate`，如学期 `startDate/endDate`） | `2026-09-01`；带时间也接受（取日期部分，`2026-09-01 00:00:00` ≡ `2026-09-01`） |
| query（如审计 `startAt/endAt`） | 同上，另接受纯日期 `2026-09-21`（`startAt` 取当日 00:00:00、`endAt` 取当日 23:59:59.999999999） |
| 出参 | 恒为 ISO-8601（`2026-09-21T09:30:00`），与入参格式无关 |

格式确实非法时返回 400 `PARAM_INVALID`，`data` 会指明字段与可接受格式：
`["windowStart: 时间格式应为 ISO-8601（2026-09-21T09:30:00）或 yyyy-MM-dd HH:mm:ss"]`。

> **2026-09-23 更正（生产实测 B12）**：前端 `src/api/semester.ts` 与 `src/utils/format.ts` 的注释写
> 「JSON body 只认 ISO、query 只认空格」——那是 `TimeFormatConfig` 上线前的旧行为，已被实测推翻：
> `PUT /api/admin/semester/{id}` 传空格格式返回 200 并真实写库。前端继续用 `toWireDateTime` 转 ISO
> 没有问题（两种都接受），但**不要再按「格式不对称」设计**：新接口不必为 body/query 分别做格式转换，
> 非法格式统一为 400 + 逐字段提示。

### A8. 学期切换/归档不可逆，且归档有二次门禁

`POST /api/admin/semester/{id}/activate` 会归档旧 active 学期，`POST /{id}/archive` 会把当前学期置为只读，
**归档不可逆**：`archived` 学期不能再激活（返回 409），也没有 active→draft 的路径。唯一的回退是
`POST /{id}/unarchive`（受限回滚，见下），且要求当前没有 active 学期（即业务已停摆）。

**归档必须带确认参数（2026-09-23 新增，生产事故修复）**：

```jsonc
// POST /api/admin/semester/{id}/archive
{ "version": 3, "confirmWindowOpen": true }
// version 必填（取列表/详情返回的 version 原样回传，不匹配 → 409）
// 窗口进行中（windowStatus=open 或 channelOpen=1）时 confirmWindowOpen 必须为 true，否则 409：
//   「该学期征订窗口仍在进行中（…）：归档会立即停止全站征订业务…，请先关闭窗口再归档，
//     或在前端强确认后带 confirmWindowOpen=true 重新提交」
```

前端确认弹窗请写明"**此操作不可撤销**"，并在 409 时用后端 message 做二次确认（不要自行拼文案）：
窗口进行中的归档一旦执行，全站接口都会报「当前没有激活学期，请先创建并激活学期」。

**误归档回滚**（新增端点，2026-09-23）：

```jsonc
// POST /api/admin/semester/{id}/unarchive
{ "version": 4, "confirm": true }   // 两者都必填
// 仅当当前无任何 active 学期时可回滚（否则 409「当前已有激活学期…请先归档它」）
// 回滚只恢复 activeStatus=active；窗口保持 closed，需管理员手动重新开启（不自动恢复填报通道）
```

> **不要拿真实学期的 activate/archive 做冒烟验证**，归档后业务会停摆（虽然现在有 unarchive 可救，
> 但仍应使用可重建库的环境，如 `scripts/e2e-smoke.sh`）。

### A9. 即时决策带来的契约变更（2026-09-23 · BE-1~BE-8）

**新增端点（14 个）**

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/admin/role` | `role:manage` | 角色列表（含 `builtIn` / `userCount` / `permCodes`） |
| POST | `/api/admin/role` | `role:manage` | 新建角色 `{roleCode, roleName, sort?}` |
| PUT | `/api/admin/role/{id}` | `role:manage` | 编辑名称/排序 |
| DELETE | `/api/admin/role/{id}` | `role:manage` | 删除（内置 400 / 有账号 409） |
| PUT | `/api/admin/role/{id}/permissions` | `role:permission:assign` | 角色-权限全量覆盖 `{permCodes:[...]}` |
| GET | `/api/admin/permission` | `role:manage` | 权限目录（按模块分组，39 条） |
| PUT | `/api/admin/user/{id}/roles` | `user:account:manage` | 账号角色覆盖 `{roleCodes:[...]}`（**强制该账号重新登录**） |
| GET | `/api/teacher/order-forms/{id}` | `order:form:view:self` | 教师本人明细（**替换原来调用的 `/api/admin/order-forms/{id}`**） |
| GET | `/api/secretary/order-forms/{id}` | `order:form:view:college` | 秘书本院明细（同上替换） |
| POST | `/api/teacher/order-form/withdraw` | `order:form:submit` | 撤回（无 body；`pending_review → draft`） |
| POST | `/api/admin/notice/tasks/{id}/send-now` | `notice:task:manage` | 立即发送一轮（返回统计） |
| GET | `/api/notice/subscribe-config` | 登录即可 | `{subscribeTemplateId, popupQueueMax}`（模板未配置 → null） |
| POST | `/api/notice/confirm-by-entry` | 登录即可 | 进入选书页即确认（幂等） |
| GET | `/api/secretary/change/template` | `change:request:submit` | 异动导入模板（6 列） |

**既有端点变更（前端必须同步）**

| 端点 | 变更 |
|------|------|
| `/api/me`、`/api/me/permissions` | **超管返回 39 条权限**（BE-1 鉴权短路）。前端菜单仍按 `meta.roles` 过滤，不受影响；但**不要**用权限条数判断角色 |
| `GET /api/admin/notice/tasks` | 新增可选 `semesterId`（缺省 = active 学期） |
| `POST /api/secretary/change/import` | 响应由同步结果体改为 **`{batchId}`**（异步批次）；进度/错误明细走 `/api/batch/{id}` |
| `GET /api/admin/change` | 新增可选 `changeType` 筛选；响应新增 `changeType` / `changeTypeLabel` |
| `GET /api/teacher/order-form`、`/order-forms`、两个新明细端点 | 响应新增 `withdrawnAt`；`status` 可能出现 `draft`（撤回后） |
| `POST /api/admin/export/notice` | 响应文件新增「渠道」列（班级之后） |
| 教师端「我的提交记录」 | 待审核时展示「撤回修改」按钮（`POST /api/teacher/order-form/withdraw`），成功后表单回到可编辑（`draft`）并提示 `withdrawnAt` |

> 通知侧行为变化：**新任务立即发首轮**（不必等次日 09:30）；窗口关闭会自动关闭该学期 active 任务；
> 通知汇总导出的「渠道」列为 `订阅消息+弹窗` / `仅弹窗（未授权）` / `仅弹窗`。

### A10. 两类协议错误码

| code | HTTP | 场景 |
|------|------|------|
| `METHOD_NOT_ALLOWED` | 405 | 请求方法不匹配（响应带 `Allow` 头） |
| `MEDIA_TYPE_NOT_SUPPORTED` | 415 | `Content-Type` 不支持 |

此前这两类会被兜底成 **500 `SERVER_ERROR`**。前端错误提示请按 `code` 分流，
不要把 405/415 显示成"服务开小差了"。

---

## B. 建议接入（后端已就绪）

| 项 | 说明 |
|----|------|
| `X-Request-Id` | 每个响应都回带；前端请求时自带该头会被**沿用**（便于串联前后端日志）。建议在错误提示里展示，便于报障时定位 |
| `GET /actuator/health` | 探活（含 db 与磁盘），`{"status":"UP","groups":["liveness","readiness"]}` |
| `GET /api/change/org-options` | 异动提交表单的学院/班级下拉数据源（只返回 `id`+`name`，不含管理字段） |
| `GET /api/teacher/textbook?keyword=` | 教师填报选书器：只在库教材，**无分页**、上限 50 条、按 id 倒序。关键词请 URL 编码（未编码的中文 URI 会被 Tomcat 判 400） |
| `GET /api/admin/semester/{id}/window/changes` | 窗口变更审计（谁/何时/原值→新值），已下推 SQL 分页 |
| 分页约定 | `page` 默认 1（上限 100 万）、`size` 默认 20（上限 200，`size<=0` 退回 20）。前端传超限值不会报错，会被归一化 |
| 补正截止 | 教师表单详情/历史的 `correctDeadline` 现在**总会**有值（`rejected` 与 `rejected_auto` 都会写）。前端应展示，过期后提交返回 409 `CORRECTION_EXPIRED` |

---

## C. 联调环境

| 项 | 值 |
|----|-----|
| baseUrl（本机） | `http://127.0.0.1:8080` |
| 启动 | `E:\tools\mysql-local.bat start` → `set -a; . ./.env.local; set +a` → `java -jar target/textbook-order-server.jar` |
| 鉴权 | 所有业务接口需 `Authorization: Bearer <accessToken>`；access 15 分钟、refresh 7 天轮换 |
| 账号 | 见 [README.md](../README.md) 账号表（`900001/Admin@123`、`800101/Sec@12345`、`700101/Tea@12345`、`20230101/Stu@12345`、`600001/Sup@12345`） |
| 首登校验 | 手机号后 4 位（如学生甲 `13700001001` → `0001`），见 README |
| 演示数据 | 学期 1（active、窗口已开）、教师 700101 ↔ 课程 1「数据结构」/ 班级 2「软工2023-1」、教材 1、学生 20230101 在班级 2、秘书 800101 在学院 1 |

### CORS（重要）

当前后端**未配置跨域白名单**，即**不返回任何 CORS 响应头**，只允许同域访问。

- **推荐**：前端 devServer 配 proxy（与生产 Nginx 同域反代一致），例如 Vite：

  ```js
  server: { proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } } }
  ```

- 若必须跨域直连，需要后端设置环境变量后重启（**不要用 `*`**，后端会拒绝通配符）：

  ```
  TEXTBOOK_CORS_ORIGINS=http://localhost:5173
  ```

### 联调期必调的两项（否则走查必然撞限流）

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE` | 10 | 登录/首登校验限频（按 IP+账号，1 分钟粒度）。多角色反复走查会在几分钟内撞 **429 `RATE_LIMITED`**；联调/压测建议调到 200+ |
| `TEXTBOOK_SECURITY_LOGIN_MAX_FAIL` | 5 | 连续失败 5 次即锁定 15 分钟，且**失败计数落库、重启不清**（锁定期间 401 `ACCOUNT_LOCKED`）。压测负例（错误口令）时请一并调大 |

> 说明：这两项是**进程内**限频/锁定（多实例部署各自计数），联调环境直接改环境变量重启即可，不需要改库。

---

## D. 已知限制（后端侧，前端需容忍）

| 限制 | 影响与建议 |
|------|-----------|
| 窗口边界精度 | 窗口判定由每分钟定时任务驱动，`window_end` 之后**最多 60 秒仍可提交**。前端倒计时归零后不要立即假设"必然 409" |
| 提交明细条数上限 | 单次提交 ≤ 500 条（超出 400） |
| 批量审批条数上限 | 单次 ≤ 500 条（超出 400，提示分批） |
| 导入错误明细保留期 | 文件保留 24 小时后清理，之后下载 404；文案会区分"该批次没有错误明细"与"错误明细文件已过期清理（保留 24 小时）" |
| 供货商清单行数上限 | `GET /api/supplier/orders` 上限 20000 行（超出截断并记日志），超出场景请用导出 |
| 供货商历史批次 | 升级前的历史导入批次 `created_by` 为 NULL，对非 ADMIN 按 404 处理（有意的 fail-closed 选择） |
| 班级人数以名单为准 + 局部名单门禁 | 学生名单导入会把 `school_class.studentCount` 重算为**文件内该班去重学生数**（教师征订数量上限的来源）。**局部名单会把上限改小**（只放 2 行 → 上限变 2，教师提交会被 `QTY_RANGE` 拦住）。2026-09-23 起下调幅度**比例 > 20% 且 ≥ 5 人**时导入直接 **409 `STATE_CONFLICT`**（不建批次、不改人数），message 含逐班 diff（如「软工2023-1（计算机学院/软件工程）50 → 2（-48，96%）」），须带 `confirmClassSizeShrink=true` 重提；也可先调 `POST /api/admin/user/import/preview`（只读，不落库）拿 `classSizeDiffs[]`/`newUserCount`/`disableEstimate` 做确认弹窗。导入摘要的 `classSizeUpdates`/`classSizeShrinks`/`classSizeShrinkConfirmed` 与 WARN 日志会记录下调与确认情况；需修正时用 `PUT /api/admin/class/{id}` |
| 微信订阅消息 | 未配置模板 id 时 `notice_record` 如实记 `unauthorized`；弹窗通道不受影响 |
| 联调夹具无法用接口清理 | 联调产生的 `[IT]` 前缀学院/专业/班级/课程/教材/账号没有删除接口（组织三表与教材为严格模式，防误删），只能由 DBA 按前缀清理；每轮联调还会新建 1 个 draft 夹具学期 |

---

## E. 后端已自测覆盖（前端可据此判断"不是后端的问题"）

- `./mvnw test -Dmysql.local.enabled=true` → **241 用例通过 / 0 失败 / 3 跳过**
  （不加该参数则跳过本地 MySQL 用例，见 README §测试；跳过的 3 项 = Testcontainers×2 + 性能用例×1）
- `bash scripts/smoke-isolated.sh` → **45 项断言全通过**（一次性库 + 独立端口，可重复执行；
  直接跑 `e2e-smoke.sh` 需声明 `SMOKE_DB=<一次性库>`，对着共享库跑会被脚本拒绝），含：
  - 五角色主流程（ADMIN / TEACHER / STUDENT / SECRETARY / SUPPLIER）
  - A1 审核版本号 CAS（过期版本必须 409）
  - A5 供货商越权负例（读内部导出任务 404、访问教师/管理端 403）
  - A3 通知定向（供货商可见通知数 = 0）
  - A7 协议边界（401 / 405+Allow / 415 / 400）
  - 分页上限、CORS、失败锁定

> 联调中若出现异常，请先带 `X-Request-Id` 找后端对日志——比"我这边报错了"高效得多。
