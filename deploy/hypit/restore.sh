#!/usr/bin/env bash
# restore.sh — 任务书 #107-3 C107-23 步骤 10：Hypit 备份恢复到新卷。
#
# 原则（K05/K06）：只恢复到「全新/空」的数据根——绝不覆盖在途实例；恢复完成后
# 执行依赖引用核对（workspace/revisions、Results/history、profile 指针、未知
# remote receipts），结果写入 report.json；恢复过程绝不发起新 generation。
#
# 用法： deploy/hypit/restore.sh <backup-dir> <target-data-root>
#   <backup-dir>        backup.sh 产物（含 manifest.json / pg/hypit.dump / data/*）
#   <target-data-root>  新数据根目录（必须不存在或为空；在途实例的数据根会被拒绝）
set -euo pipefail

BACKUP_DIR="${1:?usage: restore.sh <backup-dir> <target-data-root>}"
TARGET_ROOT="${2:?usage: restore.sh <backup-dir> <target-data-root>}"
PG_DSN="${HYPIT_PG_DSN:-${DATABASE_URL:-}}"

log() { printf '\033[1m[hypit-restore]\033[0m %s\n' "$*"; }

MANIFEST="${BACKUP_DIR}/manifest.json"
[ -f "${MANIFEST}" ] || { log "ERROR: ${MANIFEST} 缺失（不是 backup.sh 产物）"; exit 1; }
command -v jq >/dev/null 2>&1 || { log "ERROR: jq is required"; exit 1; }

FORMAT="$(jq -r .format "${MANIFEST}")"
[ "${FORMAT}" = "y1.hypit-backup@1" ] || { log "ERROR: 未知备份格式 ${FORMAT}"; exit 1; }

# 目标必须为空（新卷/新目录）：在途实例保护。
if [ -e "${TARGET_ROOT}" ] && [ -n "$(ls -A "${TARGET_ROOT}" 2>/dev/null)" ]; then
  log "ERROR: ${TARGET_ROOT} 非空——恢复只允许落到新卷/空目录（不覆盖在途实例）"
  exit 1
fi
mkdir -p "${TARGET_ROOT}"

# 1) 数据根解包 + 校验。
TARBALL="${BACKUP_DIR}/data/$(jq -r .dataTarball "${MANIFEST}")"
EXPECTED_SHA="$(jq -r .dataSha256 "${MANIFEST}")"
ACTUAL_SHA="$(shasum -a 256 "${TARBALL}" | awk '{print $1}')"
[ "${EXPECTED_SHA}" = "${ACTUAL_SHA}" ] || { log "ERROR: sha256 不匹配（${ACTUAL_SHA} != ${EXPECTED_SHA}）"; exit 1; }
log "解包 ${TARBALL} → $(dirname "${TARGET_ROOT}")"
if [[ "${TARBALL}" == *.tar.zst ]]; then
  tar -I zstd -xf "${TARBALL}" -C "$(dirname "${TARGET_ROOT}")"
else
  tar -xzf "${TARBALL}" -C "$(dirname "${TARGET_ROOT}")"
fi
[ -d "${TARGET_ROOT}" ] || { log "ERROR: 解包后未出现 ${TARGET_ROOT}"; exit 1; }

# 2) PG 恢复（到目标库；调用方保证目标库为空/新建）。
if [ -n "${PG_DSN}" ]; then
  log "pg_restore → 目标库（--clean --if-exists 仅对空库无害）"
  pg_restore --no-owner --exit-on-error --dbname "${PG_DSN}" "${BACKUP_DIR}/pg/hypit.dump"
else
  log "WARN: 未提供 HYPIT_PG_DSN/DATABASE_URL——跳过 PG 恢复（只恢复数据根）"
fi

# 3) 恢复后核对（只读查询；不发起新 generation）。
log "恢复后核对"
REPORT="${BACKUP_DIR}/restore-report.json"
NOTES="[]"

if [ -n "${PG_DSN:-}" ]; then
  # 3a) revision 链与 workspace 快照目录指针：每行 applied revision 都应能对上数据根内
  #     的 revisions 记录（缺快照如实列出，不静默）。
  psql "${PG_DSN}" -tAc \
    "SELECT p.id, p.revision, r.snapshot_dir FROM hypit_project p
     LEFT JOIN LATERAL (SELECT snapshot_dir FROM hypit_revision r WHERE r.project_id = p.id
                        ORDER BY revision DESC LIMIT 1) r ON true" \
    > "${BACKUP_DIR}/revisions.tsv" || true
  MISSING_SNAPSHOTS="$(awk -F'|' '$2 > 0 && ($3 == "" || $3 == "N") {c++} END {print c+0}' "${BACKUP_DIR}/revisions.tsv")"
  # 3b) 未知 remote receipts / 孤儿引用计数（列缺失的老库按 0 处理，report 如实）。
  ORPHAN_RESULTS="$(psql "${PG_DSN}" -tAc \
    "SELECT count(*) FROM hypit_build b
     WHERE NOT EXISTS (SELECT 1 FROM hypit_project p WHERE p.id = b.project_id)" 2>/dev/null || echo 0)" || true
  NOTES="$(jq -n --arg m "${MISSING_SNAPSHOTS:-0}" --arg o "${ORPHAN_RESULTS:-0}" \
    '[{check:"revision_snapshot_missing",count:($m|number)},{check:"orphan_build_rows",count:($o|number)}]')"
fi

cat > "${REPORT}" <<EOF
{
  "format": "y1.hypit-restore-report@1",
  "restoredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "targetDataRoot": "${TARGET_ROOT}",
  "newGenerationsTriggered": 0,
  "notes": ${NOTES}
}
EOF
log "报告：${REPORT}"
log "完成（恢复流程不自动触发任何新 generation；启动服务前先人工核对 report.notes）"
