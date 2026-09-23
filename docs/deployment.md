# 教材征订系统 · 部署手册（M5 交付物）

## 1. 环境要求

- JDK 17+、MySQL 8.0.36+（utf8mb4）、反向代理（HTTPS；模板给 Nginx，生产实际用 Caddy，见 §5.3）、2C4G 起
- 域名已备案（moonzj.com）。**模板拓扑**：系统挂在子路径 `/textbook/`（前端）+ `/api/`（后端）；
  **生产实际拓扑**：子域名 `textbooksorder.moonzj.com` 根路径（前端 `root * /var/www/textbook-order`）+ `/api/*` 反代 `127.0.0.1:8081`。偏差记录见 §5.3

## 2. 数据库初始化（DBA 执行，按顺序）

```bash
# ① 建库 + 建应用账号（应用用这个账号连库，不是 root；口令与 /opt/textbook-order/textbook.conf 的 DB_PASSWORD 一致）
mysql -uroot -p <<'SQL'
CREATE DATABASE IF NOT EXISTS textbook_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'textbook'@'localhost' IDENTIFIED BY '<强口令>';
-- ALTER/INDEX 供存量库迁移脚本使用（§2.1）；不需要 DROP/GRANT 权限
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON textbook_order.* TO 'textbook'@'localhost';
FLUSH PRIVILEGES;
SQL

# ② 建表 + 权限码（用应用账号执行即可）
mysql -utextbook -p textbook_order < src/main/resources/db/schema.sql          # 全量 DDL（IF NOT EXISTS，可重复执行）
mysql -utextbook -p textbook_order < src/main/resources/db/data-permission.sql # 39 条权限码 + 五角色映射
# data-seed.sql 仅用于本地/演示环境（内含已知口令的测试账号），严禁在生产执行
```

> **漏了 ① 的后果**：`DB_USERNAME=textbook` 但库中无该账号 → Hikari 启动即 `Access denied`，
> systemd 每 5s 重启一次、永远起不来（`Restart=always`）。
> 初始化完成后用 `SELECT user_no FROM sys_user WHERE user_no IN ('900001','800101','700101','20230101','600001');`
> 确认**没有**演示账号（若曾用 local profile 误连生产库，必须立即改口令或删号）。
>
> **已灌入过演示/联调账号的库（含生产试运行环境）**：执行一次性清理脚本（幂等，可重复跑）——
>
> ```bash
> mysql -uroot -p textbook_order < src/main/resources/db/cleanup-seed-accounts.sql
> # 校验（应为 0）：SELECT COUNT(*) FROM sys_user WHERE deleted = 0 AND status = 1
> #   AND user_no IN ('900001','900002','900003','800101','800102','800103','700101','700102',
> #                   '700103','700201','700202','20230101','20230102','20230103','20230201',
> #                   '600001','600002','600003');
> ```
>
> 脚本把这 18 个公开口令账号 `status=0` + `role_version+1` + 撤销全部 refresh（已签发的会话立即失效），
> 并把其学期归属置为不在册；真实账号（导入生成的学号/工号）不受影响。**这是后端测试报告 P1 风险项**：
> 这些账号的口令（`Admin@123`/`Sec@12345`/`Tea@12345`/`Stu@12345`/`Sup@12345`）是公开文档内容，
> 公网可访问的环境里保留一个 `status=1` 的超管账号等于把系统交出去。脚本末尾另附「彻底删除」段
> （默认注释，仅在无业务数据的演示库使用——删除会留下悬空的 `created_by`/`review_by` 引用）。

> 本地开发：`SPRING_PROFILES_ACTIVE=local` 启动会自动按上述顺序初始化（仅限全新空库）。
> 生产/试运行不要执行 `data-seed.sql`。
>
> 注意：`schema.sql` 只负责建表（已幂等），**对已存在的表是空操作**，不会补新增列/索引。
> 已上线库的结构变更走下面的迁移脚本；建议后续引入 Flyway/Liquibase 做版本化迁移。

### 2.1 存量库升级（**部署新版本前必做**）

```bash
mysqldump --single-transaction -uroot textbook_order | gzip > 升级前备份.sql.gz   # 先备份（§6）
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-21.sql
# 2026-09-23 即时决策（BE-1~BE-8）：四个脚本，可重复执行，建议按顺序
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-23-role-permission.sql
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-23-order-withdraw.sql
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-23-notify.sql
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-23-reserve.sql
```

