#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-y1-e2e-local}"
FRONTEND_PORT="${FRONTEND_PORT:-18080}"
MINIO_PROXY_PORT="${MINIO_PROXY_PORT:-19002}"
LOCAL_DB_PORT="${LOCAL_DB_PORT:-15432}"
export COMPOSE_PROJECT_NAME="$PROJECT_NAME"
export FRONTEND_PORT MINIO_PROXY_PORT LOCAL_DB_PORT
export BACKEND_PORT="${BACKEND_PORT:-13000}"
export EDGE_BFF_PORT="${EDGE_BFF_PORT:-18081}"
export KAFKA_PORT="${KAFKA_PORT:-19092}"
export REDIS_PORT="${REDIS_PORT:-16379}"
export MINIO_API_PORT="${MINIO_API_PORT:-19000}"
export MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-19001}"
export TEMPORAL_GRPC_PORT="${TEMPORAL_GRPC_PORT:-17233}"
export SETTLEMENT_DAY_SECONDS="${SETTLEMENT_DAY_SECONDS:-2}"

export E2E_EMAIL="${E2E_EMAIL:-e2e-ci@test.local}"
export E2E_PASSWORD="${E2E_PASSWORD:-E2e!$(openssl rand -hex 16)}"
export E2E_DISPLAY_NAME="${E2E_DISPLAY_NAME:-CI E2E User}"
export E2E_ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-e2e-admin-ci@test.local}"
export E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-Admin!$(openssl rand -hex 16)}"
export E2E_ADMIN_DISPLAY_NAME="${E2E_ADMIN_DISPLAY_NAME:-CI E2E Admin}"
export PUBLIC_FORWARDED_PROTO=http
export TRUSTED_PROXY_CIDR=127.0.0.1/32
export FRONTEND_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}"
export PUBLIC_BACKEND_ORIGIN="$FRONTEND_ORIGIN"
export CORS_ORIGIN="$FRONTEND_ORIGIN"
export MINIO_PUBLIC_BASE_URL="http://127.0.0.1:${MINIO_PROXY_PORT}"
export SESSION_COOKIE_SECURE=never
export SESSION_COOKIE_SAME_SITE=Lax
export DATABASE_URL='postgresql://grassland:grassland@postgres-local:5432/grassland'
export LOCAL_DB_USER=grassland LOCAL_DB_PASSWORD=grassland LOCAL_DB_NAME=grassland
export NODE_ENV=production
export DOUYIN_USER_AGENT='CI E2E'
export BILIBILI_USER_AGENT='CI E2E'
export INTERNAL_API_KEY="$(openssl rand -hex 32)"
export DOUYIN_PROXY_TOKEN_SECRET="$(openssl rand -hex 32)"
export BILIBILI_PROXY_TOKEN_SECRET="$(openssl rand -hex 32)"
export MINIO_ROOT_USER=ci-minio
export MINIO_ROOT_PASSWORD="$(openssl rand -hex 32)"
export MINIO_ACCESS_KEY="$MINIO_ROOT_USER"
export MINIO_SECRET_KEY="$MINIO_ROOT_PASSWORD"
export QWEN_BASE_URL='https://qwen-e2e.invalid/v1'
export QWEN_API_KEY="$(openssl rand -hex 32)"
export IDENTITY_ACCESS_TOKEN_SECRET="$(openssl rand -hex 32)"
export CRYPTO_KEK_BASE64="$(openssl rand -base64 32 | tr -d '\n')"
export SESSION_SECRET="$(openssl rand -hex 32)"

