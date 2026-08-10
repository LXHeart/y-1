#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSERTION_PAIR_REGISTRY="$ROOT_DIR/deploy/security/identity-assertion-key-pairs.csv"

usage() {
  cat <<'EOF'
Usage:
  scripts/rotate-identity-keys.sh generate-access-token [kid]
  scripts/rotate-identity-keys.sh validate-access-token
  scripts/rotate-identity-keys.sh plan-access-token [new-kid] [ttl-seconds] [leeway-seconds]
  scripts/rotate-identity-keys.sh list-assertion-pairs
  scripts/rotate-identity-keys.sh generate-assertion <PAIR> [new-kid]
  scripts/rotate-identity-keys.sh validate-assertion <PAIR>
  scripts/rotate-identity-keys.sh plan-assertion <PAIR> [new-kid] [ttl-seconds] [leeway-seconds]

The tool never edits .env files or secret stores. validate-access-token reads:
  IDENTITY_ACCESS_TOKEN_KID / IDENTITY_ACCESS_TOKEN_SECRET
  EDGE_ACCESS_TOKEN_KID / EDGE_ACCESS_TOKEN_SECRET
  EDGE_ACCESS_TOKEN_PREVIOUS_KEYS (optional kid=secret,kid2=secret2)

Assertion PAIR values come from deploy/security/identity-assertion-key-pairs.csv.
validate-assertion reads IDENTITY_ASSERTION_KEY_<PAIR>{_KID,,_PREVIOUS_KID,_PREVIOUS}.
EOF
}

require_openssl() {
  command -v openssl >/dev/null 2>&1 || {
    echo "openssl is required" >&2
    exit 1
  }
}

