#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-}"
[[ -n "$MODE" ]] && shift
BASE_URL=""
PROVIDER=""
PROVIDER_TASK_ID=""
JOB_ID=""
ACCESS_TOKEN_FILE=""
SECRET_FILE=""
DRILL_ID=""
OUTPUT=""
CONFIRM=""
ALLOW_HTTP=false

usage() {
  cat <<'EOF'
Usage:
  scripts/video-webhook-drill.sh plan --base-url URL --provider seedance|minimax \
    --provider-task-id ID --job-id UUID --drill-id ID [--output PATH] [--allow-http]
  scripts/video-webhook-drill.sh run --base-url URL --provider seedance|minimax \
    --provider-task-id ID --job-id UUID --drill-id ID --access-token-file PATH \
    --secret-file PATH --output PATH --confirm-live-webhook-drill [--allow-http]

Exercises a dedicated non-terminal video Job through the public webhook route: valid 80% progress,
same-event replay, a newer event carrying stale 20% progress, and an invalid signature. Run mode
mutates only the named Job and writes redacted evidence; it never prints or stores the token,
signature, or webhook secret.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="${2:?missing base URL}"; shift 2 ;;
    --provider) PROVIDER="${2:?missing provider}"; shift 2 ;;
    --provider-task-id) PROVIDER_TASK_ID="${2:?missing provider task id}"; shift 2 ;;
    --job-id) JOB_ID="${2:?missing job id}"; shift 2 ;;
    --drill-id) DRILL_ID="${2:?missing drill id}"; shift 2 ;;
    --access-token-file) ACCESS_TOKEN_FILE="${2:?missing access token file}"; shift 2 ;;
    --secret-file) SECRET_FILE="${2:?missing secret file}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    --confirm-live-webhook-drill) CONFIRM=true; shift ;;
    --allow-http) ALLOW_HTTP=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$MODE" == plan || "$MODE" == run ]] || { usage >&2; exit 2; }
