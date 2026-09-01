#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE=""
OUTPUT=""
source "$ROOT_DIR/scripts/lib/dotenv.sh"

usage() {
  cat <<'EOF'
Usage: scripts/validate-production-compose.sh [--env-file PATH] [--output PATH]

Renders the base Compose file with the production overlay and verifies that local Kafka and
Temporal are absent, external TLS settings and credential mounts are present, and all public
application services retain readiness gates.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?missing env file}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }
if [[ -n "$ENV_FILE" ]]; then
  load_dotenv "$ENV_FILE" || exit 1
fi

args=(--project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" -f "$ROOT_DIR/docker-compose.production.yml")
[[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
json="$(docker compose "${args[@]}" config --format json)"
[[ -n "$OUTPUT" ]] && printf '%s\n' "$json" > "$OUTPUT"

fail() { echo "ERROR: $*" >&2; exit 1; }
service_exists() { jq -e --arg service "$1" '.services[$service] != null' >/dev/null <<< "$json"; }
env_value() { jq -r --arg service "$1" --arg name "$2" '.services[$service].environment[$name] // empty' <<< "$json"; }
healthcheck() { jq -r --arg service "$1" '(.services[$service].healthcheck.test // []) | join(" ")' <<< "$json"; }
resource_value() { jq -r --arg service "$1" --arg kind "$2" --arg resource "$3" '.services[$service].deploy.resources[$kind][$resource] // empty' <<< "$json"; }

for service in kafka temporal; do
  service_exists "$service" && fail "production compose must not include local service: $service"
done

for service in edge-bff identity-service marketplace-service finance-service trust-service intelligence-service; do
  service_exists "$service" || fail "production compose is missing application service: $service"
  [[ "$(healthcheck "$service")" == *'/actuator/health/readiness'* ]] \
    || fail "$service must retain /actuator/health/readiness healthcheck"
  [[ "$(healthcheck "$service")" != *'bash'* ]] \
    || fail "$service healthcheck must not require bash in the Alpine runtime image"
  [[ "$(env_value "$service" OTEL_TRACING_ENABLED)" == true ]] \
    || fail "$service must enable tracing in production"
  [[ "$(env_value "$service" OTEL_EXPORT_ENABLED)" == true ]] \
    || fail "$service must enable OTLP trace export in production"
  sampling_probability="$(env_value "$service" OTEL_TRACING_SAMPLING_PROBABILITY)"
  [[ "$sampling_probability" =~ ^(0([.][0-9]+)?|1([.]0+)?)$ ]] \
    || fail "$service must define OTEL_TRACING_SAMPLING_PROBABILITY between 0 and 1"
  otlp_endpoint="$(env_value "$service" OTEL_EXPORTER_OTLP_TRACES_ENDPOINT)"
  [[ "$otlp_endpoint" =~ ^https://[^/[:space:]]+/.+ ]] \
    || fail "$service must use an explicit HTTPS OTLP traces endpoint"
  [[ "$otlp_endpoint" != *localhost* && "$otlp_endpoint" != *127.0.0.1* && "$otlp_endpoint" != *0.0.0.0* ]] \
    || fail "$service OTLP traces endpoint must not be local"
done

for service in frontend database-bootstrap edge-bff identity-service marketplace-service finance-service trust-service intelligence-service; do
  for kind in limits reservations; do
    cpus="$(resource_value "$service" "$kind" cpus)"
    memory="$(resource_value "$service" "$kind" memory)"
    [[ "$cpus" =~ ^[0-9]+([.][0-9]+)?$ && "$cpus" != 0 ]] \
      || fail "$service must define deploy.resources.$kind.cpus"
    # `docker compose config --format json` normalizes memory units to bytes.
    [[ "$memory" =~ ^[1-9][0-9]*$ ]] \
      || fail "$service must define deploy.resources.$kind.memory"
  done
done
[[ "$(healthcheck frontend)" == *'http://127.0.0.1/health'* ]] \
  || fail "frontend must retain /health healthcheck"

for service in identity-service marketplace-service finance-service trust-service intelligence-service; do
  [[ "$(env_value "$service" KAFKA_SECURITY_PROTOCOL)" == SASL_SSL ]] \
    || fail "$service must use Kafka SASL_SSL"
  [[ "$(env_value "$service" KAFKA_SASL_MECHANISM)" == SCRAM-SHA-512 ]] \
    || fail "$service must use Kafka SCRAM-SHA-512"
  [[ "$(env_value "$service" KAFKA_SSL_TRUSTSTORE_LOCATION)" == /run/secrets/grassland/kafka-truststore.p12 ]] \
    || fail "$service must mount the production Kafka truststore"
  jq -e --arg service "$service" \
    '.services[$service].volumes[]? | select(.target == "/run/secrets/grassland/kafka-truststore.p12" and .read_only == true)' \
    >/dev/null <<< "$json" || fail "$service must read-only mount the production Kafka truststore"
done

finance_psp_mode="$(env_value finance-service FINANCE_PSP_MODE)"
[[ -n "$finance_psp_mode" ]] \
  || fail "finance-service must receive FINANCE_PSP_MODE in the production overlay"
[[ "$finance_psp_mode" != sandbox ]] \
  || fail "finance-service production PSP adapter must not be Sandbox"

# 任务书 #64 卡2：视频 provider/凭据/模型收口治理台 video_generation 行（单秒价读价目表），
# overlay 不再透传任何 env 型视频渠道配置；残留即 fail（见下方封禁清单）。
# 图片生成计价线（任务书 #59 收尾）：两份模板一直声明这两项，但 compose 从未透传、application.yml
# 也写死字面量，设了等于没设。补齐透传后在此钉住，防再次被摘掉又无人察觉。
for name in IMAGE_GENERATION_PRICING_VERSION IMAGE_GENERATION_UNIT_PRICE_CENTS; do
  [[ -n "$(env_value intelligence-service "$name")" ]] \
    || fail "intelligence-service must receive $name in the production overlay"
done
# 任务书 #58：模型端点/凭据/受信端点已收口治理台控制面（platform_model_config 等表），
# QWEN_*/AI_SPEECH_*/AI_EMBEDDING_* 不再进入任何环境——overlay 若仍带这些变量直接 fail（防旧
# secret 文件残留造成「看起来还在用 env」的错觉）。
#
# 任务书 #59 追加 *_ANALYSIS_PROVIDER / *_ANALYSIS_MODEL / IMAGE_GENERATION_* 一族：这些变量早已
# 没有读取方（provider/model 由控制面解析），但值都写着 qwen/qwen-plus。留在 overlay 里最危险的
# 不是失效，而是运维照着改：治理台把 qwen 换成协议方言名后，来这里「同步」一下，什么也不会发生，
# 却以为已经切换完成。
#
# #59 收尾再追加 Express 时代 per-user 视频分析设置的一族（COZE_ANALYSIS_* / QWEN_ANALYSIS_* /
# VIDEO_ANALYSIS_API_*）与 IMAGE_GENERATION_PLATFORM_MODEL_VERSION / ANALYSIS_SETTINGS_ALLOW_REMOTE_WRITE：
# Java 侧零绑定类。其中 VIDEO_ANALYSIS_API_TOKEN 还是历史泄漏凭据之一（见 GL-P0-SEC-001），
# 封禁顺带保证它不会被重新塞回 overlay。
# 任务书 #64 卡2 追加 VIDEO_GENERATION_MODE/BASE_URL/API_KEY/MODEL/WEBHOOK_SECRET：视频渠道
# 全量走治理台 video_generation 行 + 价目表，这五个 env 已无读取方（create/poll/retrieve-path
# 与 sandbox 计价字段仍由 yml 绑定，属运行时参数不在封禁之列）。
for name in QWEN_BASE_URL QWEN_API_KEY QWEN_MODEL AI_SPEECH_API_KEY AI_EMBEDDING_API_KEY \
    BILIBILI_ANALYSIS_PROVIDER DOUYIN_ANALYSIS_PROVIDER KYB_DOCUMENT_ANALYSIS_PROVIDER \
    KYB_DOCUMENT_ANALYSIS_MODEL IMAGE_GENERATION_PROVIDER IMAGE_GENERATION_MODEL \
    IMAGE_GENERATION_BASE_URL IMAGE_GENERATION_API_KEY \
    IMAGE_GENERATION_PLATFORM_MODEL_VERSION ANALYSIS_SETTINGS_ALLOW_REMOTE_WRITE \
    COZE_ANALYSIS_BASE_URL COZE_ANALYSIS_API_TOKEN QWEN_ANALYSIS_BASE_URL \
    QWEN_ANALYSIS_API_KEY QWEN_ANALYSIS_MODEL VIDEO_ANALYSIS_API_BASE_URL \
    VIDEO_ANALYSIS_API_PATH VIDEO_ANALYSIS_API_TOKEN VIDEO_ANALYSIS_API_TIMEOUT_MS \
    VIDEO_GENERATION_MODE VIDEO_GENERATION_BASE_URL VIDEO_GENERATION_API_KEY \
    VIDEO_GENERATION_MODEL VIDEO_GENERATION_WEBHOOK_SECRET; do
  [[ -z "$(env_value intelligence-service "$name")" ]] \
    || fail "intelligence-service must NOT receive $name anymore (task #58: configure the AI control plane instead)"
done
[[ "$(env_value intelligence-service AI_PROVIDER_ALLOW_SANDBOX)" == false ]] \
  || fail "intelligence-service must disable Sandbox AI providers in production"
# 平台凭据改为信封加密落库后，KEK 对 intelligence 由可选变事实必选（决策 E）。
kek="$(env_value intelligence-service CRYPTO_KEK_BASE64)"
[[ ${#kek} -ge 40 ]] \
  || fail "intelligence-service must receive CRYPTO_KEK_BASE64 (platform credentials are envelope-encrypted)"
for name in FINANCE_CREDITS_CENTS_POLICY_VERSION FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT \
    FINANCE_CREDITS_CENTS_POLICY_ROUNDING FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR \
    FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION; do
  intelligence_value="$(env_value intelligence-service "$name")"
  finance_value="$(env_value finance-service "$name")"
  [[ -n "$intelligence_value" ]] \
    || fail "intelligence-service must receive $name in the production overlay"
  [[ -n "$finance_value" ]] \
    || fail "finance-service must receive $name in the production overlay"
  [[ "$intelligence_value" == "$finance_value" ]] \
    || fail "finance-service and intelligence-service must receive the same $name"
done
[[ "$(env_value intelligence-service AI_CREDIT_USAGE_SETTLEMENT_ENABLED)" == true ]] \
  || fail "intelligence-service must enable AI credit usage settlement in production"

for service in marketplace-service trust-service; do
  [[ "$(env_value "$service" TEMPORAL_ENABLE_HTTPS)" == true ]] \
    || fail "$service must enable Temporal HTTPS"
  [[ "$(env_value "$service" SPRING_TEMPORAL_CONNECTION_MTLS_INSECURE_TRUST_MANAGER)" == false ]] \
    || fail "$service must reject insecure Temporal trust manager"
  [[ "$(env_value "$service" SPRING_TEMPORAL_CONNECTION_MTLS_CERT_CHAIN_FILE)" == /run/secrets/grassland/temporal-client.crt ]] \
    || fail "$service must mount the Temporal client certificate"
  [[ "$(env_value "$service" SPRING_TEMPORAL_CONNECTION_MTLS_KEY_FILE)" == /run/secrets/grassland/temporal-client.key ]] \
    || fail "$service must mount the Temporal client key"
done

for service in marketplace-service trust-service; do
  jq -e --arg service "$service" \
    '.services[$service].volumes[]? | select(.target == "/run/secrets/grassland/temporal-client.crt" and .read_only == true)' \
    >/dev/null <<< "$json" || fail "$service must read-only mount the Temporal client certificate"
  jq -e --arg service "$service" \
    '.services[$service].volumes[]? | select(.target == "/run/secrets/grassland/temporal-client.key" and .read_only == true)' \
    >/dev/null <<< "$json" || fail "$service must read-only mount the Temporal client key"
done

for service in identity-service marketplace-service finance-service trust-service intelligence-service; do
  jq -e --arg service "$service" \
    '.services["edge-bff"].depends_on[$service].condition == "service_healthy"' \
    >/dev/null <<< "$json" || fail "edge-bff must wait for $service service_healthy"
done

echo "production compose contract is valid"
