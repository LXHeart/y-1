#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-}"; [[ -z "$MODE" ]] || shift
BASE_URL=""; JOB_ID=""; DRILL_ID=""; OWNER_TOKEN_FILE=""; ADMIN_TOKEN_FILE=""
OUTPUT=""; CONFIRM=false; ACK_POLICY=false; EXPECTED_POLICY_VERSION=""; ALLOW_HTTP=false

usage() {
  cat <<'EOF'
Usage:
  scripts/video-archive-reconciliation-drill.sh plan --base-url URL --job-id UUID --drill-id ID [--output PATH]
  scripts/video-archive-reconciliation-drill.sh run --base-url URL --job-id UUID --drill-id ID \
    --owner-token-file PATH --admin-token-file PATH --output PATH \
    --confirm-live-archive-drill \
    (--expected-policy-version VERSION | --ack-monetary-policy-missing)

Validates a completed video Job against the Intelligence reconciliation report, Finance's
authoritative credit consume fence, and a real download from private object storage. Evidence is
redacted and never includes access tokens or the signed download URL.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="${2:?missing base URL}"; shift 2 ;;
    --job-id) JOB_ID="${2:?missing job id}"; shift 2 ;;
    --drill-id) DRILL_ID="${2:?missing drill id}"; shift 2 ;;
    --owner-token-file) OWNER_TOKEN_FILE="${2:?missing owner token file}"; shift 2 ;;
    --admin-token-file) ADMIN_TOKEN_FILE="${2:?missing admin token file}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    --confirm-live-archive-drill) CONFIRM=true; shift ;;
    --ack-monetary-policy-missing) ACK_POLICY=true; shift ;;
    --expected-policy-version) EXPECTED_POLICY_VERSION="${2:?missing policy version}"; shift 2 ;;
    --allow-http) ALLOW_HTTP=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$MODE" == plan || "$MODE" == run ]] || { usage >&2; exit 2; }
