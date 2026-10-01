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

# C107F2-37/C107F2-39（W219）：journey 与 c36（工作区全状态验收）都属 V-09
# 浏览器层必需 spec——c36 缺席会被 stage e2e 的预期 TC 发现数门禁拦下。
export E2E_SPECS="${E2E_SPECS:-tests/e2e/hypit-fix2-journey.spec.ts tests/e2e/hypit-fix2-c36.spec.ts tests/e2e/hypit-entrypoints.spec.ts tests/e2e/hypit-clone.spec.ts tests/e2e/hypit-recovery.spec.ts tests/e2e/hypit-studio.spec.ts}"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
export HYPIT_E2E=1
# 一次性令牌（栈内 broker ↔ Java；不落仓库）。可用环境覆盖以便跨 job 复用。
export HYPIT_INTERNAL_TOKEN="${HYPIT_INTERNAL_TOKEN:-hypit-e2e-internal-0123456789abcdef}"
export HYPIT_STUDIO_TICKET_SECRET="${HYPIT_STUDIO_TICKET_SECRET:-hypit-e2e-ticket-secret-0123456789abcdef}"
export HYPIT_SESSION_TICKET_SECRET="${HYPIT_SESSION_TICKET_SECRET:-hypit-e2e-session-ticket-0123456789ab}"
export HYPIT_SESSION_ASSERTION_SECRET="${HYPIT_SESSION_ASSERTION_SECRET:-hypit-e2e-session-assert-0123456789ab}"
# C107F2-37：受控文本模型 fixture 叠加（journey 三浏览器；真实编译/渲染/PG 不受影响）。
# token 固定为与 fixtures/hypit-fix2.ts 同源的静态值：fresh 栈每轮重建，容器与
# spec 在同一次进程内取同一 env；跨 job 复用旧栈时治理台存量凭据也同 key。
export HYPIT_FIX2_TEXT_FIXTURE=1
export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-fix2-journey-fixture-key}"

exec bash scripts/ci-e2e.sh
