#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_ROOT="$ROOT_DIR"
MANIFEST="$ROOT_DIR/platform-java/deploy/released-migrations.sha256"

usage() {
  cat <<'EOF'
Usage: scripts/validate-released-migrations.sh [--root PATH] [--manifest PATH]

Verifies byte-for-byte SHA-256 checksums for migrations already applied in a released environment.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) CHECK_ROOT="${2:?missing root}"; shift 2 ;;
    --manifest) MANIFEST="${2:?missing manifest}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v shasum >/dev/null 2>&1 || { echo "shasum is required" >&2; exit 1; }
[[ -d "$CHECK_ROOT" ]] || { echo "migration root is not a directory: $CHECK_ROOT" >&2; exit 1; }
[[ -r "$MANIFEST" ]] || { echo "migration checksum manifest is not readable: $MANIFEST" >&2; exit 1; }

checked=0
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  [[ "$line" =~ ^([0-9a-f]{64})[[:space:]][[:space:]]([^[:space:]]+)$ ]] \
    || { echo "invalid migration checksum entry: $line" >&2; exit 1; }
  expected="${BASH_REMATCH[1]}"
  relative="${BASH_REMATCH[2]}"
  [[ "$relative" != /* && "$relative" != *..* ]] \
    || { echo "unsafe migration checksum path: $relative" >&2; exit 1; }
  file="$CHECK_ROOT/$relative"
  [[ -r "$file" ]] || { echo "released migration is missing: $relative" >&2; exit 1; }
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] \
    || { echo "released migration was modified: $relative" >&2; exit 1; }
  checked=$((checked + 1))
done < "$MANIFEST"

(( checked > 0 )) || { echo "migration checksum manifest is empty" >&2; exit 1; }
echo "released migration checksums are valid ($checked files)"