command -v curl >/dev/null 2>&1 || die "curl is required"
command -v jq >/dev/null 2>&1 || die "jq is required"
command -v shasum >/dev/null 2>&1 || die "shasum is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ "$JOB_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || die "--job-id must be a UUID"
[[ "$DRILL_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "--drill-id contains unsupported characters"
BASE_URL="${BASE_URL%/}"
[[ "$BASE_URL" == http://* || "$BASE_URL" == https://* ]] || die "--base-url must use http or https"
if [[ "$ALLOW_HTTP" != true && "$BASE_URL" != https://* ]]; then die "live archive drill requires https"; fi
[[ -z "$OUTPUT" || ( "$OUTPUT" != / && "$OUTPUT" != */ ) ]] || die "--output must be a file path"

plan="$(cat <<EOF
# Grassland video archive and reconciliation drill plan
drill_id=$DRILL_ID
job_id=$JOB_ID
base_url=$BASE_URL
mode=dry-run

Preconditions:
- The Job completed through a real Seedance/MiniMax provider and private object-storage archival.
- The operator has short-lived owner and platform-admin tokens in separate 0600 files.
- Finance and Intelligence are healthy; the Job remains within the latest 500 reconciliation rows.

Checks:
- The admin report classifies this Job consistent and Finance authority reports consumed.
- The report explicitly declares monetaryConversionState=policy_missing; no credits-to-cents rate is inferred.
- The owner download endpoint returns a short-lived HTTPS URL and downloading it yields 1..209715200 bytes.
- Redacted evidence records size and SHA-256 but never the token or signed URL.

No request was sent by this plan.
EOF
)"
if [[ "$MODE" == plan ]]; then
  if [[ -n "$OUTPUT" ]]; then mkdir -p "$(dirname "$OUTPUT")"; printf '%s\n' "$plan" > "$OUTPUT"; echo "dry-run plan written: $OUTPUT"; else printf '%s\n' "$plan"; fi
  exit 0
fi

[[ "$CONFIRM" == true ]] || die "run requires --confirm-live-archive-drill"
if [[ -n "$EXPECTED_POLICY_VERSION" ]]; then
  [[ "$EXPECTED_POLICY_VERSION" =~ ^[A-Za-z0-9._-]{1,64}$ ]] \
    || die "--expected-policy-version must be a 1..64 character ASCII identifier"
  [[ "$ACK_POLICY" != true ]] || die "choose expected policy version or missing-policy acknowledgement, not both"
else
  [[ "$ACK_POLICY" == true ]] || die "run requires --expected-policy-version or --ack-monetary-policy-missing"
fi
[[ -n "$OUTPUT" ]] || die "run requires --output"
for file in "$OWNER_TOKEN_FILE" "$ADMIN_TOKEN_FILE"; do
  [[ -n "$file" && -f "$file" ]] || die "run requires existing owner/admin token files"
  if [[ "$(uname -s)" == Darwin ]]; then mode="$(stat -f '%Lp' "$file")"; else mode="$(stat -c '%a' "$file")"; fi
  [[ "$mode" == 600 || "$mode" == 400 ]] || die "token file permissions must be 0600 or 0400"
done

umask 077
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
make_auth_header() {
  python3 -c 'import pathlib,sys
t=pathlib.Path(sys.argv[1]).read_text().rstrip("\r\n")
if not t: raise SystemExit("token file is empty")
pathlib.Path(sys.argv[2]).write_text("Authorization: Bearer "+t+"\n")' "$1" "$2"
}
make_auth_header "$OWNER_TOKEN_FILE" "$tmp/owner-header"
make_auth_header "$ADMIN_TOKEN_FILE" "$tmp/admin-header"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

reconcile_http="$(curl --silent --show-error --output "$tmp/reconcile.json" --write-out '%{http_code}' \
  --max-time 20 --header "@$tmp/admin-header" "$BASE_URL/api/admin/ai/video-reconciliation?limit=500")" \
  || die "reconciliation request failed"
[[ "$reconcile_http" == 200 ]] || die "reconciliation returned HTTP $reconcile_http"
item="$(jq -cer --arg id "$JOB_ID" '.items[] | select(.jobId == $id)' "$tmp/reconcile.json")" \
  || die "Job is absent from the latest reconciliation report"
[[ "$(jq -r '.reconciliationState' <<<"$item")" == consistent ]] || die "Job reconciliation is not consistent"
[[ "$(jq -r '.financeAuthorityState' <<<"$item")" == consumed ]] || die "Finance consume fence is not consumed"
policy_state="$(jq -er '.monetaryConversionState' <<<"$item")" \
  || die "reconciliation item lacks monetaryConversionState"
report_policy_state="$(jq -er '.monetaryConversionState' "$tmp/reconcile.json")" \
  || die "reconciliation report lacks monetaryConversionState"
[[ "$policy_state" == "$report_policy_state" ]] \
  || die "report and Job monetary conversion policy states differ"
if [[ -n "$EXPECTED_POLICY_VERSION" ]]; then
  [[ "$policy_state" == "$EXPECTED_POLICY_VERSION" ]] \
    || die "monetary conversion policy does not match --expected-policy-version"
  evidence_result=passed
else
  [[ "$policy_state" == policy_missing ]] || die "monetary policy is configured; do not acknowledge it as missing"
  evidence_result=passed_with_monetary_policy_pending
fi
[[ "$(jq -r '.issues | length' <<<"$item")" == 0 ]] || die "Job reconciliation contains issues"

download_http="$(curl --silent --show-error --output "$tmp/download.json" --write-out '%{http_code}' \
  --max-time 20 --header "@$tmp/owner-header" "$BASE_URL/api/video-production/jobs/$JOB_ID/download-url")" \
  || die "download URL request failed"
[[ "$download_http" == 200 ]] || die "download URL returned HTTP $download_http"
download_url="$(jq -er '.downloadUrl' "$tmp/download.json")" || die "download response lacks downloadUrl"
if [[ "$ALLOW_HTTP" != true && "$download_url" != https://* ]]; then die "signed object URL must use https"; fi
[[ "$download_url" == http://* || "$download_url" == https://* ]] || die "signed object URL is invalid"
[[ "$download_url" != *$'\n'* && "$download_url" != *$'\r'* \
   && "$download_url" != *'"'* && "$download_url" != *'\\'* ]] \
  || die "signed object URL contains unsupported characters"
printf 'url = "%s"\n' "$download_url" > "$tmp/object-curl-config"
object_http="$(curl --silent --show-error --output "$tmp/video.bin" --write-out '%{http_code}' \
  --max-time 120 --config "$tmp/object-curl-config")" || die "object download failed"
[[ "$object_http" == 200 ]] || die "object download returned HTTP $object_http"
bytes="$(wc -c < "$tmp/video.bin" | tr -d ' ')"
[[ "$bytes" -ge 1 && "$bytes" -le 209715200 ]] || die "downloaded object size is outside archive limits"
checksum="$(shasum -a 256 "$tmp/video.bin" | awk '{print $1}')"
ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mkdir -p "$(dirname "$OUTPUT")"
cat > "$OUTPUT" <<EOF
# Grassland video archive and reconciliation evidence v1
drill_id=$DRILL_ID
job_id=$JOB_ID
started_at=$started_at
ended_at=$ended_at
reconciliation_http=$reconcile_http
finance_authority_state=consumed
local_reconciliation_state=consistent
monetary_conversion_state=$policy_state
download_url_http=$download_http
object_download_http=$object_http
object_size_bytes=$bytes
object_sha256=$checksum
result=$evidence_result
secrets_recorded=false
signed_url_recorded=false
EOF
echo "archive and authority reconciliation passed; redacted evidence written: $OUTPUT"
