#!/usr/bin/env bash
# Re-exec under a Node supervisor; the lease spans build, test, and cleanup.
# Arguments: project, absolute script path, guard-options, --, original script args.
local_stack_enter() {
  local project="$1" script="$2"
  shift 2
  local -a guard_options=()
  while [[ "$#" -gt 0 && "$1" != "--" ]]; do guard_options+=("$1"); shift; done
  [[ "$#" -gt 0 ]] || { printf 'local_stack_enter: missing --\n' >&2; exit 2; }
  shift
  local guard_root
  guard_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  if [[ "${LOCAL_STACK_ENTRY:-}" != "$script" ]]; then
    exec node "$guard_root/scripts/local-stack.mjs" run --project "$project" --entry "$script" \
      ${guard_options[@]+"${guard_options[@]}"} -- bash "$script" "$@"
  fi
  node "$guard_root/scripts/local-stack.mjs" session --project "$project" || exit $?
}
