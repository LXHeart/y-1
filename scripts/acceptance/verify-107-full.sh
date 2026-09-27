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
        # dummy 需满足受验服务的凭据约束：MinIO 密码 >=8 且 access key <=20；
      # MINIO_ROOT_USER 与 MINIO_ACCESS_KEY 必须不同值（service account 禁止与
      # admin 同 key）——按变量名给可区分的 dummy。
      value="dummy-0123456789"
      case "${var}" in
        MINIO_ROOT_USER) value="dummy-root-0123" ;;
        MINIO_ACCESS_KEY) value="dummy-media-01" ;;
        # 数值/时间/URL 型门禁变量不能给字符串 dummy——会原样进容器 env
        # （Spring 绑定 management.tracing.sampling.probability 直接
        # NumberFormatException，intelligence-service 无限重启；107-4 C09 实跑暴露）。
        OTEL_TRACING_SAMPLING_PROBABILITY) value="0.1" ;;
        OTEL_EXPORTER_OTLP_TRACES_ENDPOINT) value="http://tempo:4317" ;;
        CONFIRMATION_WINDOW_SECONDS) value="900" ;;
        FINANCE_CREDITS_CENTS_POLICY_VERSION) value="verify-v1" ;;
        FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT) value="2026-01-01T00:00:00Z" ;;
        FINANCE_CREDITS_CENTS_POLICY_ROUNDING) value="HALF_UP" ;;
        FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR) value="1" ;;
        FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR) value="1" ;;
        FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION) value="100000000" ;;
      esac
      printf 'export %s=%s\n' "${var}" "$([ "${var#*_FILE}" != "${var}" ] && echo /tmp/${value} || echo ${value})"
      done
    echo 'export CRYPTO_KEK_BASE64=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx='
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
  # 2) 启动（profile hypit）。验证栈专用 overlay：
  #   - 生产 overlay 刻意不让 broker 暴露宿主端口（仅 compose 内网可达），而本脚本
  #     的 health 探测与 backup.sh 的 maintenance 端点都是宿主机视角 → 验证端口映射。
  #   - 数据根由命名卷换宿主 bind 目录：backup.sh/restore.sh 直接 tar/解包
  #     HYPIT_DATA_ROOT（命名卷宿主不可达；验证栈不叠 compose.full.yml，数据根
  #     下没有 programs/browsers 等可重建的程序环境卷，bind 无体积顾虑）。
  #   - 宿主 postgres 端口按 LOCAL_DB_PORT 语义探测避让（本机其他栈可能已占 55432）。
  export HYPIT_INTERNAL_TOKEN="test-verify-token-0123456789abcdef"
  export HYPIT_ENABLED=true
  VERIFY_DB_PORT="${LOCAL_DB_PORT:-55432}"
  if lsof -nP -iTCP:"${VERIFY_DB_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    VERIFY_DB_PORT=55433
    export LOCAL_DB_PORT="${VERIFY_DB_PORT}"
    note "55432 已被占用（本机其他栈），验证栈 postgres 宿主端口改用 ${VERIFY_DB_PORT}"
  fi
  VERIFY_BROKER_PORT=19240
  VERIFY_DATA_ROOT="${REPO_ROOT}/${ART}/hypit-data"
  mkdir -p "${VERIFY_DATA_ROOT}"
VERIFY_OVERLAY="${ART}/compose.verify-stack.yml"
cat > "${VERIFY_OVERLAY}" <<OVOL
# verify-107-full.sh 自动生成的验证栈 overlay（仅验证用，勿入生产）：
# - broker 映射宿主验证端口；状态伞 /data 换 bind 目录（兄弟目录约定要求父目录
#   可写，与生产 overlay 的 /data 卷挂载语义一致）供 backup/restore 宿主机演练。
# - intelligence 对冲生产 overlay 的外部 TLS 假设（SASL_SSL Kafka/Temporal mTLS
#   指向真实外部设施）：验证栈指回本地明文 kafka/temporal，mTLS 键置空、证书
#   bind 撤除（占位空目录会让 temporalWorkflowClient bean 解析失败无限重启）。
services:
  hypit-backend:
    ports:
      - "127.0.0.1:${VERIFY_BROKER_PORT}:9240"
    volumes: !override
      - ${VERIFY_DATA_ROOT}:/data
      - runner-sockets:/sockets
      - runner-slots:/slots
  intelligence-service:
    environment:
      TEMPORAL_TARGET: temporal:7233
      TEMPORAL_NAMESPACE: default
      TEMPORAL_ENABLE_HTTPS: "false"
      # mTLS 属性置空串仍算「已配置」会触发证书解析——指到一副一次性自签证书
      # （HTTPS=false 时仅解析不使用），避免占位空目录导致 bean 失败。
      SPRING_TEMPORAL_CONNECTION_MTLS_CERT_CHAIN_FILE: /run/secrets/verify/client.crt
      SPRING_TEMPORAL_CONNECTION_MTLS_KEY_FILE: /run/secrets/verify/client.key
      SPRING_TEMPORAL_CONNECTION_MTLS_SERVER_NAME: hypit-verify
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      KAFKA_SECURITY_PROTOCOL: PLAINTEXT
    volumes: !override
      - intelligence_media_data:/var/lib/grassland-media
      - type: bind
        source: ${VERIFY_DATA_ROOT}/../verify-certs
        target: /run/secrets/verify
        read_only: true
OVOL
  # 镜像对齐：本脚本 build 打的 tag 是 :verify，而 compose 默认引用
  # ${HYPIT_BACKEND_IMAGE:-...:latest}——不显式覆盖会用本地旧 :latest（可能缺
  # 构建期烘焙的 G 与 sockets 属主修复），出现过 .generated ENOENT / socket EACCES。
  export HYPIT_BACKEND_IMAGE="grassland/hypit-backend:verify"
  export HYPIT_RUNNER_IMAGE="grassland/hypit-runner:verify"
  if docker compose -f docker-compose.yml -f docker-compose.production.yml \
                    -f deploy/hypit/compose.production.yml -f "${VERIFY_OVERLAY}" \
                    -p hypit-verify up -d hypit-backend hypit-author-runner >>"${LOG}" 2>&1; then
    pass "compose up: hypit profile services"
  else
    fail "compose up: hypit profile services"
  fi
  # readiness 分层：health 只读探测。107-4 C09 勘误：端点为 /healthz（/health
  # 落到鉴权闸 401）。
  for _ in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:${VERIFY_BROKER_PORT}/healthz" >/dev/null 2>&1; then break; fi
    sleep 2
  done
  if curl -sf "http://127.0.0.1:${VERIFY_BROKER_PORT}/healthz" >>"${LOG}" 2>&1; then
    pass "broker /healthz read-only"
  else
    fail "broker /healthz read-only"
  fi
  # 3) 最小真实执行（渲染/转写/OpenCV/下载工具）与恶意组件隔离实跑：
  if (cd platform-hypit/backend && npm test >>"${REPO_ROOT}/${LOG}" 2>&1); then
    pass "B 全量测试（含真实浏览器渲染与恶意组件隔离用例）"
  else
    fail "B 全量测试（含真实浏览器渲染与恶意组件隔离用例）"
  fi
  # 4) 备份 → 新卷恢复演练（宿主机视角：pg 客户端 18 由 brew libpq 提供——
  #    psql/pg_dump 版本须 >= 服务端；maintenance 走 broker 验证端口映射）。
  #    restore.sh 不传 HYPIT_PG_DSN（演练数据根恢复+sha256+空目录保护；
  #    目标库为空的 PG 恢复由调用方另行保证，见脚本头注释）。
  BK="${ART}/backup-$$"
  if [ -d /opt/homebrew/opt/libpq/bin ]; then
    PATH="/opt/homebrew/opt/libpq/bin:${PATH}"
  else
    note "未找到 brew libpq——pg_dump 不可用将导致备份阶段失败"
  fi
  if HYPIT_BACKEND_URL="http://127.0.0.1:${VERIFY_BROKER_PORT}" \
     HYPIT_PG_DSN="postgresql://grassland:grassland@127.0.0.1:${VERIFY_DB_PORT}/grassland" \
     HYPIT_DATA_ROOT="${VERIFY_DATA_ROOT}" \
     bash deploy/hypit/backup.sh "${BK}" >>"${LOG}" 2>&1; then
    pass "backup.sh 在线备份"
    # restore 目标目录名必须与备份 tar 顶层一致（hypit-data）——解包才落在
    # 目标上；否则内容落到旁名目录、目录核对空转（restore.sh 隐含契约）。
    if HYPIT_DATA_ROOT="${VERIFY_DATA_ROOT}" \
       bash deploy/hypit/restore.sh "${BK}" "${BK}/restore-target/hypit-data" >>"${LOG}" 2>&1; then
      pass "restore.sh 新目录恢复 + 核对报告"
    else
      fail "restore.sh 新目录恢复 + 核对报告"
    fi
  else
    fail "backup.sh 在线备份"
  fi
  # 5) 清理（停验证栈；bind 数据根保留在 ART 下供人工核查/复跑恢复演练）
  docker compose -f docker-compose.yml -f docker-compose.production.yml \
                 -f deploy/hypit/compose.production.yml -f "${VERIFY_OVERLAY}" \
                 -p hypit-verify down >>"${LOG}" 2>&1 || true
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
