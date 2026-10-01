#!/usr/bin/env bash
# hypit-compose.sh — 107-fix-2 C107F2-02（W017）：唯一 Compose 组合与启停入口。
#
# 关闭 F01：frontend/Edge/Java 与 Hypit（及 DH）必须经同一组合规则启动/重建，
# 消除「一套栈两套配置」的覆盖 404。默认 fail-closed（§3 D-02、RULE-01）：
#   - 生产组合固定次序：docker-compose.yml → docker-compose.production.yml
#     → deploy/digital-human/compose.production.yml → deploy/hypit/compose.production.yml
#     → [ --full 追加 deploy/hypit/compose.full.yml ]。
#     Hypit overlay 恒在组合内（共享服务 labels 恒含 overlay），启用由 --enable-hypit 显式决定。
#   - 隔离测试组合（--test）：docker-compose.yml → [--enable-dh 追加
#     deploy/digital-human/compose.test.yml] → [--enable-hypit 追加
#     deploy/hypit/compose.test.yml]；专用工程名/端口/一次性密钥（§9.3）。
#   - --enable-hypit 前置预检：HYPIT_INTERNAL_TOKEN 与 HYPIT_STUDIO_TICKET_SECRET
#     必须 ≥32 字符；缺项/过短立即非零退出，不执行任何 docker 变更（TC-F2-02-04）。
#   - 共享服务重建防覆盖预检（up/build 前）：既有容器 labels 里的 overlay 组合
#     不得被本次组合静默移除；显式 --disable-hypit/--disable-dh 才允许停用并打印
#     受影响模块（TC-F2-02-03）。
#
# 用法：
#   bash scripts/acceptance/hypit-compose.sh <command> [options]
#   command = plan | config | precheck | up | down | ps | logs | build | seed-accounts
#   options = --enable-hypit | --disable-hypit | --enable-dh | --disable-dh | --full
#             --test | --project-name NAME | --env-file FILE | --json | --quiet | --help
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"
ORIGINAL_ARGS=("$@")
source "$ROOT_DIR/scripts/lib/local-stack.sh"

# ── 组合定义（固定次序；F01 修复的核心） ──────────────────────────────────
BASE_FILE="docker-compose.yml"
PROD_FILE="docker-compose.production.yml"
DH_PROD_FILE="deploy/digital-human/compose.production.yml"
HYPIT_PROD_FILE="deploy/hypit/compose.production.yml"
HYPIT_FULL_FILE="deploy/hypit/compose.full.yml"
DH_TEST_FILE="deploy/digital-human/compose.test.yml"
HYPIT_TEST_FILE="deploy/hypit/compose.test.yml"

TEST_PROJECT_DEFAULT="y1-hypit-fix2-e2e"
PROD_PROJECT_DEFAULT="y-1"

usage() {
  cat <<'USAGE'
hypit-compose.sh — 唯一 Compose 组合与启停入口（107-fix-2 C02 / D-02）
  plan            输出可审核执行计划（组合次序/启用状态/必需密钥名/受影响服务），不执行变更
  config          输出解析后的 compose 配置（--json 为 JSON；--quiet 只校验）
  precheck        只跑防覆盖预检（labels 对比既有容器组合）
  up|build        启动/构建（自动先跑 precheck；up 追加 --wait）
  stop SERVICE... 停止明确服务，保留数据卷
  reset           仅 fresh 隔离验收会话可重置自己新建的资源
  ps|logs         透传
  seed-accounts   （--test）向隔离库注入合成 e2e 账号（复用 scripts/e2e-seed.ts）
  --enable-hypit  显式启用 Hypit（profile+flags+Java enable+Studio upstream+密钥预检）
  --disable-hypit 显式停用（打印受影响模块后从组合移除 overlay；禁止静默）
  --enable-dh / --disable-dh  数字人 overlay 同理
  --full          生产组合追加 compose.full.yml
  --test          隔离测试组合（工程名默认 y1-hypit-fix2-e2e，端口 18080/18081/18082）
  --project-name NAME / --env-file FILE / --json / --quiet / --help
USAGE
}

