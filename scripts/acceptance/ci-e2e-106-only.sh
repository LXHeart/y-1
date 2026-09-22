#!/usr/bin/env bash
# 任务书 #106 C106-06（V06）：本书专用真实栈 e2e 入口（复用 scripts/ci-e2e.sh 全套隔离栈）。
#
# 与 104 wrapper 的差别：默认独立 compose project y1-e2e-task106、证据目录指向 task-106。
# 直接 exec scripts/ci-e2e.sh（不经过会覆盖 E2E_SPECS 的 104 wrapper），执行：
#   - tests/e2e/task104-lifecycle.spec.ts   组织清理真实栈闭环（R01/R02）
#   - tests/e2e/task104-browser.spec.ts     三入口视觉矩阵与共享 HTTP 错误（R07；C106-07 加强版）
#
# 默认三引擎（chromium firefox webkit，遵循 E2E_ENGINES 约定）；单引擎结果只能 PARTIAL。
# 截图 E2E_SHOT_DIR 与清单证据 TASK104_EVIDENCE_DIR 落 task-106（沿用旧 fixture 环境变量名）。
# 只操作本入口管理的合成栈/卷。
set -euo pipefail

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-y1-e2e-task106}"
export E2E_SPECS="tests/e2e/task104-lifecycle.spec.ts tests/e2e/task104-browser.spec.ts"
export E2E_SHOT_DIR="${E2E_SHOT_DIR:-test-artifacts/task-106/screenshots/e2e}"
export TASK104_EVIDENCE_DIR="${TASK104_EVIDENCE_DIR:-test-artifacts/task-106/e2e-runs/$(date -u +%Y%m%dT%H%M%SZ)}"
# 通用 runner 仅通过 TASK103_EVIDENCE_DIR 按引擎归档 JUnit/失败截图；
# 对齐到本任务目录，避免下一引擎覆盖上一引擎的执行结果。
export TASK103_EVIDENCE_DIR="$TASK104_EVIDENCE_DIR"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_STAGED_STARTUP=1
mkdir -p "$E2E_SHOT_DIR" "$TASK104_EVIDENCE_DIR"

ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
if [[ "$ENGINES" != "chromium firefox webkit" ]]; then
  echo "[ci-e2e-106-only] PARTIAL：引擎为「${ENGINES}」而非三引擎全量，结果只能标 PARTIAL" >&2
fi

exec bash "$(dirname "$0")/../ci-e2e.sh"
