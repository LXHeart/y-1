#!/usr/bin/env bash
# ci-e2e-107.sh — 任务书 #107-3 C107-24（V13 / CI hypit-local-e2e job 入口）。
#
# 从仓库根调用。复用 scripts/ci-e2e.sh 的完整隔离生命周期（构建/up/seed/每引擎
# 重置/trap 清理），叠加 Hypit 测试面：
#   HYPIT_E2E=1                → 叠加 deploy/hypit/compose.test.yml（真实 Node broker）
#   EDGE_ROUTE_HYPIT_*         → edge 最小旗标子集（清单内置于 compose.test.yml）
#   HYPIT_INTERNAL_TOKEN 等    → broker 与 Java 共享令牌（一次性值，不入库）
# 无外部收费：真实 provider 强制缺省（渲染走本地模板/无 key 链，TC107-24-06 基础链）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export E2E_SPECS="${E2E_SPECS:-tests/e2e/hypit-entrypoints.spec.ts tests/e2e/hypit-clone.spec.ts tests/e2e/hypit-recovery.spec.ts tests/e2e/hypit-studio.spec.ts}"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
export HYPIT_E2E=1
# 一次性令牌（栈内 broker ↔ Java；不落仓库）。可用环境覆盖以便跨 job 复用。
export HYPIT_INTERNAL_TOKEN="${HYPIT_INTERNAL_TOKEN:-hypit-e2e-internal-0123456789abcdef}"
export HYPIT_STUDIO_TICKET_SECRET="${HYPIT_STUDIO_TICKET_SECRET:-hypit-e2e-ticket-secret-0123456789abcdef}"

exec bash scripts/ci-e2e.sh
