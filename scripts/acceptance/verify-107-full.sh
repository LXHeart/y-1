#!/usr/bin/env bash
# verify-107-full.sh — 任务书 #107-3 C107-23/C107-24（V14/V21 入口之一）。
#
# 分层验证：
#   [阶段 0] 始终执行：三层 compose config（V12）+ 部署契约测试。
#   [阶段 1..] 仅 HYPIT_FULL_E2E=1 时执行（真实启动/程序准备/最小渲染/
#              恶意组件隔离实跑/备份恢复到新卷）。未开启时各阶段显式
#              记录 NOT_RUN 与解除条件，不静默、不伪成功。
#
# 用法： bash scripts/acceptance/verify-107-full.sh
# 输出： test-artifacts/task-107/C23/verify-full.log 与摘要行
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
ART="test-artifacts/task-107/C23"
mkdir -p "${ART}"
LOG="${ART}/verify-full.log"
FULL="${HYPIT_FULL_E2E:-0}"
FAILED=0
NOT_RUN=0

log()  { printf '%s\n' "$*" | tee -a "${LOG}"; }
pass() { log "PASS  $*"; }
fail() { log "FAIL  $*"; FAILED=1; }
note() { log "NOTE  $*"; }

log "# verify-107-full $(date -u +%Y-%m-%dT%H:%M:%SZ) HYPIT_FULL_E2E=${FULL}"

