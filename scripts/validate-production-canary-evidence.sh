#!/usr/bin/env bash
set -Eeuo pipefail

EVIDENCE=""; EXPECTED_RELEASE=""; EXPECTED_SERVICE=""; EXPECTED_CANARY_IMAGE=""; EXPECTED_BASELINE_IMAGE=""; MAX_AGE_SECONDS="86400"
usage() {
  cat <<'EOF'
Usage:
  scripts/validate-production-canary-evidence.sh --evidence PATH --release-id ID --service NAME \
    --canary-image DIGEST --baseline-image DIGEST [--max-age-seconds N]

Validates measured canary hold-point evidence for promotion. Dry-run plans are not accepted.
EOF
}
die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence) EVIDENCE="${2:?missing evidence}"; shift 2 ;;
    --release-id) EXPECTED_RELEASE="${2:?missing release ID}"; shift 2 ;;
    --service) EXPECTED_SERVICE="${2:?missing service}"; shift 2 ;;
    --canary-image) EXPECTED_CANARY_IMAGE="${2:?missing canary image}"; shift 2 ;;
    --baseline-image) EXPECTED_BASELINE_IMAGE="${2:?missing baseline image}"; shift 2 ;;
    --max-age-seconds) MAX_AGE_SECONDS="${2:?missing maximum age}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ -r "$EVIDENCE" ]] || die "canary evidence is not readable"
[[ "$EXPECTED_RELEASE" =~ ^[A-Za-z0-9._-]+$ ]] || die "--release-id is invalid"
[[ "$EXPECTED_SERVICE" =~ ^[a-z0-9][a-z0-9-]*$ ]] || die "--service is invalid"
[[ "$EXPECTED_CANARY_IMAGE" =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] || die "--canary-image must contain a lowercase SHA-256 digest"
[[ "$EXPECTED_BASELINE_IMAGE" =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] || die "--baseline-image must contain a lowercase SHA-256 digest"
[[ "$MAX_AGE_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--max-age-seconds must be positive"
(( MAX_AGE_SECONDS <= 604800 )) || die "--max-age-seconds cannot exceed 604800 (7 days)"

python3 - "$EVIDENCE" "$EXPECTED_RELEASE" "$EXPECTED_SERVICE" "$EXPECTED_CANARY_IMAGE" "$EXPECTED_BASELINE_IMAGE" "$MAX_AGE_SECONDS" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import re
import sys

path, expected_release, expected_service, expected_canary, expected_baseline, max_age = sys.argv[1:]
max_age = int(max_age)
keys = {
    "evidence_id", "release_id", "service", "canary_image", "baseline_image", "started_at", "ended_at",
    "warmup_status", "phase_1_status", "phase_10_status", "phase_25_status", "phase_50_status", "phase_100_status",
    "warmup_minutes", "phase_1_minutes", "phase_10_minutes", "phase_25_minutes", "phase_50_minutes", "phase_100_minutes",
    "canary_5xx_pct", "baseline_5xx_pct", "canary_p95_ms", "baseline_p95_ms", "readiness_status", "dependency_status",
    "finance_reconciliation_status", "alert_delivery_status", "smoke_status", "rollback_ready", "result", "secrets_recorded",
}
def fail(message):
    raise SystemExit(f"ERROR: {message}")
lines = Path(path).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "# Grassland production canary evidence v1": fail("canary evidence header/version is invalid")
values = {}
for line in lines[1:]:
    if not line or "=" not in line: fail("canary evidence contains a malformed line")
    key, value = line.split("=", 1)
    if not re.fullmatch(r"[a-z0-9_]+", key) or not value or key in values: fail("canary evidence contains an invalid, empty, or duplicate field")
    values[key] = value
missing, unknown = sorted(keys - values.keys()), sorted(values.keys() - keys)
if missing or unknown: fail(f"canary evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")
def stamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value): fail(f"{label} must be UTC ISO-8601 seconds")
    try: return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError: fail(f"{label} is invalid")
started, ended = stamp(values["started_at"], "started_at"), stamp(values["ended_at"], "ended_at")
age = (datetime.now(timezone.utc) - ended).total_seconds()
if ended < started: fail("canary evidence ended before it started")
if age < -300: fail("canary evidence is in the future")
if age > max_age: fail(f"canary evidence is stale ({int(age)} seconds old)")
if values["release_id"] != expected_release or values["service"] != expected_service: fail("canary release or service does not match expected target")
if values["canary_image"] != expected_canary or values["baseline_image"] != expected_baseline: fail("canary/baseline digest does not match expected target")
if not re.fullmatch(r"[A-Za-z0-9._-]+", values["evidence_id"]): fail("evidence_id is invalid")
for name in ("warmup_status", "phase_1_status", "phase_10_status", "phase_25_status", "phase_50_status", "phase_100_status", "readiness_status", "dependency_status", "finance_reconciliation_status", "alert_delivery_status", "smoke_status", "rollback_ready"):
    if values[name] != "passed": fail(f"{name} is not passed")
minimum_holds = {"warmup_minutes": 5, "phase_1_minutes": 10, "phase_10_minutes": 10, "phase_25_minutes": 10, "phase_50_minutes": 10, "phase_100_minutes": 10}
for name, minimum in minimum_holds.items():
    if not re.fullmatch(r"[1-9][0-9]*", values[name]) or int(values[name]) < minimum: fail(f"{name} is shorter than the required {minimum}-minute hold")
for name in ("canary_5xx_pct", "baseline_5xx_pct", "canary_p95_ms", "baseline_p95_ms"):
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", values[name]): fail(f"{name} is not a non-negative decimal")
if float(values["canary_5xx_pct"]) > 5 or float(values["canary_5xx_pct"]) - float(values["baseline_5xx_pct"]) > 2: fail("canary 5xx ratio crosses abort threshold")
if float(values["canary_p95_ms"]) > 2000 or float(values["canary_p95_ms"]) > float(values["baseline_p95_ms"]) * 1.5: fail("canary p95 latency crosses abort threshold")
if values["result"] != "passed" or values["secrets_recorded"] != "false": fail("canary evidence is not promotion-ready or recorded secrets")
print("production canary evidence is valid for promotion")
PY
