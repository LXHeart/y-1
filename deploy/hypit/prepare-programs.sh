#!/usr/bin/env bash
# prepare-programs.sh — C107-06 (task-107) operator entry for the managed
# Python programs (whisperx.local + image.opencv.local).
#
# Frozen uv locks are never modified: each program gets its own uv environment
# under HYPIT_PROGRAMS_ROOT; WhisperX additionally prepares its ASR model,
# alignment weights and NLTK sentence data explicitly (inference never
# downloads). Progress and full output land in install.log per program.
#
# Usage:
#   deploy/hypit/prepare-programs.sh [programsRoot] [distributionRoot]
# Environment:
#   HYPIT_PROGRAMS_ROOT        default test-artifacts/task-107/programs
#   HYPIT_DISTRIBUTION_ROOT    default platform-hypit/.generated/hypit
#   HYPIT_WHISPERX_ALIGNMENT_LANGUAGES  default "zh en"
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROGRAMS_ROOT="${1:-${HYPIT_PROGRAMS_ROOT:-${REPO_ROOT}/test-artifacts/task-107/programs}}"
DISTRIBUTION_ROOT="${2:-${HYPIT_DISTRIBUTION_ROOT:-${REPO_ROOT}/platform-hypit/.generated/hypit}}"
LANGUAGES="${HYPIT_WHISPERX_ALIGNMENT_LANGUAGES:-zh en}"

log() { printf '\033[1m[prepare-programs]\033[0m %s\n' "$*"; }

log "programs root: ${PROGRAMS_ROOT}"
log "distribution:  ${DISTRIBUTION_ROOT}"
log "alignment languages: ${LANGUAGES}"

command -v uv >/dev/null 2>&1 || { log "ERROR: uv is required (https://docs.astral.sh/uv/)"; exit 1; }
[ -d "${DISTRIBUTION_ROOT}/services/whisperx" ] || { log "ERROR: ${DISTRIBUTION_ROOT}/services/whisperx missing (run the distribution build first)"; exit 1; }
[ -d "${DISTRIBUTION_ROOT}/services/image-opencv" ] || { log "ERROR: ${DISTRIBUTION_ROOT}/services/image-opencv missing"; exit 1; }

mkdir -p "${PROGRAMS_ROOT}/whisperx.local" "${PROGRAMS_ROOT}/image.opencv.local"

# --- image.opencv.local: locked interpreter (Python 3.13 per upstream lock) ---
log "[1/3] image.opencv.local: uv sync --frozen"
( cd "${REPO_ROOT}" && UV_PROJECT_ENVIRONMENT="${PROGRAMS_ROOT}/image.opencv.local/.venv" \
  uv sync --project "${DISTRIBUTION_ROOT}/services/image-opencv" --frozen --no-dev \
  2>&1 | tee "${PROGRAMS_ROOT}/image.opencv.local/install.log" >/dev/null )
"${PROGRAMS_ROOT}/image.opencv.local/.venv/bin/python" -c 'import cv2, numpy, json; print(json.dumps({"cv2": cv2.__version__, "numpy": numpy.__version__}))' \
  | tee -a "${PROGRAMS_ROOT}/image.opencv.local/install.log"
log "[1/3] image.opencv.local ready"

# --- whisperx.local: pinned service environment (Python 3.10–3.13) ---
log "[2/3] whisperx.local: uv sync --frozen"
( cd "${REPO_ROOT}" && UV_PROJECT_ENVIRONMENT="${PROGRAMS_ROOT}/whisperx.local/.venv" \
  uv sync --project "${DISTRIBUTION_ROOT}/services/whisperx" --frozen --no-dev \
  2>&1 | tee "${PROGRAMS_ROOT}/whisperx.local/install.log" >/dev/null )
log "[2/3] whisperx.local environment ready"

# --- whisperx.local: explicit model/alignment/NLTK preparation ---
log "[3/3] whisperx.local: hypit-whisperx-prepare (models + NLTK; this can download)"
HYPIT_WHISPERX_ALIGNMENT_LANGUAGES="${LANGUAGES}" \
HYPIT_WHISPERX_INPUT_ROOTS="${PROGRAMS_ROOT}/whisperx.local/input" \
HYPIT_WHISPERX_MODEL_CACHE="${PROGRAMS_ROOT}/whisperx.local/model-cache" \
HYPIT_WHISPERX_NLTK_DATA="${PROGRAMS_ROOT}/whisperx.local/nltk-data" \
  "${PROGRAMS_ROOT}/whisperx.local/.venv/bin/hypit-whisperx-prepare" \
  2>&1 | tee -a "${PROGRAMS_ROOT}/whisperx.local/install.log"
log "[3/3] whisperx.local resources prepared"

log "done. Runtime start (loopback 8765):"
log "  HYPIT_WHISPERX_INPUT_ROOTS=... HYPIT_WHISPERX_MODEL_CACHE=... \\"
log "    ${PROGRAMS_ROOT}/whisperx.local/.venv/bin/hypit-whisperx-service"
log "or through the broker: POST /api/hypit/runtime/programs/up {\"program\":\"whisperx.local\"}"
