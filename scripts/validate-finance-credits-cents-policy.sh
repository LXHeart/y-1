#!/usr/bin/env bash
set -Eeuo pipefail

ENV_FILE=""
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib/dotenv.sh"
usage() {
  cat <<'EOF'
Usage: scripts/validate-finance-credits-cents-policy.sh [--env-file PATH]

Validates the approved, versioned credits-to-cents policy without printing secret values.
The policy is a rational number plus an explicit rounding mode and maximum charge. This command
does not apply the policy or mutate any ledger.
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

failures=0
required() {
  local name="$1" value="${!1:-}"
  if [[ -z "$value" || "$value" =~ replace-with|change-me|placeholder|your-|example\.com|\.invalid ]]; then
    echo "ERROR: $name is missing or placeholder" >&2; failures=$((failures + 1)); return
  fi
  printf '%-52s present\n' "$name"
}
required FINANCE_CREDITS_CENTS_POLICY_VERSION
required FINANCE_CREDITS_CENTS_POLICY_APPROVED_BY
required FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT
required FINANCE_CREDITS_CENTS_POLICY_ROUNDING
required FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR
required FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR
required FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION

[[ "${FINANCE_CREDITS_CENTS_POLICY_VERSION:-}" =~ ^[A-Za-z0-9._-]{1,64}$ ]] \
  || { echo "ERROR: policy version must be 1..64 ASCII identifier" >&2; failures=$((failures + 1)); }
[[ "${FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT:-}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || { echo "ERROR: effective_at must be UTC ISO-8601 timestamp" >&2; failures=$((failures + 1)); }
case "${FINANCE_CREDITS_CENTS_POLICY_ROUNDING:-}" in
  HALF_UP|HALF_EVEN|DOWN|UP) ;;
  *) echo "ERROR: rounding must be HALF_UP, HALF_EVEN, DOWN, or UP" >&2; failures=$((failures + 1)) ;;
esac
for name in FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR \
    FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR \
    FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION; do
  value="${!name:-}"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] \
    || { echo "ERROR: $name must be a positive integer" >&2; failures=$((failures + 1)); }
done
if (( failures > 0 )); then
  echo "Finance credits-to-cents policy validation failed with $failures issue(s)" >&2
  exit 1
fi
echo "Finance credits-to-cents policy contract is valid"
