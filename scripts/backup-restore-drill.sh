#!/usr/bin/env bash
set -Eeuo pipefail

COMMAND="${1:-}"
DESTINATION=""
MANIFEST=""
DATABASE_URL_VALUE="${DATABASE_URL:-}"
MINIO_ENDPOINT_VALUE="${MINIO_ENDPOINT:-http://localhost:9000}"
MINIO_ACCESS_KEY_VALUE="${MINIO_ACCESS_KEY:-}"
MINIO_SECRET_KEY_VALUE="${MINIO_SECRET_KEY:-}"
MINIO_BUCKET_VALUE="${MINIO_BUCKET:-grassland}"
TARGET_DATABASE_URL=""
TARGET_BUCKET=""
TARGET_MINIO_ENDPOINT="${TARGET_MINIO_ENDPOINT:-$MINIO_ENDPOINT_VALUE}"
TARGET_MINIO_ACCESS_KEY="${TARGET_MINIO_ACCESS_KEY:-$MINIO_ACCESS_KEY_VALUE}"
TARGET_MINIO_SECRET_KEY="${TARGET_MINIO_SECRET_KEY:-$MINIO_SECRET_KEY_VALUE}"
EXECUTE=false

usage() {
  cat <<'EOF'
Usage:
  scripts/backup-restore-drill.sh backup --destination DIR [--database-url URL] [--bucket NAME]
  scripts/backup-restore-drill.sh verify --manifest FILE
  scripts/backup-restore-drill.sh restore --manifest FILE --target-database-url URL --target-bucket NAME [--execute]
  scripts/backup-restore-drill.sh drill --destination DIR --target-database-url URL --target-bucket NAME [--execute]

PostgreSQL is stored as a custom-format pg_dump. MinIO is mirrored to a local directory and every
file is checksummed. Restore requires a non-production target and --execute; Redis replay keys are
intentionally excluded. Kafka and Temporal are not backed up by this script.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
sha256() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'; else sha256sum "$1" | awk '{print $1}'; fi
}
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --destination) DESTINATION="${2:?missing destination}"; shift 2 ;;
      --manifest) MANIFEST="${2:?missing manifest}"; shift 2 ;;
      --database-url) DATABASE_URL_VALUE="${2:?missing database url}"; shift 2 ;;
      --bucket) MINIO_BUCKET_VALUE="${2:?missing bucket}"; shift 2 ;;
      --target-database-url) TARGET_DATABASE_URL="${2:?missing target database url}"; shift 2 ;;
      --target-bucket) TARGET_BUCKET="${2:?missing target bucket}"; shift 2 ;;
      --minio-endpoint) MINIO_ENDPOINT_VALUE="${2:?missing minio endpoint}"; shift 2 ;;
      --minio-access-key) MINIO_ACCESS_KEY_VALUE="${2:?missing minio access key}"; shift 2 ;;
      --minio-secret-key) MINIO_SECRET_KEY_VALUE="${2:?missing minio secret key}"; shift 2 ;;
      --target-minio-endpoint) TARGET_MINIO_ENDPOINT="${2:?missing target minio endpoint}"; shift 2 ;;
      --target-minio-access-key) TARGET_MINIO_ACCESS_KEY="${2:?missing target minio access key}"; shift 2 ;;
      --target-minio-secret-key) TARGET_MINIO_SECRET_KEY="${2:?missing target minio secret key}"; shift 2 ;;
      --execute) EXECUTE=true; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done
}

write_manifest() {
  local root="$1" file
  {
    printf '# grassland backup manifest v1\n'
    printf 'created_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'postgres_dump=postgres.dump\n'
    printf 'minio_prefix=minio/%s\n' "$MINIO_BUCKET_VALUE"
    printf 'redis_replay_keys=excluded\n'
    printf 'kafka_temporal=deployment_side\n'
    while IFS= read -r file; do
      printf 'sha256 %s %s\n' "$(sha256 "$file")" "${file#"$root"/}"
    done < <(find "$root" -type f ! -name manifest.txt -print | LC_ALL=C sort)
  } > "$root/manifest.txt"
}

