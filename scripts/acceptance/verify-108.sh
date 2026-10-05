#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
case "$*" in
  unit|'it storage'|'it generation'|e2e) ;;
  *) echo 'usage: verify-108.sh unit | it storage | it generation | e2e' >&2; exit 2 ;;
esac
if [[ "$1" == e2e ]]; then
  export VOICE_E2E=1 COMPOSE_PROJECT_NAME=y1-task108-e2e E2E_WORKERS=1
  export E2E_ENGINES='chromium firefox webkit'
  export E2E_SPECS='tests/e2e/creation-voice.spec.ts tests/e2e/creation-studio.spec.ts'
  export CANVAS_E2E_TEXT_FIXTURE=0 HYPIT_FIX2_TEXT_FIXTURE=0 HYPIT_E2E=0 DH_E2E=0 DH_FIX2_E2E=0
  cleanup() { local status=$?; trap - EXIT; docker builder prune -af --filter until=24h || status=1; exit "$status"; }
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  bash scripts/ci-e2e.sh
  exit
fi
source scripts/lib/local-stack.sh
if [[ "$1" == it ]]; then
  local_stack_enter y1-task108-it "$ROOT_DIR/scripts/acceptance/verify-108.sh" --docker --cleanup -- "$@"
  cleanup() { local status=$?; trap - EXIT; docker builder prune -af --filter until=24h || status=1; exit "$status"; }
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
else
  local_stack_enter y1-task108-unit "$ROOT_DIR/scripts/acceptance/verify-108.sh" -- "$@"
fi
source scripts/lib/java-runtime.sh
ensure_java_runtime 25
filters=()
if [[ "$1" == unit ]]; then
  filters=(article.ArticlePromptsTest moments.MomentsPromptsTest imageanalysis.ImageAnalysisPromptsTest humanize.HumanizeInjectionServiceTest creationvoice.CreationVoiceLearningTest)
elif [[ "$2" == storage ]]; then
  filters=(creationvoice.CreationVoiceIT imageanalysis.StylePreferencesRepositoryIT humanize.HumanizeSkillIT)
else
  filters=('article.*' 'moments.*' 'imageanalysis.*' creationvoice.CreationVoiceGenerationIT creationstudio.TextProposalIT)
fi
args=()
for filter in "${filters[@]}"; do args+=(--tests "com.grassland.intelligence.$filter"); done
cd platform-java
./gradlew --no-daemon --max-workers=1 :services:intelligence-service:test "${args[@]}"
