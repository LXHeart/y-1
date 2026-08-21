#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ROOT="$ROOT_DIR/.release-state"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
PRODUCTION_COMPOSE_FILE="$ROOT_DIR/docker-compose.production.yml"
ENV_FILE=""
source "$ROOT_DIR/scripts/lib/dotenv.sh"
EXECUTE=false
RELEASE_ID=""
BACKUP_MANIFEST=""
WEBHOOK_EVIDENCE=""
ARCHIVE_EVIDENCE=""
EVIDENCE_JOB_ID=""
EVIDENCE_PROVIDER=""
EVIDENCE_POLICY_VERSION=""
EVIDENCE_MAX_AGE_SECONDS="3600"
CANARY_EVIDENCE=""
CANARY_SERVICE=""
CANARY_IMAGE=""
BASELINE_IMAGE=""
CANARY_MAX_AGE_SECONDS="86400"
FAILURE_EVIDENCE=""
FAILURE_SCENARIO=""
FAILURE_MAX_AGE_SECONDS="604800"
OBSERVABILITY_EVIDENCE=""
OBSERVABILITY_MAX_AGE_SECONDS="86400"
ROTATION_EVIDENCE=""
ROTATION_MAX_AGE_SECONDS="604800"
CREDENTIAL_EVIDENCE=""
CREDENTIAL_MAX_AGE_SECONDS="604800"
COMMAND=""

SERVICES=(frontend database-bootstrap edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)
HEALTH_SERVICES=(frontend edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)

