#!/usr/bin/env bash
# 数字人 S1 三浏览器验收 runner（任务书 #105E C105E-06 / V105E-06-02）。
#
# 从仓库根调用。复用 scripts/ci-e2e.sh 的完整隔离生命周期（构建/up/seed/每引擎重置/teardown），
# 只叠加 DH 测试面：DH_E2E=1（compose 扩展点默认关闭，旧入口不受影响）+ 短命 mTLS 证书 +
# DH 专用 spec。真实 provider 强制 false（Fake runtime；开放真实渲染归 H）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export E2E_SPECS="tests/e2e/digital-human-workbench.spec.ts"
export E2E_WORKERS="${E2E_WORKERS:-1}"
export E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"
export DH_E2E=1
export DH_REAL_PROVIDERS_ENABLED=false
# S1 隔离栈开启 DH 边缘路由（生产/常规栈默认 false 不变；catalog 业务开关仍由 dh_catalog 决定）。
export EDGE_ROUTE_DIGITAL_HUMAN_INTELLIGENCE=true
export EDGE_ROUTE_ADMIN_DIGITAL_HUMAN_INTELLIGENCE=true

# 短命证书就位（幂等；只写 test-artifacts/ 本地目录，不入库）。
bash scripts/acceptance/task-105-test-certificates.sh

exec bash scripts/ci-e2e.sh