require_secret() {
  local name="$1"
  local value="$2"
  if [[ ${#value} -lt 32 ]]; then
    echo "$name must be at least 32 characters" >&2
    return 1
  fi
}

assertion_pair_row() {
  local pair="$1"
  [[ "$pair" =~ ^[A-Z0-9_]+$ ]] || {
    echo "assertion pair must use uppercase letters, digits, and underscore" >&2
    return 1
  }
  local row
  row="$(awk -F',' -v pair="$pair" 'NR > 1 && $1 == pair { print; exit }' "$ASSERTION_PAIR_REGISTRY")"
  [[ -n "$row" ]] || {
    echo "unknown assertion pair: $pair" >&2
    return 1
  }
  printf '%s\n' "$row"
}

assertion_var() {
  printf 'IDENTITY_ASSERTION_KEY_%s%s' "$1" "$2"
}

generate_assertion() {
  require_openssl
  local pair="$1"
  local row issuer audience purpose default_kid
  row="$(assertion_pair_row "$pair")"
  IFS=',' read -r _ issuer audience purpose default_kid <<< "$row"
  local kid="${2:-${default_kid%-v*}-$(date -u +%Y%m%d%H%M%S)}"
  [[ "$kid" =~ ^[A-Za-z0-9._-]+$ ]] || {
    echo "kid may only contain letters, digits, dot, underscore, and hyphen" >&2
    return 1
  }
  printf '# pair=%s issuer=%s audience=%s purpose=%s generated-at=%s\n' \
    "$pair" "$issuer" "$audience" "$purpose" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '%s=%s\n' "$(assertion_var "$pair" _KID)" "$kid"
  printf '%s=%s\n' "$(assertion_var "$pair" "")" "$(openssl rand -hex 32)"
}

validate_assertion() {
  local pair="$1"
  assertion_pair_row "$pair" >/dev/null
  local kid_var secret_var previous_kid_var previous_secret_var
  kid_var="$(assertion_var "$pair" _KID)"
  secret_var="$(assertion_var "$pair" "")"
  previous_kid_var="$(assertion_var "$pair" _PREVIOUS_KID)"
  previous_secret_var="$(assertion_var "$pair" _PREVIOUS)"
  local kid="${!kid_var:-}" secret="${!secret_var:-}"
  local previous_kid="${!previous_kid_var:-}" previous_secret="${!previous_secret_var:-}"
  [[ -n "$kid" ]] || { echo "$kid_var must be configured" >&2; return 1; }
  require_secret "$secret_var" "$secret"
  if [[ -n "$previous_kid" || -n "$previous_secret" ]]; then
    [[ -n "$previous_kid" && -n "$previous_secret" ]] || {
      echo "previous kid and secret must be configured together" >&2
      return 1
    }
    [[ "$previous_kid" != "$kid" ]] || {
      echo "current and previous kid must differ" >&2
      return 1
    }
    require_secret "$previous_secret_var" "$previous_secret"
  fi
  echo "assertion key pair $pair is valid"
}

plan_assertion() {
  require_openssl
  local pair="$1"
  local row default_kid
  row="$(assertion_pair_row "$pair")"
  IFS=',' read -r _ _ _ _ default_kid <<< "$row"
  local new_kid="${2:-${default_kid%-v*}-$(date -u +%Y%m%d%H%M%S)}"
  local ttl="${3:-${IDENTITY_ASSERTION_TTL_SECONDS:-60}}"
  local leeway="${4:-${IDENTITY_ASSERTION_LEEWAY_SECONDS:-5}}"
  [[ "$ttl" =~ ^[0-9]+$ && "$leeway" =~ ^[0-9]+$ ]] || {
    echo "ttl-seconds and leeway-seconds must be non-negative integers" >&2
    return 1
  }
  local current_kid_var current_secret_var previous_kid_var previous_secret_var
  current_kid_var="$(assertion_var "$pair" _KID)"
  current_secret_var="$(assertion_var "$pair" "")"
  previous_kid_var="$(assertion_var "$pair" _PREVIOUS_KID)"
  previous_secret_var="$(assertion_var "$pair" _PREVIOUS)"
  local new_secret wait_seconds retire_not_before
  new_secret="$(openssl rand -hex 32)"
  wait_seconds=$((ttl + leeway))
  retire_not_before=$(($(date -u +%s) + wait_seconds))

  cat <<EOF
Rotation metadata:
  pair=${pair}
  new-kid=${new_kid}
  generated-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  old-key-retire-not-before-epoch=${retire_not_before}

Phase 1 - publish the new key in every verifier's previous slot; current signers stay unchanged:
  ${previous_kid_var}=${new_kid}
  ${previous_secret_var}=${new_secret}

Phase 2 - after every verifier has Phase 1, roll current signing/verification and retain the old key:
  ${current_kid_var}=${new_kid}
  ${current_secret_var}=${new_secret}
  ${previous_kid_var}=<old-kid>
  ${previous_secret_var}=<old-secret>

Phase 3 - wait at least ${wait_seconds} seconds (TTL ${ttl} + leeway ${leeway}), then clear:
  ${previous_kid_var}=
  ${previous_secret_var}=

Run validate-assertion ${pair} for every phase. Apply changes through the deployment secret manager.
EOF
}

generate_access_token() {
  require_openssl
  local kid="${1:-access-token-$(date -u +%Y%m%d%H%M%S)}"
  [[ "$kid" =~ ^[A-Za-z0-9._-]+$ ]] || {
    echo "kid may only contain letters, digits, dot, underscore, and hyphen" >&2
    exit 1
  }
  local secret
  secret="$(openssl rand -hex 32)"
  printf 'IDENTITY_ACCESS_TOKEN_KID=%s\n' "$kid"
  printf 'IDENTITY_ACCESS_TOKEN_SECRET=%s\n' "$secret"
}

validate_previous_keys() {
  local value="$1"
  [[ -z "$value" ]] && return 0
  local entry kid secret
  local seen=","
  IFS=',' read -r -a entries <<< "$value"
  for entry in "${entries[@]}"; do
    [[ "$entry" == *=* ]] || {
      echo "EDGE_ACCESS_TOKEN_PREVIOUS_KEYS entry must use kid=secret: $entry" >&2
      return 1
    }
    kid="${entry%%=*}"
    secret="${entry#*=}"
    [[ -n "$kid" && -n "$secret" ]] || {
      echo "EDGE_ACCESS_TOKEN_PREVIOUS_KEYS contains an empty kid or secret" >&2
      return 1
    }
    [[ "$seen" != *",$kid,"* ]] || {
      echo "EDGE_ACCESS_TOKEN_PREVIOUS_KEYS contains duplicate kid: $kid" >&2
      return 1
    }
    seen+="$kid,"
    require_secret "previous key $kid" "$secret"
  done
}

validate_access_token() {
  local identity_kid="${IDENTITY_ACCESS_TOKEN_KID:-}"
  local identity_secret="${IDENTITY_ACCESS_TOKEN_SECRET:-}"
  local edge_kid="${EDGE_ACCESS_TOKEN_KID:-$identity_kid}"
  local edge_secret="${EDGE_ACCESS_TOKEN_SECRET:-$identity_secret}"
  local previous="${EDGE_ACCESS_TOKEN_PREVIOUS_KEYS:-}"

  [[ -n "$identity_kid" && -n "$edge_kid" ]] || {
    echo "current identity/edge kid must be configured" >&2
    exit 1
  }
  require_secret IDENTITY_ACCESS_TOKEN_SECRET "$identity_secret"
  require_secret EDGE_ACCESS_TOKEN_SECRET "$edge_secret"
  [[ "$identity_kid" == "$edge_kid" ]] || {
    echo "identity and edge current kid differ" >&2
    exit 1
  }
  [[ "$identity_secret" == "$edge_secret" ]] || {
    echo "identity and edge current secret differ" >&2
    exit 1
  }
  validate_previous_keys "$previous"
  if [[ ",$previous," == *",$identity_kid="* ]]; then
    echo "current kid must not also appear in EDGE_ACCESS_TOKEN_PREVIOUS_KEYS" >&2
    exit 1
  fi
  echo "access-token key configuration is valid"
}

plan_access_token() {
  require_openssl
  local new_kid="${1:-access-token-$(date -u +%Y%m%d%H%M%S)}"
  local ttl="${2:-${IDENTITY_ACCESS_TOKEN_TTL_SECONDS:-900}}"
  local leeway="${3:-${EDGE_ACCESS_TOKEN_LEEWAY_SECONDS:-5}}"
  [[ "$ttl" =~ ^[0-9]+$ && "$leeway" =~ ^[0-9]+$ ]] || {
    echo "ttl-seconds and leeway-seconds must be non-negative integers" >&2
    exit 1
  }
  local new_secret wait_seconds
  new_secret="$(openssl rand -hex 32)"
  wait_seconds=$((ttl + leeway))

  cat <<EOF
Phase 1 - publish the new verify key to edge while identity still signs with the old key:
  EDGE_ACCESS_TOKEN_PREVIOUS_KEYS=${new_kid}=${new_secret}

Phase 2 - after every edge instance has Phase 1, switch signing and retain the old key for verification:
  IDENTITY_ACCESS_TOKEN_KID=${new_kid}
  IDENTITY_ACCESS_TOKEN_SECRET=${new_secret}
  EDGE_ACCESS_TOKEN_KID=${new_kid}
  EDGE_ACCESS_TOKEN_SECRET=${new_secret}
  EDGE_ACCESS_TOKEN_PREVIOUS_KEYS=<old-kid>=<old-secret>

Phase 3 - wait at least ${wait_seconds} seconds (TTL ${ttl} + leeway ${leeway}), then remove the old entry:
  EDGE_ACCESS_TOKEN_PREVIOUS_KEYS=

Run validate-access-token against each phase before deployment. Keep secrets in the deployment secret manager.
EOF
}

case "${1:-}" in
  generate-access-token)
    shift
    generate_access_token "${1:-}"
    ;;
  validate-access-token)
    validate_access_token
    ;;
  plan-access-token)
    shift
    plan_access_token "${1:-}" "${2:-}" "${3:-}"
    ;;
  list-assertion-pairs)
    awk -F',' 'NR > 1 { printf "%-45s issuer=%-14s audience=%-24s purpose=%s\n", $1, $2, $3, $4 }' \
      "$ASSERTION_PAIR_REGISTRY"
    ;;
  generate-assertion)
    shift
    [[ -n "${1:-}" ]] || { usage >&2; exit 1; }
    generate_assertion "$1" "${2:-}"
    ;;
  validate-assertion)
    shift
    [[ -n "${1:-}" ]] || { usage >&2; exit 1; }
    validate_assertion "$1"
    ;;
  plan-assertion)
    shift
    [[ -n "${1:-}" ]] || { usage >&2; exit 1; }
    plan_assertion "$1" "${2:-}" "${3:-}" "${4:-}"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
