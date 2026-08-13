#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.observability.yml"
KEEP="false"

usage() {
  cat <<'EOF'
Usage: scripts/local-otel-trace-smoke.sh [--keep]

Starts the local-only OTel Collector, sends one OTLP/HTTP JSON span, and proves that the
collector debug exporter received its trace/span IDs. This is development evidence only.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

compose=(docker compose --project-name grassland-otel-smoke --project-directory "$ROOT_DIR" --profile observability -f "$COMPOSE_FILE")
cleanup() {
  if [[ "$KEEP" != true ]]; then
    "${compose[@]}" down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

"${compose[@]}" up -d otel-collector >/dev/null

health_url="http://127.0.0.1:${OTEL_COLLECTOR_HEALTH_PORT:-13133}/"
for _ in {1..30}; do
  curl --fail --silent --show-error "$health_url" >/dev/null 2>&1 && break
  sleep 1
done
curl --fail --silent --show-error "$health_url" >/dev/null \
  || { "${compose[@]}" logs --no-color otel-collector >&2; echo "collector did not become healthy" >&2; exit 1; }

trace_id="$(openssl rand -hex 16)"
span_id="$(openssl rand -hex 8)"
started="$(date +%s)000000000"
ended="$((started + 1000000))"
payload="$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"grassland-local-smoke"}}]},"scopeSpans":[{"scope":{"name":"grassland.local.smoke"},"spans":[{"traceId":"%s","spanId":"%s","name":"local-otlp-delivery-smoke","kind":1,"startTimeUnixNano":"%s","endTimeUnixNano":"%s","status":{"code":1}}]}]}]}' "$trace_id" "$span_id" "$started" "$ended")"

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  --data "$payload" \
  "http://127.0.0.1:${OTEL_COLLECTOR_HTTP_PORT:-4318}/v1/traces" >/dev/null

for _ in {1..15}; do
  logs="$("${compose[@]}" logs --no-color otel-collector 2>&1)"
  if [[ "$logs" == *"Trace ID"*"$trace_id"* && "$logs" == *"ID             : $span_id"* ]]; then
    printf 'local OTLP trace delivery passed (trace_id=%s span_id=%s)\n' "$trace_id" "$span_id"
    exit 0
  fi
  sleep 1
done

"${compose[@]}" logs --no-color otel-collector >&2
echo "collector debug exporter did not record the smoke trace" >&2
exit 1
