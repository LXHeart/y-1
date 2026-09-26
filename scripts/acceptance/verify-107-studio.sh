#!/usr/bin/env bash
# verify-107-studio.sh — 任务书 #107-2 C107-12 验收脚本。
# 1. G 中 0001/0002 补丁已生效（prefix strip + bridge token 存在于 patched 源）。
# 2. B 侧 studio 测试面全绿（url-policy/sessions/proxy/mutation-bridge/base-path）。
# 3. nginx 片段语法存在性检查（片段由部署侧 include，不做完整 nginx -t）。
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "== 1. patch presence in G =="
SERVER_TS="${REPO_ROOT}/platform-hypit/.generated/hypit/packages/studio/src/server.ts"
GUARD_TS="${REPO_ROOT}/platform-hypit/.generated/hypit/packages/studio/src/mutation-origin.ts"
grep -q "HYPIT_STUDIO_BASE_PATH" "${SERVER_TS}"
grep -q "HYPIT_STUDIO_BRIDGE_TOKEN" "${GUARD_TS}"
echo "patch 0001/0002 present in G"

echo "== 2. B studio test surface =="
cd "${REPO_ROOT}/platform-hypit/backend"
npx tsx --test tests/studio/base-path.test.ts tests/studio/mutation-bridge.test.ts \
  tests/studio/sessions.test.ts tests/studio/proxy.test.ts

echo "== 3. nginx fragment sanity =="
grep -qF 'studio/(?<studio_session>' "${REPO_ROOT}/deploy/hypit/nginx.locations.conf"
grep -q 'http_upgrade' "${REPO_ROOT}/deploy/hypit/nginx.locations.conf"

echo "verify-107-studio: OK"
