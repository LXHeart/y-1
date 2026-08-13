#!/usr/bin/env bash
set -Eeuo pipefail

EVIDENCE=""
EXPECTED_RELEASE=""
EXPECTED_SCENARIO=""
MAX_AGE_SECONDS="604800"

usage() {
  cat <<'EOF'
Usage:
  scripts/validate-production-failure-evidence.sh --evidence PATH --release-id ID \
    --scenario NAME [--max-age-seconds N]

Validates measured fault-injection and recovery evidence. Dry-run plans are not accepted.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence) EVIDENCE="${2:?missing evidence}"; shift 2 ;;
    --release-id) EXPECTED_RELEASE="${2:?missing release ID}"; shift 2 ;;
    --scenario) EXPECTED_SCENARIO="${2:?missing scenario}"; shift 2 ;;
    --max-age-seconds) MAX_AGE_SECONDS="${2:?missing maximum age}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ -r "$EVIDENCE" ]] || die "failure evidence is not readable"
[[ "$EXPECTED_RELEASE" =~ ^[A-Za-z0-9._-]+$ ]] || die "--release-id is invalid"
case "$EXPECTED_SCENARIO" in
  kafka-unavailable|temporal-unavailable|minio-unavailable|video-provider-unavailable|readiness-failure) ;;
  *) die "unsupported --scenario" ;;
esac
[[ "$MAX_AGE_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--max-age-seconds must be positive"
(( MAX_AGE_SECONDS <= 2592000 )) || die "--max-age-seconds cannot exceed 30 days"

python3 - "$EVIDENCE" "$EXPECTED_RELEASE" "$EXPECTED_SCENARIO" "$MAX_AGE_SECONDS" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import re
import sys

path, expected_release, expected_scenario, max_age = sys.argv[1:]
max_age = int(max_age)
keys = {
    "evidence_id", "release_id", "scenario", "dependency", "started_at", "ended_at",
    "fault_injected_at", "recovered_at", "injection_status", "recovery_status",
    "alert_delivery_status", "readiness_status", "smoke_status", "data_consistency_status",
    "finance_reconciliation_status", "rto_seconds", "rpo_seconds", "rollback_ready",
    "result", "secrets_recorded",
}

def fail(message):
    raise SystemExit(f"ERROR: {message}")

lines = Path(path).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "# Grassland production failure evidence v1":
    fail("failure evidence header/version is invalid")
values = {}
for line in lines[1:]:
    if not line or "=" not in line:
        fail("failure evidence contains a malformed line")
    key, value = line.split("=", 1)
    if not re.fullmatch(r"[a-z0-9_]+", key) or not value or key in values:
        fail("failure evidence contains an invalid, empty, or duplicate field")
    values[key] = value
missing, unknown = sorted(keys - values.keys()), sorted(values.keys() - keys)
if missing or unknown:
    fail(f"failure evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")

def stamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value):
        fail(f"{label} must be UTC ISO-8601 seconds")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        fail(f"{label} is invalid")

started = stamp(values["started_at"], "started_at")
ended = stamp(values["ended_at"], "ended_at")
fault = stamp(values["fault_injected_at"], "fault_injected_at")
recovered = stamp(values["recovered_at"], "recovered_at")
age = (datetime.now(timezone.utc) - ended).total_seconds()
if ended < started or fault < started or recovered < fault or ended < recovered:
    fail("failure evidence timestamps are out of order")
if age < -300:
    fail("failure evidence is in the future")
if age > max_age:
    fail(f"failure evidence is stale ({int(age)} seconds old)")
if values["release_id"] != expected_release or values["scenario"] != expected_scenario:
    fail("failure release or scenario does not match expected target")
if not re.fullmatch(r"[A-Za-z0-9._-]+", values["evidence_id"]):
    fail("evidence_id is invalid")
if not values["dependency"] or "\n" in values["dependency"]:
    fail("dependency is invalid")
for name in ("injection_status", "recovery_status", "alert_delivery_status", "readiness_status", "smoke_status", "data_consistency_status", "finance_reconciliation_status", "rollback_ready"):
    if values[name] != "passed":
        fail(f"{name} is not passed")
for name in ("rto_seconds", "rpo_seconds"):
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)", values[name]):
        fail(f"{name} must be a non-negative integer")
if values["result"] != "passed" or values["secrets_recorded"] != "false":
    fail("failure evidence is not promotion-ready or recorded secrets")
print("production failure evidence is valid for promotion")
PY
