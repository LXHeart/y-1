#!/usr/bin/env bash
# 任务书 #103 C103-24/25（V15）：本书专用真实栈 e2e 入口。
#
# 复用 scripts/ci-e2e.sh 的全套隔离栈生命周期（波次拉栈/健康等待/播种/治理台控制面
# 三件套/每引擎重置栈），执行本书三个专用 spec 及委托的订单全链：
#   - task103-consistency.spec.ts      六组跨域业务不变量（C103-24）
#   - task103-ui.spec.ts               状态/主题/视口矩阵（C103-25）
#   - task103-dispute-lifecycle.spec.ts 争议真实路由/跨案竞态（C103-25）
#   - task98-full-chain.spec.ts       TC103-24-02 订单资金全链
#
# 默认三引擎（chromium firefox webkit，遵循 E2E_ENGINES 约定）；显式单引擎调试允许：
#   E2E_ENGINES=chromium bash scripts/acceptance/ci-e2e-103-only.sh
# 单引擎/双引擎运行结果须标 PARTIAL（三浏览器验收未齐），不得记为完整 V15 PASS。
#
# 与 98-only 同款时间窗压缩（compose 环境变量，reset_stack 时生效）：
#   - MARKETPLACE_SETTLEMENT_DISPUTE_WINDOW_SECONDS=0 / SETTLEMENT_DAY_SECONDS=2（ci-e2e.sh 缺省）
#   - MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE=15：commerce 冷静期秒级，
#     「结算后拒退 409 + refundBlockedReason」可在用例时限内验证；
#   - E2E_SHOT_DIR：C103-25 双主题×双视口截图输出目录。
#
# 外部服务边界：全部 Sandbox/测试适配器（AI 假端点 qwen-e2e.invalid + DNS pinning 固定表、
# 履约链接 example.com、无真实 PSP/公众号/SMTP）；真实渠道验收不在本入口内。
set -euo pipefail

export E2E_SPECS="tests/e2e/task103-consistency.spec.ts tests/e2e/task103-ui.spec.ts tests/e2e/task103-dispute-lifecycle.spec.ts tests/e2e/task98-full-chain.spec.ts"
export MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE="${MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE:-15}"
export E2E_SHOT_DIR="${E2E_SHOT_DIR:-test-artifacts/task-103/screenshots/e2e}"
# 三个 spec 文件共享同一隔离栈与种子账号（per-session 活动身份/内部断言），必须串行。
export E2E_WORKERS="${E2E_WORKERS:-1}"
# 复用既有分阶段启动，避免多个冷 JVM 争用同一桌面 runner；保留健康检查与超时。
export E2E_STAGED_STARTUP=1
export TASK103_EVIDENCE_DIR="${TASK103_EVIDENCE_DIR:-test-artifacts/task-103/e2e-runs/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$E2E_SHOT_DIR"

ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
if [[ "$ENGINES" != "chromium firefox webkit" ]]; then
  echo "[ci-e2e-103-only] PARTIAL：引擎为「${ENGINES}」而非三引擎全量，结果只能标 PARTIAL" >&2
fi

exec bash "$(dirname "$0")/../ci-e2e.sh"
