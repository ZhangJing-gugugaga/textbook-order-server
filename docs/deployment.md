# 教材征订系统 · 部署手册（M5 交付物）

## 1. 环境要求

- JDK 17+、MySQL 8.0.36+（utf8mb4）、Nginx（HTTPS）、2C4G 起
- 域名已备案（moonzj.com），系统挂在子路径 `/textbook/`（前端）+ `/api/`（后端）

## 2. 数据库初始化（DBA 执行，按顺序）

```bash
mysql -uroot -p textbook_order < src/main/resources/db/schema.sql          # 全量 DDL（IF NOT EXISTS，可重复执行）
mysql -uroot -p textbook_order < src/main/resources/db/data-permission.sql # 37 条权限码 + 五角色映射
# data-seed.sql 仅用于本地/演示环境（内含已知口令的测试账号），严禁在生产执行
```

> 本地开发：`SPRING_PROFILES_ACTIVE=local` 启动会自动按上述顺序初始化（仅限全新空库）。
> 生产/试运行不要执行 `data-seed.sql`。
>
> 注意：`schema.sql` 只负责建表（已幂等），**对已存在的表是空操作**，不会补新增列/索引。
> 已上线库的结构变更走下面的迁移脚本；建议后续引入 Flyway/Liquibase 做版本化迁移。

### 2.1 存量库升级（**部署新版本前必做**）

```bash
mysqldump --single-transaction -uroot textbook_order | gzip > 升级前备份.sql.gz   # 先备份（§6）
mysql -uroot -p textbook_order < src/main/resources/db/migration-2026-09-21.sql
```

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

## 3. 环境变量（/etc/textbook/env，systemd EnvironmentFile）

```ini
DB_URL=jdbc:mysql:<SECRET_824596b7>
DB_USERNAME=textbook
DB_PASSWORD=<强密码>
JWT_SECRET=<openssl rand -base64 48 生成，≥32字节>
WX_MINIAPP_APPID=<小程序 AppID>
WX_MINIAPP_SECRET=<小程序 Secret>
WX_SUBSCRIBE_TEMPLATE_ID=<订阅消息模板 id（未申请前可留空，重发记 unauthorized）>
TEXTBOOK_EXPORT_TMP=/opt/textbook/data/export
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
- `/actuator/health` 已开放用于探活；其余 actuator 端点未暴露。

## 4. systemd 守护（/etc/systemd/system/textbook-order-server.service）

```ini
[Unit]
Description=textbook-order-server
After=network.target mysql.service

[Service]
Type=simple
User=www-data
EnvironmentFile=/etc/textbook/env
# WorkingDirectory 必填：textbook.export.tmp-dir / TEXTBOOK_LOG_DIR 的相对路径以它为基准，
# 缺失时会落到 / 导致不可写（启动自检会因此中止）
WorkingDirectory=/opt/textbook
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -Duser.timezone=Asia/Shanghai \
  -jar /opt/textbook/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}
Restart=always
RestartSec=5
# 优雅停机：先停收新请求，等待在途请求与异步导入/导出收尾（最长 30s）
KillSignal=SIGTERM
TimeoutStopSec=45
StandardOutput=append:/var/log/textbook/app.log
StandardError=append:/var/log/textbook/app.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now textbook-order-server
```

## 5. Nginx（双 location）

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

## 6. 备份（每日 02:00 全量，保留 14 天，W22）

`/opt/textbook/backup.sh`：

```bash
#!/bin/bash
set -euo pipefail
DIR=/opt/textbook/backup
KEEP_DAYS=14
STAMP=$(date +%Y%m%d-%H%M%S)
mysqldump --single-transaction --quick --routines -uroot textbook_order \
  | gzip > "$DIR/textbook_order-$STAMP.sql.gz"
find "$DIR" -name 'textbook_order-*.sql.gz' -mtime +$KEEP_DAYS -delete
```

```bash
chmod +x /opt/textbook/backup.sh
crontab -l 2>/dev/null | { cat; echo "0 2 * * * /opt/textbook/backup.sh >> /var/log/textbook/backup.log 2>&1"; } | crontab -
```

恢复：`gunzip -c textbook_order-YYYYMMDD-HHMMSS.sql.gz | mysql -uroot textbook_order`

## 7. 移交检查单（SPEC §15）

- [ ] 环境变量清单交接（§3）
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
- 备份恢复演练：建议每学期至少一次（`gunzip -c ... | mysql`），恢复后校验
  `semester` 的 active 学期与 `system_config` 一致
- 多实例部署前：定时任务（窗口扫描/通知重发/导出清理）、登录限频与角色缓存
  （Caffeine 进程内）、`SEMESTER_LOCK`（JVM 级锁）都需换分布式锁/外置缓存或 xxl-job（R3）；
  **并须把 `TEXTBOOK_ASYNC_RECOVER=false`**——启动补偿按「`updated_at` 早于本进程启动时刻」
  回收遗留任务，多实例滚动发布时应由运维人工收敛（彻底方案见待确认清单）
