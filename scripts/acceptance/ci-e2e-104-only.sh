#!/usr/bin/env bash
# 任务书 #104 C104-10（V09）：本书专用真实栈 e2e 入口。
#
# 复用 scripts/ci-e2e.sh 的全套隔离栈生命周期（波次拉栈/健康等待/播种/每引擎重置），
# 执行本书两个专用 spec：
#   - task104-lifecycle.spec.ts   组织清理真实栈闭环（R01/R02；retention 下限 1 天按
#                                 仓库 fixture 口径 DB 拨秒，此后由真实 worker 推进）
#   - task104-browser.spec.ts     三入口 12+2 视觉矩阵与共享 HTTP 错误跨入口（R07）
#
# 默认三引擎（chromium firefox webkit，遵循 E2E_ENGINES 约定）；单引擎结果只能 PARTIAL。
# 截图输出 E2E_SHOT_DIR（默认 test-artifacts/task-104/screenshots/e2e）；清单证据
# TASK104_EVIDENCE_DIR。只操作本入口管理的合成栈/卷。
set -euo pipefail

export E2E_SPECS="tests/e2e/task104-lifecycle.spec.ts tests/e2e/task104-browser.spec.ts"
export E2E_SHOT_DIR="${E2E_SHOT_DIR:-test-artifacts/task-104/screenshots/e2e}"
export TASK104_EVIDENCE_DIR="${TASK104_EVIDENCE_DIR:-test-artifacts/task-104/e2e-runs/$(date -u +%Y%m%dT%H%M%SZ)}"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_STAGED_STARTUP=1
mkdir -p "$E2E_SHOT_DIR" "$TASK104_EVIDENCE_DIR"

ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
if [[ "$ENGINES" != "chromium firefox webkit" ]]; then
  echo "[ci-e2e-104-only] PARTIAL：引擎为「${ENGINES}」而非三引擎全量，结果只能标 PARTIAL" >&2
fi

exec bash "$(dirname "$0")/../ci-e2e.sh"
