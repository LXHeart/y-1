#!/usr/bin/env bash
# 数字人 H 阶段全量矩阵 runner（任务书 #105H C105H-02 / V105H-02-03）。
#
# 从仓库根调用。固定运行四组阶段 spec（workbench/recording/governance/lifecycle）；
# engine 缺省 "chromium firefox webkit" 串行，每引擎独立隔离重置与 seed（ci-e2e.sh 语义）；
# 复用 ci-e2e.sh 完整隔离生命周期（构建/up/seed/每引擎重置/teardown），DH_E2E=1 叠加
# 隔离 Fake runtime；真实 provider 恒 false（开放真实渲染归 H03 条件门禁）。
# 失败保产物并非零退出（ci-e2e.sh 对任一引擎失败即 exit 非零；不 passWithNoTests）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export E2E_SPECS="tests/e2e/digital-human-workbench.spec.ts tests/e2e/digital-human-recording.spec.ts tests/e2e/digital-human-governance.spec.ts tests/e2e/digital-human-lifecycle.spec.ts"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
export DH_E2E=1
export DH_REAL_PROVIDERS_ENABLED=false
# S1 隔离栈开启 DH 边缘路由（生产/常规栈默认 false 不变；业务开关仍由 dh_catalog 决定）。
export EDGE_ROUTE_DIGITAL_HUMAN_INTELLIGENCE=true
export EDGE_ROUTE_ADMIN_DIGITAL_HUMAN_INTELLIGENCE=true

# 短命证书就位（幂等；只写 test-artifacts/ 本地目录，不入库）。
bash scripts/acceptance/task-105-test-certificates.sh

exec bash scripts/ci-e2e.sh