| 2026-09-23 脚本 | 内容 | 不做会怎样 |
|------|------|-----------|
| `migration-2026-09-23-role-permission.sql` | 新增 `role:manage` / `role:permission:assign` 两条权限码（37 → 39） | 角色管理接口 403（权限码不存在），权限目录少 2 条 |
| `migration-2026-09-23-order-withdraw.sql` | `order_form.withdrawn_at` | 教师「撤回修改」500（Unknown column） |
| `migration-2026-09-23-notify.sql` | `notice_record.semester_id`（含回填）+ `idx_record_semester` + `notice_record_history` 表 + `send_status` 加宽到 24 + `change_request.change_type` | 学期归档迁移无判定列；入口确认 500（`confirmed_by_entry` 18 字符放不进 VARCHAR(16)）；异动类型导入/筛选 500 |
| `migration-2026-09-23-reserve.sql` | 22 张表补 `reserve1~6` | 结构漂移（新装库有、存量库无），后续按预留列做的扩展在旧库失败 |

> 幂等：四个脚本均先查 `information_schema` 再 ALTER（`role-permission` 用 `ON DUPLICATE KEY UPDATE`），可重复执行；
> 真库验证见 `LocalMySqlIntegrationTest#migrationScripts20260923_areIdempotentAndComplete`（连跑两轮 + 对象齐备断言）。

脚本可重复执行（内部按 `information_schema` 判断对象是否已存在），内容与影响：

| 变更 | 不做会怎样 |
|------|-----------|
| `order_form.content_version` | **教师提交与管理员审核全部 500**（Unknown column），功能完全不可用 |
| `notice_task.uk_task_active` | 并发下可建出 2 个 active 任务（S6 保护静默失效） |
| `notice_record.uk_notice_confirm` | 并发重复确认可插多行（S25 保护静默失效） |
| 4 个新索引（order_form/student_order/change_request/audit_log） | 相关查询退化为全表扫描 |
| `sys_user_token.uk_token_hash` 加宽含 `deleted` | 与该文件「唯一键一律含 deleted」的规则不符 |
| 数据回填：`rejected_auto` 行的 `correct_deadline` | 升级前已存在的 `rejected_auto` 在新判定下永久无法补正（教师只能请管理员驳回） |

脚本自带存量重复数据清理（重复 active 任务置 closed、重复确认记录逻辑删除、
重复 token hash 置 revoked），因此加唯一键不会因既有数据失败。

> `import_batch.created_by` 列**本就存在**，无需 ALTER；但历史行的该列为 NULL，
> 而新增的批次归属校验对非 ADMIN 要求 `created_by = 本人`，故**历史批次对秘书不可见（404）**。
> 这是有意的 fail-closed 选择（无法推断历史批次的上传者，放行等于重开枚举漏洞）。

## 3. 环境变量（/opt/textbook-order/textbook.conf，systemd EnvironmentFile）

```ini
DB_URL=jdbc:mysql://127.0.0.1:3306/textbook_order?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=textbook
DB_PASSWORD=<强密码>
JWT_SECRET=<openssl rand -base64 48 生成，≥32字节>
WX_MINIAPP_APPID=<小程序 AppID>
WX_MINIAPP_SECRET=<小程序 Secret>
WX_SUBSCRIBE_TEMPLATE_ID=<订阅消息模板 id（未申请前可留空，重发记 unauthorized）>
TEXTBOOK_EXPORT_TMP=/opt/textbook-order/data/export
TEXTBOOK_LOG_DIR=/var/log/textbook
TEXTBOOK_CORS_ORIGINS=https://moonzj.com
TEXTBOOK_TRUSTED_PROXIES=127.0.0.1,::1
TZ=Asia/Shanghai
SPRING_PROFILES_ACTIVE=trial
```

### 3.1 必填项与启动自检（fail-fast）

服务在启动期做以下断言，不满足即中止启动（不会带病运行）：

| 项 | 缺失/不合规时的行为 | 说明 |
|----|--------------------|------|
| `SPRING_PROFILES_ACTIVE` | 启动失败 | 不再默认 `local`。默认 `local` 会在生产漏设时执行 `data-seed.sql`，向生产库写入 18 个已知口令的测试账号（含超管） |
| `JWT_SECRET` | 启动失败 | 无默认值；长度 < 32 字节、或命中仓库内公开的历史默认值/示例值（如 `textbook-dev-only-insecure-secret-key-32b`）一律拒绝。用公开字符串签发令牌等于任何人可离线伪造超管 token |
| `textbook.export.tmp-dir` 可写 | 启动失败 | 默认 `./data/export` 是相对路径，systemd 未设 `WorkingDirectory` 时会落到 `/` 不可写。生产请显式设为绝对路径 |
| 微信 appid/secret/模板 id | 仅告警 | 缺失时订阅消息通道整体降级为 `unauthorized`（弹窗通道不受影响） |

