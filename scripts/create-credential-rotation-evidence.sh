#!/usr/bin/env bash
set -Eeuo pipefail

OUTPUT=""
RELEASE_ID=""
TARGET=""
BACKEND=""
OLD_ID=""
NEW_ID=""
SERVICES=""
ROLLOUT_MODE=""
REVOCATION_METHOD=""
APPROVAL_REFERENCE=""

usage() {
  cat <<'EOF'
Usage:
  scripts/create-credential-rotation-evidence.sh --output PATH --release-id ID --target NAME \
    --backend aws-secrets-manager|gcp-secret-manager|azure-key-vault|vault|sops|other \
    --old-credential-id ID --new-credential-id ID --services CSV \
    --rollout-mode rolling|blue-green|maintenance \
    --revocation-method provider-revoked|account-disabled|certificate-revoked|secret-version-destroyed|session-invalidated \
    --approval-reference ID

Creates a mode-0600 evidence worksheet containing identifiers and status only. Never pass secret
values as credential IDs. Replace PENDING fields with measured UTC timestamps and results.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
safe_id() {
  local label="$1" value="$2"
  [[ "$value" =~ ^[A-Za-z0-9._:/@#-]{1,160}$ ]] || die "$label is invalid"
  [[ ! "$value" =~ ^[A-Fa-f0-9]{32,}$ ]] || die "$label looks like secret material; use a key/version ID"
  [[ ! "$value" =~ ^(sk-|gh[pousr]_|AKIA) ]] || die "$label looks like secret material; use a key/version ID"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT="${2:?missing output}"; shift 2 ;;
    --release-id) RELEASE_ID="${2:?missing release ID}"; shift 2 ;;
    --target) TARGET="${2:?missing target}"; shift 2 ;;
    --backend) BACKEND="${2:?missing backend}"; shift 2 ;;
    --old-credential-id) OLD_ID="${2:?missing old credential ID}"; shift 2 ;;
    --new-credential-id) NEW_ID="${2:?missing new credential ID}"; shift 2 ;;
    --services) SERVICES="${2:?missing services}"; shift 2 ;;
    --rollout-mode) ROLLOUT_MODE="${2:?missing rollout mode}"; shift 2 ;;
    --revocation-method) REVOCATION_METHOD="${2:?missing revocation method}"; shift 2 ;;
    --approval-reference) APPROVAL_REFERENCE="${2:?missing approval reference}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$OUTPUT" ]] || die "--output is required"
[[ "$OUTPUT" == /* ]] || die "--output must be an absolute path"
[[ ! -e "$OUTPUT" ]] || die "refusing to overwrite existing evidence: $OUTPUT"
safe_id release_id "$RELEASE_ID"
safe_id target "$TARGET"
safe_id old_credential_id "$OLD_ID"
safe_id new_credential_id "$NEW_ID"
safe_id approval_reference "$APPROVAL_REFERENCE"
[[ "$OLD_ID" != "$NEW_ID" ]] || die "old and new credential IDs must differ"
[[ "$BACKEND" =~ ^(aws-secrets-manager|gcp-secret-manager|azure-key-vault|vault|sops|other)$ ]] \
  || die "unsupported backend"
[[ "$SERVICES" =~ ^[a-z0-9-]+(,[a-z0-9-]+)*$ ]] || die "services must be a comma-separated slug list"
[[ "$ROLLOUT_MODE" =~ ^(rolling|blue-green|maintenance)$ ]] || die "unsupported rollout mode"
[[ "$REVOCATION_METHOD" =~ ^(provider-revoked|account-disabled|certificate-revoked|secret-version-destroyed|session-invalidated)$ ]] \
  || die "unsupported revocation method"

output_dir="$(dirname "$OUTPUT")"
[[ -d "$output_dir" ]] || die "output directory does not exist: $output_dir"
umask 077
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
evidence_id="credential-${TARGET}-$(date -u +%Y%m%d%H%M%S)"
{
  printf '%s\n' '# Grassland credential rotation evidence v1'
  printf 'evidence_id=%s\nrelease_id=%s\ntarget=%s\n' "$evidence_id" "$RELEASE_ID" "$TARGET"
  printf 'secret_backend=%s\nold_credential_id=%s\nnew_credential_id=%s\n' "$BACKEND" "$OLD_ID" "$NEW_ID"
  printf 'affected_services=%s\nrollout_mode=%s\nrevocation_method=%s\napproval_reference=%s\n' \
    "$SERVICES" "$ROLLOUT_MODE" "$REVOCATION_METHOD" "$APPROVAL_REFERENCE"
  printf 'audit_reference=PENDING\nrotation_started_at=%s\n' "$created_at"
  printf '%s\n' 'new_materialized_at=PENDING' 'rollout_completed_at=PENDING'
  printf '%s\n' 'rollback_started_at=PENDING' 'rollback_completed_at=PENDING'
  printf '%s\n' 'old_revoked_at=PENDING' 'verification_completed_at=PENDING'
  printf '%s\n' 'materialization_status=pending' 'rollout_status=pending'
  printf '%s\n' 'readiness_status=pending' 'smoke_status=pending' 'rollback_status=pending'
  printf '%s\n' 'old_credential_rejection_status=pending' 'audit_status=pending'
  printf '%s\n' 'old_credential_revoked=false' 'secrets_recorded=false' 'result=pending'
} > "$OUTPUT"
chmod 600 "$OUTPUT"
echo "credential rotation evidence worksheet created at $OUTPUT"
