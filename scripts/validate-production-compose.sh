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

for name in VIDEO_GENERATION_MODE VIDEO_GENERATION_BASE_URL VIDEO_GENERATION_API_KEY \
    VIDEO_GENERATION_MODEL VIDEO_GENERATION_CREATE_PATH VIDEO_GENERATION_POLL_PATH \
    VIDEO_GENERATION_PRICING_VERSION VIDEO_GENERATION_UNIT_PRICE_CENTS VIDEO_GENERATION_WEBHOOK_SECRET; do
  [[ -n "$(env_value intelligence-service "$name")" ]] \
    || fail "intelligence-service must receive $name in the production overlay"
done
qwen_base_url="$(env_value intelligence-service QWEN_BASE_URL)"
[[ "$qwen_base_url" =~ ^https://dashscope[.]aliyuncs[.]com/compatible-mode/v1/?$ ]] \
  || fail "intelligence-service QWEN_BASE_URL must use the trusted DashScope HTTPS origin"
qwen_api_key="$(env_value intelligence-service QWEN_API_KEY)"
[[ ${#qwen_api_key} -ge 16 && "$qwen_api_key" != *replace-with* && "$qwen_api_key" != *placeholder* ]] \
  || fail "intelligence-service QWEN_API_KEY must be non-placeholder and at least 16 characters"
for name in FINANCE_CREDITS_CENTS_POLICY_VERSION FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT \
    FINANCE_CREDITS_CENTS_POLICY_ROUNDING FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR \
    FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION; do
  [[ -n "$(env_value intelligence-service "$name")" ]] \
    || fail "intelligence-service must receive $name in the production overlay"
done
[[ "$(env_value intelligence-service VIDEO_GENERATION_MODE)" == seedance \
    || "$(env_value intelligence-service VIDEO_GENERATION_MODE)" == minimax ]] \
  || fail "production video adapter must not be Sandbox"

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
