#!/usr/bin/env bash
# 任务书 #98 C98-06（§12.2 V98-06）：真实栈整栈集成验收——单引擎 CI 变体。
#
# 参照 ci-e2e-92-only.sh 先例（E2E_ENGINES=chromium + spec 限定）：只跑本任务
# task98-full-chain.spec.ts（全链状态机 / rlid 归因 / 退款边界 / 三端待办 / 金额守恒 /
# 双主题截图）。栈拉起、播种、DNS pinning 全部复用 scripts/ci-e2e.sh。
#
# 与全量 e2e 的唯一差异 = 时间窗压缩（compose 环境变量，reset_stack 时生效）：
#   - MARKETPLACE_SETTLEMENT_DISPUTE_WINDOW_SECONDS=0 / CONFIRMATION_WINDOW_SECONDS=5
#     / SETTLEMENT_DAY_SECONDS=2：任务观察期与确认窗口即刻过（ci-e2e.sh 既有缺省）；
#   - MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE=15：commerce 订单冷静期
#     缩到秒级，结算后退款闸门可在用例时限内验证；
#   - E2E_SHOT_DIR：AC-98-30 双主题截图输出目录（spec 内判定，缺省零开销）。
#
# 用法（CI/长跑环境；本地 macOS 跑不了完整栈，不得在本地标 PASS）：
#   bash scripts/acceptance/ci-e2e-98-only.sh
set -euo pipefail

export E2E_ENGINES=chromium
export E2E_SPECS="tests/e2e/task98-full-chain.spec.ts"
export MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE="${MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE:-15}"
export E2E_SHOT_DIR="${E2E_SHOT_DIR:-test-artifacts/task-98/shots/e2e}"
mkdir -p "$E2E_SHOT_DIR"

exec bash "$(dirname "$0")/../ci-e2e.sh"
