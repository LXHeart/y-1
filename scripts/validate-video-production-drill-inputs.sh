#!/usr/bin/env bash
set -Eeuo pipefail

ENV_FILE=""
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib/dotenv.sh"
usage() {
  cat <<'EOF'
Usage: scripts/validate-video-production-drill-inputs.sh [--env-file PATH]

Checks only whether the external inputs required by the real video/provider and archive drills
exist and are non-placeholder. It never prints secret values, calls a provider, or mutates data.
EOF
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?missing env file}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -n "$ENV_FILE" ]]; then
  load_dotenv "$ENV_FILE" || exit 1
fi

missing=0
check() {
  local name="$1" value="${!1:-}"; local state="missing"
  if [[ -n "$value" && ! "$value" =~ replace-with|change-me|placeholder|your-|example\.com|\.invalid ]]; then
    state="present"
  else
    missing=$((missing + 1))
  fi
  printf '%-42s %s\n' "$name" "$state"
}
check_url() {
  local name="$1" value="${!1:-}"; local state="missing"
  if [[ "$value" == https://* && ! "$value" =~ example\.com|\.invalid ]]; then state="present"; else missing=$((missing + 1)); fi
  printf '%-42s %s\n' "$name" "$state"
}

echo "Video production real-drill input preflight (values redacted)"
check VIDEO_GENERATION_MODE
check_url VIDEO_GENERATION_BASE_URL
check VIDEO_GENERATION_API_KEY
check VIDEO_GENERATION_MODEL
check VIDEO_GENERATION_CREATE_PATH
check VIDEO_GENERATION_POLL_PATH
check VIDEO_GENERATION_PRICING_VERSION
check VIDEO_GENERATION_UNIT_PRICE_CENTS
check VIDEO_GENERATION_WEBHOOK_SECRET
check PUBLIC_HEALTH_URL
check PUBLIC_SMOKE_BASE_URL
check MINIO_ENDPOINT
check MINIO_ACCESS_KEY
check MINIO_SECRET_KEY
check FINANCE_RELEASE_APPROVED_BY
check FINANCE_CREDITS_CENTS_POLICY_VERSION
check FINANCE_CREDITS_CENTS_POLICY_APPROVED_BY

if (( missing > 0 )); then
  echo "real video drill inputs incomplete: $missing field(s) missing or placeholder" >&2
  exit 1
fi
[[ "$VIDEO_GENERATION_MODE" == seedance || "$VIDEO_GENERATION_MODE" == minimax ]] \
  || { echo "ERROR: VIDEO_GENERATION_MODE must be seedance or minimax" >&2; exit 1; }
[[ "$VIDEO_GENERATION_UNIT_PRICE_CENTS" =~ ^[1-9][0-9]*$ ]] \
  || { echo "ERROR: VIDEO_GENERATION_UNIT_PRICE_CENTS must be positive integer" >&2; exit 1; }
[[ ${#VIDEO_GENERATION_WEBHOOK_SECRET} -ge 32 ]] \
  || { echo "ERROR: VIDEO_GENERATION_WEBHOOK_SECRET must be at least 32 characters" >&2; exit 1; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
policy_args=()
[[ -n "$ENV_FILE" ]] && policy_args+=(--env-file "$ENV_FILE")
"$script_dir/validate-finance-credits-cents-policy.sh" "${policy_args[@]}" >/dev/null \
  || { echo "ERROR: approved Finance credits-to-cents policy is not valid" >&2; exit 1; }
echo "real video drill input preflight passed"
