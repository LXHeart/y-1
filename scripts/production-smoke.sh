#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL=""
ACCESS_TOKEN="${RELEASE_SMOKE_ACCESS_TOKEN:-}"
ALLOW_HTTP=false

usage() {
  cat <<'EOF'
Usage: scripts/production-smoke.sh --base-url URL [--access-token TOKEN] [--allow-http]

Runs read-only public-entrypoint checks. Without an access token it verifies health, private-path
closure, unknown-route fail-closed behavior, authentication enforcement, and the configured real
video provider. With a short-lived access token it additionally verifies an authenticated Java
Marketplace task-feed read.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="${2:?missing base URL}"; shift 2 ;;
    --access-token) ACCESS_TOKEN="${2:?missing access token}"; shift 2 ;;
    --allow-http) ALLOW_HTTP=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v curl >/dev/null 2>&1 || die "curl is required"
[[ -n "$BASE_URL" ]] || die "--base-url is required"
BASE_URL="${BASE_URL%/}"
if [[ "$ALLOW_HTTP" != true && "$BASE_URL" != https://* ]]; then
  die "production smoke base URL must use https"
fi
[[ "$BASE_URL" == http://* || "$BASE_URL" == https://* ]] || die "base URL must use http or https"

response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

request_status() {
  local path="$1" token="${2:-}" args
  args=(--silent --show-error --output "$response_file" --write-out '%{http_code}' --max-time 10)
  [[ -z "$token" ]] || args+=(--header "Authorization: Bearer $token")
  curl "${args[@]}" "$BASE_URL$path"
}

expect_status() {
  local path="$1" expected="$2" token="${3:-}" status
  status="$(request_status "$path" "$token")" || die "request failed: $path"
  [[ "$status" == "$expected" ]] || die "$path returned HTTP $status, expected $expected"
  printf '[smoke] %-42s HTTP %s\n' "$path" "$status"
}

expect_status /health 200
expect_status /internal 404
expect_status /api/internal 404
expect_status /api/__release_smoke_not_found__ 404
expect_status /api/tasks/feed 401
command -v jq >/dev/null 2>&1 || die "jq is required for production smoke"
expect_status /api/video-production/capabilities 200
jq -e '.available == true and (.provider == "seedance" or .provider == "minimax")' \
  "$response_file" >/dev/null \
  || die "production video provider is unavailable or still using Sandbox"
echo "[smoke] configured production video provider passed"

if [[ -n "$ACCESS_TOKEN" ]]; then
  expect_status /api/tasks/feed 200 "$ACCESS_TOKEN"
  jq -e '.success == true and (.data.items | type == "array")' "$response_file" >/dev/null \
    || die "authenticated task feed returned an unexpected response contract"
  echo "[smoke] authenticated Java Marketplace read passed"
else
  echo "[smoke] protocol smoke passed; authenticated business read skipped (no RELEASE_SMOKE_ACCESS_TOKEN)"
fi
