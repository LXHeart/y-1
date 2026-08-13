#!/usr/bin/env bash

# Load a small dotenv grammar without evaluating file contents.
load_dotenv() {
  local file="$1" line name value raw
  [[ -r "$file" ]] || { echo "env file is not readable: $file" >&2; return 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]]; then
      echo "invalid dotenv assignment in $file" >&2
      return 1
    fi
    name="${BASH_REMATCH[2]}"
    raw="${BASH_REMATCH[3]}"
    raw="${raw#${raw%%[![:space:]]*}}"
    raw="${raw%${raw##*[![:space:]]}}"
    if [[ "$raw" == *'$('* || "$raw" == *'`'* ]]; then
      echo "dotenv command substitution is forbidden in $file" >&2
      return 1
    fi
    if [[ "$raw" == \"*\" ]]; then
      [[ "${raw: -1}" == '"' ]] || { echo "unterminated dotenv quote in $file" >&2; return 1; }
      value="${raw:1:${#raw}-2}"
    elif [[ "$raw" == \'*\' ]]; then
      [[ "${raw: -1}" == "'" ]] || { echo "unterminated dotenv quote in $file" >&2; return 1; }
      value="${raw:1:${#raw}-2}"
    else
      value="$raw"
    fi
    printf -v "$name" '%s' "$value"
    export "$name"
  done < "$file"
}