# ───────────────────────── 阶段 0：静态契约（始终） ─────────────────────────
source_env='/tmp/verify-107-prod-env'
if [ ! -f "${source_env}" ]; then
  # 生产 overlay 的 :? 变量以 dummy 满足插值（config 不做真实连接）。
  {
    grep -oE '\$\{[A-Z0-9_]+:\?' docker-compose.production.yml docker-compose.yml 2>/dev/null \
      | grep -oE '[A-Z0-9_]+' | sort -u | while read -r var; do
        printf 'export %s=%s\n' "${var}" "$([ "${var#*_FILE}" != "${var}" ] && echo /tmp/dummy || echo dummy)"
      done
    echo 'export CRYPTO_KEK_BASE64=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
  } > "${source_env}"
fi
# shellcheck disable=SC1090
source "${source_env}"

if docker compose -f docker-compose.yml -f docker-compose.production.yml config -q >>"${LOG}" 2>&1; then
  pass "compose config: base+production"
else
  fail "compose config: base+production"
fi
if HYPIT_INTERNAL_TOKEN=dummy-token-0123456789abcdef \
   docker compose -f docker-compose.yml -f docker-compose.production.yml \
                  -f deploy/hypit/compose.production.yml config -q >>"${LOG}" 2>&1; then
  pass "compose config: +deploy/hypit/compose.production.yml"
else
  fail "compose config: +deploy/hypit/compose.production.yml"
fi
if HYPIT_INTERNAL_TOKEN=dummy-token-0123456789abcdef \
   docker compose -f docker-compose.yml -f docker-compose.production.yml \
                  -f deploy/hypit/compose.production.yml -f deploy/hypit/compose.full.yml config -q >>"${LOG}" 2>&1; then
  pass "compose config: +deploy/hypit/compose.full.yml"
else
  fail "compose config: +deploy/hypit/compose.full.yml"
fi
if npx vitest run tests/deployment/hypit-compose.contract.test.ts tests/deployment/hypit-entrypoint.contract.test.ts >>"${LOG}" 2>&1; then
  pass "deployment contract tests"
else
  fail "deployment contract tests"
fi

# ───────────────────────── 阶段 1+：真实启动（需 HYPIT_FULL_E2E=1） ─────────────────────────
if [ "${FULL}" != "1" ]; then
  NOT_RUN=1
  note "NOT_RUN[1] 真实启动（镜像构建/doctor/Programs prepare/最小渲染/转写/OpenCV/下载工具）：需 HYPIT_FULL_E2E=1（首次构建需下载 Node/base 镜像与浏览器缓存）"
  note "NOT_RUN[2] 恶意组件隔离实跑（读宿主路径/读 token env/连 Java internal/写 Distribution）：需 HYPIT_FULL_E2E=1；B 侧静态/单机用例由 V04 覆盖（runner-isolation.test.ts）"
  note "NOT_RUN[3] 备份→新卷恢复演练（backup.sh/restore.sh + 恢复后核对）：需 HYPIT_FULL_E2E=1 且本机 Docker 可用"
  note "解除条件：HYPIT_FULL_E2E=1 bash scripts/acceptance/verify-107-full.sh（需 Docker 与外网或预热缓存）"
else
  # 1) 镜像构建
  if docker build -f deploy/hypit/Dockerfile.backend -t grassland/hypit-backend:verify . >>"${LOG}" 2>&1; then
    pass "image build: Dockerfile.backend"
  else
    fail "image build: Dockerfile.backend"
  fi
  if docker build -f deploy/hypit/Dockerfile.runner -t grassland/hypit-runner:verify . >>"${LOG}" 2>&1; then
    pass "image build: Dockerfile.runner"
  else
    fail "image build: Dockerfile.runner"
  fi
  # 2) 启动（profile hypit）
  export HYPIT_INTERNAL_TOKEN="test-verify-token-0123456789abcdef"
  export HYPIT_ENABLED=true
  if docker compose -f docker-compose.yml -f docker-compose.production.yml \
                    -f deploy/hypit/compose.production.yml -p hypit-verify up -d hypit-backend hypit-author-runner >>"${LOG}" 2>&1; then
    pass "compose up: hypit profile services"
  else
    fail "compose up: hypit profile services"
  fi
  # readiness 分层：health 只读探测（installed→configured→prepared→healthy 由 broker 端点返回）
  for _ in $(seq 1 30); do
    if curl -sf http://127.0.0.1:9240/health >/dev/null 2>&1; then break; fi
    sleep 2
  done
  if curl -sf http://127.0.0.1:9240/health >>"${LOG}" 2>&1; then
    pass "broker /health read-only"
  else
    fail "broker /health read-only"
  fi
  # 3) 最小真实执行（渲染/转写/OpenCV/下载工具）与恶意组件隔离实跑：
  if (cd platform-hypit/backend && npm test >>"${REPO_ROOT}/${LOG}" 2>&1); then
    pass "B 全量测试（含真实浏览器渲染与恶意组件隔离用例）"
  else
    fail "B 全量测试（含真实浏览器渲染与恶意组件隔离用例）"
  fi
  # 4) 备份 → 新卷恢复演练
  BK="${ART}/backup-$$"
  if bash deploy/hypit/backup.sh "${BK}" >>"${LOG}" 2>&1; then
    pass "backup.sh 在线备份"
    if bash deploy/hypit/restore.sh "${BK}" "${BK}/restore-target" >>"${LOG}" 2>&1; then
      pass "restore.sh 新目录恢复 + 核对报告"
    else
      fail "restore.sh 新目录恢复 + 核对报告"
    fi
  else
    fail "backup.sh 在线备份"
  fi
  # 5) 清理（停 overlay 保留数据卷）
  docker compose -f docker-compose.yml -f docker-compose.production.yml \
                 -f deploy/hypit/compose.production.yml -p hypit-verify down >>"${LOG}" 2>&1 || true
fi

if [ "${FAILED}" != 0 ]; then
  log "# 结果：HAS-FAILURES"
  exit 1
fi
if [ "${NOT_RUN}" != 0 ]; then
  # 约定：缺环境返回非零并写明哪项（上方 NOT_RUN[n]），不空 skip、不冒充 PASS。
  log "# 结果：GREEN-WITH-NOT-RUN（阶段0 全绿；完整阶段未执行——见 NOT_RUN[n]）"
  exit 2
fi
log "# 结果：ALL-GREEN"
exit 0