# ── 参数解析 ─────────────────────────────────────────────────────────────
CMD=""
ENABLE_HYPIT=0; DISABLE_HYPIT=0; ENABLE_DH=0; DISABLE_DH=0; FULL=0; TEST=0
PROJECT_NAME=""; ENV_FILE_ARG=""; JSON_OUT=0; QUIET=0; PASS_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --enable-hypit) ENABLE_HYPIT=1; shift ;;
    --disable-hypit) DISABLE_HYPIT=1; shift ;;
    --enable-dh) ENABLE_DH=1; shift ;;
    --disable-dh) DISABLE_DH=1; shift ;;
    --full) FULL=1; shift ;;
    --test) TEST=1; shift ;;
    --project-name) PROJECT_NAME="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE_ARG="${2:-}"; shift 2 ;;
    --json) JSON_OUT=1; shift ;;
    --quiet|-q) QUIET=1; shift ;;
    --volumes|--wait|--no-build|--remove-orphans) PASS_ARGS+=("$1"); shift ;;
    --help|-h) usage; exit 0 ;;
    -*) printf '未知参数: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    *) if [ -n "$CMD" ]; then PASS_ARGS+=("$1"); else CMD="$1"; fi; shift ;;
  esac
done
case "${CMD}" in
  plan|config|precheck|up|stop|reset|down|ps|logs|build|seed-accounts) ;;
  "") printf '缺少命令\n' >&2; usage >&2; exit 2 ;;
  *) printf '未知命令: %s\n' "${CMD}" >&2; usage >&2; exit 2 ;;
esac
if [ "${ENABLE_HYPIT}" -eq 1 ] && [ "${DISABLE_HYPIT}" -eq 1 ]; then
  printf '矛盾参数：--enable-hypit 与 --disable-hypit 互斥\n' >&2; exit 2
fi
if [ "${ENABLE_DH}" -eq 1 ] && [ "${DISABLE_DH}" -eq 1 ]; then
  printf '矛盾参数：--enable-dh 与 --disable-dh 互斥\n' >&2; exit 2
fi
if [ "${TEST}" -eq 1 ] && [ "${FULL}" -eq 1 ]; then
  printf '矛盾参数：--test 与 --full 互斥\n' >&2; exit 2
fi

PROJECT_NAME="${PROJECT_NAME:-$([ "${TEST}" -eq 1 ] && echo "${TEST_PROJECT_DEFAULT}" || echo "${PROD_PROJECT_DEFAULT}")}"

# ── 组合文件清单（固定次序） ─────────────────────────────────────────────
COMPOSE_FILES=("${BASE_FILE}")
HYPIT_IN_COMBO=1; DH_IN_COMBO=1
if [ "${TEST}" -eq 1 ]; then
  # 隔离测试组合：DH/Hypit overlay 只随显式启用叠加（缺省=纯 base，业务面全关）。
  # --disable-* 在 test 模式等价缺省（本来就不叠），显式旗标只用于打印受影响模块。
  DH_IN_COMBO="${ENABLE_DH}"; HYPIT_IN_COMBO="${ENABLE_HYPIT}"
  # down 是拆除操作：必须覆盖工程全部可能的 overlay 组合，否则启用期创建的
  # hypit/dh 容器会残留为孤儿（TC-F2-02-02「down 后应无容器」）。
  if [ "${CMD}" = "down" ]; then DH_IN_COMBO=1; HYPIT_IN_COMBO=1; fi
  if [ "${DH_IN_COMBO}" -eq 1 ]; then COMPOSE_FILES+=("${DH_TEST_FILE}"); fi
  if [ "${HYPIT_IN_COMBO}" -eq 1 ]; then
    COMPOSE_FILES+=("${HYPIT_TEST_FILE}")
    # C107F2-37：journey 受控文本模型 fixture——env 显式开启才叠加（测试栈专用）。
    if [ "${HYPIT_FIX2_TEXT_FIXTURE:-0}" = "1" ]; then
      COMPOSE_FILES+=("tests/e2e/fixtures/hypit-fix2-model.compose.yml")
    fi
  fi
