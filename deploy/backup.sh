#!/usr/bin/env sh
# 备份：MySQL 全库 + KB 原件卷。
#
#   ./backup.sh                      # 输出到 deploy/backups/<UTC 时间戳>/
#   ENV_FILE=.env.production ./backup.sh
#
# 备份范围的取舍（明写）：
#   * MySQL  = 全部**真相源**（租户/Key/渠道/路由/配额/限流/审计/账单/request_log/kb_document/kb_chunk）
#   * admin-files = KB **原件**（唯一真相源；向量可以从它重建，反过来不行）
#   * Chroma **不备**：它是**可重建的派生数据**（见 CONVENTIONS §6.8）。⚠️ 但本仓库**没有**
#     "重建工具"（重传原件 ≠ 重建同一 collection）⇒ 真要恢复到"向量也在"的状态，恢复完需重传原件。
set -eu

cd "$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-.env.production}"
COMPOSE="docker compose --env-file $ENV_FILE -f docker-compose.prod.yml"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="backups/$STAMP"
mkdir -p "$OUT"

echo "==> MySQL dump"
$COMPOSE exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --default-character-set=utf8mb4 aihub' \
  > "$OUT/aihub.sql"

echo "==> KB originals (volume aihub-prod_admin-files)"
docker run --rm \
  -v aihub-prod_admin-files:/data:ro \
  -v "$(pwd)/$OUT":/backup \
  "${AIHUB_IMAGE_ALPINE:-docker.m.daocloud.io/library/alpine:3.20}" \
  tar czf /backup/admin-files.tgz -C /data .

echo "==> written to $OUT"
ls -lh "$OUT"
cat <<'EOF'

恢复（概要；细节见 README「回滚与恢复」）：
  1) mysql：  docker compose ... exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" aihub' < aihub.sql
  2) 原件卷： docker run --rm -v aihub-prod_admin-files:/data -v "$PWD":/backup alpine \
                sh -c 'cd /data && tar xzf /backup/admin-files.tgz'
  3) 重启 admin 让 Flyway/缓存回到一致状态；Chroma 若为空，需重传原件让向量重建。
EOF
