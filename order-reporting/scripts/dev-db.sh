#!/usr/bin/env bash
# 可选：使用已有 MySQL 8.4 二进制创建本项目专用实例，不访问已有实例/数据目录。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p .local
if [[ -f .env ]]; then set -a; source .env; set +a; fi
if [[ -n "${MYSQL_BIN:-}" ]]; then export PATH="$MYSQL_BIN:$PATH"; fi
command -v mysqld >/dev/null || { echo '缺少 mysqld；安装 MySQL 8.4 或设置 MYSQL_BIN。' >&2; exit 1; }
command -v mysql >/dev/null || { echo '缺少 mysql 客户端。' >&2; exit 1; }
STATE="$ROOT/.local/mysql"
# Unix socket 路径长度有限；使用项目路径摘要区分实例。
INSTANCE_KEY="$(printf '%s' "$ROOT" | shasum | cut -c1-12)"
SOCKET="/tmp/order_reporting-$INSTANCE_KEY.sock"
PORT=13307
mkdir -p "$STATE"
if [[ -f "$STATE/admin.env" ]]; then source "$STATE/admin.env"; fi
mysql_admin() { MYSQL_PWD="${ORDERREPORT_ROOT_PASSWORD:-}" mysqladmin --no-defaults --socket="$SOCKET" -uroot "$@"; }
start_server() {
  local mode="$1"
  local args=(--no-defaults --datadir="$STATE/data" --socket="$SOCKET" --pid-file="$STATE/mysql.pid" --log-error="$STATE/mysql.log" --mysqlx=OFF --default-time-zone=+08:00)
  if [[ "$mode" == bootstrap ]]; then args+=(--skip-networking); else args+=(--bind-address=127.0.0.1 --port="$PORT"); fi
  nohup mysqld "${args[@]}" > "$STATE/console.log" 2>&1 &
  MYSQL_SERVER_PID=$!
  for ((i=0; i<40; i++)); do
    if MYSQL_PWD="${ORDERREPORT_ROOT_PASSWORD:-}" mysql --no-defaults --socket="$SOCKET" -uroot -Nse 'SELECT 1' >/dev/null 2>&1; then return; fi
    sleep 1
  done
  echo "MySQL 未能启动，请查看 $STATE/mysql.log" >&2
  exit 1
}
case "${1:-start}" in
  start|run)
    if [[ -f "$STATE/data/auto.cnf" ]]; then
      if mysql_admin ping >/dev/null 2>&1; then echo "Order Reporting MySQL 已启动（127.0.0.1:${PORT}）。"; exit 0; fi
      [[ -f "$STATE/ready" ]] || { echo '初始化未完成；保留数据目录，请先检查 mysql.log，勿重复初始化。' >&2; exit 1; }
      start_server normal
    else
      if [[ -f .env ]]; then echo '已有 .env。为防止覆盖，请使用现有数据库或先自行保管该文件，再运行此可选初始化脚本。' >&2; exit 1; fi
      umask 077
      ORDERREPORT_NEW_ROOT_PASSWORD="$(openssl rand -hex 24)"
      ORDERREPORT_APP_PASSWORD="$(openssl rand -hex 24)"
      ORDERREPORT_TEST_PASSWORD="$(openssl rand -hex 24)"
      mysqld --no-defaults --initialize-insecure --datadir="$STATE/data" --log-error="$STATE/mysql.log"
      # 临时无密码管理员只通过 Unix socket 访问；设置随机密码后才启用 TCP。
      start_server bootstrap
      mysql --no-defaults --socket="$SOCKET" -uroot <<SQL
CREATE DATABASE order_reporting CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE order_reporting_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'order_reporting'@'127.0.0.1' IDENTIFIED BY '$ORDERREPORT_APP_PASSWORD';
CREATE USER 'order_reporting_test'@'127.0.0.1' IDENTIFIED BY '$ORDERREPORT_TEST_PASSWORD';
GRANT ALL PRIVILEGES ON order_reporting.* TO 'order_reporting'@'127.0.0.1';
GRANT ALL PRIVILEGES ON order_reporting_test.* TO 'order_reporting_test'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$ORDERREPORT_NEW_ROOT_PASSWORD';
SQL
      ORDERREPORT_ROOT_PASSWORD="$ORDERREPORT_NEW_ROOT_PASSWORD"
      printf 'ORDERREPORT_ROOT_PASSWORD=%s\n' "$ORDERREPORT_ROOT_PASSWORD" > "$STATE/admin.env"
      cat > .env <<ENV
DB_URL='jdbc:mysql://127.0.0.1:$PORT/order_reporting?connectionTimeZone=Asia/Shanghai'
DB_USERNAME=order_reporting
DB_PASSWORD=$ORDERREPORT_APP_PASSWORD
TEST_DB_URL='jdbc:mysql://127.0.0.1:$PORT/order_reporting_test?connectionTimeZone=Asia/Shanghai'
TEST_DB_USERNAME=order_reporting_test
TEST_DB_PASSWORD=$ORDERREPORT_TEST_PASSWORD
SPRING_PROFILES_ACTIVE=demo
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8081
DEMO_STORE1_PASSWORD=$(openssl rand -hex 24)
DEMO_STORE2_PASSWORD=$(openssl rand -hex 24)
DEMO_ADMIN_PASSWORD=$(openssl rand -hex 24)
ENV
      touch "$STATE/ready"
      mysql_admin shutdown
      start_server normal
    fi
    echo "Order Reporting MySQL 已就绪：127.0.0.1:${PORT}；账号配置保存在 .env（已被 Git 忽略）。"
    if [[ "${1:-start}" == run ]]; then
      trap 'mysql_admin shutdown >/dev/null 2>&1 || true' INT TERM
      wait "$MYSQL_SERVER_PID"
    fi
    ;;
  stop) mysql_admin shutdown; echo 'Order Reporting MySQL 已停止，数据已保留。' ;;
  status) mysql_admin ping ;;
  *) echo 'Usage: scripts/dev-db.sh [start|run|stop|status]' >&2; exit 1 ;;
esac