else
  # 生产组合：overlay 恒在（labels 修复 F01），显式 --disable 才移除。
  if [ "${DISABLE_DH}" -eq 1 ]; then DH_IN_COMBO=0; fi
  if [ "${DISABLE_HYPIT}" -eq 1 ]; then HYPIT_IN_COMBO=0; fi
  COMPOSE_FILES+=("${PROD_FILE}")
  [ "${DH_IN_COMBO}" -eq 1 ] && COMPOSE_FILES+=("${DH_PROD_FILE}")
  [ "${HYPIT_IN_COMBO}" -eq 1 ] && COMPOSE_FILES+=("${HYPIT_PROD_FILE}")
  [ "${FULL}" -eq 1 ] && COMPOSE_FILES+=("${HYPIT_FULL_FILE}")
fi

# ── Edge 旗标名集合（从 hypit 生产 overlay 解析，单一事实源） ─────────────
HYPIT_EDGE_FLAGS=()
while IFS= read -r name; do HYPIT_EDGE_FLAGS+=("${name}"); done < <(
  grep -oE 'EDGE_ROUTE_HYPIT_[A-Z0-9_]+' "${HYPIT_PROD_FILE}" | sort -u
)

# ── 启用前置预检：密钥完整且 ≥32 字符（TC-F2-02-04；不打印任何值） ────────
check_enable_secrets() {
  local missing=()
  local token="${HYPIT_INTERNAL_TOKEN:-}"
  local ticket="${HYPIT_STUDIO_TICKET_SECRET:-}"
  if [ "${TEST}" -eq 1 ]; then
    # 隔离栈允许 wrapper 生成一次性值（不落仓库/日志）；显式提供时仍校验长度。
    [ "${#token}" -lt 32 ] && token="$(openssl rand -hex 24)" && export HYPIT_INTERNAL_TOKEN="${token}"
    [ "${#ticket}" -lt 32 ] && ticket="$(openssl rand -hex 24)" && export HYPIT_STUDIO_TICKET_SECRET="${ticket}"
    return 0
  fi
  [ -z "${token}" ] && missing+=("HYPIT_INTERNAL_TOKEN(未设置)")
  [ "${#token}" -gt 0 ] && [ "${#token}" -lt 32 ] && missing+=("HYPIT_INTERNAL_TOKEN(长度 ${#token} < 32)")
  [ -z "${ticket}" ] && missing+=("HYPIT_STUDIO_TICKET_SECRET(未设置)")
  [ "${#ticket}" -gt 0 ] && [ "${#ticket}" -lt 32 ] && missing+=("HYPIT_STUDIO_TICKET_SECRET(长度 ${#ticket} < 32)")
  if [ "${#missing[@]}" -gt 0 ]; then
    printf 'PRECHECK-FAIL --enable-hypit 密钥预检未通过（未执行任何 docker 变更）：\n' >&2
    local m; for m in "${missing[@]}"; do printf '  - %s\n' "${m}" >&2; done
    printf '生产密钥必须显式提供（≥32 字符），本入口不生成生产默认密钥。\n' >&2
    return 1
  fi
  return 0
}

apply_enable_env() {
  # 启用态环境（生产组合）：Java enable、全部 Edge 旗标、Studio upstream。
  export HYPIT_ENABLED=true
  export HYPIT_SIDECAR_BASE_URL="${HYPIT_SIDECAR_BASE_URL:-http://hypit-backend:9240}"
  export HYPIT_STUDIO_UPSTREAM="${HYPIT_STUDIO_UPSTREAM:-hypit-backend:9240}"
  local f; for f in "${HYPIT_EDGE_FLAGS[@]}"; do export "${f}=true"; done
}
apply_disable_env() {
  # 缺省 fail-closed：Java 关、业务旗标全 false、Studio upstream 空。
  # 隔离栈例外：capabilities 读路由保持可读——禁用态的可用性自述面
  # （TC-F2-02-02「capabilities 表示 disabled（登录可读）」）。生产组合保持
  # overlay 自身默认（false），不静默改生产姿态。
  export HYPIT_ENABLED=false
  export HYPIT_STUDIO_UPSTREAM=""
  if [ "${TEST}" -eq 1 ]; then export EDGE_ROUTE_HYPIT_CAPABILITIES_READ=true; fi
  local f
  for f in "${HYPIT_EDGE_FLAGS[@]}"; do
    if [ "${TEST}" -eq 1 ] && [ "${f}" = "EDGE_ROUTE_HYPIT_CAPABILITIES_READ" ]; then continue; fi
    export "${f}=false"
  done
}

