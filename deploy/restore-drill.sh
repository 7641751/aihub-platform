#!/bin/sh
# 恢复演练：把一份备份导进**一次性 MySQL 容器**，验证"备份真的能恢复"（而不只是"文件存在"）。
#
#   ./restore-drill.sh                                  # 用最新那份备份
#   BACKUP_DIR=backups/20261011T050006Z ./restore-drill.sh
#
# 判据（可证伪）：
#   ① 导入过程**零报错**；
#   ② 演练库的**表数**与生产库**相等** —— 不等说明 dump 缺 schema（退 1）。
#   行数只打印供人眼比对：`request_log` / `audit_log` 这类只增表在备份之后会合法地多出若干行。
#
# 安全边界：**只读**生产库（SELECT）；演练库用自己的匿名卷、**不发布宿主端口**；结束时删掉容器。
# 为什么要做：cron 里那几条"最小可用性校验"只能证明"dump 非空且有 CREATE TABLE"，
# 证明不了"它导得回去" —— 恢复演练才是那条证据。
set -eu

DEPLOY="${DEPLOY:-$(cd "$(dirname "$0")" && pwd)}"
cd "$DEPLOY"

BACKUP_DIR="${BACKUP_DIR:-}"
if [ -z "$BACKUP_DIR" ]; then
  BACKUP_DIR="$(ls -1dt "$DEPLOY"/backups/20* 2>/dev/null | head -1 || true)"
fi
if [ -z "$BACKUP_DIR" ] || [ ! -s "$BACKUP_DIR/aihub.sql" ]; then
  echo "FATAL: 找不到可用的备份（$BACKUP_DIR/aihub.sql）" >&2
  exit 1
fi

CONTAINER=aihub-drill
DRILL_PW=drill-only
echo "=== 演练源：$BACKUP_DIR ==="
ls -l "$BACKUP_DIR"

# 只允许删我们自己这个固定名字的容器；不存在也无所谓。
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

echo "=== 起演练库（不发布端口）==="
docker run -d --name "$CONTAINER" \
  -e MYSQL_ROOT_PASSWORD="$DRILL_PW" \
  -v "$DEPLOY/$BACKUP_DIR":/backup:ro \
  "${AIHUB_IMAGE_MYSQL:-mysql:8.4}" >/dev/null

echo "=== 等就绪（最多 120 秒）==="
i=0
while [ "$i" -lt 60 ]; do
  if docker exec "$CONTAINER" sh -c 'mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then
    echo "READY_AFTER_SEC=$((i * 2))"
    break
  fi
  sleep 2
  i=$((i + 1))
done
docker exec "$CONTAINER" sh -c 'mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD"' 2>&1 \
  | grep -v 'password on the command line' | tail -1

echo "=== 建库 + 导入 ==="
docker exec "$CONTAINER" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE aihub"' 2>&1 \
  | grep -v 'password on the command line' || true
docker exec -i "$CONTAINER" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" aihub' < "$BACKUP_DIR/aihub.sql" 2>&1 \
  | grep -v 'password on the command line' | tail -3

cat > /tmp/drill-cmp.sql <<'SQLEOF'
SELECT 'tables', COUNT(*) FROM information_schema.tables WHERE table_schema = 'aihub';
SELECT 'sys_user', COUNT(*) FROM sys_user;
SELECT 'tenant', COUNT(*) FROM tenant;
SELECT 'api_key', COUNT(*) FROM api_key;
SELECT 'channel', COUNT(*) FROM channel;
SELECT 'quota', COUNT(*) FROM quota;
SQLEOF

docker cp /tmp/drill-cmp.sql "$CONTAINER":/tmp/drill-cmp.sql >/dev/null
echo "--- 演练库（从备份恢复）---"
DRILL_OUT="$(docker exec "$CONTAINER" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B aihub < /tmp/drill-cmp.sql' 2>&1 \
  | grep -v 'password on the command line')"
printf '%s\n' "$DRILL_OUT"

docker cp /tmp/drill-cmp.sql aihub-prod-mysql-1:/tmp/drill-cmp.sql >/dev/null
echo "--- 生产库（当前）---"
PROD_OUT="$(docker exec aihub-prod-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B aihub < /tmp/drill-cmp.sql' 2>&1 \
  | grep -v 'password on the command line')"
printf '%s\n' "$PROD_OUT"

DRILL_TABLES="$(printf '%s\n' "$DRILL_OUT" | awk -F'\t' '$1=="tables"{print $2}')"
PROD_TABLES="$(printf '%s\n' "$PROD_OUT" | awk -F'\t' '$1=="tables"{print $2}')"
echo "=== 判据：表数 drill=$DRILL_TABLES prod=$PROD_TABLES ==="
if [ -n "$DRILL_TABLES" ] && [ "$DRILL_TABLES" = "$PROD_TABLES" ]; then
  echo "DRILL=PASS"
  rm -f /tmp/drill-cmp.sql
  exit 0
fi
echo "DRILL=FAIL（dump 缺 schema 或生产库不可读）" >&2
rm -f /tmp/drill-cmp.sql
exit 1
