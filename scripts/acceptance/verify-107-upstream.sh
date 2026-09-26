#!/usr/bin/env bash
# verify-107-upstream.sh — C107-01 (task-107) upstream vendor integrity check.
#
# Default: verify platform-hypit/upstream against platform-hypit/upstream-manifest.json
# (path set equality + per-file sha256 + git mode). The original download directory
# is NOT required for the default check.
#
# --source <repo>  additionally export the manifest's commit from the given local
#                  Git repository via `git archive` and verify the manifest against
#                  that pristine export (catches a manifest tampered to match a
#                  modified working copy).
# --root <dir>     verify this directory instead of platform-hypit/upstream
#                  (used by tamper tests on throwaway copies).
#
# Exit codes: 0 = identical; 1 = missing/extra/changed/modeChanged differences found
# (each category lists its paths); 2 = usage or internal error.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${REPO_ROOT}/platform-hypit/upstream-manifest.json"
UPSTREAM_DIR="${REPO_ROOT}/platform-hypit/upstream"
SOURCE_REPO=""

usage() {
  echo "usage: bash scripts/acceptance/verify-107-upstream.sh [--source <local-hypit-repo>] [--root <dir>]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || usage
      SOURCE_REPO="$2"
      shift 2
      ;;
    --root)
      [[ $# -ge 2 ]] || usage
      UPSTREAM_DIR="$2"
      shift 2
      ;;
    -h|--help) usage ;;
    *) usage ;;
  esac
done

[[ -f "${MANIFEST}" ]] || { echo "manifest not found: ${MANIFEST}" >&2; exit 2; }
[[ -d "${UPSTREAM_DIR}" ]] || { echo "upstream dir not found: ${UPSTREAM_DIR}" >&2; exit 2; }

# Core verifier: node script reads manifest + root from argv, walks the tree,
# classifies differences, prints per-category paths, exits 1 on any.
run_verify() {
  local manifest="$1"
  local root="$2"
  node --input-type=module - "${manifest}" "${root}" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, lstatSync, readlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';

const [manifestPath, rootArg] = process.argv.slice(2);
// resolve collapses '//' (macOS TMPDIR ends with '/'), keeping rel paths exact
const rootDir = resolve(rootArg);
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (manifest.format !== 'y1.hypit-upstream@1') {
  console.error(`unknown manifest format: ${manifest.format}`);
  process.exit(2);
}

// Explicit generated-output set excluded from "extra" detection. Deliberately a
// closed list (upstream's own ignore semantics); tracked paths never collide with
// any of these names — verified at manifest generation time.
const IGNORED_DIRS = new Set([
  'node_modules', 'dist', '.venv', 'venv', '__pycache__', '.pytest_cache',
  '.mypy_cache', '.ruff_cache', 'coverage', 'playwright-report', 'test-results',
  '.svml-cache', '.hypit', '.turbo', '.nyc_output', '.idea', '.vscode', 'build', 'out',
]);
const IGNORED_FILE_SUFFIXES = ['.log', '.tmp', '.temp', '.tsbuildinfo', '.sqlite', '.db', '.sqlite3'];
const IGNORED_FILE_NAMES = new Set(['.DS_Store']);

function sha256Bytes(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

function digestEntry(abs, expectSymlink) {
  const st = lstatSync(abs);
  if (expectSymlink) {
    if (!st.isSymbolicLink()) return null;
    return sha256Bytes(Buffer.from(readlinkSync(abs), 'utf8'));
  }
  if (!st.isFile() || st.isSymbolicLink()) return null;
  return sha256Bytes(readFileSync(abs));
}

function modeOf(st, expectSymlink) {
  if (expectSymlink) return st.isSymbolicLink() ? '120000' : null;
  if (!st.isFile()) return null;
  return st.mode & 0o111 ? '100755' : '100644';
}

const expected = new Map(manifest.files.map((f) => [f.path, f]));
const missing = [];
const changed = [];
const modeChanged = [];
const sizeMismatch = [];
const typeMismatch = [];

for (const [path, meta] of expected) {
  const abs = join(rootDir, path);
  let st;
  try {
    st = lstatSync(abs);
  } catch {
    missing.push(path);
    continue;
  }
  const expectSymlink = meta.mode === '120000';
  const actual = digestEntry(abs, expectSymlink);
  if (actual === null) {
    typeMismatch.push(path);
    continue;
  }
  const mode = modeOf(st, expectSymlink);
  if (mode !== meta.mode) modeChanged.push(`${path} (expected ${meta.mode}, got ${mode})`);
  if (actual !== meta.sha256) changed.push(path);
  else if (st.isSymbolicLink()) {
    if (Buffer.byteLength(readlinkSync(abs)) !== meta.sizeBytes) sizeMismatch.push(path);
  } else if (st.size !== meta.sizeBytes) sizeMismatch.push(path);
}

const extra = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    const rel = abs.slice(rootDir.length + 1);
    const st = lstatSync(abs);
    if (st.isDirectory()) {
      if (IGNORED_DIRS.has(name)) continue;
      walk(abs);
    } else if (IGNORED_FILE_NAMES.has(name)) {
      continue;
    } else if (IGNORED_FILE_SUFFIXES.some((s) => name.endsWith(s))) {
      continue;
    } else if (!expected.has(rel)) {
      extra.push(rel);
    }
  }
})(rootDir);

const categories = { missing, extra, changed, modeChanged, sizeMismatch, typeMismatch };
let bad = 0;
for (const [name, list] of Object.entries(categories)) {
  if (list.length === 0) continue;
  bad += list.length;
  console.log(`${name} (${list.length}):`);
  for (const p of list) console.log(`  ${p}`);
}
if (bad === 0) {
  console.log(
    `upstream OK: ${manifest.files.length} tracked paths match manifest ` +
      `(commit ${manifest.commit}, version ${manifest.version})`,
  );
  process.exit(0);
}
console.error(`${bad} difference(s) across ${Object.entries(categories).filter(([, l]) => l.length).map(([n]) => n).join(', ')}`);
process.exit(1);
NODE
}

fail=0
if ! run_verify "${MANIFEST}" "${UPSTREAM_DIR}"; then
  fail=1
fi

if [[ -n "${SOURCE_REPO}" ]]; then
  if [[ ! -d "${SOURCE_REPO}/.git" ]]; then
    echo "--source is not a Git repository: ${SOURCE_REPO}" >&2
    exit 2
  fi
  commit="$(node --input-type=module - "${MANIFEST}" <<'NODE'
import { readFileSync } from 'node:fs';
console.log(JSON.parse(readFileSync(process.argv[2], 'utf8')).commit);
NODE
)"
  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/hypit-verify.XXXXXX")"
  trap 'rm -rf "${tmpdir}"' EXIT
  git -C "${SOURCE_REPO}" archive "${commit}" | tar -x -C "${tmpdir}"
  echo "--source: verifying manifest against git archive of ${commit}"
  if ! run_verify "${MANIFEST}" "${tmpdir}"; then
    echo "--source: manifest does not match commit ${commit} in ${SOURCE_REPO}" >&2
    fail=1
  fi
fi

exit "${fail}"
