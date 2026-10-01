#!/usr/bin/env bash
# backup.sh — C107F2-33（§6.15、§7.4）：Hypit 在线完整备份（manifest v2）。
#
# 顺序（K06/RULE-13：先停新副作用并排空，再在同一已排空维护窗口内取一致状态，
# 绝不热拷贝活动 workspace）：
#   1) broker 维护租约（leaseId 持有；drained=false 拒绝继续并释放自己的租约）；
#   2) 备份 PostgreSQL（pg_dump -Fc 一致性快照）；
#   3) 打包数据伞（HYPIT_DATA_ROOT 及其 /data 兄弟目录：projects/work/revisions、
#      resources、results、profiles、programs 锁、受控加密 credentials——临时
#      slot/socket/cache/staging 明确 omitted，不混入归档）；
#   4) manifest v2（y1.hypit-backup@2）：roots 角色 + omitted 理由 +
#      pgDumpSha256/dataSha256；complete=true 只在全步骤成功后随 manifest 最终
#      原子写入——任何一步失败不产生 complete 清单且非零退出。
#
# 用法： deploy/hypit/backup.sh <output-dir>
# 环境变量：
#   HYPIT_PG_DSN        备份用 PG 连接串（默认读 DATABASE_URL；必须指向目标实例）
#   HYPIT_DATA_ROOT     数据根（默认 /data/hypit；其父目录即备份伞）
#   HYPIT_BACKEND_URL   broker 维护口（默认 http://127.0.0.1:9240）
#   HYPIT_INTERNAL_TOKEN 与 broker 共享令牌（maintenance 端点鉴权；不进日志）
#   HYPIT_SOURCE_COMMIT / HYPIT_ENGINE_DIGEST / HYPIT_PG_SERVER_VERSION
#                       可选元数据；缺省分别取 git rev-parse / broker healthz / psql
#
# 产物布局（<output-dir>/）：
#   pg/hypit.dump            pg_dump -Fc 自定义格式（0600）
#   data/hypit-data.tar.*    数据伞归档（0600；相对路径，临时面已排除）
#   manifest.json            y1.hypit-backup@2（0600；恢复脚本以 manifest 为准）
set -euo pipefail

OUTPUT_DIR="${1:?usage: backup.sh <output-dir>}"
HYPIT_DATA_ROOT="${HYPIT_DATA_ROOT:-/data/hypit}"
HYPIT_BACKEND_URL="${HYPIT_BACKEND_URL:-http://127.0.0.1:9240}"
PG_DSN="${HYPIT_PG_DSN:-${DATABASE_URL:-}}"

log() { printf '\033[1m[hypit-backup]\033[0m %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }
[ -n "${PG_DSN}" ] || die "HYPIT_PG_DSN/DATABASE_URL 未设置"
[ -d "${HYPIT_DATA_ROOT}" ] || die "HYPIT_DATA_ROOT 不存在：${HYPIT_DATA_ROOT}"

MAINT_HEADERS=(-H "Authorization: Bearer ${HYPIT_INTERNAL_TOKEN:-}" -H "Content-Type: application/json")

# ---------------------------------------------------------------------------
# 维护租约（C107F2-32/§6.15）：单槽 leaseId；只有本租约能 exit。
# ---------------------------------------------------------------------------
LEASE_ID=""
release_own_lease() {
  [ -n "${LEASE_ID}" ] || return 0
  curl -sS -X POST "${MAINT_HEADERS[@]}" "${HYPIT_BACKEND_URL}/internal/v1/maintenance/exit" \
    -d "{\"leaseId\":\"${LEASE_ID}\"}" >/dev/null || true
  LEASE_ID=""
}
# 失败清理只针对未完成清单：complete=true 的最终 manifest 不受 EXIT 影响。
discard_partial_manifest() {
  if [ -f "${OUTPUT_DIR}/manifest.json" ] && ! grep -q '"complete": true' "${OUTPUT_DIR}/manifest.json"; then
    rm -f "${OUTPUT_DIR}/manifest.json"
  fi
}
cleanup() { discard_partial_manifest; release_own_lease; }
# 失败/信号退出：清掉无 complete 的部分清单并释放自己的租约（EXIT/INT/TERM 幂等）。
trap cleanup EXIT INT TERM

log "请求 broker 进入维护模式（暂停新副作用并排空在途）"
MAINT_ENTER_BODY="${TMPDIR:-/tmp}/hypit-maint-enter-$$.json"
HTTP_CODE="$(curl -sS -o "${MAINT_ENTER_BODY}" -w '%{http_code}' -X POST \
  "${MAINT_HEADERS[@]}" "${HYPIT_BACKEND_URL}/internal/v1/maintenance/enter" -d '{"reason":"backup"}' || true)"
case "${HTTP_CODE}" in
  200)
    LEASE_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("leaseId",""))' "${MAINT_ENTER_BODY}" 2>/dev/null || true)"
    [ -n "${LEASE_ID}" ] || die "维护端点 200 但未返回 leaseId——拒绝在无租约保障下备份"
    if [ "$(python3 -c 'import json,sys;print("true" if json.load(open(sys.argv[1])).get("drained") else "false")' "${MAINT_ENTER_BODY}" 2>/dev/null)" != "true" ]; then
      log "排空超时，仍有在途业务："
      python3 -c 'import json,sys;[print("  -", i.get("commandId"), i.get("kind"), i.get("state")) for i in json.load(open(sys.argv[1])).get("inflight", [])]' "${MAINT_ENTER_BODY}" 2>/dev/null || true
      release_own_lease
      die "已释放本租约，非零退出（不强杀在途任务）"
    fi
    log "维护模式：已取得租约并完成排空" ;;
  409) die "维护窗口已由其他操作者持有（409）——不得继续备份" ;;
  *) die "维护端点不可达（HTTP ${HTTP_CODE:-none}）——拒绝在无排空保障下备份" ;;
