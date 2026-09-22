#!/usr/bin/env bash
# 任务书 #106 C106-06（D06 / V05）：三引擎真实双页 cache/轮询 harness 一命令入口。
#
# - 启动/停止测试专用 Vite（task-106.vite.config.ts：固定 loopback + strictPort）；
#   Playwright webServer 自管生命周期，测试结束自动关闭自身 server。
# - 先做端口占用对照（TC106-06-05）：strictPort 下被占端口必须让入口明确失败（非零），
#   不连接陌生已有服务；探针自身只占用/释放自己创建的监听。
# - 默认三引擎（chromium firefox webkit，遵循 E2E_ENGINES）；单引擎结果只能 PARTIAL。
# - 结果 JSON 分引擎输出（后引擎不覆盖前引擎）；证据落 test-artifacts/task-106/harness/。
set -euo pipefail
cd "$(dirname "$0")/../.."

export TASK106_HARNESS_PORT="${TASK106_HARNESS_PORT:-18190}"
EVIDENCE_DIR="${TASK106_EVIDENCE_DIR:-test-artifacts/task-106/harness}"
mkdir -p "$EVIDENCE_DIR"

echo "[task-106-browser] 端口占用对照（strictPort 须失败，不连陌生服务）：port=${TASK106_HARNESS_PORT}"
PORT_PROBE_LOG="${EVIDENCE_DIR}/port-probe.log"
if node scripts/local/task-106-port-probe.mjs >"$PORT_PROBE_LOG" 2>&1; then
  echo "[task-106-browser] 端口对照通过（被占时入口非零退出）"
else
  echo "[task-106-browser] 端口对照失败：strictPort 入口在占用时未按预期失败" >&2
  cat "$PORT_PROBE_LOG" >&2
  exit 1
fi

ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
if [[ "$ENGINES" != "chromium firefox webkit" ]]; then
  echo "[task-106-browser] PARTIAL：引擎为「${ENGINES}」而非三引擎全量，结果只能标 PARTIAL" >&2
fi

status=0
for engine in $ENGINES; do
  echo "[task-106-browser] 运行引擎 ${engine}"
  if ! E2E_TASK106_RESULTS="${EVIDENCE_DIR}/results-${engine}.json" \
    npx playwright test --config tests/e2e/fixtures/task-106.playwright.config.ts \
      --project="${engine}" 2>&1 | tee "${EVIDENCE_DIR}/run-${engine}.log"; then
    status=1
    echo "[task-106-browser] 引擎 ${engine} 失败" >&2
  fi
done

if [[ "$status" -ne 0 ]]; then
  echo "[task-106-browser] 存在失败引擎，整体非零" >&2
fi
exit "$status"
