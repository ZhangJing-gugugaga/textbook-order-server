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

**前端**：`status=reviewed` 时隐藏/禁用"提交/修改"按钮，文案引导"如需修改请联系教材室驳回后补正"。

### A5. 归属失败的语义统一为 **404**（不再是 403）

| 端点 | 场景 | 现在 |
|------|------|------|
| `GET /api/export-task/{id}` | 非本人任务 | **404** `NOT_FOUND` |
| `GET /api/export-task/{id}/download` | 非本人任务 | **404** |
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
5. `filePath`（服务器内部路径）**永不下发**；`paramsJson` 会下发（含 semesterId/collegeId）。

### A7. 新增两类协议错误码

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
| 微信订阅消息 | 未配置模板 id 时 `notice_record` 如实记 `unauthorized`；弹窗通道不受影响 |

---

## E. 后端已自测覆盖（前端可据此判断"不是后端的问题"）

- `./mvnw test -Dmysql.local.enabled=true` → **217 用例通过 / 0 失败 / 3 跳过**
- `bash scripts/e2e-smoke.sh` → **45 项断言全通过**，含：
  - 五角色主流程（ADMIN / TEACHER / STUDENT / SECRETARY / SUPPLIER）
  - A1 审核版本号 CAS（过期版本必须 409）
  - A5 供货商越权负例（读内部导出任务 404、访问教师/管理端 403）
  - A3 通知定向（供货商可见通知数 = 0）
  - A7 协议边界（401 / 405+Allow / 415 / 400）
  - 分页上限、CORS、失败锁定

> 联调中若出现异常，请先带 `X-Request-Id` 找后端对日志——比"我这边报错了"高效得多。