esac
rm -f "${MAINT_ENTER_BODY}"

# ---------------------------------------------------------------------------
# 备份伞：HYPIT_DATA_ROOT 的父目录（/data 兄弟目录一个不漏）。
# ---------------------------------------------------------------------------
UMBRELLA="$(cd "$(dirname "${HYPIT_DATA_ROOT}")" && pwd)"
mkdir -p "${OUTPUT_DIR}/pg" "${OUTPUT_DIR}/data"

sha256_of() {
  # C107F2-38：工具容器多为 alpine 基座——没有 perl 系 shasum，只有
  # coreutils/busybox 的 sha256sum（round-4 实录：shasum 缺失 + stderr 吞掉 →
  # 摘要恒空 → 备份在 pg_dump 校验步无声死去）。两者择一；都没有时输出为空，
  # 由调用处 [ -n ] || die 如实报错。
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" 2>/dev/null | awk '{print $1}'
  else
    shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'
  fi
}

# 2) PG 一致性快照（同一已排空窗口内）。
log "pg_dump → ${OUTPUT_DIR}/pg/hypit.dump"
pg_dump --format=custom --no-owner "${PG_DSN}" --file "${OUTPUT_DIR}/pg/hypit.dump"
PG_DUMP_SHA="$(sha256_of "${OUTPUT_DIR}/pg/hypit.dump")"
[ -n "${PG_DUMP_SHA}" ] || die "pg_dump 产物为空/校验失败"
chmod 600 "${OUTPUT_DIR}/pg/hypit.dump"

# 3) 数据伞打包（相对路径归档；临时面用 --exclude 明确剔除，manifest 记录理由）。
TARBALL="${OUTPUT_DIR}/data/hypit-data.tar.zst"
ZSTD_OK=0
command -v zstd >/dev/null 2>&1 && ZSTD_OK=1
# 归档条目形如 ./name/…（tar -C 伞目录 + .）；排除模式按该形状锚定，临时面一个不进。
TAR_EXCLUDES=()
for _tmp in runner-slots runner-sockets runner-tmp .staging package-transfers cache; do
  TAR_EXCLUDES+=(--exclude "./${_tmp}" --exclude "./${_tmp}/*"
    --exclude "*//${_tmp}" --exclude "*//${_tmp}/*")
done
# 归档整个伞目录（相对路径 ./…），/data 兄弟目录一个不漏；临时面 --exclude 剔除。
if [ "${ZSTD_OK}" -eq 1 ]; then
  tar -c -C "${UMBRELLA}" "${TAR_EXCLUDES[@]}" -f - . | zstd -q -f -o "${TARBALL}"
else
  TARBALL="${OUTPUT_DIR}/data/hypit-data.tar.gz"
  tar -czf "${TARBALL}" -C "${UMBRELLA}" "${TAR_EXCLUDES[@]}" .
fi
chmod 600 "${TARBALL}"
DATA_SHA="$(sha256_of "${TARBALL}")"
[ -n "${DATA_SHA}" ] || die "数据伞打包校验失败"

# ---------------------------------------------------------------------------
# manifest v2：files[] 与归档严格一致（pg dump + tarball 两个归档件）+ roots 角色
# + omitted 理由；凭据目录原样进归档（受控加密存储、0600 权限由 tar 保留），
# 内容与路径永不打印。先写不含 complete 的清单，全部成功后原子补 complete=true。
# ---------------------------------------------------------------------------
log "生成 manifest"
TAR_LIST="${TMPDIR:-/tmp}/hypit-backup-tarlist-$$.txt"
if [ "${ZSTD_OK}" -eq 1 ]; then
  zstd -q -d -c "${TARBALL}" | tar -t > "${TAR_LIST}"
else
  tar -tzf "${TARBALL}" > "${TAR_LIST}"
fi

UMBRELLA="${UMBRELLA}" TAR_LIST="${TAR_LIST}" PARTIAL="${OUTPUT_DIR}/manifest.json" \
DUMP_PATH="${OUTPUT_DIR}/pg/hypit.dump" DUMP_SHA="${PG_DUMP_SHA}" \
TARBALL_PATH="${TARBALL}" TARBALL_SHA="${DATA_SHA}" \
HYPIT_DATA_ROOT="${HYPIT_DATA_ROOT}" \
python3 - <<'PYEOF'
import hashlib, json, os

