# textbook-order-server · 教材征订系统服务端

> Spring Boot 3 单体服务：RBAC 五角色数据隔离、征订窗口引擎、学期数据双缓冲、教师两级审查、学生选购、异动审批、通知确认闭环、Excel 异步导入导出、供货商只读接口。
>
> 需求见 [PRD.md](PRD.md)，实现规格见 [SPEC.md](SPEC.md)，开发计划见 [03-后端开发计划与决策.md](03-后端开发计划与决策.md)，**前后端联调接口手册见 [API.md](API.md)**（95 端点/权限码/错误码/关键流程），本次 MVP 实现范围与裁剪点见 [docs/IMPLEMENTATION-MVP.md](docs/IMPLEMENTATION-MVP.md)，部署见 [docs/deployment.md](docs/deployment.md)。
>
> **进度状态（rpd）**：功能进度清单/决策/阻塞见 [.project-state.md](.project-state.md)；会话活跃上下文与下一步见 [.rpd/active-context.md](.rpd/active-context.md)。

## 技术栈（版本锁定 · SPEC §1）

Java 17 · Spring Boot 3.3.13 · MyBatis-Plus 3.5.7 · MySQL 8.0.36+ · EasyExcel 3.3.4 · jjwt 0.12.6 · springdoc-openapi 2.6.0 · weixin-java-miniapp 4.6.0 · Caffeine 3.1.8 · Maven 3.9（mvnw 已入库）

## 快速开始

```bash
# 1) 准备 MySQL 8，建库
mysql -uroot -p -e "CREATE DATABASE textbook_order DEFAULT CHARSET utf8mb4;"

# 2) 本地 profile 启动（自动执行 db/schema.sql + data-permission.sql + data-seed.sql）
export DB_URL='jdbc:mysql:<SECRET_824596b7>'
export DB_USERNAME=root DB_PASSWORD=root
export JWT_SECRET='请替换为至少32字节随机串（openssl rand -base64 48）'
export SPRING_PROFILES_ACTIVE=local      # 必填：无默认 profile，缺失即启动失败
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run

# 3) 打可执行 jar
./mvnw clean package -DskipTests
java -jar target/textbook-order-server.jar --spring.profiles.active=trial
```

其他 profile：`local`（自动建库，仅限全新空库）/ `trial`（试运行）/ `school`（移交学校）。
生产环境 DB 初始化由 DBA 手动执行 `src/main/resources/db/` 下的 `schema.sql` + `data-permission.sql`
（**不执行** `data-seed.sql`，它含已知口令的测试账号；部署手册交付物）。

### 启动自检（fail-fast）

服务在启动期断言必填配置，不满足即中止（详见 [docs/deployment.md §3.1](docs/deployment.md)）：
未指定 `SPRING_PROFILES_ACTIVE`、`JWT_SECRET` 缺失/过短/命中仓库内公开弱值、导出临时目录不可写
——三者任一不满足都直接启动失败，不会静默降级。

## 环境变量（SPEC §13，不硬编码）

| 变量 | 用途 |
|------|------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `JWT_SECRET` | access/refresh 签名密钥（≥32 字节随机） |
| `WX_MINIAPP_APPID` / `WX_MINIAPP_SECRET` | 小程序 code2session / 订阅消息 |
| `WX_SUBSCRIBE_TEMPLATE_ID` | 订阅消息模板 id（未配置时重发记 unauthorized，W5/R10） |
| `TEXTBOOK_EXPORT_TMP` | 导出/导入临时目录（生产请用绝对路径，默认 `./data/export`） |
| `TEXTBOOK_LOG_DIR` | 日志目录（默认 `./logs`） |
| `TEXTBOOK_CORS_ORIGINS` | 跨域来源白名单（逗号分隔完整 origin，默认空 = 不返回 CORS 头；严禁 `*`） |
| `TEXTBOOK_TRUSTED_PROXIES` | 可信反向代理 IP（默认空 = 不采信 `X-Forwarded-For`；同机 Nginx 填 `127.0.0.1,::1`） |
| `SPRINGDOC_ENABLED` | 是否开放 `/swagger-ui.html` 与 `/v3/api-docs`（默认 false，仅 local 默认开） |
| `TZ` | `Asia/Shanghai`（业务时间已统一走 `AppTime`，此项作第三方库兜底） |
| `SPRING_PROFILES_ACTIVE` | **必填**：local / trial / school |

## 测试账号（种子数据 · 初始密码 = 学号/工号后 6 位）

**每个角色均含「正常 / 首登待改密 / 停用」三种状态**（M1 验收口径），共 18 个账号：