command -v curl >/dev/null 2>&1 || die "curl is required"
command -v jq >/dev/null 2>&1 || die "jq is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
[[ "$PROVIDER" == seedance || "$PROVIDER" == minimax ]] || die "--provider must be seedance or minimax"
[[ "$JOB_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || die "--job-id must be a UUID"
[[ "$DRILL_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "--drill-id contains unsupported characters"
[[ "$PROVIDER_TASK_ID" =~ ^[A-Za-z0-9._:/-]+$ ]] \
  || die "--provider-task-id contains unsupported characters"
BASE_URL="${BASE_URL%/}"
[[ "$BASE_URL" == http://* || "$BASE_URL" == https://* ]] || die "--base-url must use http or https"
if [[ "$ALLOW_HTTP" != true && "$BASE_URL" != https://* ]]; then
  die "live webhook drill requires https"
fi
[[ -z "$OUTPUT" || ( "$OUTPUT" != / && "$OUTPUT" != */ ) ]] || die "--output must be a file path"

plan_text="$(cat <<EOF
# Grassland video webhook drill plan
drill_id=$DRILL_ID
provider=$PROVIDER
provider_task_id=$PROVIDER_TASK_ID
job_id=$JOB_ID
base_url=$BASE_URL
mode=dry-run

Preconditions:
- The named Job is a dedicated, owner-accessible, non-terminal production drill Job for this provider task ID.
- Provider polling is paused for this Job or confirmed not to race the short drill window.
- The operator has a short-lived owner access token and a 0600 webhook secret file.
- Dashboards, alert receiver, object storage, AI Run, compensation, and reconciliation views are observable.

Sequence:
- Send signed processing progress 80, perform a same-event replay with identical bytes, then send a new signed event with stale progress 20.
- Query the owner-scoped Job and require status=processing and progress=80.
- Send the same body with an invalid signature and require a non-2xx response.

Evidence:
- Redacted HTTP status codes, event IDs, final Job status/progress, and start/end timestamps.
- Operator separately records ingress logs, inbox row counts, alerts, and reconciliation output in the maintenance ticket.

No webhook was sent by this plan.
EOF
)"

if [[ "$MODE" == plan ]]; then
  if [[ -n "$OUTPUT" ]]; then
    mkdir -p "$(dirname "$OUTPUT")"
    printf '%s\n' "$plan_text" > "$OUTPUT"
    printf 'dry-run plan written: %s\n' "$OUTPUT"
  else
    printf '%s\n' "$plan_text"
  fi
  exit 0
fi

[[ "$CONFIRM" == true ]] || die "run requires --confirm-live-webhook-drill"
[[ -n "$ACCESS_TOKEN_FILE" && -f "$ACCESS_TOKEN_FILE" ]] \
  || die "run requires an existing --access-token-file"
[[ -n "$SECRET_FILE" && -f "$SECRET_FILE" ]] || die "run requires an existing --secret-file"
[[ -n "$OUTPUT" ]] || die "run requires --output"
file_mode() {
  if [[ "$(uname -s)" == Darwin ]]; then stat -f '%Lp' "$1"; else stat -c '%a' "$1"; fi
}
secret_mode="$(file_mode "$SECRET_FILE")"
token_mode="$(file_mode "$ACCESS_TOKEN_FILE")"
[[ "$secret_mode" == 600 || "$secret_mode" == 400 ]] \
  || die "--secret-file permissions must be 0600 or 0400"
[[ "$token_mode" == 600 || "$token_mode" == 400 ]] \
  || die "--access-token-file permissions must be 0600 or 0400"
secret_length="$(python3 -c 'import pathlib,sys; print(len(pathlib.Path(sys.argv[1]).read_text().rstrip("\r\n")))' \
  "$SECRET_FILE")"
[[ "$secret_length" -ge 32 ]] || die "webhook secret must be at least 32 characters"

umask 077
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
webhook_url="$BASE_URL/api/video-production/webhooks/$PROVIDER"
job_url="$BASE_URL/api/video-production/jobs/$JOB_ID"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
event_80="$DRILL_ID-progress-80"
event_20="$DRILL_ID-stale-20"
body_80="$(jq -cn --arg task "$PROVIDER_TASK_ID" \
  '{task_id:$task,status:"processing",progress:80}')"
body_20="$(jq -cn --arg task "$PROVIDER_TASK_ID" \
  '{task_id:$task,status:"processing",progress:20}')"

signature() {
  local timestamp="$1" event_id="$2" body="$3"
  python3 -c 'import hashlib,hmac,pathlib,sys
secret=pathlib.Path(sys.argv[1]).read_text().rstrip("\r\n").encode()
value=(sys.argv[2]+"."+sys.argv[3]+"."+sys.argv[4]).encode()
print(hmac.new(secret,value,hashlib.sha256).hexdigest())' \
    "$SECRET_FILE" "$timestamp" "$event_id" "$body"
}

send_webhook() {
  local event_id="$1" timestamp="$2" body="$3" signed="$4" header_file
  header_file="$tmp_dir/webhook-headers-$event_id"
  cat > "$header_file" <<EOF
Content-Type: application/json
X-Video-Event-Id: $event_id
X-Video-Timestamp: $timestamp
X-Video-Signature: $signed
EOF
  curl --silent --show-error --output "$tmp_dir/webhook-response" --write-out '%{http_code}' \
    --max-time 15 --request POST --header "@$header_file" --data-binary "$body" "$webhook_url"
}

timestamp_80="$(date +%s)"
signature_80="$(signature "$timestamp_80" "$event_80" "$body_80")"
status_80="$(send_webhook "$event_80" "$timestamp_80" "$body_80" "$signature_80")" \
  || die "80% webhook request failed"
[[ "$status_80" == 200 ]] || die "80% webhook returned HTTP $status_80"
status_replay="$(send_webhook "$event_80" "$timestamp_80" "$body_80" "$signature_80")" \
  || die "replay webhook request failed"
[[ "$status_replay" == 200 ]] || die "replay webhook returned HTTP $status_replay"
timestamp_20="$(date +%s)"
signature_20="$(signature "$timestamp_20" "$event_20" "$body_20")"
status_stale="$(send_webhook "$event_20" "$timestamp_20" "$body_20" "$signature_20")" \
  || die "stale webhook request failed"
[[ "$status_stale" == 200 ]] || die "stale webhook returned HTTP $status_stale"

python3 -c 'import pathlib,sys
token=pathlib.Path(sys.argv[1]).read_text().rstrip("\r\n")
if not token: raise SystemExit("access token file is empty")
pathlib.Path(sys.argv[2]).write_text("Authorization: Bearer "+token+"\n")' \
  "$ACCESS_TOKEN_FILE" "$tmp_dir/authorization-header"
job_status="$(curl --silent --show-error --output "$tmp_dir/job-response" --write-out '%{http_code}' \
  --max-time 15 --header "@$tmp_dir/authorization-header" "$job_url")" || die "Job query failed"
[[ "$job_status" == 200 ]] || die "Job query returned HTTP $job_status"
final_state="$(jq -er '.data.status' "$tmp_dir/job-response")" || die "Job response lacks data.status"
final_progress="$(jq -er '.data.progress' "$tmp_dir/job-response")" || die "Job response lacks data.progress"
[[ "$final_state" == processing && "$final_progress" == 80 ]] \
  || die "out-of-order invariant failed: status=$final_state progress=$final_progress"

status_bad_signature="$(send_webhook "$DRILL_ID-bad-signature" "$(date +%s)" "$body_20" "00")" \
  || die "invalid-signature request failed"
[[ "$status_bad_signature" != 2* ]] || die "invalid signature was accepted with HTTP $status_bad_signature"

ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "$(dirname "$OUTPUT")"
cat > "$OUTPUT" <<EOF
# Grassland video webhook drill evidence v1
drill_id=$DRILL_ID
provider=$PROVIDER
provider_task_id=$PROVIDER_TASK_ID
job_id=$JOB_ID
started_at=$started_at
ended_at=$ended_at
progress_80_http=$status_80
same_event_replay_http=$status_replay
stale_progress_20_http=$status_stale
invalid_signature_http=$status_bad_signature
final_job_status=$final_state
final_job_progress=$final_progress
result=passed
secrets_recorded=false
EOF
printf 'live webhook drill passed; redacted evidence written: %s\n' "$OUTPUT"