其余安全相关默认值：

- `TEXTBOOK_CORS_ORIGINS` 为空 = 不返回任何 CORS 响应头（Web 走 Nginx 同域反代，小程序端不受 CORS 约束）。**严禁配置为 `*`**。
- `TEXTBOOK_TRUSTED_PROXIES` 为空 = 完全不采信 `X-Forwarded-For`，客户端 IP 取 `remoteAddr`。同机 Nginx 部署填 `127.0.0.1,::1`；否则登录限频与审计 IP 可被伪造，且可用 5 次失败锁死任意账号（含超管）。
- `SPRINGDOC_ENABLED` 默认 `false`（仅 `local` profile 默认开启）。
- `TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE` 默认 `10`（次/分钟，按 IP+账号）。**联调/压测环境必须调大**（如 `200`）：多角色反复走查会在几分钟内撞 429 `RATE_LIMITED`。
- `TEXTBOOK_SECURITY_LOGIN_MAX_FAIL` 默认 `5`：连续失败即锁定 15 分钟，且**失败计数落库、重启不清**；压测负例（错误口令）时一并调大。
- `/actuator/health` 已开放用于探活；其余 actuator 端点未暴露。

## 4. systemd 守护（/etc/systemd/system/textbook-order-server.service）

先建目录与产物（**漏了这步启动自检会中止**：导出目录不可写）：

```bash
# 目录 + 属主（服务以 textbook 运行）
sudo mkdir -p /opt/textbook-order/data/export /opt/textbook-order/backup /var/log/textbook
sudo chown -R textbook:textbook /opt/textbook /var/log/textbook

# 构建并放置可执行 jar（在开发机或服务器上，仓库根目录执行）
./mvnw clean package -DskipTests
sudo cp target/textbook-order-server.jar /opt/textbook-order/app.jar
sudo chown textbook:textbook /opt/textbook-order/app.jar
```

```ini
[Unit]
Description=textbook-order-server
After=network.target mysql.service

[Service]
Type=simple
User=textbook
EnvironmentFile=/opt/textbook-order/textbook.conf
# WorkingDirectory 必填：textbook.export.tmp-dir / TEXTBOOK_LOG_DIR 的相对路径以它为基准，
# 缺失时会落到 / 导致不可写（启动自检会因此中止）
WorkingDirectory=/opt/textbook
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -Duser.timezone=Asia/Shanghai \
  -jar /opt/textbook-order/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}
Restart=always
RestartSec=5
# 优雅停机：先停收新请求，等待在途请求与异步导入/导出收尾（最长 30s）
KillSignal=SIGTERM
TimeoutStopSec=45
# 日志只走 logback（/var/log/textbook/textbook-order-server.log，按天 + 50MB 轮转、保留 30 天）。
# 不要在这里再 append 一份：那会让每行日志写两遍，且 systemd 的 append 不做轮转，
# 最终把磁盘写满 → health 因 diskspace DOWN、导入导出全部失败。

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now textbook-order-server
```

## 5. Nginx（双 location · 模板）

