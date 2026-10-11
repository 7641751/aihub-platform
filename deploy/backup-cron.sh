#!/bin/sh
# aihub 定时备份的**包装脚本**（由 cron 每天调用；本文件是**唯一来源**）。
#
# 安装（服务器上；cron 调的是安装后的副本，避免"clone 被移动/清理"导致备份静默消失）：
#   install -m 755 deploy/backup-cron.sh /usr/local/bin/aihub-backup.sh
#   install -m 644 deploy/cron/aihub-backup /etc/cron.d/aihub-backup     # 见 deploy/cron/ 目录
#
# 为什么不让 cron 直接调 `deploy/backup.sh`：本脚本多做三件 backup.sh 不做的事 ——
#   ① 备份**可用性最小校验**（空的 / 没建表的 dump 一律算失败 ⇒ 让"备份没成功"可见，
#      而不是留一个 28K 的空壳目录冒充成功）；
#   ② 保留期清理（KEEP_DAYS，默认 14 天；**只**删 backups/ 下匹配时间戳格式的目录）；
#   ③ 落"最后一次成功"戳，便于外部（或你）一眼查新鲜度。
#
# 证据面：/var/log/aihub-backup.log（每次一行 RESULT=OK/FAIL）＋
#         /var/lib/aihub-backup/{last-ok,last-dir,last-pruned}
set -eu

# cron 的 PATH 很小（常常只有 /usr/bin:/bin）—— 自己给全。docker 在 /usr/bin。
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH

DEPLOY="${DEPLOY:-/opt/aihub/deploy}"
LOG="${LOG:-/var/log/aihub-backup.log}"
STATE_DIR="${STATE_DIR:-/var/lib/aihub-backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG"; }

mkdir -p "$STATE_DIR"
log "=== backup start (KEEP_DAYS=$KEEP_DAYS) ==="

if [ ! -d "$DEPLOY" ]; then
  log "RESULT=FAIL reason=deploy_dir_missing path=$DEPLOY"
  exit 1
fi
cd "$DEPLOY"

# ① 跑仓库里的 backup.sh（MySQL dump + KB 原件卷）
if ! ./backup.sh >> "$LOG" 2>&1; then
  log "RESULT=FAIL reason=backup_sh_nonzero"
  exit 1
fi

# ② 拿**最新**那个备份目录做最小可用性校验（目录存在 ≠ 备份可用）
LATEST="$(ls -1dt "$DEPLOY"/backups/20* 2>/dev/null | head -1 || true)"
if [ -z "$LATEST" ]; then
  log "RESULT=FAIL reason=no_backup_dir"
  exit 1
fi
if [ ! -s "$LATEST/aihub.sql" ]; then
  log "RESULT=FAIL reason=dump_empty dir=$LATEST"
  exit 1
fi
if [ "$(wc -c < "$LATEST/aihub.sql")" -lt 1024 ]; then
  log "RESULT=FAIL reason=dump_too_small dir=$LATEST"
  exit 1
fi
if ! grep -q 'CREATE TABLE' "$LATEST/aihub.sql"; then
  log "RESULT=FAIL reason=dump_has_no_create_table dir=$LATEST"
  exit 1
fi
if [ -s "$LATEST/admin-files.tgz" ]; then
  if ! tar tzf "$LATEST/admin-files.tgz" >/dev/null 2>&1; then
    log "RESULT=FAIL reason=kb_tgz_unreadable dir=$LATEST"
    exit 1
  fi
fi

# ③ 保留期清理：只删 backups/ 下、时间戳格式、且 mtime 超过 KEEP_DAYS 的目录
DELETED=0
for d in "$DEPLOY"/backups/20*; do
  [ -d "$d" ] || continue
  if [ -n "$(find "$d" -maxdepth 0 -mtime +"$KEEP_DAYS" 2>/dev/null)" ]; then
    rm -rf "$d"
    log "pruned $d"
    DELETED=$((DELETED + 1))
  fi
done

# ④ 落戳
date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE_DIR/last-ok"
printf '%s\n' "$LATEST" > "$STATE_DIR/last-dir"
printf '%s\n' "$DELETED" > "$STATE_DIR/last-pruned"

log "RESULT=OK dir=$LATEST pruned=$DELETED size=$(du -sh "$LATEST" 2>/dev/null | cut -f1)"