if [ "${ENABLE_HYPIT}" -eq 1 ]; then
  check_enable_secrets || exit 1
  apply_enable_env
else
  apply_disable_env
fi

case "${CMD}" in
  up|build|seed-accounts) local_stack_enter "$PROJECT_NAME" "$ROOT_DIR/scripts/acceptance/hypit-compose.sh" --docker -- "${ORIGINAL_ARGS[@]}" ;;
  stop) local_stack_enter "$PROJECT_NAME" "$ROOT_DIR/scripts/acceptance/hypit-compose.sh" -- "${ORIGINAL_ARGS[@]}" ;;
  down) printf '禁止无边界 down/删卷；请用 stop SERVICE...，全新隔离验收由 reset 收尾。\n' >&2; exit 2 ;;
  reset) node "$ROOT_DIR/scripts/local-stack.mjs" reset --project "$PROJECT_NAME"; exit $? ;;
esac

# ── 隔离测试组合的环境（镜像 ci-e2e.sh 的隔离栈一次性密钥；仅 --test） ────
# 一次性密钥持久在 test-artifacts（本机、git-ignored）：同一隔离工程跨多次
# wrapper 调用（up/seed/up）密钥稳定，避免二次 up 因 env 漂移重建容器换钥。
TEST_ENV_FILE="${TEST_ENV_FILE:-test-artifacts/task-107/fix2/isolated-stack.env}"
load_test_env_file() {
  [ -f "${TEST_ENV_FILE}" ] || return 0
  local line key val
  while IFS= read -r line; do
    case "$line" in ''|\#*) continue ;; esac
    key="${line%%=*}"; val="${line#*=}"
    [ -n "${!key:-}" ] || export "$key"="$val"
  done < "${TEST_ENV_FILE}"
}
save_test_env_file() {
  local key
  mkdir -p "$(dirname "${TEST_ENV_FILE}")"
  : > "${TEST_ENV_FILE}"; chmod 600 "${TEST_ENV_FILE}"
  for key in \
    E2E_PASSWORD E2E_ADMIN_PASSWORD DOUYIN_PROXY_TOKEN_SECRET BILIBILI_PROXY_TOKEN_SECRET \
    MINIO_ROOT_PASSWORD MINIO_SECRET_KEY PLATFORM_AI_E2E_API_KEY IDENTITY_ACCESS_TOKEN_SECRET \
    CRYPTO_KEK_BASE64 SESSION_SECRET TRUST_EVIDENCE_PSEUDONYM_SECRET \
    IDENTITY_ASSERTION_KEY_EDGE_USER_IDENTITY IDENTITY_ASSERTION_KEY_EDGE_USER_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_EDGE_USER_FINANCE IDENTITY_ASSERTION_KEY_EDGE_USER_TRUST \
    IDENTITY_ASSERTION_KEY_EDGE_USER_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_TRUST \
    IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_INTELLIGENCE IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_IDENTITY \
    IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_TRUST IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_TRUST_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_TRUST_SERVICE_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY IDENTITY_ASSERTION_KEY_TRUST_SERVICE_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_MARKETPLACE IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_FINANCE \
    IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_IDENTITY \
    HYPIT_INTERNAL_TOKEN HYPIT_STUDIO_TICKET_SECRET \
    HYPIT_SESSION_TICKET_SECRET HYPIT_SESSION_ASSERTION_SECRET; do
    [ -n "${!key:-}" ] && printf '%s=%s\n' "$key" "${!key}" >> "${TEST_ENV_FILE}"
  done
}
load_test_env_file
setup_test_env() {
  export SERVICE_JAVA_TOOL_OPTIONS="${SERVICE_JAVA_TOOL_OPTIONS:--Xmx512m}"
  export E2E_KAFKA_HEAP_OPTS="${E2E_KAFKA_HEAP_OPTS:--Xmx384m -Xms128m}"
  export FRONTEND_PORT="${FRONTEND_PORT:-18080}" OPS_FRONTEND_PORT="${OPS_FRONTEND_PORT:-18081}" AI_FRONTEND_PORT="${AI_FRONTEND_PORT:-18082}"
  export EDGE_BFF_PORT="${EDGE_BFF_PORT:-18085}" MINIO_PROXY_PORT="${MINIO_PROXY_PORT:-19002}"
  export LOCAL_DB_PORT="${LOCAL_DB_PORT:-15432}" KAFKA_PORT="${KAFKA_PORT:-19092}" REDIS_PORT="${REDIS_PORT:-16379}"
  export MINIO_API_PORT="${MINIO_API_PORT:-19000}" MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-19001}" TEMPORAL_GRPC_PORT="${TEMPORAL_GRPC_PORT:-17233}"
  export SETTLEMENT_DAY_SECONDS="${SETTLEMENT_DAY_SECONDS:-2}"
  export MARKETPLACE_SETTLEMENT_DISPUTE_WINDOW_SECONDS="${MARKETPLACE_SETTLEMENT_DISPUTE_WINDOW_SECONDS:-0}"
  export MARKETPLACE_SYSTEM_ACTOR_ACCOUNT_ID="${MARKETPLACE_SYSTEM_ACTOR_ACCOUNT_ID:-00000000-0000-0000-0000-000000000901}"
  export AI_APP_ORIGIN="http://127.0.0.1:${AI_FRONTEND_PORT}"
  export GRASSLAND_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}"
  export E2E_EMAIL="${E2E_EMAIL:-e2e-ci@test.local}"
  # C107F2-19/20 起 compose.test 硬性要求会话票据/断言密钥（:? 内插）；与其它
  # 一次性密钥同法生成并持久（跨 up/seed/up 稳定，避免重建容器换钥）。
  export HYPIT_SESSION_TICKET_SECRET="${HYPIT_SESSION_TICKET_SECRET:-$(openssl rand -hex 24)}"
  export HYPIT_SESSION_ASSERTION_SECRET="${HYPIT_SESSION_ASSERTION_SECRET:-$(openssl rand -hex 24)}"
  export E2E_PASSWORD="${E2E_PASSWORD:-E2e!$(openssl rand -hex 16)}"
  export E2E_DISPLAY_NAME="${E2E_DISPLAY_NAME:-CI E2E User}"
  export E2E_ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-e2e-admin-ci@test.local}"
  export E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-Admin!$(openssl rand -hex 16)}"
  export E2E_ADMIN_DISPLAY_NAME="${E2E_ADMIN_DISPLAY_NAME:-CI E2E Admin}"
  export PUBLIC_FORWARDED_PROTO=http
  export TRUSTED_PROXY_CIDR=127.0.0.1/32
  export FRONTEND_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}"
  export PUBLIC_BACKEND_ORIGIN="$FRONTEND_ORIGIN"
  export CORS_ORIGIN="$FRONTEND_ORIGIN"
  export MINIO_PUBLIC_BASE_URL="http://127.0.0.1:${MINIO_PROXY_PORT}"
  export SESSION_COOKIE_SECURE=never
  export SESSION_COOKIE_SAME_SITE=Lax
  export LOCAL_DB_USER="${LOCAL_DB_USER:-grassland}" LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-grassland}" LOCAL_DB_NAME="${LOCAL_DB_NAME:-grassland}"
  export DATABASE_URL='postgresql://grassland:grassland@postgres-local:5432/grassland'
  export NODE_ENV=production
  export DOUYIN_USER_AGENT='CI E2E'
  export BILIBILI_USER_AGENT='CI E2E'
  export DOUYIN_PROXY_TOKEN_SECRET="${DOUYIN_PROXY_TOKEN_SECRET:-$(openssl rand -hex 32)}"
  export BILIBILI_PROXY_TOKEN_SECRET="${BILIBILI_PROXY_TOKEN_SECRET:-$(openssl rand -hex 32)}"
  export MINIO_ROOT_USER="${MINIO_ROOT_USER:-ci-minio}"
  export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-$(openssl rand -hex 32)}"
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-ci-media-runtime}"
  export MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-$(openssl rand -hex 20)}"
  export AI_DNS_PINNING_TRUSTED_DOMAINS="${AI_DNS_PINNING_TRUSTED_DOMAINS:-qwen-e2e.invalid=127.0.0.1}"
  export PLATFORM_AI_E2E_BASE_URL="${PLATFORM_AI_E2E_BASE_URL:-https://qwen-e2e.invalid/v1}"
  export PLATFORM_AI_E2E_API_KEY="${PLATFORM_AI_E2E_API_KEY:-$(openssl rand -hex 32)}"
  export PLATFORM_AI_E2E_ORIGIN="${PLATFORM_AI_E2E_ORIGIN:-https://qwen-e2e.invalid}"
  export E2E_SEED_PASSWORD="${E2E_PASSWORD}"
  export E2E_SEED_ADMIN_EMAIL='e2e-admin@test.local'
  export TEMPORAL_ENABLE_HTTPS=false TEMPORAL_MTLS_CERT_CHAIN_FILE= TEMPORAL_MTLS_KEY_FILE= TEMPORAL_MTLS_SERVER_NAME= TEMPORAL_NAMESPACE=default
  export TEMPORAL_DEADLOCK_DETECTION_TIMEOUT_MS="${TEMPORAL_DEADLOCK_DETECTION_TIMEOUT_MS:-60000}"
  export IDENTITY_SECURITY_LOGIN_RATE_LIMIT_IP_MAX=200
  export IDENTITY_SECURITY_LOGIN_RATE_LIMIT_ACCOUNT_IP_MAX=100
  export IDENTITY_KYB_MEDIA_VALIDATION_TIMEOUT_MS=10000
  rand_hex() { openssl rand -hex 32; }
  export IDENTITY_ACCESS_TOKEN_SECRET="${IDENTITY_ACCESS_TOKEN_SECRET:-$(rand_hex)}"
  export CRYPTO_KEK_BASE64="${CRYPTO_KEK_BASE64:-$(openssl rand -base64 32 | tr -d '\n')}"
  export SESSION_SECRET="${SESSION_SECRET:-$(rand_hex)}"
  export TRUST_EVIDENCE_PSEUDONYM_SECRET="${TRUST_EVIDENCE_PSEUDONYM_SECRET:-$(rand_hex)}"
  local key
  for key in \
    IDENTITY_ASSERTION_KEY_EDGE_USER_IDENTITY IDENTITY_ASSERTION_KEY_EDGE_USER_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_EDGE_USER_FINANCE IDENTITY_ASSERTION_KEY_EDGE_USER_TRUST \
    IDENTITY_ASSERTION_KEY_EDGE_USER_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_TRUST \
    IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_INTELLIGENCE IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_IDENTITY \
    IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_TRUST IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_TRUST_SERVICE_FINANCE IDENTITY_ASSERTION_KEY_TRUST_SERVICE_MARKETPLACE \
    IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY IDENTITY_ASSERTION_KEY_TRUST_SERVICE_INTELLIGENCE \
    IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_MARKETPLACE IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_FINANCE \
    IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_IDENTITY; do
    if [ -z "${!key:-}" ]; then printf -v "$key" '%s' "$(rand_hex)"; export "$key"; fi
  done
  # DH 隔离面：Fake runtime + 短命 mTLS 证书（幂等，只写 test-artifacts/）。
  if [ "${DH_IN_COMBO}" -eq 1 ]; then
    export DH_REAL_PROVIDERS_ENABLED=false
    export EDGE_ROUTE_DIGITAL_HUMAN_INTELLIGENCE="${EDGE_ROUTE_DIGITAL_HUMAN_INTELLIGENCE:-true}"
    export EDGE_ROUTE_ADMIN_DIGITAL_HUMAN_INTELLIGENCE="${EDGE_ROUTE_ADMIN_DIGITAL_HUMAN_INTELLIGENCE:-true}"
    bash scripts/acceptance/task-105-test-certificates.sh >/dev/null 2>&1
  fi
  # 端口占用预检：只报告，不杀进程（§9.3）。仅首次起栈检查——本工程容器已
  # 在监听这些端口属正常复用（up 幂等），不算占用冲突。
  local existing
  existing="$(docker ps -q --filter "label=com.docker.compose.project=${PROJECT_NAME}" 2>/dev/null || true)"
  if [ -z "${existing}" ]; then
    local p; for p in "${FRONTEND_PORT}" "${OPS_FRONTEND_PORT}" "${AI_FRONTEND_PORT}" "${LOCAL_DB_PORT}"; do
      if nc -z 127.0.0.1 "${p}" 2>/dev/null; then
        printf 'PRECHECK-FAIL 端口 127.0.0.1:%s 已被占用（先核对归属并协调释放；禁止换端口另起第二套栈）\n' "${p}" >&2
        return 1
      fi
    done
  fi
}
[ "${TEST}" -eq 1 ] && { setup_test_env || exit 1; save_test_env_file; }