```nginx
server {
  listen 443 ssl;
  server_name moonzj.com;
  # 证书（certbot 或云厂商托管，续期：certbot renew && nginx -s reload）
  ssl_certificate     /etc/letsencrypt/live/moonzj.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/moonzj.com/privkey.pem;

  client_max_body_size 12m;   # 上传 ≤10MB + 包络余量

  location /textbook/ {
    root /var/www;
    try_files $uri $uri/ /textbook/index.html;
  }

  location /api/ {
    proxy_pass http://127.0.0.1:8080/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

## 5.1 本地开发环境（Windows · 免安装 MySQL，无需管理员）

本仓库在 Windows 上准备了一套**免安装**的本地 MySQL，用于真实库联调与迁移脚本验证
（H2 全绿不等于生产可用）：

| 项 | 位置 |
|----|------|
| MySQL 8.0.29 便携版 | `E:	ools\mysql\mysql-8.0.29-winx64` |
| 数据目录 / 配置 | `E:	ools\mysql\data`、`E:	ools\mysql\my.ini` |
| 控制脚本 | `E:	ools\mysql-local.bat`（start / stop / status / client / logs） |
| 连接 | `127.0.0.1:3306`，`root` / `root`，字符集 utf8mb4，时区 +08:00 |

```bat
E:	ools\mysql-local.bat start     :: 启动（用户进程，非 Windows 服务）
E:	ools\mysql-local.bat status
E:	ools\mysql-local.bat client    :: 进入 mysql 命令行
E:	ools\mysql-local.bat stop
```

启动应用（`local` profile 会自动建库 + 灌权限种子 + 演示账号）：

```bash
set -a; . ./.env.local; set +a        # 仓库根目录的本地环境变量（.gitignore 已忽略 .env.*）
./mvnw spring-boot:run
# 或 java -jar target/textbook-order-server.jar
```

> `.env.local` 已生成，含 `DB_URL` / `DB_PASSWORD=root` / `JWT_SECRET` / 导出与日志目录
> （均指向 E: 盘，因为本机 C: 盘空间紧张）。
>
> 注意：`local` profile 的 `sql.init` 为 `always`，会重复执行 `schema.sql` 与两个种子脚本；
> 三者均已幂等（`CREATE TABLE IF NOT EXISTS` + `ON DUPLICATE KEY UPDATE`），可反复重启。
> 生产/试运行**不要**用 `local` profile（见 §3.1）。

### 5.2 本地全量验证（无需 Docker）

本机无 Docker 时，用本地 MySQL 完成「全量后端验证」的两条命令：

```bash
# 1) 全量自动化测试（含真实 MySQL 原生 DDL / 生成列唯一约束 / 迁移脚本幂等）
./mvnw test -Dmysql.local.enabled=true
#    不加 -Dmysql.local.enabled=true 时，MySQL 用例自动跳过（CI 无 MySQL 也能跑）

# 2) 端到端冒烟（推荐：一次性库 + 独立端口，可重复执行、不碰演示库）
bash scripts/smoke-isolated.sh

