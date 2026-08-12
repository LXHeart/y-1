#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ROOT="$ROOT_DIR/.release-state"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
PRODUCTION_COMPOSE_FILE="$ROOT_DIR/docker-compose.production.yml"
ENV_FILE=""
EXECUTE=false
RELEASE_ID=""
BACKUP_MANIFEST=""
COMMAND=""

SERVICES=(frontend backend edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)

usage() {
  cat <<'EOF'
Usage:
  scripts/production-release.sh [--env-file PATH] preflight
  scripts/production-release.sh [--env-file PATH] plan --release-id ID
  scripts/production-release.sh [--env-file PATH] deploy --release-id ID [--backup-manifest PATH] [--execute]
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
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

service_var() {
  local service="$1"
  printf 'RELEASE_IMAGE_%s' "$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')" | tr '-' '_'
}

validate_gates() {
  [[ "${MIGRATION_COMPATIBILITY_APPROVED:-}" == yes ]] || die "set MIGRATION_COMPATIBILITY_APPROVED=yes after expand/contract review"
  [[ -n "${PUBLIC_HEALTH_URL:-}" ]] || die "PUBLIC_HEALTH_URL is required"
  [[ -n "${FINANCE_RELEASE_APPROVED_BY:-}" ]] || die "FINANCE_RELEASE_APPROVED_BY is required for a platform release"
}

validate_secrets() {
  local args=()
  [[ -n "$ENV_FILE" ]] && args+=(--env-file "$ENV_FILE")
  "$ROOT_DIR/scripts/validate-production-secrets.sh" "${args[@]}"
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
  compose_config
  log "preflight passed"
}

plan() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_release_images
  local override="$STATE_ROOT/$RELEASE_ID/deploy.compose.yml"
  mkdir -p "$(dirname "$override")"
  render_override "$override" deploy
  compose_config "$override"
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

deploy() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  validate_gates
  validate_secrets
  validate_release_images
  local dir="$STATE_ROOT/$RELEASE_ID" override="$STATE_ROOT/$RELEASE_ID/deploy.compose.yml"
  mkdir -p "$dir"
  render_override "$override" deploy
  compose_config "$override"
  if [[ "$EXECUTE" != true ]]; then
    log "dry-run: would deploy release $RELEASE_ID with Compose"
    log "run again with --execute after reviewing $override"
    return 0
  fi
  capture_images "$dir/previous-images.tsv"
  printf 'release_id=%s\ncreated_at=%s\nfinance_approved_by=%s\n' "$RELEASE_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${FINANCE_RELEASE_APPROVED_BY:-}" > "$dir/release.env"
  [[ -z "$BACKUP_MANIFEST" || -r "$BACKUP_MANIFEST" ]] || die "backup manifest is not readable: $BACKUP_MANIFEST"
  [[ -z "$BACKUP_MANIFEST" ]] || cp "$BACKUP_MANIFEST" "$dir/backup-manifest"
  compose -f "$override" up -d --no-build
  if ! wait_for_health "${PUBLIC_HEALTH_URL:?PUBLIC_HEALTH_URL is required}"; then
    echo "ERROR: health check failed; rollback with: $0${ENV_FILE:+ --env-file $ENV_FILE} rollback --release-id $RELEASE_ID --execute" >&2
    exit 1
  fi
  log "release $RELEASE_ID deployed"
}

rollback() {
  load_env
  [[ -n "$RELEASE_ID" ]] || die "--release-id is required"
  local dir="$STATE_ROOT/$RELEASE_ID" state="$dir/previous-images.tsv" override="$dir/rollback.compose.yml"
  [[ -r "$state" ]] || die "release state not found: $state"
  render_override "$override" rollback "$state"
  compose_config "$override"
  log "rollback plan for release $RELEASE_ID"
  log "this changes application images/routes only and does not reverse database migrations"
  if [[ "$EXECUTE" != true ]]; then
    log "dry-run: run again with --execute to apply rollback"
    return 0
  fi
  compose -f "$override" up -d --no-build
  if ! wait_for_health "${PUBLIC_HEALTH_URL:?PUBLIC_HEALTH_URL is required}"; then
    die "rollback health check failed; stop and investigate the deployment"
  fi
  log "release $RELEASE_ID rolled back"
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
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    preflight|plan|deploy|rollback|status) COMMAND="$1"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
case "${COMMAND:-}" in
  preflight) preflight ;;
  plan) plan ;;
  deploy) deploy ;;
  rollback) rollback ;;
  status) status ;;
  *) usage >&2; exit 2 ;;
esac
