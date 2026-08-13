#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE=""
COSIGN_BIN="${COSIGN_BIN:-cosign}"
COSIGN_PUBLIC_KEY_FILE="${COSIGN_PUBLIC_KEY_FILE:-}"

SERVICES=(frontend database-bootstrap edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)

usage() {
  cat <<'EOF'
Usage: scripts/validate-image-provenance.sh [--state-file PATH]

Verifies cosign signatures and SPDX attestations for every immutable release image. By default
the RELEASE_IMAGE_<SERVICE> variables are used; --state-file reads the image reference from the
fourth field of production-release previous-images.tsv. The public key is supplied through
COSIGN_PUBLIC_KEY_FILE and is never stored in the repository.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --state-file) STATE_FILE="${2:?missing state file}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v "$COSIGN_BIN" >/dev/null 2>&1 || die "cosign is required"
[[ -n "$COSIGN_PUBLIC_KEY_FILE" && -r "$COSIGN_PUBLIC_KEY_FILE" ]] \
  || die "COSIGN_PUBLIC_KEY_FILE must point to a readable deployment public key"
[[ -z "$STATE_FILE" || -r "$STATE_FILE" ]] || die "release image state file is not readable: $STATE_FILE"

image_for_service() {
  local service="$1" var
  if [[ -n "$STATE_FILE" ]]; then
    awk -F'|' -v service="$service" '$1 == service { if ($4 != "") print $4; else if ($2 != "") print $2; else print $3 }' "$STATE_FILE"
    return
  fi
  var="RELEASE_IMAGE_$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]' | tr '-' '_')"
  printf '%s\n' "${!var:-}"
}

for service in "${SERVICES[@]}"; do
  image="$(image_for_service "$service")"
  [[ "$image" == *@sha256:* ]] || die "$service image must be pinned by digest for provenance verification"
  echo "[provenance] verifying signature: $service"
  "$COSIGN_BIN" verify --key "$COSIGN_PUBLIC_KEY_FILE" "$image" >/dev/null
  echo "[provenance] verifying SPDX attestation: $service"
  "$COSIGN_BIN" verify-attestation --key "$COSIGN_PUBLIC_KEY_FILE" --type spdxjson "$image" >/dev/null
done

echo "image signatures and SPDX attestations are valid"