# 3) 若应用已在 8080 运行，也可直接对着它跑（必须声明目标库）
SMOKE_DB=textbook_smoke bash scripts/e2e-smoke.sh          # 一次性库
SMOKE_DB=textbook_order ALLOW_SHARED_DB=1 bash scripts/e2e-smoke.sh   # 共享库（见下）
```

- `LocalMySqlIntegrationTest`（9 例）替代 Testcontainers 的验证职责：原生 DDL 建表、
  JSON 列与 DATETIME(3) 精度、**三个生成列唯一约束**（`uk_semester_active` /
  `uk_task_active` / `uk_notice_confirm`）、种子脚本幂等、迁移脚本幂等与「补齐被删对象」、
  方言敏感 SQL 直跑。使用独立库 `textbook_verify`，不触碰开发库 `textbook_order`。
- `scripts/e2e-smoke.sh`（45 项断言）覆盖：ADMIN/TEACHER/STUDENT/SECRETARY/SUPPLIER
  五角色主流程、S3 审核版本号 CAS（过期版本必须 409）、F3 供货商越权负例、
  S23 通知定向、S18 协议边界（401/405/415/400）、R5 分页上限、S24 CORS、R1 失败锁定。
- **为什么冒烟要对着一次性库跑**（`scripts/smoke-isolated.sh`）：脚本会真实提交并
  **审核通过**一张征订单（`reviewed` 是终态、系统不提供撤销审核），还会改动账号锁定状态与
  班级人数；而组织/账号等表是严格模式、**没有删除接口**。对着演示库跑，第二轮就必然失败，
  并留下 `[IT]` 夹具（只能 DBA 按前缀清理）。一次性库把「清理」变成「重建」，从根上消除该问题。
- 磁盘：测试 JVM 的 `java.io.tmpdir` 已在 pom 中指向 `target/tmp`，
  不再写入系统盘用户 Temp 目录（单轮约 13~26MB）。

#### 5.2.1 一次性测试环境的几种业界做法（选型参考）

调研结论（官方文档与维护者讨论为主）：一次性环境是共识，差异只在「用容器还是用库」。
本项目因**开发机无 Docker** 采用「一次性 schema」路线；有 Docker 时应升级到 Testcontainers。

| 做法 | 说明 | 适配情况 |
|------|------|----------|
| Testcontainers `MySQLContainer` + Spring Boot `@ServiceConnection` | 每次测试起一个真实 MySQL 容器，Spring Boot 3.1+ 免去 `@DynamicPropertySource` 样板 | **需要 Docker**（本机不可用；`MySqlContainerIntegrationTest` 已按 `-Drun.mysql.tests=true` 门控保留，换机即启用） |
| 一次性 schema / database（本项目采用） | 运行前 `DROP/CREATE DATABASE` + 指向它的 `DB_URL`，跑完即弃；与 Testcontainers 思路一致但不需要容器 | ✅ 已落地为 `scripts/smoke-isolated.sh`；`LocalMySqlIntegrationTest` 早先已用 `textbook_verify` 库验证同一机制 |
| 测试事务回滚（`@Transactional` + 默认 rollback） | 同线程、同事务管理器时最省事 | ⚠️ 只适合同线程服务层测试；异步导入/导出、`REQUIRES_NEW`（登录失败计数/审计）**不会被回滚**，本仓库的 H2 套件继续用「逐用例清表」更稳 |
| 声明式夹具生命周期（`@Sql` 的 `BEFORE/AFTER_TEST_METHOD`、Database Rider `@DataSet(cleanBefore/cleanAfter)`） | 把「建夹具/清夹具」写进注解，`skipCleaningFor` 可保护共享表 | ⚠️ 官方文档未明确保证测试失败时 `AFTER_TEST_METHOD` 仍执行，清理不应作为唯一保障 |
| 前缀标记 + 定期清理（`[IT]%` + SQL/事件调度） | 数据必须留在共享库时的兜底 | ⚠️ 无官方模式；且本仓库严格模式禁删除接口，仅适合 DBA 手工 SQL |
| 每次运行新起整套栈（docker compose / CI service containers / Spring Boot 官方 smoke-test 模块） | 最彻底，CI 里最常用 | 需要 Docker/CI；作为后续 CI 化目标 |

参考链接：Testcontainers MySQL 模块 <https://java.testcontainers.org/modules/databases/mysql/> ·
Spring Boot Testcontainers 支持 <https://docs.spring.io/spring-boot/3.3/reference/testing/testcontainers.html> ·
Spring 并行测试与共享服务的官方告诫 <https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/parallel-test-execution.html> ·
`@Sql` 执行时机 <https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/executing-sql.html> ·
Database Rider 数据集清理 <https://database-rider.github.io/database-rider/latest/documentation.html> ·
GitHub Actions service containers <https://docs.github.com/en/actions/using-containerized-services/about-service-containers> ·
Spring Boot 官方 `smoke-test/` 模块 <https://github.com/spring-projects/spring-boot/tree/main/smoke-test>。

> 试运行库（真实数据）与开发机演示库**永远不要**跑冒烟脚本：脚本会改账号锁定与班级人数。
> 需要验证试运行环境时，用 `scripts/smoke-isolated.sh` 起一次性库，或只跑只读断言。

## 5.3 生产实际拓扑与本文模板的偏差（2026-09-23 核实）

以生产服务器实际配置与 `docs/上线联调验证报告-20260923.md` §3.6 为准：

| 项 | 本文模板 | 生产实际 |
|----|----------|----------|
| 反向代理 | Nginx | **Caddy**（`/etc/caddy/Caddyfile`，`caddy validate` 通过；仓库模板用了 `request_body` 指令，官方标注需 ≥ 2.7，生产 2.6.2 实测可用） |
| 前端路径 | `/var/www` + `location /textbook/`（子路径） | `/var/www/textbook-order`（**子域名根路径**，与 `VITE_BASE=/` 构建产物一致） |
| 后端端口 | `127.0.0.1:8080` | `127.0.0.1:8081` |
| 应用目录 / 配置 | `/opt/textbook-order/` + `/opt/textbook-order/textbook.conf` | 一致（systemd `EnvironmentFile=/opt/textbook-order/textbook.conf`，`root:textbook 640`） |
| 运行账号 | `textbook` | 一致（专用系统用户，nologin） |
| 安全响应头 | 模板未含 | 生产 Caddyfile 已配 `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`/`-Server`；**CSP、Permissions-Policy、HSTS、Cache-Control 尚未配**（见联调报告 §3） |

> 迁移到生产前，请以服务器实际 Caddyfile 为准逐项核对本模板；前端仓库的 `docs/DEPLOYMENT.md` §2.4 为 Caddy 版模板。

## 6. 备份（每日 02:00 全量，保留 14 天，W22）

先建凭据文件（cron 没有 TTY，`mysqldump` 不能靠交互输口令；**照抄 `-uroot` 不带口令的脚本会每天生成一个空备份**）：

```bash
sudo install -m 600 -o root -g root /dev/null /etc/textbook/.my.cnf
sudo tee /etc/textbook/.my.cnf >/dev/null <<'EOF'
[client]
user=textbook
password=<DB_PASSWORD>
host=127.0.0.1
EOF
```

`/opt/textbook-order/backup.sh`：

```bash
#!/bin/bash
set -euo pipefail
DIR=/opt/textbook-order/backup
KEEP_DAYS=14
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$DIR"
FILE="$DIR/textbook_order-$STAMP.sql.gz"

