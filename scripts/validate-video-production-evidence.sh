#!/usr/bin/env bash
set -Eeuo pipefail

WEBHOOK_EVIDENCE=""
ARCHIVE_EVIDENCE=""
EXPECTED_JOB_ID=""
EXPECTED_PROVIDER=""
EXPECTED_POLICY_VERSION=""
MAX_AGE_SECONDS="3600"

usage() {
  cat <<'EOF'
Usage:
  scripts/validate-video-production-evidence.sh \
    --webhook-evidence PATH --archive-evidence PATH \
    --job-id UUID --provider seedance|minimax --policy-version VERSION \
    [--max-age-seconds SECONDS]

Validates a redacted, fresh production video evidence bundle. This is a fail-closed promotion
gate: policy_missing and passed_with_monetary_policy_pending evidence are never accepted.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --webhook-evidence) WEBHOOK_EVIDENCE="${2:?missing webhook evidence}"; shift 2 ;;
    --archive-evidence) ARCHIVE_EVIDENCE="${2:?missing archive evidence}"; shift 2 ;;
    --job-id) EXPECTED_JOB_ID="${2:?missing Job ID}"; shift 2 ;;
    --provider) EXPECTED_PROVIDER="${2:?missing provider}"; shift 2 ;;
    --policy-version) EXPECTED_POLICY_VERSION="${2:?missing policy version}"; shift 2 ;;
    --max-age-seconds) MAX_AGE_SECONDS="${2:?missing maximum age}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ -r "$WEBHOOK_EVIDENCE" ]] || die "webhook evidence is not readable"
