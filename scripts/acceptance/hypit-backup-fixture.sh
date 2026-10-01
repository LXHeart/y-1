#!/usr/bin/env bash
# hypit-backup-fixture.sh — C107F2-33（W203）：可重建的隔离备份 fixture。
#
# 产出（<target-dir>/）：
#   data/                      备份伞（backup.sh 的 HYPIT_DATA_ROOT 指向 data/hypit）
#     hypit/bridge/…           broker 持久状态样本
#     projects/p-0001/…        两 revision（rev-1 文本 + rev-2 冻结快照）
#     resources/…              二进制资源（PNG 魔数开头，非文本字节）
#     results/…                结果归档文件
#     credentials/…            受控加密凭据样本（0600；占位内容非真实秘密）
#     runner-slots/ cache/     不应备份的临时面（manifest 明确 omitted）
#   bin/pg_dump, bin/psql      受控桩：确定性字节输出（不触任何真实 PG）
#   bin/fail-tar               注入桩：TC-F2-33-03 用 PATH 前置使 tar 中途失败
#
# 用法： scripts/acceptance/hypit-backup-fixture.sh <target-dir>
# 只写 <target-dir>，绝不访问用户实际卷/真实数据库。
set -euo pipefail

TARGET="${1:?usage: hypit-backup-fixture.sh <target-dir>}"
rm -rf "${TARGET}"
mkdir -p "${TARGET}/data/hypit/bridge" "${TARGET}/bin"

# --- projects：两 revision（真实快照布局 revisions/<n>/ + manifest 格式） ----
# 与生产写入侧对齐（round-11 实录勘误）：broker 快照恒落 revisions/<n>/，内含
# main.svrun 字节闭包 + manifest.json（y1.hypit-workspace-manifest@1，pretty 写盘）。
mkdir -p "${TARGET}/data/projects/p-0001/revisions/1" \
         "${TARGET}/data/projects/p-0001/revisions/2" \
         "${TARGET}/data/projects/p-0001/work"
printf 'run main (rev1)\n' > "${TARGET}/data/projects/p-0001/revisions/1/main.svrun"
printf 'run main (rev2)\n' > "${TARGET}/data/projects/p-0001/revisions/2/main.svrun"
write_snapshot_manifest() { # <snapshot-dir>：按生产 manifest.ts 算法写 manifest.json
  python3 - "$1" <<'PY'
import hashlib, json, os, sys

snapshot = sys.argv[1]
data = open(os.path.join(snapshot, "main.svrun"), "rb").read()
manifest = {"format": "y1.hypit-workspace-manifest@1",
            "entries": [{"path": "main.svrun", "sha256": hashlib.sha256(data).hexdigest(),
                         "sizeBytes": len(data)}]}
with open(os.path.join(snapshot, "manifest.json"), "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY
}
canonical_manifest_hash() { # <manifest.json>：broker manifestHash() 同款 canonical
  python3 - "$1" <<'PY'
import hashlib, json, sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
canonical = json.dumps({"format": manifest["format"],
                        "entries": [[e["path"], e["sha256"], e["sizeBytes"]] for e in manifest["entries"]]},
                       separators=(",", ":"), ensure_ascii=False)
print(hashlib.sha256(canonical.encode("utf-8")).hexdigest())
PY
}
write_snapshot_manifest "${TARGET}/data/projects/p-0001/revisions/1"
write_snapshot_manifest "${TARGET}/data/projects/p-0001/revisions/2"
head -c 2048 /dev/urandom > "${TARGET}/data/projects/p-0001/work/asset.bin"

# --- resources：二进制资源（PNG 魔数开头） -----------------------------------
mkdir -p "${TARGET}/data/resources"
{ printf '\x89PNG\r\n\x1a\n'; head -c 4090 /dev/urandom; } \
  > "${TARGET}/data/resources/res-0123456789abcdef-0.png"

# --- results：结果归档 --------------------------------------------------------
mkdir -p "${TARGET}/data/results/b-0001"
printf 'output final.video placeholder bytes\n' > "${TARGET}/data/results/b-0001/final.video"

# --- credentials：受控加密凭据样本（0600；占位，非真实秘密） -------------------
mkdir -p "${TARGET}/data/credentials"
printf 'ENVELOPED-PLACEHOLDER-NOT-A-REAL-SECRET\n' > "${TARGET}/data/credentials/provider-key.enc"
chmod 600 "${TARGET}/data/credentials/provider-key.enc"

# --- broker 持久状态样本 ------------------------------------------------------
printf 'bridge state sample\n' > "${TARGET}/data/hypit/bridge/bridge.sqlite"

# --- 不应备份的临时面（backup.sh 必须明确 omitted） ---------------------------
mkdir -p "${TARGET}/data/runner-slots/slot-9" "${TARGET}/data/cache"
head -c 512 /dev/urandom > "${TARGET}/data/runner-slots/slot-9/scratch.bin"
head -c 512 /dev/urandom > "${TARGET}/data/cache/tmp.bin"

# --- 受控桩：pg_dump/psql（确定性输出；不触真实 PG） --------------------------
cat > "${TARGET}/bin/pg_dump" <<'EOF'
#!/usr/bin/env bash
# 受控桩：确定性字节（含 DSN 引用标记），布局与真 pg_dump -Fc 一致地落 --file。
dsn=""
out=""
prev=""
for arg in "$@"; do
  case "${prev}" in
    --file) out="${arg}" ;;
  esac
  case "${arg}" in
    --file) prev="${arg}"; continue ;;
  esac
  prev=""
  case "${arg}" in --*) ;; *) [ -z "${dsn}" ] && dsn="${arg}" ;; esac