mysqldump --defaults-extra-file=/etc/textbook/.my.cnf --single-transaction --quick --routines \
  --default-character-set=utf8mb4 textbook_order | gzip > "$FILE"

# 空/过小的备份视为失败（gzip 会为 0 字节输入生成合法但不可恢复的 .gz，必须显式拦截）
SIZE=$(stat -c %s "$FILE")
if [ "$SIZE" -lt 10240 ]; then
  echo "备份失败：$FILE 仅 $SIZE 字节" >&2
  rm -f "$FILE"
  exit 1
fi
find "$DIR" -name 'textbook_order-*.sql.gz' -mtime +$KEEP_DAYS -delete
echo "备份完成：$FILE ($SIZE 字节)"
```

```bash
sudo chmod +x /opt/textbook-order/backup.sh
crontab -l 2>/dev/null | { cat; echo "0 2 * * * /opt/textbook-order/backup.sh >> /var/log/textbook/backup.log 2>&1"; } | crontab -
```

**恢复**（先停服务，避免恢复过程中表被 DROP 引发 500）：

```bash
sudo systemctl stop textbook-order-server
gunzip -c /opt/textbook-order/backup/textbook_order-YYYYMMDD-HHMMSS.sql.gz \
  | mysql --defaults-extra-file=/etc/textbook/.my.cnf textbook_order
sudo systemctl start textbook-order-server
curl -s localhost:8080/actuator/health   # 期望 {"status":"UP"}
```

> **上线前必须演练一次**：恢复到临时库（`CREATE DATABASE textbook_restore; ... textbook_restore`）并核对
> `sys_user` 账号数、`sys_role_permission` 权限码数、`semester` 的 active 学期、`system_config` 8 键。
> 未演练过的备份等于没有备份（空 .gz 只有恢复时才会暴露）。

## 7. 移交检查单（SPEC §15）

- [ ] 环境变量清单交接（§3）
- [ ] 数据库与应用账号已创建并授权（§2 ①；`DB_USERNAME` 对应的账号存在且有 ALTER/INDEX 权限）
- [ ] 目录与产物就位（§4：`/opt/textbook{,/data/export,/backup}`、`/var/log/textbook`、`app.jar`）
- [ ] 启动日志确认三行自检通过（profile / 导出目录 / **可信反向代理已生效**）；
      `TEXTBOOK_TRUSTED_PROXIES` 配好后必须看到「可信反向代理已生效: …」，否则审计 IP 恒为 127.0.0.1
- [ ] `SPRING_PROFILES_ACTIVE` 为 `trial` 或 `school`（**不是** `local`；local 连非本机库会被启动自检拒绝）
- [ ] 备份已跑通并**演练过一次恢复**（§6；空 .gz 会在恢复时才暴露）
- [ ] `schema.sql` + `data-permission.sql` 初始化（§2；**不执行** `data-seed.sql`）
- [ ] 存量库已执行 `migration-2026-09-21.sql` 并跑完脚本末尾的验证 SQL（§2.1）
- [ ] `TEXTBOOK_ASYNC_RECOVER` 与实例数一致（单实例默认 true；多实例须置 false）
- [ ] Nginx 双 location（`/textbook/` + `/api/`）生效（§5）
- [ ] `TEXTBOOK_TRUSTED_PROXIES` 与实际反代拓扑一致（同机 Nginx = `127.0.0.1,::1`）
- [ ] `TEXTBOOK_CORS_ORIGINS` 为显式域名（非 `*`，非空）
- [ ] systemd 守护（含 `WorkingDirectory`）+ 备份 crontab 生效（§4/§6）
- [ ] 管理员账号交接与首登流程演练（生产账号由教材室建号，初始口令 = 工号后 6 位）
- [ ] `GET /actuator/health` 探活可用；`/v3/api-docs` 保持关闭（`SPRINGDOC_ENABLED` 未设）
- [ ] 小程序 baseUrl 与微信后台合法域名更新清单（前端主责，后端提供接口域名确认）
- [ ] 订阅消息模板 id 申请进度（W5/R10：未申请前重发记 unauthorized，弹窗为主触达）

## 8. 运维要点

- 时区：业务时间统一走 `AppTime.now()`（固定 Asia/Shanghai），不再依赖 `-Duser.timezone`；
  仍建议保留该参数作为第三方库的兜底
- 日志：应用按天轮转保留 30 天（logback），不含密码/token；每行带 `[traceId]`，
  与响应头 `X-Request-Id` 对应，可据此串联前端报错与服务端日志
- 健康检查：`GET /actuator/health`（含 db 与磁盘空间），供 systemd 或外部探活调用
- 清理：导出文件 24 小时后自动删除并置 `expired`；导入临时文件解析后即删；
  临时目录下超过保留期的孤儿文件每小时清理一次
- 重启补偿：启动时把上次进程遗留的 `running/queued` 导入批次与导出任务置为 `failed`
  （提示「服务重启导致中断，请重新发起」），前端不会一直轮询到永远 running 的任务
- 归档：学期归档 = 置 `active_status=archived` **并同时关闭窗口**（`channel_open=0`
  + `window_status=closed`），数据只读保留、可查可导（D2-A）
- **归档二次门禁（2026-09-23 生产事故修复）**：`POST /api/admin/semester/{id}/archive` 必须带
  `version`（乐观锁），窗口进行中（`window_status=open` 或 `channel_open=1`）还必须带
  `confirmWindowOpen=true`，否则 409。**归档会立刻停掉全站征订业务**（没有 active 学期后，
  学生选购/教师填报/导出统一报「当前没有激活学期」），因此不要对真实学期做探针式调用。
  误归档的救援路径：`POST /api/admin/semester/{id}/unarchive`（body `{version, confirm:true}`，
  仅当当前没有 active 学期时可用；恢复后窗口仍为 closed，需手动重新开启）。
  运维兜底（仅在无接口可用时）：`UPDATE semester SET active_status='active' WHERE id=<id>;`
- **超管全权限（BE-1）**：`ADMIN` 在鉴权层短路持有全部 39 条权限，`sys_role_permission` 的 28 行只服务前端菜单过滤。
  改角色/权限后 `AuthUserService#evictAllPermissions()` 会失效 5 分钟缓存（接口内部已调用），无需重启