# ── compose 调用 ─────────────────────────────────────────────────────────
dc() {
  local -a args=(--project-name "${PROJECT_NAME}")
  if [ -n "${ENV_FILE_ARG}" ]; then args+=(--env-file "${ENV_FILE_ARG}")
  elif [ "${TEST}" -eq 1 ]; then args+=(--env-file /dev/null)
  else args+=(--env-file .env.docker.example); fi
  local f; for f in "${COMPOSE_FILES[@]}"; do args+=(-f "${ROOT_DIR}/${f}"); done
  if [ "${ENABLE_HYPIT}" -eq 1 ] && [ "${TEST}" -ne 1 ]; then args+=(--profile hypit); fi
  node "$ROOT_DIR/scripts/local-stack.mjs" compose "${args[@]}" -- "$@"
}

# ── 防覆盖预检（TC-F2-02-03） ────────────────────────────────────────────
# 既有容器的 labels 记录创建时的组合；本次组合缺了已启用 overlay 且未显式
# --disable → 非零拒绝；显式 disable → 打印受影响模块。
run_precheck() {
  local ids
  ids="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT_NAME}" 2>/dev/null || true)"
  [ -z "${ids}" ] && { [ "${QUIET}" -eq 1 ] || printf 'PRECHECK OK 工程无既有容器（%s）\n' "${PROJECT_NAME}"; return 0; }
  local existing_hypit=0 existing_dh=0 id label_files
  for id in ${ids}; do
    label_files="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "${id}" 2>/dev/null || true)"
    case "${label_files}" in
      *hypit/compose.*) existing_hypit=1 ;;
    esac
    case "${label_files}" in
      *digital-human/compose.*) existing_dh=1 ;;
    esac
  done
  local violations=()
  [ "${existing_hypit}" -eq 1 ] && [ "${HYPIT_IN_COMBO}" -eq 0 ] && [ "${DISABLE_HYPIT}" -eq 0 ] && violations+=("hypit")
  [ "${existing_dh}" -eq 1 ] && [ "${DH_IN_COMBO}" -eq 0 ] && [ "${DISABLE_DH}" -eq 0 ] && violations+=("digital-human")
  if [ "${#violations[@]}" -gt 0 ]; then
    printf 'PRECHECK-FAIL 工程 %s 的既有容器组合含以下 overlay，本次组合将静默移除（须显式 --disable-* 并确认受影响模块）：\n' "${PROJECT_NAME}" >&2
    local v; for v in "${violations[@]}"; do printf '  - %s（受影响：hypit-backend/hypit-author-runner/dh-* 及相关路由将停止）\n' "${v}" >&2; done
    return 1
  fi
  # 显式 disable 的受影响模块提示。
  if [ "${existing_hypit}" -eq 1 ] && [ "${DISABLE_HYPIT}" -eq 1 ]; then
    printf 'NOTE 显式停用 hypit：受影响模块 hypit-backend/hypit-author-runner；共享服务将以无 Hypit overlay 组合重建。\n'
  fi
  if [ "${existing_dh}" -eq 1 ] && [ "${DISABLE_DH}" -eq 1 ]; then
    printf 'NOTE 显式停用 digital-human：受影响模块 dh-runtime/dh-redis 及 DH 路由。\n'
  fi
  [ "${QUIET}" -eq 1 ] || printf 'PRECHECK OK 既有启用模块与本次组合一致（%s）\n' "${PROJECT_NAME}"
  return 0
}