| 角色 | 账号 | 姓名 | 口令 | 状态 |
|------|------|------|------|------|
| ADMIN（教材室） | 900001 | 张管理 | `Admin@123` | 正常 |
| ADMIN | 900002 | 李管理 | `900002` | 首登待改密 |
| ADMIN | 900003 | 王管理 | — | 停用 |
| SECRETARY（学院秘书） | 800101 | 秘书甲 | `Sec@12345` | 正常（计算机学院） |
| SECRETARY | 800102 | 秘书乙 | `800102` | 首登待改密 |
| SECRETARY | 800103 | 秘书丙 | — | 停用 |
| TEACHER（任课教师） | 700101 | 教师甲 | `Tea@12345` | 正常（计算机学院，任课 数据结构/软工2023-1） |
| TEACHER | 700103 | 教师戊 | `Tea@12345` | 正常（**教师+秘书双角色**，演示切换身份与多角色并集） |
| TEACHER | 700201 | 教师丙 | `Tea@12345` | 正常（外国语学院，任课 大学英语/英语2023-1） |
| TEACHER | 700102 | 教师乙 | `700102` | 首登待改密 |
| TEACHER | 700202 | 教师丁 | — | 停用 |
| STUDENT（学生） | 20230101 | 学生甲 | `Stu@12345` | 正常（软工2023-1） |
| STUDENT | 20230201 | 学生丁 | `Stu@12345` | 正常（英语2023-1） |
| STUDENT | 20230102 | 学生乙 | `20230102` | 首登待改密 |
| STUDENT | 20230103 | 学生丙 | — | 停用 |
| SUPPLIER（供货商） | 600001 | 供货商甲 | `Sup@12345` | 正常 |
| SUPPLIER | 600003 | 供货商丙 | `600003` | 首登待改密 |
| SUPPLIER | 600002 | 供货商乙 | — | 停用 |

- 首登校验 = 手机号后 4 位（如学生甲 `13700001001` → 后 4 位 `0001`）或小程序 openid 绑定
- 种子数据来源：`db/data-seed.sql`（头部注释含完整账号清单）；`local` profile 首次启动自动执行，试运行/移交环境由 DBA 手动执行（见 [docs/deployment.md](docs/deployment.md)）
- 停用账号用于验证「停用即时踢下线」「登录拒绝」；首登待改密账号用于验证「改密前业务接口一律 403」

## 测试

```bash
./mvnw test                # 单元 + 切片（越权矩阵/401语义/首登拦截）+ H2 集成 + ArchUnit 机检（187 用例）
./mvnw test -Drun.mysql.tests=true   # Docker 可用时追加 Testcontainers(MySQL) 集成用例
```

- 单元（65 例）：字段审查 6 规则 × 边界（含数量上限回退、ISBN 校验位）、导出阈值、配置白名单、窗口状态机、数据隔离条件构建（多角色 OR 并集 + fail-closed 默认拒绝）、JWT 密钥强度自检、客户端 IP 可信代理解析、LIKE 通配符转义
- 切片（30 例）：5 角色 × 资源 × 操作越权矩阵（100% 拒绝 + 审计）、401 三类语义、must_change_password 拦截、多角色并集、联调新增端点权限
- 集成（92 例，H2 MySQL 模式）：双缓冲原子切换（含 version 冲突回滚）、窗口自动开关幂等、导入批次与停用比对、**真实 HTTP multipart 上传**（相对 tmp-dir 落盘回归防护）、重提覆盖、confirm 幂等、一次性 token 410、JSON 列读回（submit_snapshot / detail_json）、**越权修复回归**（供货商导出 IDOR / 秘书跨院导出 / 异动跨学期审批 / 通知 target_roles 定向 / 异动目标范围）
- 机检（7 例）：ArchUnit + 数据隔离护栏 —— supplier 包禁 import 学生/教师 Mapper、common 包禁 import 业务 Mapper、Controller 禁直连 Mapper、按用户维度的 Mapper 方法必须声明隔离口径
- 性能（默认禁用）：1 万行导入样本实测 169s（≤5 分钟，W22）

## 工程结构（SPEC §2）

```
src/main/java/com/tian/textbook/
├─ auth/          登录、JWT(access+refresh)、首登校验、密码策略、强制改密拦截
├─ system/        RBAC 五表、账号、组织三表、审计切面与查询、系统配置
├─ semester/      学期、窗口引擎、双缓冲切换、按学期归属（user_semester_profile）
├─ textbook/      教材库、课程、任课关系
├─ order/         教师征订单（两级审查）、学生选购
├─ approval/      异动两级审批（逐条 + 批量）
├─ importexport/  Excel 导入中心（异步批次）、导出中心（同步/异步 + 一次性 token）
├─ notify/        通知任务、订阅消息适配、确认追踪、重发调度
├─ supplier/      供货商只读接口（物理隔离，ArchUnit 机检）
├─ stats/         数据看板
└─ common/        统一响应、异常、审计注解、@WithinWindow、@CollegeScope、学期上下文
```

接口契约基线见 SPEC §11，**联调速查手册见 [API.md](API.md)**；OpenAPI 3 文档（M1 冻结的唯一契约源）：`/v3/api-docs`、`/swagger-ui.html`。

## 部署

Nginx 反代 + systemd 守护 + MySQL 每日备份的配置模板、环境变量清单（含必填项与启动自检）与移交检查单见 [docs/deployment.md](docs/deployment.md)。

可观测性：`GET /actuator/health`（含 db 与磁盘空间）；每个请求生成/透传 `X-Request-Id` 并写入日志 MDC，前端报错可带上该 id 以便串联排查。
