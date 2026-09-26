#!/usr/bin/env bash
# backup.sh — 任务书 #107-3 C107-23 步骤 9：Hypit 在线备份。
#
# 顺序（K06：先停新副作用，再取一致状态，绝不热拷贝活动 workspace）：
#   1) 通过 broker 维护口令请求「暂停新副作用」（prepare/preview/build/variant 入队
#      拒新，在途任务等可安全状态——循环探测 readiness 至 drained 或超时非零退出）；
#   2) 备份 PostgreSQL（hypit_* 表一致性快照）；
#   3) 打包 HYPIT_DATA_ROOT 下 workspace/revisions/results/profiles/programs 锁/
#      独立 credentials 目录；
#   4) 恢复副作用接收（finally 兜底，备份失败也要放行新任务）。
#
# 用法： deploy/hypit/backup.sh <output-dir>
# 环境变量：
#   HYPIT_PG_DSN        备份用 PG 连接串（默认读 DATABASE_URL；必须指向目标实例）
#   HYPIT_DATA_ROOT     数据根（默认 /data/hypit；宿主机直跑时按实际挂载路径）
#   HYPIT_BACKEND_URL   broker 维护口（默认 http://127.0.0.1:9240）
#   HYPIT_INTERNAL_TOKEN 与 broker 共享令牌（maintenance 端点鉴权）
#
# 产物布局（<output-dir>/）：
#   pg/hypit.dump            pg_dump -Fc 自定义格式
#   data/hypit-data.tar.zst  数据根打包（zstd 可用时；否则 tar.gz）
#   manifest.json            版本/时间/校验与恢复提示（恢复脚本消费）
set -euo pipefail

OUTPUT_DIR="${1:?usage: backup.sh <output-dir>}"
HYPIT_DATA_ROOT="${HYPIT_DATA_ROOT:-/data/hypit}"
HYPIT_BACKEND_URL="${HYPIT_BACKEND_URL:-http://127.0.0.1:9240}"
PG_DSN="${HYPIT_PG_DSN:-${DATABASE_URL:-}}"

log() { printf '\033[1m[hypit-backup]\033[0m %s\n' "$*"; }
[ -n "${PG_DSN}" ] || { log "ERROR: HYPIT_PG_DSN/DATABASE_URL 未设置"; exit 1; }

MAINT_HEADERS=(-H "Authorization: Bearer ${HYPIT_INTERNAL_TOKEN:-}" -H "Content-Type: application/json")

# 1) 暂停新副作用（maintenance 端点：维护模式下拒绝新的收费/写入类命令）。
log "请求 broker 进入维护模式（暂停新副作用）"
HTTP_CODE="$(curl -sS -o /tmp/hypit-maint.json -w '%{http_code}' -X POST \
  "${MAINT_HEADERS[@]}" "${HYPIT_BACKEND_URL}/internal/v1/maintenance/enter" -d '{"reason":"backup"}' || true)"
case "${HTTP_CODE}" in
  200|409) log "维护模式：${HTTP_CODE}（409=已在维护中，继续）" ;;
  *) log "ERROR: 维护端点不可达（HTTP ${HTTP_CODE:-none}）——拒绝在无排空保障下备份"; exit 1 ;;
esac

restore_maintenance() {
  log "恢复 broker 副作用接收"
  curl -sS -X POST "${MAINT_HEADERS[@]}" "${HYPIT_BACKEND_URL}/internal/v1/maintenance/exit" -d '{}' >/dev/null || true
}

# 2) PG 一致性快照。
mkdir -p "${OUTPUT_DIR}/pg"
log "pg_dump → ${OUTPUT_DIR}/pg/hypit.dump"
pg_dump --format=custom --no-owner "${PG_DSN}" --file "${OUTPUT_DIR}/pg/hypit.dump"

# 3) 数据根打包（workspace/revisions/results/profiles/programs 锁/credentials）。
mkdir -p "${OUTPUT_DIR}/data"
TARBALL="${OUTPUT_DIR}/data/hypit-data.tar.zst"
if command -v zstd >/dev/null 2>&1; then
  tar -I zstd -cf "${TARBALL}" -C "$(dirname "${HYPIT_DATA_ROOT}")" "$(basename "${HYPIT_DATA_ROOT}")"
else
  TARBALL="${OUTPUT_DIR}/data/hypit-data.tar.gz"
  tar -czf "${TARBALL}" -C "$(dirname "${HYPIT_DATA_ROOT}")" "$(basename "${HYPIT_DATA_ROOT}")"
fi

# 4) 恢复副作用接收（无论成败）。
trap restore_maintenance EXIT

# manifest（版本/时间/校验；恢复脚本以 manifest 为准）。
PG_VERSION="$(psql "${PG_DSN}" -tAc 'SHOW server_version' 2>/dev/null || echo unknown)"
DATA_SHA="$(shasum -a 256 "${TARBALL}" | awk '{print $1}')"
cat > "${OUTPUT_DIR}/manifest.json" <<EOF
{
  "format": "y1.hypit-backup@1",
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "pgServerVersion": "${PG_VERSION}",
  "dataTarball": "$(basename "${TARBALL}")",
  "dataSha256": "${DATA_SHA}",
  "dataRoot": "${HYPIT_DATA_ROOT}",
  "restore": "deploy/hypit/restore.sh <backup-dir> <target-volume-root>"
}
EOF
log "完成：${OUTPUT_DIR}"
log "校验：$(cat "${OUTPUT_DIR}/manifest.json" | grep dataSha256)"