print_plan() {
  printf '# hypit-compose 计划（不执行变更）\n'
  printf 'mode=%s project=%s\n' "$([ "${TEST}" -eq 1 ] && echo test || echo production)" "${PROJECT_NAME}"
  printf 'compose 次序：\n'
  local f; for f in "${COMPOSE_FILES[@]}"; do printf '  1. %s\n' "${f}"; done
  printf 'hypit=%s dh=%s full=%s\n' \
    "$([ "${ENABLE_HYPIT}" -eq 1 ] && echo enabled || echo disabled-fail-closed)" \
    "$([ "${DH_IN_COMBO}" -eq 1 ] && echo in-combo || echo out)" \
    "${FULL}"
  printf '启用密钥名（值不输出）：HYPIT_INTERNAL_TOKEN HYPIT_STUDIO_TICKET_SECRET\n'
  printf 'edge hypit 旗标数：%d（启用时全 true，缺省全 false）\n' "${#HYPIT_EDGE_FLAGS[@]}"
  printf 'Studio upstream=%s\n' "$([ "${ENABLE_HYPIT}" -eq 1 ] && echo hypit-backend:9240 || echo '(空=片段 404)')"
  if [ "${TEST}" -ne 1 ]; then
    printf '生产模式：本入口不操作正在运行的栈；up/build 前请人工审核以上计划。\n'
  fi
}

