#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE=""
source "$ROOT_DIR/scripts/lib/dotenv.sh"

usage() {
  cat <<'EOF'
Usage: scripts/validate-production-secrets.sh [--env-file PATH]

Validates the production secret contract without printing secret values. The env file must be a
trusted shell-compatible dotenv file. Real secret files should be materialized by SOPS, Vault, or
the cloud Secret Manager immediately before this command runs.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?missing env file}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -n "$ENV_FILE" ]]; then
  load_dotenv "$ENV_FILE" || exit 1
fi

failures=0
fail() { echo "ERROR: $*" >&2; failures=$((failures + 1)); }
valid_value() {
  local value="$1"
  [[ -n "$value" ]] || return 1
  [[ ! "$value" =~ replace-with|change-me|placeholder|your-|example\.com|\.invalid ]] || return 1
}

while IFS=',' read -r name min_length required owner; do
  [[ "$name" != "name" ]] || continue
  value="${!name:-}"
  if [[ "$required" == "true" ]] && ! valid_value "$value"; then
    fail "$name is missing or still contains a placeholder ($owner owner)"
    continue
  fi
  if [[ -n "$value" && ${#value} -lt $min_length ]]; then
    fail "$name must contain at least $min_length characters"
  fi
done < "$ROOT_DIR/deploy/security/production-secret-contract.csv"

if [[ -n "${CRYPTO_KEK_BASE64:-}" ]]; then
  kek_bytes="$(printf '%s' "$CRYPTO_KEK_BASE64" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' ')"
  [[ "$kek_bytes" == "32" ]] || fail "CRYPTO_KEK_BASE64 must decode to exactly 32 bytes"
fi

[[ "${MINIO_ROOT_USER:-}" != "${MINIO_ACCESS_KEY:-}" ]] || fail "MinIO root and runtime access keys must differ"
[[ "${MINIO_ROOT_PASSWORD:-}" != "${MINIO_SECRET_KEY:-}" ]] || fail "MinIO root and runtime secret keys must differ"
[[ "${PUBLIC_FORWARDED_PROTO:-}" == "https" ]] || fail "PUBLIC_FORWARDED_PROTO must be https"
[[ "${SESSION_COOKIE_SECURE:-}" != "never" ]] || fail "SESSION_COOKIE_SECURE must not be never"
[[ "${IDENTITY_ASSERTION_REPLAY_ENABLED:-false}" == "true" ]] || fail "identity assertion replay protection must be enabled"
[[ "${IDENTITY_ASSERTION_REPLAY_STORAGE:-redis}" == "redis" ]] || fail "identity assertion replay storage must be redis"
[[ "${CONFIRMATION_WINDOW_SECONDS:-0}" -ge 259200 ]] || fail "CONFIRMATION_WINDOW_SECONDS must be at least 259200 in production"
[[ -n "${FINANCE_PSP_MODE:-}" ]] || fail "FINANCE_PSP_MODE is required in production"
[[ "${FINANCE_PSP_MODE:-}" != "sandbox" ]] || fail "FINANCE_PSP_MODE must select a real production adapter"

# A production deployment must never silently run the deterministic Sandbox video
# adapter. Validate the vendor contract here, before Compose or containers start.
video_mode="${VIDEO_GENERATION_MODE:-}"
if [[ "$video_mode" != "seedance" && "$video_mode" != "minimax" ]]; then
  fail "VIDEO_GENERATION_MODE must be seedance or minimax in production"
else
  for video_name in VIDEO_GENERATION_BASE_URL VIDEO_GENERATION_API_KEY VIDEO_GENERATION_MODEL \
      VIDEO_GENERATION_CREATE_PATH VIDEO_GENERATION_POLL_PATH VIDEO_GENERATION_PRICING_VERSION \
      VIDEO_GENERATION_UNIT_PRICE_CENTS VIDEO_GENERATION_WEBHOOK_SECRET; do
    video_value="${!video_name:-}"
    valid_value "$video_value" || fail "$video_name is missing or still contains a placeholder"
  done
  [[ "${VIDEO_GENERATION_BASE_URL:-}" == https://* ]] \
    || fail "VIDEO_GENERATION_BASE_URL must use https in production"
  [[ "${VIDEO_GENERATION_CREATE_PATH:-}" == /* && "${VIDEO_GENERATION_CREATE_PATH:-}" != //* ]] \
    || fail "VIDEO_GENERATION_CREATE_PATH must be an absolute path"
  [[ "${VIDEO_GENERATION_POLL_PATH:-}" == /* && "${VIDEO_GENERATION_POLL_PATH:-}" != //* ]] \
    || fail "VIDEO_GENERATION_POLL_PATH must be an absolute path"
[[ "${VIDEO_GENERATION_UNIT_PRICE_CENTS:-0}" =~ ^[1-9][0-9]*$ ]] \
    || fail "VIDEO_GENERATION_UNIT_PRICE_CENTS must be a positive integer"
fi

policy_args=()
[[ -n "$ENV_FILE" ]] && policy_args+=(--env-file "$ENV_FILE")
if ! "$ROOT_DIR/scripts/validate-finance-credits-cents-policy.sh" "${policy_args[@]}" >/dev/null; then
  fail "Finance credits-to-cents policy is missing, ambiguous, or not approved"
fi

[[ "${KAFKA_SECURITY_PROTOCOL:-}" == "SASL_SSL" ]] || fail "KAFKA_SECURITY_PROTOCOL must be SASL_SSL"
[[ "${KAFKA_SASL_MECHANISM:-}" == "SCRAM-SHA-512" ]] || fail "KAFKA_SASL_MECHANISM must be SCRAM-SHA-512"
[[ -n "${KAFKA_BOOTSTRAP_SERVERS:-}" ]] || fail "KAFKA_BOOTSTRAP_SERVERS is required"
[[ "${KAFKA_BOOTSTRAP_SERVERS:-}" != *"kafka:9092"* && "${KAFKA_BOOTSTRAP_SERVERS:-}" != *"localhost"* ]] \
  || fail "KAFKA_BOOTSTRAP_SERVERS must point to an external production cluster"
[[ -r "${KAFKA_SSL_TRUSTSTORE_FILE:-}" ]] || fail "KAFKA_SSL_TRUSTSTORE_FILE must be readable"

[[ -n "${TEMPORAL_TARGET:-}" ]] || fail "TEMPORAL_TARGET is required"
[[ "${TEMPORAL_TARGET:-}" != "temporal:7233" && "${TEMPORAL_TARGET:-}" != *"localhost"* ]] \
  || fail "TEMPORAL_TARGET must point to an external production cluster"
[[ -n "${TEMPORAL_NAMESPACE:-}" && "${TEMPORAL_NAMESPACE:-}" != "default" ]] \
  || fail "TEMPORAL_NAMESPACE must be a dedicated non-default namespace"
[[ "${TEMPORAL_ENABLE_HTTPS:-}" == "true" ]] || fail "TEMPORAL_ENABLE_HTTPS must be true"
[[ -n "${TEMPORAL_MTLS_SERVER_NAME:-}" ]] || fail "TEMPORAL_MTLS_SERVER_NAME is required"
[[ -r "${TEMPORAL_MTLS_CERT_CHAIN_FILE:-}" ]] || fail "TEMPORAL_MTLS_CERT_CHAIN_FILE must be readable"
[[ -r "${TEMPORAL_MTLS_KEY_FILE:-}" ]] || fail "TEMPORAL_MTLS_KEY_FILE must be readable"

for url_name in FRONTEND_ORIGIN PUBLIC_BACKEND_ORIGIN CORS_ORIGIN; do
  url_value="${!url_name:-}"
  [[ "$url_value" == https://* ]] || fail "$url_name must be an https URL"
done

export EDGE_ACCESS_TOKEN_KID="${EDGE_ACCESS_TOKEN_KID:-${IDENTITY_ACCESS_TOKEN_KID:-}}"
export EDGE_ACCESS_TOKEN_SECRET="${EDGE_ACCESS_TOKEN_SECRET:-${IDENTITY_ACCESS_TOKEN_SECRET:-}}"
if ! "$ROOT_DIR/scripts/rotate-identity-keys.sh" validate-access-token >/dev/null; then
  fail "access-token signing and verification keys are inconsistent"
fi

while IFS=',' read -r pair _; do
  [[ "$pair" != "pair" ]] || continue
  if ! "$ROOT_DIR/scripts/rotate-identity-keys.sh" validate-assertion "$pair" >/dev/null; then
    fail "identity assertion pair $pair is invalid"
  fi
done < "$ROOT_DIR/deploy/security/identity-assertion-key-pairs.csv"

check_secret_file() {
  local name="$1" path="$2" example="$3"
  [[ -r "$path" ]] || { fail "$name file is not readable: $path"; return; }
  [[ "$(cd "$(dirname "$path")" && pwd)/$(basename "$path")" != "$example" ]] || fail "$name still points to the repository example"
  local mode
  mode="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path" 2>/dev/null || true)"
  [[ -z "$mode" || $((8#$mode & 077)) -eq 0 ]] || fail "$name file must not be group/world accessible (mode $mode)"
}

if [[ -r "${TEMPORAL_MTLS_KEY_FILE:-}" ]]; then
  temporal_key_mode="$(stat -f '%Lp' "$TEMPORAL_MTLS_KEY_FILE" 2>/dev/null || stat -c '%a' "$TEMPORAL_MTLS_KEY_FILE" 2>/dev/null || true)"
  [[ -z "$temporal_key_mode" || $((8#$temporal_key_mode & 077)) -eq 0 ]] \
    || fail "TEMPORAL_MTLS_KEY_FILE must not be group/world accessible (mode $temporal_key_mode)"
fi

alert_example="$ROOT_DIR/platform-java/deploy/observability/alertmanager/webhook-url.example"
grafana_example="$ROOT_DIR/platform-java/deploy/observability/grafana/admin-password.example"
check_secret_file ALERTMANAGER_WEBHOOK_URL "${ALERTMANAGER_WEBHOOK_URL_FILE:-$alert_example}" "$alert_example"
check_secret_file GRAFANA_ADMIN_PASSWORD "${GRAFANA_ADMIN_PASSWORD_FILE:-$grafana_example}" "$grafana_example"

if (( failures > 0 )); then
  echo "production secret validation failed with $failures issue(s)" >&2
  exit 1
fi
echo "production secret contract is valid"