done
printf 'PGDMP-FAKE-C33 for %s\n%s\n' "${dsn}" "$(cat "$(dirname "$0")/../dump-payload.txt")" > "${out}"
EOF
printf 'fixture-db-reference: hypit@(isolated-fixture)\nrevision evidence: p-0001 rev-1..rev-2\n' \
  > "${TARGET}/dump-payload.txt"
mkdir -p "${TARGET}/pg-fixture"
# 恢复核验用的真实 schema 行（V91：number/snapshot_handle/manifest_hash）。
# snapshot_handle 用生产三形态中的两种：rev1=模板 provision 的工程根句柄、
# rev2=apply 快照句柄（绝对 /data 路径）；rev-99 行指向被删除的快照（TC-F2-34-03：
# 核验必须报缺失类别+计数，禁止 READY）。manifest_hash 按 broker canonical 算法
# 算出（不是 manifest.json 文件字节 sha256——pretty 写盘，字节比对必错）。
REV1_SHA="$(canonical_manifest_hash "${TARGET}/data/projects/p-0001/revisions/1/manifest.json")"
REV2_SHA="$(canonical_manifest_hash "${TARGET}/data/projects/p-0001/revisions/2/manifest.json")"
cat > "${TARGET}/pg-fixture/revisions.tsv" <<EOF
p-0001|2|2|/data/projects/p-0001/revisions/2|${REV2_SHA}
p-0001|1|1|/data/projects/p-0001|${REV1_SHA}
p-0001|99|99|/data/projects/p-0001/revisions/99|${REV1_SHA}
EOF
cat > "${TARGET}/bin/psql" <<'EOF'
#!/usr/bin/env bash
# 受控桩：SHOW server_version 与恢复核验查询（真实 V91 列）；失败注入走
# HYPIT_FIXTURE_PSQL_FAIL=1（TC-F2-34-02：SQL 异常必须非零，不得 missing=0 冒充）。
args=" $* "
if [ -n "${HYPIT_FIXTURE_PSQL_FAIL:-}" ]; then
  echo "psql-stub: injected SQL failure" >&2
  exit 1
fi
case "$args" in
  *"SHOW server_version"*) printf '16.2-fixture\n' ;;
  *"snapshot_handle"*)
    # HYPIT_FIXTURE_PSQL_TSV 可指向无缺失行的替代清单（READY 路径，TC-F2-34-01）。
    if [ -n "${HYPIT_FIXTURE_PSQL_TSV:-}" ]; then cat "${HYPIT_FIXTURE_PSQL_TSV}"; else cat "$(dirname "$0")/../pg-fixture/revisions.tsv"; fi ;;
  *) printf '' ;;
esac
EOF
cat > "${TARGET}/bin/pg_restore" <<'EOF'
#!/usr/bin/env bash
# 受控桩：成功空操作（布局校验 --dbname/--exit-on-error 存在即可）。
exit 0
EOF
chmod +x "${TARGET}/bin/pg_dump" "${TARGET}/bin/psql" "${TARGET}/bin/pg_restore"

# --- 注入桩：中途失败的 tar（TC-F2-33-03） ------------------------------------
cat > "${TARGET}/bin/fail-tar" <<'EOF'
#!/usr/bin/env bash
# 模拟压缩/打包进程中途崩溃：先正常启动（让上游开始写）再立即非零退出。
echo "fail-tar: injected archive failure (C107F2-33 TC-F2-33-03)" >&2
exit 42
EOF
chmod +x "${TARGET}/bin/fail-tar"

echo "fixture ready: ${TARGET}"
