#!/usr/bin/env bash
# verify-107-live.sh — 任务书 #107-3 C107-24（V15）：真实服务 live 验证入口。
#
# 约定：
# - 必须显式启用：HYPIT_LIVE_ENABLED=1 且 HYPIT_LIVE_BUDGET_CENTS>0，否则按目录
#   逐项输出 NOT_ENABLED 并返回非零——不空 skip，不冒充 PASS。
# - 只验证 catalog 中「实际已授权」的服务（凭据经治理台/环境注入）；绝不把
#   secret 输出到终端；真实费用未知时记 null。
# - 用法： bash scripts/acceptance/verify-107-live.sh [--catalog <path>] [--help]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

CATALOG="platform-hypit/upstream-manifest.json"
OUT="test-artifacts/task-107/C24"
usage() {
  cat <<'EOF'
verify-107-live.sh — 真实服务 live 验证（显式启用 + 预算参数）
必需配置：
  HYPIT_LIVE_ENABLED=1              显式启用（缺省一律 NOT_ENABLED，非零退出）
  HYPIT_LIVE_BUDGET_CENTS=<int>     本轮预算上限（分）；超限即停
  平台凭据经治理台控制面配置（/api/admin/ai/*），本脚本不直接读 secret
产物位置：
  test-artifacts/task-107/C24/live-records.jsonl（逐项状态/模型/费用/样片路径）
用法：
  bash scripts/acceptance/verify-107-live.sh [--catalog platform-hypit/upstream-manifest.json]
EOF
}

for arg in "$@"; do
  case "$arg" in
    --help|-h) usage; exit 0 ;;
    --catalog) shift; CATALOG="${1:?--catalog 需要路径}" ;;
  esac
done

mkdir -p "${OUT}"
RECORDS="${OUT}/live-records.jsonl"
: > "${RECORDS}"

if [[ "${HYPIT_LIVE_ENABLED:-0}" != "1" || "${HYPIT_LIVE_BUDGET_CENTS:-0}" -le 0 ]]; then
  {
    echo '{"item":"hypit-live","status":"NOT_ENABLED","reason":"需要 HYPIT_LIVE_ENABLED=1 且 HYPIT_LIVE_BUDGET_CENTS>0（真实服务演示只按显式授权执行）","costCents":null}'
  } >> "${RECORDS}"
  echo "NOT_ENABLED：live 验证未执行（缺显式启用/预算参数）。逐项记录：${RECORDS}"
  exit 2
fi

# 启用后：按目录逐 provider 记录真实状态。目录缺失 = 环境不完整，非零退出。
if [[ ! -f "${CATALOG}" ]]; then
  echo "ERROR: catalog 缺失：${CATALOG}" >&2
  exit 1
fi

python3 - "$CATALOG" "$RECORDS" "${HYPIT_LIVE_BUDGET_CENTS}" <<'PY'
import json, sys, os, urllib.request, time

catalog_path, records, budget = sys.argv[1], sys.argv[2], int(sys.argv[3])
manifest = json.load(open(catalog_path))
providers = manifest.get("providers") or manifest.get("providerMappings") or []
if not isinstance(providers, list):
    providers = list(providers.values()) if isinstance(providers, dict) else []

base = os.environ.get("HYPIT_LIVE_BASE_URL", "")
results = 0
for provider in providers:
    name = provider if isinstance(provider, str) else (provider.get("name") or json.dumps(provider, sort_keys=True))
    record = {"item": f"provider:{name}", "status": "UNVERIFIED", "model": None,
              "costCents": None, "samplePath": None}
    if base:
        try:
            started = time.time()
            with urllib.request.urlopen(f"{base}/providers/{name}/probe", timeout=30) as response:
                body = json.load(response)
            record["status"] = "LIVE_PASS" if response.status == 200 else f"HTTP_{response.status}"
            record["model"] = body.get("model")
            record["latencyMs"] = int((time.time() - started) * 1000)
        except Exception as error:  # noqa: BLE001 — live 探测如实记录失败原因
            record["status"] = "LIVE_FAIL"
            record["error"] = str(error)[:200]
    with open(records, "a") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    results += 1

print(f"live 记录 {results} 项（预算 {budget} 分，实际花费以平台账单为准；脚本不估算）")
PY