usage() {
  cat <<'EOF'
Usage:
  scripts/production-release.sh [--env-file PATH] migrate
  scripts/production-release.sh [--env-file PATH] preflight
  scripts/production-release.sh [--env-file PATH] plan --release-id ID
  scripts/production-release.sh [--env-file PATH] deploy --release-id ID --backup-manifest PATH [--execute]
  scripts/production-release.sh [--env-file PATH] promote --release-id ID \
    --webhook-evidence PATH --archive-evidence PATH --job-id UUID \
    --provider seedance|minimax [--policy-version VERSION] [--max-age-seconds N]
  scripts/production-release.sh [--env-file PATH] canary-promote --release-id ID \
    --evidence PATH --service NAME --canary-image DIGEST --baseline-image DIGEST
  scripts/production-release.sh [--env-file PATH] failure-promote --release-id ID \
    --failure-evidence PATH --failure-scenario NAME [--failure-max-age-seconds N]
  scripts/production-release.sh [--env-file PATH] observability-promote --release-id ID \
    --observability-evidence PATH [--observability-max-age-seconds N]
  scripts/production-release.sh [--env-file PATH] key-rotation-promote --release-id ID \
    --rotation-evidence PATH [--rotation-max-age-seconds N]
  scripts/production-release.sh [--env-file PATH] credential-rotation-promote --release-id ID \
    --credential-evidence PATH [--credential-max-age-seconds N]
  scripts/production-release.sh [--env-file PATH] rollback --release-id ID [--execute]
  scripts/production-release.sh [--env-file PATH] status

Deploy and rollback are dry-runs unless --execute is supplied. Deploy expects pinned image
references in RELEASE_IMAGE_<SERVICE> variables (hyphens become underscores). The rollback
restores application images/routes only; it never reverses a database migration.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
log() { printf '[release] %s\n' "$*"; }

compose() {
  local args=(--project-directory "$ROOT_DIR" -f "$COMPOSE_FILE" -f "$PRODUCTION_COMPOSE_FILE")
  [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
  docker compose "${args[@]}" "$@"
}

load_env() {
  [[ -z "$ENV_FILE" ]] && return 0
  [[ -r "$ENV_FILE" ]] || die "env file is not readable: $ENV_FILE"
  load_dotenv "$ENV_FILE" || die "failed to parse env file: $ENV_FILE"
}

service_var() {
  local service="$1"
  printf 'RELEASE_IMAGE_%s' "$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')" | tr '-' '_'
}

validate_gates() {
  [[ "${MIGRATION_COMPATIBILITY_APPROVED:-}" == yes ]] || die "set MIGRATION_COMPATIBILITY_APPROVED=yes after expand/contract review"
  [[ -n "${PUBLIC_HEALTH_URL:-}" ]] || die "PUBLIC_HEALTH_URL is required"
  [[ -n "${FINANCE_RELEASE_APPROVED_BY:-}" ]] || die "FINANCE_RELEASE_APPROVED_BY is required for a platform release"
  [[ "${FINANCE_CREDITS_CENTS_POLICY_VERSION:-}" =~ ^[A-Za-z0-9._-]{1,64}$ ]] \
    || die "FINANCE_CREDITS_CENTS_POLICY_VERSION is required for a platform release"
}

validate_secrets() {
  local args=()
  [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
  "$ROOT_DIR/scripts/validate-production-secrets.sh" "${args[@]}"
}

validate_finance_credits_cents_policy() {
  local args=()
  [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
  "$ROOT_DIR/scripts/validate-finance-credits-cents-policy.sh" "${args[@]}" >/dev/null
}

validate_production_compose() {
  local args=()
  [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
  "$ROOT_DIR/scripts/validate-production-compose.sh" "${args[@]}" >/dev/null
}

validate_observability_config() {
  "$ROOT_DIR/scripts/validate-observability-config.sh" >/dev/null
}

validate_released_migrations() {
  "$ROOT_DIR/scripts/validate-released-migrations.sh" >/dev/null
}

validate_image_provenance() {
  local args=()
  [[ -n "$1" ]] && args+=(--state-file "$1")
  "$ROOT_DIR/scripts/validate-image-provenance.sh" "${args[@]}" >/dev/null
}

run_public_smoke() {
  local base_url="${PUBLIC_SMOKE_BASE_URL:-}"
  if [[ -z "$base_url" ]]; then
    [[ "${PUBLIC_HEALTH_URL:-}" == */health ]] \
      || die "PUBLIC_SMOKE_BASE_URL is required when PUBLIC_HEALTH_URL does not end in /health"
    base_url="${PUBLIC_HEALTH_URL%/health}"
  fi
  local args=(--base-url "$base_url")
  [[ -z "${RELEASE_SMOKE_ACCESS_TOKEN:-}" ]] || args+=(--access-token "$RELEASE_SMOKE_ACCESS_TOKEN")
  "$ROOT_DIR/scripts/production-smoke.sh" "${args[@]}"
}

validate_release_images() {
  local missing=0
  for service in "${SERVICES[@]}"; do
    local var image
    var="$(service_var "$service")"
    image="${!var:-}"
    if [[ -z "$image" ]]; then
      echo "ERROR: $var is required" >&2
      missing=1
    elif [[ "$image" != *@sha256:* ]]; then
      echo "ERROR: $var must be pinned by repository digest (@sha256:...)" >&2
      missing=1
    fi
  done
  (( missing == 0 )) || exit 1
}

validate_backup_manifest() {
  [[ -n "$BACKUP_MANIFEST" ]] || die "--backup-manifest is required for deploy"
  [[ -r "$BACKUP_MANIFEST" ]] || die "backup manifest is not readable: $BACKUP_MANIFEST"
  "$ROOT_DIR/scripts/backup-restore-drill.sh" verify --manifest "$BACKUP_MANIFEST" >/dev/null
}

validate_video_evidence() {
  [[ -n "$WEBHOOK_EVIDENCE" ]] || die "--webhook-evidence is required for promote"
  [[ -n "$ARCHIVE_EVIDENCE" ]] || die "--archive-evidence is required for promote"
  [[ -n "$EVIDENCE_JOB_ID" ]] || die "--job-id is required for promote"
  [[ -n "$EVIDENCE_PROVIDER" ]] || die "--provider is required for promote"
  local policy_version="${EVIDENCE_POLICY_VERSION:-${FINANCE_CREDITS_CENTS_POLICY_VERSION:-}}"
  [[ -n "$policy_version" ]] || die "--policy-version or FINANCE_CREDITS_CENTS_POLICY_VERSION is required for promote"
  "$ROOT_DIR/scripts/validate-video-production-evidence.sh" \
    --webhook-evidence "$WEBHOOK_EVIDENCE" \
    --archive-evidence "$ARCHIVE_EVIDENCE" \
    --job-id "$EVIDENCE_JOB_ID" \
    --provider "$EVIDENCE_PROVIDER" \
    --policy-version "$policy_version" \
    --max-age-seconds "$EVIDENCE_MAX_AGE_SECONDS"
}

validate_canary_evidence() {
  [[ -n "$CANARY_EVIDENCE" ]] || die "--evidence is required for canary-promote"
  [[ -n "$CANARY_SERVICE" ]] || die "--service is required for canary-promote"
  [[ -n "$CANARY_IMAGE" ]] || die "--canary-image is required for canary-promote"
  [[ -n "$BASELINE_IMAGE" ]] || die "--baseline-image is required for canary-promote"
  "$ROOT_DIR/scripts/validate-production-canary-evidence.sh" \
    --evidence "$CANARY_EVIDENCE" --release-id "$RELEASE_ID" --service "$CANARY_SERVICE" \
    --canary-image "$CANARY_IMAGE" --baseline-image "$BASELINE_IMAGE" \
    --max-age-seconds "$CANARY_MAX_AGE_SECONDS"
}

validate_failure_evidence() {
  [[ -n "$FAILURE_EVIDENCE" ]] || die "--evidence is required for failure-promote"
  [[ -n "$FAILURE_SCENARIO" ]] || die "--scenario is required for failure-promote"
  "$ROOT_DIR/scripts/validate-production-failure-evidence.sh" \
    --evidence "$FAILURE_EVIDENCE" --release-id "$RELEASE_ID" --scenario "$FAILURE_SCENARIO" \
    --max-age-seconds "$FAILURE_MAX_AGE_SECONDS"
}

validate_observability_evidence() {
  [[ -n "$OBSERVABILITY_EVIDENCE" ]] || die "--observability-evidence is required for observability-promote"
  "$ROOT_DIR/scripts/validate-observability-evidence.sh" \
    --evidence "$OBSERVABILITY_EVIDENCE" --release-id "$RELEASE_ID" \
    --max-age-seconds "$OBSERVABILITY_MAX_AGE_SECONDS"
}

validate_rotation_evidence() {
  [[ -n "$ROTATION_EVIDENCE" ]] || die "--rotation-evidence is required for key-rotation-promote"
  "$ROOT_DIR/scripts/validate-identity-key-rotation-evidence.sh" \
    --evidence "$ROTATION_EVIDENCE" --release-id "$RELEASE_ID" \
    --max-age-seconds "$ROTATION_MAX_AGE_SECONDS"
}

validate_credential_rotation_evidence() {
  [[ -n "$CREDENTIAL_EVIDENCE" ]] || die "--credential-evidence is required for credential-rotation-promote"
  "$ROOT_DIR/scripts/validate-credential-rotation-evidence.sh" \
    --evidence "$CREDENTIAL_EVIDENCE" --release-id "$RELEASE_ID" \
    --max-age-seconds "$CREDENTIAL_MAX_AGE_SECONDS"
}

capture_images() {
  local output="$1"
  : > "$output"
  for service in "${SERVICES[@]}"; do
    local container ref image_id repo_digest
    container="$(compose ps -q "$service" 2>/dev/null || true)"
    [[ -n "$container" ]] || continue
    IFS='|' read -r ref image_id repo_digest < <(docker inspect -f '{{.Config.Image}}|{{.Image}}|{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' "$container")
    printf '%s|%s|%s|%s\n' "$service" "$ref" "$image_id" "$repo_digest" >> "$output"
  done
}

render_override() {
  local output="$1" mode="$2" state_file="${3:-}"
  {
    printf 'services:\n'
    for service in "${SERVICES[@]}"; do
      local image=""
      if [[ "$mode" == deploy ]]; then
        local var
        var="$(service_var "$service")"
        image="${!var:-}"
        [[ -n "$image" ]] || continue
      else
        image="$(awk -F'|' -v service="$service" '$1 == service { if ($4 != "") print $4; else if ($2 != "") print $2; else print $3 }' "$state_file")"
        [[ -n "$image" ]] || continue
      fi
      printf '  %s:\n    image: %s\n    build: null\n' "$service" "$image"
    done
  } > "$output"
}

compose_config() {
  local override="${1:-}"
  if [[ -n "$override" ]]; then
    local args=(--project-directory "$ROOT_DIR" -f "$COMPOSE_FILE" -f "$PRODUCTION_COMPOSE_FILE" -f "$override")
    [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
    docker compose "${args[@]}" config >/dev/null
  else
    compose config >/dev/null
  fi
}

preflight() {
  load_env
  validate_gates
  validate_secrets
  validate_finance_credits_cents_policy
  validate_release_images
  validate_image_provenance ""
  compose_config
  validate_production_compose
  validate_observability_config
  validate_released_migrations
  log "preflight passed"
}

plan() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_release_images
  validate_image_provenance ""
  local override="$STATE_ROOT/$RELEASE_ID/deploy.compose.yml"
  mkdir -p "$(dirname "$override")"
  render_override "$override" deploy
  compose_config "$override"
  validate_production_compose
  validate_observability_config
  validate_released_migrations
  log "dry-run plan for release $RELEASE_ID"
  for service in "${SERVICES[@]}"; do
    local var image
    var="$(service_var "$service")"
    image="${!var:-<not configured>}"
    printf '  %-24s %s\n' "$service" "$image"
  done
  log "no containers changed; generated $override"
}

wait_for_health() {
  local url="$1" attempts=0
  while (( attempts < 30 )); do
    if curl --fail --silent --show-error --max-time 5 "$url" >/dev/null; then
      log "public health check passed: $url"
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  return 1
}

wait_for_compose_health() {
  local attempts=0
  while (( attempts < 60 )); do
    local all_healthy=true
    for service in "${HEALTH_SERVICES[@]}"; do
      local container status
      container="$(compose ps -q "$service" 2>/dev/null || true)"
      if [[ -z "$container" ]]; then
        all_healthy=false
        continue
      fi
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container" 2>/dev/null || true)"
      if [[ "$status" != healthy ]]; then
        all_healthy=false
        log "waiting for $service health ($status)"
      fi
    done
    if [[ "$all_healthy" == true ]]; then
      log "all application readiness probes passed"
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  return 1
}

# 独立 release migration job（进度指南「生产切流前阻塞项 #1」）：五服务迁移按固定顺序在
# one-shot 容器执行，先于应用镜像滚动。dry-run 模式只做迁移清单校验不落库。
run_release_migrations() {
  load_env
  validate_released_migrations
  if [[ "$EXECUTE" != true ]]; then
    log "dry-run: would run release-migrator (ordered Flyway for 5 services)"
    return 0
  fi
  compose -f "$COMPOSE_FILE" -f "$PRODUCTION_COMPOSE_FILE" --profile release run --rm release-migrator
  log "release migrations applied in order"
}

deploy() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_secrets
  validate_release_images
  validate_backup_manifest
  validate_image_provenance ""
  local dir="$STATE_ROOT/$RELEASE_ID" override="$STATE_ROOT/$RELEASE_ID/deploy.compose.yml"
  mkdir -p "$dir"
  render_override "$override" deploy
  compose_config "$override"
  validate_production_compose
  validate_observability_config
  validate_released_migrations
  if [[ "$EXECUTE" != true ]]; then
    log "dry-run: would deploy release $RELEASE_ID with Compose"
    log "run again with --execute after reviewing $override"
    return 0
  fi
  # 先迁移后滚动：release-migrator one-shot（顺序见 services/release-migrator），失败即中止发布
  compose -f "$COMPOSE_FILE" -f "$PRODUCTION_COMPOSE_FILE" --profile release run --rm release-migrator
  log "release migrations applied in order"
  capture_images "$dir/previous-images.tsv"
  printf 'release_id=%s\ncreated_at=%s\nfinance_approved_by=%s\n' "$RELEASE_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${FINANCE_RELEASE_APPROVED_BY:-}" > "$dir/release.env"
  cp "$BACKUP_MANIFEST" "$dir/backup-manifest"
  compose -f "$override" up -d --no-build
  if ! wait_for_compose_health; then
    echo "ERROR: application readiness checks failed; rollback with: $0${ENV_FILE:+ --env-file $ENV_FILE} rollback --release-id $RELEASE_ID --execute" >&2
    exit 1
  fi
  if ! wait_for_health "${PUBLIC_HEALTH_URL:?PUBLIC_HEALTH_URL is required}"; then
    echo "ERROR: health check failed; rollback with: $0${ENV_FILE:+ --env-file $ENV_FILE} rollback --release-id $RELEASE_ID --execute" >&2
    exit 1
  fi
  run_public_smoke
  log "release $RELEASE_ID deployed"
}

rollback() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  local dir="$STATE_ROOT/$RELEASE_ID" state="$dir/previous-images.tsv" override="$dir/rollback.compose.yml"
  [[ -r "$state" ]] || die "release state not found: $state"
  validate_image_provenance "$state"
  render_override "$override" rollback "$state"
  compose_config "$override"
  validate_production_compose
  validate_observability_config
  validate_released_migrations
  log "rollback plan for release $RELEASE_ID"
  log "this changes application images/routes only and does not reverse database migrations"
  if [[ "$EXECUTE" != true ]]; then
    log "dry-run: run again with --execute to apply rollback"
    return 0
  fi
  compose -f "$override" up -d --no-build
  if ! wait_for_compose_health; then
    die "application readiness checks failed during rollback"
  fi
  if ! wait_for_health "${PUBLIC_HEALTH_URL:?PUBLIC_HEALTH_URL is required}"; then
    die "rollback health check failed; stop and investigate the deployment"
  fi
  run_public_smoke
  log "release $RELEASE_ID rolled back"
}

promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_video_evidence
  log "release $RELEASE_ID production evidence accepted; promotion gate passed"
}

canary_promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_canary_evidence
  log "release $RELEASE_ID canary evidence accepted; canary promotion gate passed"
}

failure_promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_failure_evidence
  log "release $RELEASE_ID failure-drill evidence accepted; recovery promotion gate passed"
}