- **角色管理（BE-2）**：内置角色（ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER）不可删除、编码不可改；ADMIN 权限集不可改；
  删除仍有账号绑定的角色返回 409；**账号角色变更会强制该账号重新登录**（`role_version+1` + 撤销 refresh）
- **通知归档（BE-5d）**：学期归档时把该学期 `notice_record` 分批（5000/批）迁入 `notice_record_history`，独立事务、失败仅告警
  （可重跑，幂等靠 `uk_history_record`）；进度/失败名单/导出对两表 UNION 查询，历史任务仍可查可导。
  排查时注意：历史学期的记录不在 `notice_record` 而在 `notice_record_history`
- **通知节奏（BE-5c）**：调度每小时第 5 分钟扫描 + `notice.interval_hours` 判定；**新任务立即发首轮**。
  窗口关闭（提前截止/自动截止）会自动关闭该学期 active 任务，之后不再重发
- **教师撤回（BE-4）**：`pending_review` 可在窗口内撤回为 `draft`；撤回后管理员审核会被 409 拦住（审批结论不会被静默撤销）
- **局部名单门禁（2026-09-23）**：学生名单导入会把班级人数重算为文件内该班去重人数（教师填报上限），
  下调比例 > 20% 且 ≥ 5 人时导入返回 409 并要求 `confirmClassSizeShrink=true`；阈值可用
  `TEXTBOOK_IMPORT_CLASS_SIZE_SHRINK_CONFIRM_PCT` / `..._MIN_DROP` 调整（默认 20 / 5），
  设为极大值即关闭门禁（不推荐：局部名单会静默压小上限，线上实测 50 → 2 卡死教师填报）
- 备份恢复演练：建议每学期至少一次（`gunzip -c ... | mysql`），恢复后校验
  `semester` 的 active 学期与 `system_config` 一致
- **账号被锁定（401 `ACCOUNT_LOCKED`）**：连续 5 次错误口令锁定 15 分钟，且**计数落库、重启不清**。
  锁定只影响该账号（可用其它超管账号登录）。解锁：
  `UPDATE sys_user SET fail_count = 0, lock_until = NULL WHERE user_no = '<工号>';`
  若工号可被外人猜到（学号/工号是公开信息），建议把 `TEXTBOOK_SECURITY_LOGIN_MAX_FAIL` 调大，
  或由教材室先确认无人恶意尝试再解锁。
- **停机预算**：`spring.lifecycle.timeout-per-shutdown-phase=30s` < systemd `TimeoutStopSec=45s` <
  异步池 `awaitTerminationSeconds=60`。万行导入实测约 3 分钟，**停机时正在跑的导入会被中断**
  （启动补偿会把它置 `failed`；已提交的分批数据保留，重新导入是幂等 upsert，可收敛）。
  发版请避开导入/导出高峰，或先确认没有 `running` 批次。
