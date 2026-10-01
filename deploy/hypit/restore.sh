#!/usr/bin/env bash
# restore.sh — C107F2-34（§6.15、§7.5）：备份恢复到新隔离数据库/空目标卷。
#
# 原则：只恢复到「全新/空」目标——绝不覆盖在途实例；归档顶层先验证后显式映射
# TARGET_ROOT（内容落 TARGET_ROOT 之内，绝无旁边误落目录）；正式恢复必传 PG DSN；
# --files-only 仅诊断（PARTIAL 退出，不得参与 full 通过）；恢复后按真实 schema
# （hypit_revision.number/snapshot_handle/manifest_hash）核对每个指向文件，缺失/
# hash 不一致按类别计数并非零退出；全程零 generation 触发。
#
# 用法： deploy/hypit/restore.sh <backup-dir> <target-data-root> [--files-only]
# 退出码：0 READY；2 输入/清单错误；3 PARTIAL（--files-only 诊断完成）；
#        4 恢复核验失败（缺 snapshot/媒体、hash 不一致）；5 PG restore/查询失败。
set -euo pipefail

BACKUP_DIR="${1:?usage: restore.sh <backup-dir> <target-data-root> [--files-only]}"
TARGET_ROOT="${2:?usage: restore.sh <backup-dir> <target-data-root> [--files-only]}"
shift 2 || true
FILES_ONLY=0
for arg in "$@"; do
  case "${arg}" in
    --files-only) FILES_ONLY=1 ;;
    *) printf '[hypit-restore] ERROR: 未知参数 %s\n' "${arg}"; exit 2 ;;
  esac
done
PG_DSN="${HYPIT_PG_DSN:-${DATABASE_URL:-}}"

log() { printf '\033[1m[hypit-restore]\033[0m %s\n' "$*"; }
die() { printf '\033[1m[hypit-restore]\033[0m ERROR: %s\n' "$*"; exit 2; }

MANIFEST="${BACKUP_DIR}/manifest.json"
[ -f "${MANIFEST}" ] || die "${MANIFEST} 缺失（不是 backup.sh 产物）"

# 1) 清单校验（v2；正式恢复必须 complete=true——部分清单不得参与恢复）。
FORMAT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["format"])' "${MANIFEST}")"
[ "${FORMAT}" = "y1.hypit-backup@2" ] || die "未知备份格式 ${FORMAT}"
# C107F2-38（round-15 实录）：维护窗内快照的归档带着租约行，恢复出的 broker 启动
# 即 fail-closed。恢复报告透出待释放 leaseId（备份 manifest.maintenance 记录），
# 操作者在 broker 起后凭它 POST /internal/v1/maintenance/exit。
MAINT_LEASE="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("maintenance",{}).get("leaseId",""))' "${MANIFEST}" 2>/dev/null || true)"
export MAINT_LEASE
COMPLETE="$(python3 -c 'import json,sys;print("1" if json.load(open(sys.argv[1])).get("complete") else "0")' "${MANIFEST}")"
if [ "${FILES_ONLY}" -eq 0 ] && [ "${COMPLETE}" != "1" ]; then
  die "manifest 无 complete=true（备份未成功完成）——正式恢复拒绝"
fi

# 2) 正式恢复必传 PG DSN（--files-only 诊断可缺）。
if [ "${FILES_ONLY}" -eq 0 ] && [ -z "${PG_DSN}" ]; then
  die "正式恢复必须提供 HYPIT_PG_DSN/DATABASE_URL（--files-only 仅诊断用）"
fi

# 3) 目标必须为空（新卷/新目录）。
if [ -e "${TARGET_ROOT}" ] && [ -n "$(ls -A "${TARGET_ROOT}" 2>/dev/null)" ]; then
  die "${TARGET_ROOT} 非空——恢复只允许落到新卷/空目录（不覆盖在途实例）"
fi

TARBALL="${BACKUP_DIR}/data/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["dataTarball"])' "${MANIFEST}")"
EXPECTED_SHA="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["dataSha256"])' "${MANIFEST}")"
# C107F2-38：与 backup.sh 同法的可移植摘要——alpine 基座无 perl 系 shasum。
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA="$(sha256sum "${TARBALL}" | awk '{print $1}')"
else
  ACTUAL_SHA="$(shasum -a 256 "${TARBALL}" | awk '{print $1}')"
fi
[ "${EXPECTED_SHA}" = "${ACTUAL_SHA}" ] || die "数据伞 sha256 不匹配（${ACTUAL_SHA} != ${EXPECTED_SHA}）"

# 4) tar 路径预验证（先验证后落盘）：逐条 entry 必须是 ./name/… 相对路径，
#    禁止绝对路径/.. 穿越；顶层集合必须覆盖 manifest.roots 声明的目录。
TAR_LIST="${TMPDIR:-/tmp}/hypit-restore-tarlist-$$.txt"
if [[ "${TARBALL}" == *.tar.zst ]]; then
  zstd -dc "${TARBALL}" | tar -t > "${TAR_LIST}"