case "${CMD}" in
  plan) print_plan; exit 0 ;;
  precheck) run_precheck; exit $? ;;
  config)
    if [ "${JSON_OUT}" -eq 1 ]; then dc config --format json; else dc config ${QUIET:+-q}; fi
    exit $?
    ;;
  up)
    run_precheck || exit 1
    # 守卫强制显式服务，展开真实依赖，并逐个构建；不再固定启动五个 Java 服务。
    # HYPIT_COMPOSE_NO_BUILD=1：宿主对 registry 出网不可用（daemon DNS 污染实录）
    # 时的等价最小路径——镜像须已全部构建在本地（含最新 bootJar 产物）。CI 不设
    # 此变量，保持逐服务构建的完整校验。
    if [[ " ${PASS_ARGS[*]:-} " == *" --no-build "* || "${HYPIT_COMPOSE_NO_BUILD:-0}" = "1" ]]; then
      dc up -d ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
    else
      dc up -d --build ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
    fi
    exit $?
    ;;
  build)
    run_precheck || exit 1
    dc build ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
    exit $?
    ;;
  stop)
    dc stop ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
    exit $?
    ;;
  ps) dc ps ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}; exit $? ;;
  logs) dc logs --no-color ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}; exit $? ;;
  seed-accounts)
    [ "${TEST}" -eq 1 ] || { printf 'seed-accounts 仅限 --test 隔离工程\n' >&2; exit 2; }
    local_db_user="${LOCAL_DB_USER:-grassland}"
    local_db_password="${LOCAL_DB_PASSWORD:-grassland}"
    local_db_url="postgresql://${local_db_user}:${local_db_password}@127.0.0.1:${LOCAL_DB_PORT:-15432}/${LOCAL_DB_NAME:-grassland}"
    DATABASE_URL="${local_db_url}" npm run e2e:seed:auth >/dev/null
    DATABASE_URL="${local_db_url}" npx tsx scripts/e2e-seed.ts >/dev/null
    printf 'seed 完成（合成 e2e 账号；口令运行期注入，不入库）\n'
    exit $?
    ;;
esac
