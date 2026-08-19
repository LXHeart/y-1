#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT=""
OUTPUT="/run/secrets/grassland/production.env"
SOPS_BIN="${SOPS_BIN:-sops}"
EXECUTE=false
REPLACE=false
TMP_FILE=""

usage() {
  cat <<'EOF'
Usage:
  scripts/materialize-production-secrets.sh --input ENCRYPTED_ENV [--output PATH] [--execute] [--replace]

Decrypts a SOPS dotenv file into a mode-0600 temporary file, validates the complete production
secret contract, and atomically installs it only when --execute is supplied. Existing output is
never replaced unless --replace is also supplied. Secret values are never written to stdout.
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }
cleanup() { [[ -z "$TMP_FILE" ]] || rm -f -- "$TMP_FILE"; }
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="${2:?missing encrypted input}"; shift 2 ;;
    --output) OUTPUT="${2:?missing output path}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    --replace) REPLACE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$INPUT" ]] || die "--input is required"
[[ -r "$INPUT" ]] || die "encrypted input is not readable: $INPUT"
[[ ! -L "$INPUT" ]] || die "encrypted input must not be a symbolic link"
[[ "$OUTPUT" == /* ]] || die "--output must be an absolute path"
[[ "$OUTPUT" != */ ]] || die "--output must name a file"
command -v "$SOPS_BIN" >/dev/null 2>&1 || die "sops executable not found: $SOPS_BIN"

output_dir="$(dirname "$OUTPUT")"
output_name="$(basename "$OUTPUT")"
[[ -d "$output_dir" ]] || die "output directory does not exist: $output_dir"
[[ ! -L "$output_dir" ]] || die "output directory must not be a symbolic link"
output_dir="$(cd "$output_dir" && pwd -P)"
OUTPUT="$output_dir/$output_name"
[[ ! -L "$OUTPUT" ]] || die "output must not be a symbolic link"
input_dir="$(cd "$(dirname "$INPUT")" && pwd -P)"
input_path="$input_dir/$(basename "$INPUT")"
[[ "$input_path" != "$OUTPUT" ]] || die "encrypted input and materialized output must differ"
if [[ -e "$OUTPUT" && "$input_path" -ef "$OUTPUT" ]]; then
  die "encrypted input and materialized output must not reference the same file"
fi

dir_mode="$(stat -f '%Lp' "$output_dir" 2>/dev/null || stat -c '%a' "$output_dir" 2>/dev/null || true)"
if [[ -n "$dir_mode" ]] && (( 8#$dir_mode & 077 )); then
  die "output directory must not be accessible by group or other users (mode $dir_mode)"
fi
if [[ -e "$OUTPUT" && "$EXECUTE" == true && "$REPLACE" != true ]]; then
  die "output already exists; pass --replace after reviewing the rotation plan"
fi

umask 077
TMP_FILE="$(mktemp "$output_dir/.${output_name}.tmp.XXXXXX")"
if ! "$SOPS_BIN" --decrypt --input-type dotenv --output-type dotenv "$INPUT" > "$TMP_FILE"; then
  die "SOPS decryption failed"
fi
[[ -s "$TMP_FILE" ]] || die "SOPS produced an empty dotenv file"
chmod 600 "$TMP_FILE"

if ! "$ROOT_DIR/scripts/validate-production-secrets.sh" --env-file "$TMP_FILE" >/dev/null; then
  die "decrypted production secret contract validation failed"
fi

if [[ "$EXECUTE" != true ]]; then
  echo "production secrets decrypted and validated; dry-run left $OUTPUT unchanged"
  exit 0
fi

mv -f -- "$TMP_FILE" "$OUTPUT"
TMP_FILE=""
chmod 600 "$OUTPUT"
echo "production secrets validated and atomically materialized at $OUTPUT"