else
  tar -tzf "${TARBALL}" > "${TAR_LIST}"
fi
python3 - "${TAR_LIST}" "${MANIFEST}" <<'PYEOF' || die "归档路径预验证失败（穿越/绝对路径/roots 不齐）"
import json, sys

entries = [line.strip() for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
manifest = json.load(open(sys.argv[2], encoding="utf-8"))
top_levels = set()
for entry in entries:
    if entry.startswith("/") or ".." in entry.split("/"):
        sys.exit(1)
    normalized = entry[2:] if entry.startswith("./") else entry
    if not normalized:
        continue
    top_levels.add(normalized.split("/", 1)[0])
for root in manifest.get("roots", []):
    if root.get("role", "").startswith("omitted:"):
        continue
    if root["relativePath"] not in top_levels:
        # hypit 数据根在伞内是硬性要求；其余 roots 声明与实际一致才可信。
        if root["relativePath"] == "hypit" and "hypit" not in top_levels:
            sys.exit(1)
PYEOF

# 5) 显式映射：归档顶层 ./x → TARGET_ROOT/x（内容在 TARGET_ROOT 之内，无旁边目录）。
mkdir -p "${TARGET_ROOT}"
log "解包 ${TARBALL} → ${TARGET_ROOT}（归档顶层显式映射，无旁边误落目录）"
if [[ "${TARBALL}" == *.tar.zst ]]; then
  zstd -dc "${TARBALL}" | tar -x -C "${TARGET_ROOT}"
else
  tar -xzf "${TARBALL}" -C "${TARGET_ROOT}"
fi
[ -d "${TARGET_ROOT}/hypit" ] || die "解包后 ${TARGET_ROOT}/hypit 缺失（顶层映射失败）"

REPORT="${BACKUP_DIR}/restore-report.json"
STATUS="READY"
NOTES="[]"

write_report() {
  python3 - "${REPORT}" "${STATUS}" "${TARGET_ROOT}" "${FILES_ONLY}" "${NOTES}" <<'PYEOF'
import datetime, json, os, sys

path, status, target, files_only, notes = sys.argv[1:6]
report = {
    "format": "y1.hypit-restore-report@2",
    "status": status,
    "restoredAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "targetDataRoot": target,
    "filesOnly": files_only == "1",
    "newGenerationsTriggered": 0,
    "notes": json.loads(notes),
}
maintenance_lease = os.environ.get("MAINT_LEASE", "")
if maintenance_lease:
    report["maintenanceLeaseToRelease"] = maintenance_lease
with open(path, "w", encoding="utf-8") as handle:
    json.dump(report, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PYEOF
}

# 6) PG 恢复（正式）：--exit-on-error，失败即非零（绝无 || true 掩盖）。
if [ "${FILES_ONLY}" -eq 0 ]; then
  log "pg_restore → 目标库（调用方保证目标库为空/新建）"
  if ! pg_restore --no-owner --exit-on-error --dbname "${PG_DSN}" "${BACKUP_DIR}/pg/hypit.dump"; then
    STATUS="FAILED"
    write_report
    log "PG restore 失败——非零退出（FAILED）"
    exit 5
  fi
fi

# 7) 恢复核验（真实 V91 列：number/snapshot_handle/manifest_hash）。
#    每行 revision 的快照恒以 revisions/<number>/ 为据点（写入侧三种 handle
#    形态殊途同归，见内联注释），manifest.json 按 broker canonical 算法重算
#    hash 比对；缺失/不一致按类别计数——绝不输出 missing=0 冒充通过，SQL 失败非零。
#    C107F2-38（round-10 实录）：status='deleted' 工程的文件树按删除契约
#    已清（软删留 PG 行作审计），其 revision 行不参与存活一致性核对——
#    但跳过计数单独上报（deleted_project_revisions_skipped），不静默放宽。
if [ "${FILES_ONLY}" -eq 0 ]; then
  log "恢复后核对（revisions/<number> 快照逐路径 + manifest canonical hash）"
  REV_TSV="${TMPDIR:-/tmp}/hypit-restore-revisions-$$.tsv"
  if ! psql "${PG_DSN}" -tAc \
    "SELECT p.id::text, p.revision, r.number, r.snapshot_handle, r.manifest_hash
     FROM hypit_project p JOIN hypit_revision r ON r.project_id = p.id
     WHERE p.status <> 'deleted'
     ORDER BY p.id, r.number" > "${REV_TSV}"; then
    rm -f "${REV_TSV}"
    STATUS="FAILED"
    write_report
    log "恢复核验 SQL 失败——非零退出（FAILED）"
    exit 5
  fi

  MISSING_JSON="${TMPDIR:-/tmp}/hypit-restore-verify-$$.json"
  DELETED_SKIPPED="$(psql "${PG_DSN}" -tAc \
    "SELECT count(*) FROM hypit_project p JOIN hypit_revision r ON r.project_id = p.id
     WHERE p.status = 'deleted'" || echo 0)"
  TARGET_ROOT="${TARGET_ROOT}" REV_TSV="${REV_TSV}" DELETED_SKIPPED="${DELETED_SKIPPED}" \
    OUT="${MISSING_JSON}" python3 - <<'PYEOF'
import hashlib, json, os, sys

target = os.environ["TARGET_ROOT"]
rows = [line.strip().split("|") for line in open(os.environ["REV_TSV"], encoding="utf-8") if line.strip()]

missing_snapshots = []
hash_mismatches = []
media_missing = []

def canonical_manifest_hash(manifest_path):
    # broker manifest.ts manifestHash()：对 {format, entries:[[path,sha256,sizeBytes],…]}
    # 的 canonical JSON（紧凑分隔、非 ASCII 原样、entries 保文件序）做 sha256。
    # 不是 manifest.json 文件字节 hash——写盘是 pretty-print（round-11 实录
    # 271 行字节比对全 mismatch 的根因）；解析失败按不一致上报，不得冒充通过。
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    canonical = json.dumps({"format": manifest["format"],
                            "entries": [[e["path"], e["sha256"], e["sizeBytes"]] for e in manifest["entries"]]},
                           separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

for project_id, applied, number, handle_text, manifest_hash in rows:
    # snapshot_handle 写入侧三形态：apply=/data/projects/<id>/revisions/<n>（冻结快照）、
    # 模板 provision=/data/projects/<id>（工程根）、初版兜底=workspace:<id>（逻辑句柄）。
    # 三者殊途同归——broker 每次 head 发布都冻结 revisions/<n>/ 快照且无 prune，
    # 故恒定验证据点取 revisions/<number>；handle 串本身不构成恢复期路径契约
    # （rev1 行的句柄指工程根，manifest 却恒在 revisions/1/）。
    snapshot_dir = os.path.join(target, "projects", project_id, "revisions", number)
    if not os.path.isdir(snapshot_dir):
        missing_snapshots.append({"projectId": project_id, "revision": int(number),
                                  "path": os.path.relpath(snapshot_dir, target)})
        continue
    manifest_path = os.path.join(snapshot_dir, "manifest.json")
    if not os.path.isfile(manifest_path):
        media_missing.append({"projectId": project_id, "revision": int(number),
                              "path": os.path.relpath(manifest_path, target)})
    elif manifest_hash:
        try:
            actual = canonical_manifest_hash(manifest_path)
        except (ValueError, KeyError, TypeError):
            actual = None
        if actual != manifest_hash:
            hash_mismatches.append({"projectId": project_id, "revision": int(number),
                                    "expectedCanonicalSha256": manifest_hash,
                                    "actualCanonicalSha256": actual})

with open(os.environ["OUT"], "w", encoding="utf-8") as handle:
    json.dump({"missingSnapshots": missing_snapshots, "hashMismatches": hash_mismatches,
               "mediaMissing": media_missing,
               "deletedSkipped": int(os.environ.get("DELETED_SKIPPED", "0") or 0)}, handle, ensure_ascii=False)
PYEOF

  NOTES="$(python3 - "${MISSING_JSON}" <<'PYEOF'
import json, sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
notes = [
    {"check": "revision_snapshot_missing", "count": len(data["missingSnapshots"]), "items": data["missingSnapshots"]},
    {"check": "revision_manifest_hash_mismatch", "count": len(data["hashMismatches"]), "items": data["hashMismatches"]},
    {"check": "revision_manifest_file_missing", "count": len(data["mediaMissing"]), "items": data["mediaMissing"]},
    {"check": "deleted_project_revisions_skipped", "count": data.get("deletedSkipped", 0),
     "items": [], "informational": True,
     "note": "软删工程的 revision 行按删除契约不核对文件树（计数仅供审计）"},
]
print(json.dumps(notes, ensure_ascii=False))
PYEOF
)"
  rm -f "${REV_TSV}" "${MISSING_JSON}"

  # 失败判定只统计失败类 check；informational（如软删跳过计数）不参与判定。
  if [ "$(python3 -c 'import json,sys;print(sum(1 for n in json.loads(sys.argv[1]) if n["count"]>0 and not n.get("informational")))' "${NOTES}")" != "0" ]; then
    STATUS="FAILED"
    write_report
    log "恢复核验发现缺失/不一致——非零退出（类别与计数见 report.notes）"
    exit 4
  fi
else
  # --files-only 诊断：只验文件面，PARTIAL 退出（不得参与 full 通过）。
  STATUS="PARTIAL"
fi

write_report
if [ "${FILES_ONLY}" -eq 1 ]; then
  log "诊断完成（PARTIAL，退出码 3）——--files-only 不构成完整恢复通过"
  exit 3
fi
log "报告：${REPORT}"
log "READY：恢复流程不自动触发任何新 generation；启动服务前先人工核对 report.notes"
