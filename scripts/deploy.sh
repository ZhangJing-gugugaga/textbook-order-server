#!/usr/bin/env bash
# ============================================================================
# textbook-order 生产部署脚本（前端 + 后端 + 数据库迁移 + 回滚）
# 用法：
#   ./deploy.sh migrate             备份数据库 → 按顺序执行 db/migration-*.sql → 输出 ADMIN 权限数供人工核对
#   ./deploy.sh backend             上传后端 jar → 重启 systemd 服务 → 健康检查
#   ./deploy.sh frontend            上传前端 dist → 验证入口 hash
#   ./deploy.sh all                 migrate + backend + frontend（标准发版）
#   ./deploy.sh rollback backend    回滚后端到最近一次备份
#   ./deploy.sh rollback frontend   回滚前端到最近一次备份
# 前置条件：
#   1) 本机已配置到部署服务器的 SSH 免密（公钥在服务器 authorized_keys）
#   2) 后端产物：textbook-order-server/target/textbook-order-server.jar（mvnw package -DskipTests）
#   3) 前端产物：textbook-order-web/dist（VITE_BASE=/ npm run build，子域名根路径拓扑）
#   4) 敏感配置（DB/JWT/CORS）在服务器 /opt/textbook-order/textbook.conf，本脚本不触碰
# ============================================================================
set -euo pipefail

HOST="root@123.57.30.132"          # 服务器
APP_DIR="/opt/textbook-order"      # 后端部署目录（jar / textbook.conf / logs / data）
WEB_DIR="/var/www/textbook-order"  # 前端部署目录
SERVICE="textbook-order"           # systemd 服务名（监听 8081）
SITE="https://textbooksorder.moonzj.com"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_REPO="$(cd "$SCRIPT_DIR/.." && pwd)"                       # textbook-order-server
WEB_REPO="$(cd "$SERVER_REPO/../textbook-order-web" 2>/dev/null && pwd || echo "")"
LOCAL_JAR="$SERVER_REPO/target/textbook-order-server.jar"
LOCAL_DIST="$WEB_REPO/dist"
STAMP=$(date +%Y%m%d-%H%M)

say() { echo "[$(date +%H:%M:%S)] $*"; }

health_check() {
  # 登录接口空 body 返回 400 = Tomcat 与业务链路存活（参数校验预期行为）
  for i in $(seq 1 12); do
    sleep 5
    code=$(ssh "$HOST" "curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8081/api/auth/login -H 'Content-Type: application/json' -d '{}' || true")
    if [ "$code" = "400" ]; then say "[OK] 服务健康（400=参数校验，链路正常）"; return 0; fi
    say "[..] 等待启动 ($i/12) code=$code"
  done
  say "[FAIL] 健康检查未通过，查日志：ssh $HOST journalctl -u $SERVICE -n 50"
  return 1
}

do_migrate() {
  say "备份数据库 → /root/.textbook-creds/textbook_order-backup-$STAMP.sql"
  ssh "$HOST" "mkdir -p /root/.textbook-creds && mysqldump textbook_order > /root/.textbook-creds/textbook_order-backup-$STAMP.sql"
  say "按文件名顺序执行迁移（幂等，重复执行无副作用）"
  for f in "$SERVER_REPO"/src/main/resources/db/migration-*.sql; do
    [ -e "$f" ] || { say "无迁移文件"; return 0; }
    name=$(basename "$f")
    say "  - $name"
    scp -q "$f" "$HOST:/tmp/$name"
    ssh "$HOST" "mysql textbook_order < /tmp/$name && rm -f /tmp/$name"
  done
  say "核对（人工确认）：ADMIN 权限条数、关键权限"
  ssh "$HOST" "mysql -N textbook_order -e \"SELECT COUNT(*) AS admin_perms FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.role_code='ADMIN';\""
}

do_backend() {
  [ -f "$LOCAL_JAR" ] || { say "缺少 jar：$LOCAL_JAR（先执行 cd $SERVER_REPO && ./mvnw package -DskipTests）"; exit 1; }
  say "备份旧 jar"
  ssh "$HOST" "cp $APP_DIR/textbook-order-server.jar $APP_DIR/textbook-order-server.jar.bak-$STAMP"
  say "上传新 jar（约 80MB）"
  scp -q "$LOCAL_JAR" "$HOST:$APP_DIR/textbook-order-server.jar"
  say "重启 $SERVICE"
  ssh "$HOST" "systemctl restart $SERVICE"
  health_check
}

do_frontend() {
  [ -f "$LOCAL_DIST/index.html" ] || { say "缺少 dist：$LOCAL_DIST（先在前端仓库执行 VITE_BASE=/ npm run build）"; exit 1; }
  say "备份旧前端"
  ssh "$HOST" "cp -r $WEB_DIR ${WEB_DIR}.bak-$STAMP"
  say "上传 dist（tar 流）"
  tar czf - -C "$(dirname "$LOCAL_DIST")" dist | ssh "$HOST" "tar xzf - -C $WEB_DIR --strip-components=1"
  say "新入口：$(ssh "$HOST" "grep -o 'index-[^\"]*\.js' $WEB_DIR/index.html | head -1")"
  curl -s -o /dev/null -w "线上状态 %{http_code}\n" "$SITE/"
}

rollback() {
  case "${2:-}" in
    backend)
      latest=$(ssh "$HOST" "ls -t $APP_DIR/textbook-order-server.jar.bak-* 2>/dev/null | head -1")
      [ -n "$latest" ] || { say "无后端备份"; exit 1; }
      say "回滚到 $latest"
      ssh "$HOST" "cp $latest $APP_DIR/textbook-order-server.jar && systemctl restart $SERVICE"
      health_check ;;
    frontend)
      latest=$(ssh "$HOST" "ls -dt ${WEB_DIR}.bak-* 2>/dev/null | head -1")
      [ -n "$latest" ] || { say "无前端备份"; exit 1; }
      say "回滚到 $latest"
      ssh "$HOST" "rm -rf $WEB_DIR && cp -r $latest $WEB_DIR"
      curl -s -o /dev/null -w "回滚后线上状态 %{http_code}\n" "$SITE" ;;
    *) say "用法: $0 rollback backend|frontend"; exit 1 ;;
  esac
}

case "${1:-}" in
  migrate)  do_migrate ;;
  backend)  do_backend ;;
  frontend) do_frontend ;;
  all)      do_migrate; do_backend; do_frontend ;;
  rollback) rollback "$@" ;;
  *) echo "用法: $0 {migrate|backend|frontend|all|rollback backend|rollback frontend}"; exit 1 ;;
esac
