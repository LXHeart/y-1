#!/usr/bin/env bash
set -Eeuo pipefail

EVIDENCE=""
EXPECTED_RELEASE=""
MAX_AGE_SECONDS="604800"

usage() {
  cat <<'EOF'
Usage:
  scripts/validate-credential-rotation-evidence.sh --evidence PATH --release-id ID [--max-age-seconds N]

Validates one completed non-identity credential rotation. Evidence must prove materialization,
rollout, readiness, smoke, rollback, provider-side revocation, old-credential rejection, and audit
archival without containing secret material.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence) EVIDENCE="${2:?missing evidence}"; shift 2 ;;
    --release-id) EXPECTED_RELEASE="${2:?missing release ID}"; shift 2 ;;
    --max-age-seconds) MAX_AGE_SECONDS="${2:?missing maximum age}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ -r "$EVIDENCE" ]] || die "credential rotation evidence is not readable"
[[ "$EXPECTED_RELEASE" =~ ^[A-Za-z0-9._-]+$ ]] || die "--release-id is invalid"
[[ "$MAX_AGE_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--max-age-seconds must be positive"
(( MAX_AGE_SECONDS <= 2592000 )) || die "--max-age-seconds cannot exceed 30 days"

python3 - "$EVIDENCE" "$EXPECTED_RELEASE" "$MAX_AGE_SECONDS" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import re, sys

path, expected_release, max_age = sys.argv[1:]
max_age = int(max_age)
keys = {
    "evidence_id", "release_id", "target", "secret_backend", "old_credential_id",
    "new_credential_id", "affected_services", "rollout_mode", "revocation_method",
    "approval_reference", "audit_reference", "rotation_started_at", "new_materialized_at",
    "rollout_completed_at", "rollback_started_at", "rollback_completed_at", "old_revoked_at",
    "verification_completed_at", "materialization_status", "rollout_status", "readiness_status",
    "smoke_status", "rollback_status", "old_credential_rejection_status", "audit_status",
    "old_credential_revoked", "secrets_recorded", "result",
}
def fail(message): raise SystemExit(f"ERROR: {message}")
lines = Path(path).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "# Grassland credential rotation evidence v1": fail("credential rotation evidence header/version is invalid")
values = {}
for line in lines[1:]:
    if not line or "=" not in line: fail("credential rotation evidence contains a malformed line")
    key, value = line.split("=", 1)
    if not re.fullmatch(r"[a-z0-9_]+", key) or not value or key in values: fail("credential rotation evidence contains an invalid, empty, or duplicate field")
    values[key] = value
missing, unknown = sorted(keys - values.keys()), sorted(values.keys() - keys)
if missing or unknown: fail(f"credential rotation evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")
if values["release_id"] != expected_release: fail("credential rotation release does not match expected release")
identifier = re.compile(r"[A-Za-z0-9._:/@#-]{1,160}")
for name in ("evidence_id", "release_id", "target", "old_credential_id", "new_credential_id", "approval_reference", "audit_reference"):
    if not identifier.fullmatch(values[name]): fail(f"{name} is invalid")
for name in ("old_credential_id", "new_credential_id"):
    value = values[name]
    if re.fullmatch(r"[A-Fa-f0-9]{32,}", value) or re.match(r"^(sk-|gh[pousr]_|AKIA)", value):
        fail(f"{name} looks like secret material; record only a key/version ID")
if values["old_credential_id"] == values["new_credential_id"]: fail("old and new credential IDs must differ")
if values["secret_backend"] not in {"aws-secrets-manager", "gcp-secret-manager", "azure-key-vault", "vault", "sops", "other"}: fail("secret_backend is unsupported")
if values["rollout_mode"] not in {"rolling", "blue-green", "maintenance"}: fail("rollout_mode is unsupported")
if values["revocation_method"] not in {"provider-revoked", "account-disabled", "certificate-revoked", "secret-version-destroyed", "session-invalidated"}: fail("revocation_method is unsupported")
services = values["affected_services"].split(",")
if len(set(services)) != len(services) or any(not re.fullmatch(r"[a-z0-9-]+", service) for service in services): fail("affected_services must be unique comma-separated slugs")
def stamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value): fail(f"{label} must be UTC ISO-8601 seconds")
    try: return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError: fail(f"{label} is invalid")
timeline_names = ("rotation_started_at", "new_materialized_at", "rollout_completed_at", "rollback_started_at", "rollback_completed_at", "old_revoked_at", "verification_completed_at")
timeline = [stamp(values[name], name) for name in timeline_names]
if any(later < earlier for earlier, later in zip(timeline, timeline[1:])): fail("credential rotation events are out of order")
age = (datetime.now(timezone.utc) - timeline[-1]).total_seconds()
if age < -300: fail("credential rotation evidence is in the future")
if age > max_age: fail(f"credential rotation evidence is stale ({int(age)} seconds old)")
for name in ("materialization_status", "rollout_status", "readiness_status", "smoke_status", "rollback_status", "old_credential_rejection_status", "audit_status"):
    if values[name] != "passed": fail(f"{name} is not passed")
if values["old_credential_revoked"] != "true": fail("old credential is not recorded as revoked")
if values["secrets_recorded"] != "false" or values["result"] != "passed": fail("credential rotation evidence is not promotion-ready or recorded secrets")
print("credential rotation evidence is valid for promotion")
PY