observability_promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_observability_config
  validate_released_migrations
  validate_observability_evidence
  log "release $RELEASE_ID observability evidence accepted; alert-delivery gate passed"
}

key_rotation_promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_secrets
  validate_rotation_evidence
  log "release $RELEASE_ID key rotation evidence accepted; rotation gate passed"
}

credential_rotation_promote() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_secrets
  validate_credential_rotation_evidence
  log "release $RELEASE_ID credential rotation evidence accepted; revocation gate passed"
}

status() {
  if [[ ! -d "$STATE_ROOT" ]]; then log "no release state found"; return 0; fi
  find "$STATE_ROOT" -mindepth 2 -maxdepth 2 -name release.env -print | sort | while read -r file; do
    printf '%s\n' "--- $file"
    sed -n '1,3p' "$file"
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?missing env file}"; shift 2 ;;
    --release-id) RELEASE_ID="${2:?missing release id}"; shift 2 ;;
    --backup-manifest) BACKUP_MANIFEST="${2:?missing backup manifest}"; shift 2 ;;
    --webhook-evidence) WEBHOOK_EVIDENCE="${2:?missing webhook evidence}"; shift 2 ;;
    --archive-evidence) ARCHIVE_EVIDENCE="${2:?missing archive evidence}"; shift 2 ;;
    --job-id) EVIDENCE_JOB_ID="${2:?missing Job ID}"; shift 2 ;;
    --provider) EVIDENCE_PROVIDER="${2:?missing provider}"; shift 2 ;;
    --policy-version) EVIDENCE_POLICY_VERSION="${2:?missing policy version}"; shift 2 ;;
    --max-age-seconds) EVIDENCE_MAX_AGE_SECONDS="${2:?missing maximum evidence age}"; shift 2 ;;
    --evidence) CANARY_EVIDENCE="${2:?missing canary evidence}"; shift 2 ;;
    --service) CANARY_SERVICE="${2:?missing canary service}"; shift 2 ;;
    --canary-image) CANARY_IMAGE="${2:?missing canary image}"; shift 2 ;;
    --baseline-image) BASELINE_IMAGE="${2:?missing baseline image}"; shift 2 ;;
    --canary-max-age-seconds) CANARY_MAX_AGE_SECONDS="${2:?missing canary evidence age}"; shift 2 ;;
    --failure-max-age-seconds) FAILURE_MAX_AGE_SECONDS="${2:?missing failure evidence age}"; shift 2 ;;
    --failure-evidence) FAILURE_EVIDENCE="${2:?missing failure evidence}"; shift 2 ;;
    --failure-scenario) FAILURE_SCENARIO="${2:?missing failure scenario}"; shift 2 ;;
    --observability-evidence) OBSERVABILITY_EVIDENCE="${2:?missing observability evidence}"; shift 2 ;;
    --observability-max-age-seconds) OBSERVABILITY_MAX_AGE_SECONDS="${2:?missing observability evidence age}"; shift 2 ;;
    --rotation-evidence) ROTATION_EVIDENCE="${2:?missing rotation evidence}"; shift 2 ;;
    --rotation-max-age-seconds) ROTATION_MAX_AGE_SECONDS="${2:?missing rotation evidence age}"; shift 2 ;;
    --credential-evidence) CREDENTIAL_EVIDENCE="${2:?missing credential rotation evidence}"; shift 2 ;;
    --credential-max-age-seconds) CREDENTIAL_MAX_AGE_SECONDS="${2:?missing credential rotation evidence age}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    migrate|preflight|plan|deploy|promote|canary-promote|failure-promote|observability-promote|key-rotation-promote|credential-rotation-promote|rollback|status) COMMAND="$1"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
case "${COMMAND:-}" in
  migrate) run_release_migrations ;;
  preflight) preflight ;;
  plan) plan ;;
  deploy) deploy ;;
  promote) promote ;;
  canary-promote) canary_promote ;;
  failure-promote) failure_promote ;;
  observability-promote) observability_promote ;;
  key-rotation-promote) key_rotation_promote ;;
  credential-rotation-promote) credential_rotation_promote ;;
  rollback) rollback ;;
  status) status ;;
  *) usage >&2; exit 2 ;;
esac
