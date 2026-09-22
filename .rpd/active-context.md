# 活跃上下文 · textbook-order-server

> 迁移说明（v1→v2）：本项目原无 rpd 状态文件；2026-09-21 首次接入 rpd v2，
> v1 兼容状态文件已建立于 `.project-state.md`（功能进度清单/决策/阻塞的完整版），
> 本文件为 v2 产品层进度落点（会话收尾必写）。

## 上次进度（2026-09-21 收尾）

- **MVP 已完成**：PRD 九大模块全部实现（认证/RBAC 隔离/窗口引擎/双缓冲/导入导出/两级审查/学生选购/异动审批/通知闭环/配置审计/供货商只读/看板）
- **质量门**：`./mvnw clean package` 通过；164 用例 0 失败（单元 54 / 切片 30 / 集成 75 / ArchUnit 5；跳过 3 = 性能默认禁用 + Testcontainers 无 Docker）
- **联调交付**：`API.md`（95 端点 + 权限码 + 错误码 + 关键流程），与代码程序化交叉校验 100% 一致；OpenAPI 3 = `/v3/api-docs`
- **运维交付**：`docs/deployment.md`（Nginx/systemd/mysqldump 备份/移交检查单）
- **安全扫描**：8 条 `.gitignore` 缺项已修复；4 条 SEC-007 确证误报（Java `Map.put/get` 的变量名尾字母 `r` 命中 Express 路由正则，真实鉴权由 Security 链 + `@PreAuthorize` 承担，越权矩阵已验证）
- **测试账号**：18 个种子账号，五角色各 ≥3 且三态齐备（正常/首登待改密/停用）；本轮补齐供货商 600003（首登待改密）后已实跑校验（H2 执行 data-seed.sql 逐角色计数通过）
- **提交状态**：rpd 接入与账号补齐改动见最近提交（工作区干净）

## 下一步（按优先级）

1. 前端联调：以 `API.md` / OpenAPI 为契约，Web（Vue）与小程序并行对接；联调中发现契约问题走「变更记录 + 双方确认」
2. 试运行环境（moonzj.com HTTPS）部署：按 `docs/deployment.md` 执行，随后验收订阅消息 5 轮重发与 unauthorized 统计
3. 性能量化验收：MySQL 生产等价环境压测（万行导入 ≤5 分钟已本地达标 169s；列表接口 P95<500ms 待测）
4. 签字版样张替换：`src/main/resources/templates/secretary-signature.xlsx`（代码不改）
5. 订阅消息模板 id 申请后配置 `WX_SUBSCRIBE_TEMPLATE_ID`，重发链路即生效

## 阻塞项

- 订阅消息模板 id（张敬申请中）——阻塞项仅影响订阅消息通道，弹窗主触达不受影响
- 田老师签字版样张——阻塞模板版式最终确认
- 生产等价环境——阻塞 P95 与 5 轮重发的量化验收（本地不可验）

## 未落盘告警

- 无（rpd 接入 + 种子账号补齐改动均已落盘）

## 工具降级告警（R-05 / §5.3）

- **code-map 三件套不可用**：`code-map-generator.py` 当前仅支持 C/C++ 扩展名（`.c/.cpp/.h/...`），本项目为 Java，
  生成结果为 0 文件 0 符号（已删除空产物，避免误导红线机检）。
- 降级路径：新会话接手时**跳过 code-map 定位**，直接使用：① `.project-state.md` 功能进度清单
  ② `API.md` 端点地图 ③ `SPEC.md` §2 工程结构 ④ 全文检索（Grep/Glob）定位符号。
- 恢复条件：生成器支持 Java（或接入 knowledge-graph.json）后重跑 `code-map-generator.py`。

## 风险与注意

- MyBatis-Plus 版本：锁定 3.5.7（3.5.8+ 移除 `DataPermissionInterceptor`/`PaginationInnerInterceptor`，与 SPEC §5 架构不符）；升级前需评估迁移到新分页/数据权限 API
- 多实例部署前：窗口扫描/通知重发/导出清理三个 `@Scheduled` 需换分布式锁或 xxl-job（R3）
- 软删除为显式实现（查询带 `deleted=0`、删除写毫秒时间戳）：新增查询须遵守该约定，否则会读到已删数据