backup() {
  require_cmd pg_dump
  require_cmd mc
  [[ -n "$DATABASE_URL_VALUE" ]] || die "DATABASE_URL or --database-url is required"
  [[ -n "$MINIO_ACCESS_KEY_VALUE" && -n "$MINIO_SECRET_KEY_VALUE" ]] || die "MinIO source credentials are required"
  [[ "$MINIO_BUCKET_VALUE" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || die "bucket name is invalid"
  [[ -n "$DESTINATION" ]] || die "--destination is required"
  mkdir -p "$DESTINATION"
  DESTINATION="$(cd "$DESTINATION" && pwd)"
  [[ "$DESTINATION" != / ]] || die "refusing broad backup destination: $DESTINATION"
  [[ -z "$(find "$DESTINATION" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "backup destination must be empty: $DESTINATION"
  pg_dump --format=custom --file "$DESTINATION/postgres.dump" "$DATABASE_URL_VALUE"
  local alias="grassland-backup-$$"
  mc alias set "$alias" "$MINIO_ENDPOINT_VALUE" "$MINIO_ACCESS_KEY_VALUE" "$MINIO_SECRET_KEY_VALUE" >/dev/null
  mkdir -p "$DESTINATION/minio/$MINIO_BUCKET_VALUE"
  mc mirror --overwrite "$alias/$MINIO_BUCKET_VALUE" "$DESTINATION/minio/$MINIO_BUCKET_VALUE"
  mc alias rm "$alias" >/dev/null 2>&1 || true
  write_manifest "$DESTINATION"
  printf '%s\n' "$DESTINATION/manifest.txt"
}

verify() {
  [[ -r "$MANIFEST" ]] || die "manifest is not readable: $MANIFEST"
  local root="$(cd "$(dirname "$MANIFEST")" && pwd)" hash rel actual
  while read -r kind hash rel; do
    [[ "$kind" == sha256 ]] || continue
    [[ "$rel" != /* && "$rel" != *..* ]] || die "unsafe manifest path: $rel"
    [[ -f "$root/$rel" ]] || die "manifest file missing: $rel"
    actual="$(sha256 "$root/$rel")"
    [[ "$actual" == "$hash" ]] || die "checksum mismatch: $rel"
  done < "$MANIFEST"
  printf '%s\n' "manifest verified: $MANIFEST"
}

target_is_safe() {
  [[ -n "$TARGET_DATABASE_URL" && -n "$TARGET_BUCKET" ]] || die "restore requires --target-database-url and --target-bucket"
  [[ "$TARGET_DATABASE_URL" != "$DATABASE_URL_VALUE" ]] || die "target database must differ from production DATABASE_URL"
  local host="${TARGET_DATABASE_URL#*://}"
  host="${host##*@}"
  host="${host%%[:/]*}"
  [[ "$host" =~ ^(localhost|127\.0\.0\.1|::1)$ || "$host" == *restore* || "$host" == *drill* || "$host" == *staging* ]] || die "target database host must be localhost or explicitly named restore/drill/staging"
  [[ "$TARGET_BUCKET" == grassland-restore-drill-* ]] || die "target bucket must start with grassland-restore-drill-"
}

restore() {
  target_is_safe
  [[ -r "$MANIFEST" ]] || die "manifest is not readable: $MANIFEST"
  verify
  [[ "$EXECUTE" == true ]] || {
    printf '%s\n' "dry-run: restore would target $TARGET_DATABASE_URL and bucket $TARGET_BUCKET; add --execute to apply"
    return 0
  }
  require_cmd pg_restore
  require_cmd psql
  require_cmd mc
  [[ -n "$TARGET_MINIO_ACCESS_KEY" && -n "$TARGET_MINIO_SECRET_KEY" ]] || die "MinIO target credentials are required"
  local root="$(cd "$(dirname "$MANIFEST")" && pwd)" alias="grassland-restore-$$" source_prefix
  source_prefix="$(sed -n 's/^minio_prefix=//p' "$MANIFEST")"
  [[ "$source_prefix" != /* && "$source_prefix" != *..* ]] || die "unsafe MinIO backup prefix"
  [[ -n "$source_prefix" && -d "$root/$source_prefix" ]] || die "MinIO backup prefix is missing"
  [[ -f "$root/postgres.dump" ]] || die "PostgreSQL dump is missing"
  pg_restore --clean --if-exists --no-owner --dbname "$TARGET_DATABASE_URL" "$root/postgres.dump"
  mc alias set "$alias" "$TARGET_MINIO_ENDPOINT" "$TARGET_MINIO_ACCESS_KEY" "$TARGET_MINIO_SECRET_KEY" >/dev/null
  mc mb --ignore-existing "$alias/$TARGET_BUCKET" >/dev/null
  mc mirror --overwrite "$root/$source_prefix" "$alias/$TARGET_BUCKET"
  mc alias rm "$alias" >/dev/null 2>&1 || true
  psql "$TARGET_DATABASE_URL" -v ON_ERROR_STOP=1 -Atqc "SELECT 'restore-ok', current_database(), COALESCE((SELECT count(*) FROM app_users), 0), COALESCE((SELECT count(*) FROM organization), 0);"
  printf '%s\n' "restore completed against explicitly non-production targets"
}

shift || true
parse_args "$@"
case "$COMMAND" in
  backup) backup ;;
  verify) verify ;;
  restore) restore ;;
  drill)
    backup
    MANIFEST="$DESTINATION/manifest.txt"
    verify
    restore
    ;;
  -h|--help|"") usage ;;
  *) usage >&2; exit 2 ;;
esac