[[ -r "$ARCHIVE_EVIDENCE" ]] || die "archive evidence is not readable"
[[ "$EXPECTED_JOB_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
  || die "--job-id must be a UUID"
[[ "$EXPECTED_PROVIDER" == seedance || "$EXPECTED_PROVIDER" == minimax ]] \
  || die "--provider must be seedance or minimax"
[[ "$EXPECTED_POLICY_VERSION" =~ ^[A-Za-z0-9._-]{1,64}$ ]] \
  || die "--policy-version must be a 1..64 character ASCII identifier"
[[ "$MAX_AGE_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--max-age-seconds must be a positive integer"
(( MAX_AGE_SECONDS <= 604800 )) || die "--max-age-seconds cannot exceed 604800 (7 days)"

python3 - "$WEBHOOK_EVIDENCE" "$ARCHIVE_EVIDENCE" "$EXPECTED_JOB_ID" \
  "$EXPECTED_PROVIDER" "$EXPECTED_POLICY_VERSION" "$MAX_AGE_SECONDS" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import re
import sys

webhook_path, archive_path, expected_job, expected_provider, expected_policy, max_age = sys.argv[1:]
max_age = int(max_age)

WEBHOOK_HEADER = "# Grassland video webhook drill evidence v1"
ARCHIVE_HEADER = "# Grassland video archive and reconciliation evidence v1"
WEBHOOK_KEYS = {
    "drill_id", "provider", "provider_task_id", "job_id", "started_at", "ended_at",
    "progress_80_http", "same_event_replay_http", "stale_progress_20_http",
    "invalid_signature_http", "final_job_status", "final_job_progress", "result",
    "secrets_recorded",
}
ARCHIVE_KEYS = {
    "drill_id", "job_id", "started_at", "ended_at", "reconciliation_http",
    "finance_authority_state", "local_reconciliation_state", "monetary_conversion_state",
    "download_url_http", "object_download_http", "object_size_bytes", "object_sha256",
    "result", "secrets_recorded", "signed_url_recorded",
}

def fail(message):
    raise SystemExit(f"ERROR: {message}")

def parse(path, expected_header, expected_keys, label):
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        fail(f"cannot read {label} evidence: {error}")
    if not lines or lines[0] != expected_header:
        fail(f"{label} evidence header/version is invalid")
    if any(not line or "=" not in line for line in lines[1:]):
        fail(f"{label} evidence contains a blank or malformed line")
    values = {}
    for line in lines[1:]:
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[a-z0-9_]+", key) or not value:
            fail(f"{label} evidence contains an invalid key or empty value")
        if key in values:
            fail(f"{label} evidence contains duplicate field: {key}")
        values[key] = value
    missing = sorted(expected_keys - values.keys())
    unknown = sorted(values.keys() - expected_keys)
    if missing or unknown:
        fail(f"{label} evidence fields differ from v1 contract; missing={missing}, unknown={unknown}")
    return values

def timestamp(value, label):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value):
        fail(f"{label} must be a UTC second-precision timestamp")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        fail(f"{label} is not a real timestamp")

def validate_window(values, label):
    started = timestamp(values["started_at"], f"{label}.started_at")
    ended = timestamp(values["ended_at"], f"{label}.ended_at")
    now = datetime.now(timezone.utc)
    if ended < started:
        fail(f"{label} ended before it started")
    if (ended - started).total_seconds() > 1800:
        fail(f"{label} duration exceeds 30 minutes")
    age = (now - ended).total_seconds()
    if age < -300:
        fail(f"{label} evidence is more than 5 minutes in the future")
    if age > max_age:
        fail(f"{label} evidence is stale ({int(age)} seconds old; maximum {max_age})")

webhook = parse(webhook_path, WEBHOOK_HEADER, WEBHOOK_KEYS, "webhook")
archive = parse(archive_path, ARCHIVE_HEADER, ARCHIVE_KEYS, "archive")
validate_window(webhook, "webhook")
validate_window(archive, "archive")

if webhook["job_id"].lower() != expected_job.lower() or archive["job_id"].lower() != expected_job.lower():
    fail("evidence Job IDs do not both match the expected Job")
if webhook["job_id"].lower() != archive["job_id"].lower():
    fail("webhook and archive evidence Job IDs differ")
if webhook["provider"] != expected_provider:
    fail("webhook evidence provider does not match the expected provider")
if not re.fullmatch(r"[A-Za-z0-9._:/-]+", webhook["provider_task_id"]):
    fail("provider task ID is malformed")
if not re.fullmatch(r"[A-Za-z0-9._-]+", webhook["drill_id"]):
    fail("webhook drill ID is malformed")
if not re.fullmatch(r"[A-Za-z0-9._-]+", archive["drill_id"]):
    fail("archive drill ID is malformed")

for key in ("progress_80_http", "same_event_replay_http", "stale_progress_20_http"):
    if webhook[key] != "200":
        fail(f"webhook {key} must be 200")
bad_signature = webhook["invalid_signature_http"]
if not re.fullmatch(r"[1-5]\d\d", bad_signature) or bad_signature.startswith("2"):
    fail("invalid signature evidence must contain a non-2xx HTTP status")
if webhook["final_job_status"] != "processing" or webhook["final_job_progress"] != "80":
    fail("webhook monotonic progress result is invalid")
if webhook["result"] != "passed" or webhook["secrets_recorded"] != "false":
    fail("webhook evidence did not pass or recorded secrets")

for key in ("reconciliation_http", "download_url_http", "object_download_http"):
    if archive[key] != "200":
        fail(f"archive {key} must be 200")
if archive["finance_authority_state"] != "consumed":
    fail("Finance authority state is not consumed")
if archive["local_reconciliation_state"] != "consistent":
    fail("local reconciliation state is not consistent")
if archive["monetary_conversion_state"] != expected_policy:
    fail("monetary conversion policy version does not match the approved release policy")
if archive["monetary_conversion_state"] == "policy_missing":
    fail("policy_missing evidence cannot be promoted")
if archive["result"] != "passed":
    fail("archive evidence is not promotion-ready")
if archive["secrets_recorded"] != "false" or archive["signed_url_recorded"] != "false":
    fail("archive evidence recorded a secret or signed URL")
if not re.fullmatch(r"[1-9]\d*", archive["object_size_bytes"]):
    fail("object size is malformed")
size = int(archive["object_size_bytes"])
if size > 209715200:
    fail("object size exceeds the 200 MiB archive limit")
if not re.fullmatch(r"[0-9a-f]{64}", archive["object_sha256"]):
    fail("object SHA-256 must be 64 lowercase hexadecimal characters")

print("video production evidence bundle is valid for promotion")
PY
