# 教材征订系统 · 部署手册（M5 交付物）

## 1. 环境要求

- JDK 17+、MySQL 8.0.36+（utf8mb4）、Nginx（HTTPS）、2C4G 起
- 域名已备案（moonzj.com），系统挂在子路径 `/textbook/`（前端）+ `/api/`（后端）

## 2. 数据库初始化（DBA 执行，按顺序）

```bash
mysql -uroot -p textbook_order < src/main/resources/db/schema.sql          # 全量 DDL
mysql -uroot -p textbook_order < src/main/resources/db/data-permission.sql # 37 条权限码 + 五角色映射
mysql -uroot -p textbook_order < src/main/resources/db/data-seed.sql       # 种子数据 + 测试账号
```

> 本地开发可直接用 `local` profile 启动（自动按上述顺序初始化，仅限全新空库）。

## 3. 环境变量（/etc/textbook/env，systemd EnvironmentFile）

```ini
DB_URL=jdbc:mysql:<SECRET_824596b7>
DB_USERNAME=textbook
DB_PASSWORD=<强密码>
JWT_SECRET=<openssl rand -base64 48 生成，≥32字节>
WX_MINIAPP_APPID=<小程序 AppID>
WX_MINIAPP_SECRET=<小程序 Secret>
WX_SUBSCRIBE_TEMPLATE_ID=<订阅消息模板 id（未申请前可留空，重发记 unauthorized）>
TZ=Asia/Shanghai
SPRING_PROFILES_ACTIVE=trial
```

## 4. systemd 守护（/etc/systemd/system/textbook-order-server.service）

```ini
[Unit]
Description=textbook-order-server
After=network.target mysql.service

[Service]
Type=simple
User=www-data
EnvironmentFile=/etc/textbook/env
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -Duser.timezone=Asia/Shanghai \
  -jar /opt/textbook/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}
Restart=always
RestartSec=5
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
- [ ] `schema.sql` + `data-permission.sql` + `data-seed.sql` 初始化（§2）
- [ ] Nginx 双 location（`/textbook/` + `/api/`）生效（§5）
- [ ] systemd 守护 + 备份 crontab 生效（§4/§6）
- [ ] 管理员账号交接与首登流程演练（900001/Admin@123，首登校验 + 改密）
- [ ] OpenAPI 文档访问方式说明（`/v3/api-docs`，试运行环境建议关闭或加权限）
- [ ] 小程序 baseUrl 与微信后台合法域名更新清单（前端主责，后端提供接口域名确认）
- [ ] 订阅消息模板 id 申请进度（W5/R10：未申请前重发记 unauthorized，弹窗为主触达）

## 8. 运维要点

- 时区：JVM `-Duser.timezone=Asia/Shanghai` + `TZ` 双保险（窗口判定，W11）
- 日志：应用按天轮转保留 30 天（logback），不含密码/token
- 清理：导出文件 24 小时后自动删除；导入临时文件解析后即删
- 归档：学期归档 = 置 `active_status=archived`，数据只读保留、可查可导（D2-A）
- 多实例部署前：定时任务（窗口扫描/通知重发/导出清理）需换分布式锁或 xxl-job（R3）
