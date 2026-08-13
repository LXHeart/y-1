#!/usr/bin/env bash
set -Eeuo pipefail

EVIDENCE=""
EXPECTED_RELEASE=""
MAX_AGE_SECONDS="86400"

usage() {
  cat <<'EOF'
Usage: scripts/validate-observability-evidence.sh --evidence PATH --release-id ID [--max-age-seconds N]

Validates measured Prometheus/Alertmanager delivery evidence. Static configuration alone is not accepted.
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
[[ -r "$EVIDENCE" ]] || die "observability evidence is not readable"
[[ "$EXPECTED_RELEASE" =~ ^[A-Za-z0-9._-]+$ ]] || die "--release-id is invalid"
[[ "$MAX_AGE_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--max-age-seconds must be positive"
(( MAX_AGE_SECONDS <= 604800 )) || die "--max-age-seconds cannot exceed 7 days"

python3 - "$EVIDENCE" "$EXPECTED_RELEASE" "$MAX_AGE_SECONDS" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import re
import sys

path, expected_release, max_age = sys.argv[1:]
max_age = int(max_age)
keys = {
    "evidence_id", "release_id", "alert_name", "started_at", "ended_at", "fired_at", "delivered_at", "resolved_at",
    "prometheus_query_status", "alertmanager_status", "receiver_status", "delivery_status", "resolved_status",
    "targets_status", "smoke_status", "secrets_recorded", "result",
}

def fail(message):
    raise SystemExit(f"ERROR: {message}")

lines = Path(path).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "# Grassland observability evidence v1":
    fail("observability evidence header/version is invalid")
values = {}
for line in lines[1:]:
    if not line or "=" not in line:
        fail("observability evidence contains a malformed line")
    key, value = line.split("=", 1)
    if not re.fullmatch(r"[a-z0-9_]+", key) or not value or key in values:
        fail("observability evidence contains an invalid, empty, or duplicate field")
    values[key] = value
missing, unknown = sorted(keys - values.keys()), sorted(values.keys() - keys)
if missing or unknown:
    fail(f"observability evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")

def stamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value):
        fail(f"{label} must be UTC ISO-8601 seconds")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        fail(f"{label} is invalid")

started = stamp(values["started_at"], "started_at")
ended = stamp(values["ended_at"], "ended_at")
fired = stamp(values["fired_at"], "fired_at")
delivered = stamp(values["delivered_at"], "delivered_at")
resolved = stamp(values["resolved_at"], "resolved_at")
age = (datetime.now(timezone.utc) - ended).total_seconds()
if ended < started or fired < started or delivered < fired or resolved < delivered or ended < resolved:
    fail("observability timestamps are out of order")
if age < -300:
    fail("observability evidence is in the future")
if age > max_age:
    fail(f"observability evidence is stale ({int(age)} seconds old)")
if values["release_id"] != expected_release or not re.fullmatch(r"[A-Za-z0-9._:-]+", values["alert_name"]):
    fail("release or alert name is invalid")
for name in ("prometheus_query_status", "alertmanager_status", "receiver_status", "delivery_status", "resolved_status", "targets_status", "smoke_status"):
    if values[name] != "passed":
        fail(f"{name} is not passed")
if values["secrets_recorded"] != "false" or values["result"] != "passed":
    fail("observability evidence is not promotion-ready or recorded secrets")
print("production observability evidence is valid for promotion")
PY
