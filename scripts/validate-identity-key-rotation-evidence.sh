#!/usr/bin/env bash
set -Eeuo pipefail

EVIDENCE=""
EXPECTED_RELEASE=""
MAX_AGE_SECONDS="604800"

usage() {
  cat <<'EOF'
Usage:
  scripts/validate-identity-key-rotation-evidence.sh --evidence PATH --release-id ID [--max-age-seconds N]

Validates a measured three-phase access-token or assertion key rotation. Secrets must never be
written to the evidence file; the validator checks ordering, TTL+leeway retirement, validation,
rollback readiness, and the release binding.
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
[[ -r "$EVIDENCE" ]] || die "key rotation evidence is not readable"
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
    "evidence_id", "release_id", "rotation_kind", "target", "old_kid", "new_kid",
    "phase_1_started_at", "phase_1_completed_at", "phase_2_started_at", "phase_2_completed_at",
    "phase_3_started_at", "phase_3_completed_at", "ttl_seconds", "leeway_seconds",
    "phase_1_status", "phase_2_status", "phase_3_status", "validation_status", "rollback_status",
    "old_key_retired", "secrets_recorded", "result",
}
def fail(message): raise SystemExit(f"ERROR: {message}")
lines = Path(path).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "# Grassland identity key rotation evidence v1": fail("key rotation evidence header/version is invalid")
values = {}
for line in lines[1:]:
    if not line or "=" not in line: fail("key rotation evidence contains a malformed line")
    key, value = line.split("=", 1)
    if not re.fullmatch(r"[a-z0-9_]+", key) or not value or key in values: fail("key rotation evidence contains an invalid, empty, or duplicate field")
    values[key] = value
missing, unknown = sorted(keys - values.keys()), sorted(values.keys() - keys)
if missing or unknown: fail(f"key rotation evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")
if values["release_id"] != expected_release: fail("rotation evidence release does not match expected release")
if values["rotation_kind"] not in ("access-token", "assertion"): fail("rotation_kind must be access-token or assertion")
if not re.fullmatch(r"[A-Za-z0-9._-]+", values["target"]): fail("rotation target is invalid")
for name in ("old_kid", "new_kid", "evidence_id"):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", values[name]): fail(f"{name} is invalid")
if values["old_kid"] == values["new_kid"]: fail("old and new kid must differ")
def stamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value): fail(f"{label} must be UTC ISO-8601 seconds")
    try: return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError: fail(f"{label} is invalid")
phase_times = []
for phase in ("phase_1", "phase_2", "phase_3"):
    started, ended = stamp(values[f"{phase}_started_at"], f"{phase}_started_at"), stamp(values[f"{phase}_completed_at"], f"{phase}_completed_at")
    if ended < started: fail(f"{phase} completed before it started")
    phase_times.append((started, ended))
if phase_times[1][0] < phase_times[0][1] or phase_times[2][0] < phase_times[1][1]: fail("rotation phases are out of order")
try:
    ttl, leeway = int(values["ttl_seconds"]), int(values["leeway_seconds"])
except ValueError: fail("ttl_seconds and leeway_seconds must be integers")
if ttl < 0 or leeway < 0: fail("ttl_seconds and leeway_seconds must be non-negative")
if (phase_times[2][0] - phase_times[1][1]).total_seconds() < ttl + leeway: fail("phase 3 started before TTL plus leeway elapsed")
age = (datetime.now(timezone.utc) - phase_times[2][1]).total_seconds()
if age < -300: fail("rotation evidence is in the future")
if age > max_age: fail(f"rotation evidence is stale ({int(age)} seconds old)")
for name in ("phase_1_status", "phase_2_status", "phase_3_status", "validation_status", "rollback_status"):
    if values[name] != "passed": fail(f"{name} is not passed")
if values["old_key_retired"] != "true": fail("old key was not retired")
if values["secrets_recorded"] != "false" or values["result"] != "passed": fail("rotation evidence is not promotion-ready or recorded secrets")
print("identity key rotation evidence is valid for promotion")
PY