- **登录限频**：默认 10 次/分钟（按 IP+账号），试运行前按预期并发调大
  （`TEXTBOOK_SECURITY_LOGIN_RATE_PER_MINUTE`），否则集中首登期会大面积 429。
- 多实例部署前：定时任务（窗口扫描/通知重发/导出清理）、登录限频与角色缓存
  （Caffeine 进程内）、`SEMESTER_LOCK`（JVM 级锁）都需换分布式锁/外置缓存或 xxl-job（R3）；
  **并须把 `TEXTBOOK_ASYNC_RECOVER=false`**——启动补偿按「`updated_at` 早于本进程启动时刻」
  回收遗留任务，多实例滚动发布时应由运维人工收敛（彻底方案见待确认清单）

## 9. 已知架构边界（登记项 · 本轮不改造）

> 来源：`docs/13-后端文档对齐-goal-prompt-20260923.md` §2（B-G4/B-G5）。逐项写明「现状 + 触发条件 + 改造代价」，供 DBA/运维决策；**当前版本按现状交付，未做改造**。

### 9.1 限单实例部署（B-G4）【重要】

**当前架构限单实例运行，横向扩容前必须改造**——以下机制全部是**进程内实现**，多实例会静默失效：

| 机制 | 实现 | 多实例后果 |
|------|------|-----------|
| 登录限频 | Caffeine 进程内计数 | 限频被放大 N 倍，等于没有限频 |
| 角色/权限缓存 | Caffeine 5 分钟（`AuthUserService`） | 各实例缓存不同步，改权限后行为不一致 |
| active 学期缓存 | 进程内 | 切换学期后各实例看到的 active 不同 |
| `SEMESTER_LOCK` | JVM 级 `ReentrantLock` | 窗口变更/学期切换跨实例不再互斥（双缓冲原子性依赖 DB 唯一约束兜底） |
| 定时任务 | `@Scheduled` + 进程内锁 | 窗口扫描/通知重发/导出清理在 N 个实例上重复执行 |
| 启动补偿 | 按 `updated_at < 本进程启动时刻` 回收 `running/queued` 任务 | 滚动发布时新实例会误杀旧实例在途任务 → **必须置 `TEXTBOOK_ASYNC_RECOVER=false`** |

- **触发条件**：确认要横向扩容（或要上多副本滚动发布）时。
- **改造代价**：限频/缓存改 Redis 或 Caffeine+Redis 二级；锁改分布式锁（Redisson/DB 锁）或换 xxl-job 统一调度；启动补偿改由运维人工收敛。约 3-5 人日 + 一次全量回归。

### 9.2 数据完整性工程项（B-G5）

| 项 | 现状 | 触发条件 | 改造代价 |
|----|------|----------|----------|
| 外键缺失 | 全库关联列均为裸 `BIGINT`，引用完整性只在应用层（配合逻辑删除 `deleted` 语义，加物理外键会与「逻辑删除后重建同键」冲突） | 出现跨表脏数据事故，或交由 DBA 统一治理时 | 需先定「逻辑删除 vs 物理外键」取舍；全量 DDL 改造 + 数据清洗 + 回归，约 5-8 人日 |
| 无版本化迁移工具 | `schema.sql` 幂等但不做列级迁移，已上线库的结构变更靠人工按序执行 `db/migration-*.sql`（每次发版须核对清单） | 发版频率上升、或迁移脚本数量超过 10 个时 | 引入 Flyway/Liquibase：需为存量库建立 baseline，历史脚本改写成版本化迁移，约 2-3 人日 |
| 窗口边界精度 | `window_end` 之后最多 60 秒仍可提交（窗口状态由每分钟 cron 扫描推进，非请求时实时判定） | 要求秒级精确截止时 | 在 `@WithinWindow` 校验里加实时时间判定（与落库状态取或），约 0.5 人日 |
| 宽列与 `SELECT *` | `sys_user` 含 `reserve1~6`、JSON 列（`field_check_result`/`submit_snapshot`）在热表内；部分查询 `SELECT *` | 单表行数增长到影响查询性能时 | 宽列拆分 + 查询列收敛，需逐条核对 Mapper，约 2-3 人日 |

### 9.3 本轮明确不做

- 多实例改造、外键、Flyway 迁移：**仅登记，不实施**（见 9.1/9.2）。
- 支付、微服务/消息队列、教学班模型、AI 功能、短信/企业微信催办渠道：PRD 排除项。
- 生产数据清理（`cleanup-seed-accounts.sql` / `cleanup-demo-and-fixtures.sql`）：属正式开放上线前的独立动作，由甲方决策触发。