for key in \
  IDENTITY_ASSERTION_KEY_EDGE_USER_IDENTITY \
  IDENTITY_ASSERTION_KEY_EDGE_USER_MARKETPLACE \
  IDENTITY_ASSERTION_KEY_EDGE_USER_FINANCE \
  IDENTITY_ASSERTION_KEY_EDGE_USER_TRUST \
  IDENTITY_ASSERTION_KEY_EDGE_USER_INTELLIGENCE \
  IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_FINANCE \
  IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_TRUST \
  IDENTITY_ASSERTION_KEY_MARKETPLACE_SERVICE_INTELLIGENCE \
  IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_FINANCE \
  IDENTITY_ASSERTION_KEY_IDENTITY_SERVICE_INTELLIGENCE \
  IDENTITY_ASSERTION_KEY_TRUST_SERVICE_FINANCE \
  IDENTITY_ASSERTION_KEY_TRUST_SERVICE_MARKETPLACE \
  IDENTITY_ASSERTION_KEY_TRUST_SERVICE_IDENTITY \
  IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_MARKETPLACE \
  IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_FINANCE; do
  printf -v "$key" '%s' "$(openssl rand -hex 32)"
  export "$key"
done

dc() {
  docker compose --project-name "$PROJECT_NAME" --env-file /dev/null "$@"
}

capture_failure_logs() {
  local status="$1"
  if [[ "$status" -eq 0 ]]; then
    return
  fi
  mkdir -p test-artifacts
  dc ps > test-artifacts/compose-ps.txt 2>&1 || true
  dc logs --no-color --tail=300 > test-artifacts/compose.log 2>&1 || true
}

cleanup() {
  local status=$?
  capture_failure_logs "$status"
  dc down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

dc down --volumes --remove-orphans >/dev/null 2>&1 || true

wait_for_postgres() {
  local max_attempts="${1:-60}"
  local attempts=0
  while (( attempts < max_attempts )); do
    if dc exec -T postgres-local pg_isready -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" >/dev/null 2>&1; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  echo "Timed out waiting for postgres-local" >&2
  return 1
}

dc up -d postgres-local
wait_for_postgres 60

HOST_DATABASE_URL="postgresql://${LOCAL_DB_USER}:${LOCAL_DB_PASSWORD}@127.0.0.1:${LOCAL_DB_PORT}/${LOCAL_DB_NAME}"
DATABASE_URL="$HOST_DATABASE_URL" npm run db:migrate

if [[ "${SKIP_JAVA_BUILD:-0}" != "1" ]]; then
  (
    cd platform-java
    # Local macOS machines may still default to Java 8 even when the project JDK is installed.
    source "$ROOT_DIR/scripts/lib/java-runtime.sh"
    ensure_java_runtime 25
    ./gradlew \
      :services:edge-bff:bootJar \
      :services:identity-service:bootJar \
      :services:marketplace-service:bootJar \
      :services:finance-service:bootJar \
      :services:trust-service:bootJar \
      :services:intelligence-service:bootJar \
      --no-daemon --console=plain
  )
fi

dc up -d --build

wait_for_public_endpoint() {
  local path="$1"
  local expected_status="${2:-}"
  local attempts=0
  while (( attempts < 120 )); do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${FRONTEND_PORT}${path}" || true)"
    if [[ "$code" =~ ^[0-9]+$ ]] && (( code > 0 && code < 500 )) \
      && [[ -z "$expected_status" || "$code" == "$expected_status" ]]; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  echo "Timed out waiting for ${path}" >&2
  return 1
}

wait_for_public_endpoint /health 200
wait_for_public_endpoint /api/auth/captcha 200
DATABASE_URL="$HOST_DATABASE_URL" npm run e2e:seed:auth
DATABASE_URL="$HOST_DATABASE_URL" npx tsx scripts/e2e-seed.ts

wait_for_public_endpoint /api/tasks/feed 401
wait_for_public_endpoint /api/finance/wallets/me 401
wait_for_public_endpoint /api/trust/disputes 405
wait_for_public_endpoint /api/media/media 404

BASE_URL="http://127.0.0.1:${FRONTEND_PORT}" E2E_DATABASE_URL="$HOST_DATABASE_URL" npm run e2e
