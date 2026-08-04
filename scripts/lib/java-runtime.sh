#!/usr/bin/env bash

java_major_version() {
  local java_bin="$1"
  local version
  local major

  version="$("$java_bin" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')" || return 1
  if [[ "$version" == 1.* ]]; then
    major="${version#1.}"
    major="${major%%.*}"
  else
    major="${version%%[._-]*}"
  fi

  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$major"
}

java_meets_minimum() {
  local java_bin="$1"
  local minimum="$2"
  local major

  [[ -x "$java_bin" ]] || return 1
  major="$(java_major_version "$java_bin")" || return 1
  (( major >= minimum ))
}

java_home_from_bin() {
  local java_bin="$1"
  local resolved_bin="$java_bin"
  local candidate_home

  if command -v realpath >/dev/null 2>&1; then
    resolved_bin="$(realpath "$java_bin" 2>/dev/null || printf '%s' "$java_bin")"
  elif command -v readlink >/dev/null 2>&1; then
    resolved_bin="$(readlink -f "$java_bin" 2>/dev/null || printf '%s' "$java_bin")"
  fi

  candidate_home="$(cd "$(dirname "$resolved_bin")/.." 2>/dev/null && pwd)" || return 1
  [[ -x "$candidate_home/bin/java" ]] || return 1
  printf '%s\n' "$candidate_home"
}

ensure_java_runtime() {
  local minimum="${1:-17}"
  local detected_home=""
  local candidate
  local configured_candidates="${JAVA_RUNTIME_CANDIDATES:-}"

  if command -v java >/dev/null 2>&1 && java_meets_minimum "$(command -v java)" "$minimum"; then
    if [[ -n "${JAVA_HOME:-}" ]] && java_meets_minimum "$JAVA_HOME/bin/java" "$minimum"; then
      return 0
    fi

    local command_home
    command_home="$(java_home_from_bin "$(command -v java)" 2>/dev/null || true)"
    if [[ -n "$command_home" ]] && java_meets_minimum "$command_home/bin/java" "$minimum"; then
      export JAVA_HOME="$command_home"
      export PATH="$JAVA_HOME/bin:$PATH"
      printf 'Using Java %s from %s\n' "$(java_major_version "$JAVA_HOME/bin/java")" "$JAVA_HOME" >&2
      return 0
    fi
  fi

  if [[ "${JAVA_RUNTIME_DISABLE_DEFAULTS:-0}" != "1" ]] && [[ -x /usr/libexec/java_home ]]; then
    detected_home="$(/usr/libexec/java_home -v "${minimum}+" 2>/dev/null || true)"
  fi

  if [[ -n "$configured_candidates" ]]; then
    configured_candidates="${configured_candidates}:"
  fi
  if [[ "${JAVA_RUNTIME_DISABLE_DEFAULTS:-0}" != "1" ]]; then
    configured_candidates="${configured_candidates}${detected_home}:/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home:/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home:/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home:/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home:/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home:/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  fi

  while IFS= read -r candidate; do
    if [[ -n "$candidate" ]] && java_meets_minimum "$candidate/bin/java" "$minimum"; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      printf 'Using Java %s from %s\n' "$(java_major_version "$JAVA_HOME/bin/java")" "$JAVA_HOME" >&2
      return 0
    fi
  done < <(printf '%s' "$configured_candidates" | tr ':' '\n')

  printf 'Java %s or later is required. Set JAVA_HOME or install a compatible OpenJDK.\n' "$minimum" >&2
  return 1
}