umbrella = os.environ["UMBRELLA"]
tar_list = os.environ["TAR_LIST"]
partial = os.environ["PARTIAL"]
dump_path = os.environ["DUMP_PATH"]
dump_sha = os.environ["DUMP_SHA"]
tarball_path = os.environ["TARBALL_PATH"]
tarball_sha = os.environ["TARBALL_SHA"]

ROLES = [
    ("hypit", "data-root"), ("projects", "projects"), ("resources", "resources"),
    ("results", "results"), ("revisions", "revisions"), ("profiles", "profiles"),
    ("programs", "programs"), ("credentials", "credentials"), ("work", "work"),
]
OMITTED = [
    ("runner-slots", "temp-runner-slots"), ("runner-sockets", "temp-runner-sockets"),
    ("runner-tmp", "temp-runner-tmp"), (".staging", "upload-staging"),
    ("package-transfers", "transfer-staging"), ("cache", "cache"),
]
tar_entries = [line.strip().lstrip("./") for line in open(tar_list, encoding="utf-8") if line.strip()]

roots = []
for name, role in ROLES:
    if os.path.isdir(os.path.join(umbrella, name)) or any(e == name or e.startswith(name + "/") for e in tar_entries):
        roots.append({"relativePath": name, "role": role})
omitted = []
for name, reason in OMITTED:
    if os.path.exists(os.path.join(umbrella, name)):
        omitted.append({"path": name, "reason": reason})

files = [
    {"path": "pg/hypit.dump", "sha256": dump_sha, "sizeBytes": os.path.getsize(dump_path)},
    {"path": "data/" + os.path.basename(tarball_path), "sha256": tarball_sha,
     "sizeBytes": os.path.getsize(tarball_path)},
]

manifest = {
    "format": "y1.hypit-backup@2",
    "createdAt": "",
    "sourceCommit": os.environ.get("HYPIT_SOURCE_COMMIT", "unknown"),
    "engineDigest": os.environ.get("HYPIT_ENGINE_DIGEST", "unknown"),
    "pgServerVersion": os.environ.get("HYPIT_PG_SERVER_VERSION", "unknown"),
    "dataRoot": os.environ["HYPIT_DATA_ROOT"],
    "pgDumpSha256": dump_sha,
    "dataTarball": os.path.basename(tarball_path),
    "dataSha256": tarball_sha,
    "roots": roots,
    "files": files,
    "omitted": omitted,
    "restore": "deploy/hypit/restore.sh <backup-dir> <target-volume-root>",
}
with open(partial, "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
os.chmod(partial, 0o600)
PYEOF

rm -f "${TAR_LIST}"

# 元数据探测（git/engineDigest/PG 版本），随 complete=true 一起最终定稿。
SOURCE_COMMIT="${HYPIT_SOURCE_COMMIT:-$(git -C "$(dirname "$0")/.." rev-parse HEAD 2>/dev/null || echo unknown)}"
ENGINE_DIGEST="${HYPIT_ENGINE_DIGEST:-$(curl -sS --max-time 5 "${HYPIT_BACKEND_URL}/healthz" 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get("engineDigest","unknown"))' 2>/dev/null || echo unknown)}"
PG_VERSION="${HYPIT_PG_SERVER_VERSION:-$(psql "${PG_DSN}" -tAc 'SHOW server_version' 2>/dev/null | tr -d '[:space:]' || echo unknown)}"

PARTIAL="${OUTPUT_DIR}/manifest.json" SOURCE_COMMIT="${SOURCE_COMMIT}" \
ENGINE_DIGEST="${ENGINE_DIGEST}" PG_VERSION="${PG_VERSION}" LEASE_ID="${LEASE_ID}" \
python3 - <<'PYEOF'
import datetime, json, os

path = os.environ["PARTIAL"]
with open(path, encoding="utf-8") as handle:
    manifest = json.load(handle)
manifest["sourceCommit"] = os.environ["SOURCE_COMMIT"]
manifest["engineDigest"] = os.environ["ENGINE_DIGEST"]
manifest["pgServerVersion"] = os.environ["PG_VERSION"]
manifest["createdAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
# C107F2-38（round-15 实录）：维护窗内快照意味着归档 umbrella 里的 broker 命令库
# 必然带着本租约行——恢复出的 broker 启动即 fail-closed 拒新命令。把 leaseId 记入
# manifest，恢复侧凭它退出维护窗（broker 起后 POST /internal/v1/maintenance/exit）。
manifest["maintenance"] = {
    "leaseId": os.environ.get("LEASE_ID", ""),
    "note": "维护窗内快照：归档内 broker 命令库含本租约行；恢复后 broker 启动 fail-closed，"
            "须凭此 leaseId 调 /internal/v1/maintenance/exit 后方可接受新命令",
}
manifest["complete"] = True
tmp = path + ".complete.tmp"
with open(tmp, "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PYEOF

log "完成：${OUTPUT_DIR}（complete=true，数据伞=${UMBRELLA}）"
