#!/usr/bin/env bash
# build-107-engine.sh — C107-02 (task-107) engine build: U -> G with replayable patches.
#
# 1. Verify platform-hypit/upstream against upstream-manifest.json (V01).
# 2. Recreate platform-hypit/.generated/hypit (G) from the manifest's tracked set.
# 3. Apply platform-hypit/patches/*.patch in order (git apply --check first).
# 4. Verify each patched file's before/after SHA-256 against patches/manifest.json.
# 5. Ensure G has frozen dependencies installed (pnpm --frozen-lockfile, idempotent).
# 6. Print the stable G source digest (same inputs => same digest; patch failure exits non-zero).
#
# Flags: --no-install   skip dependency installation (tests that don't execute G)
#        --skip-verify  skip the upstream verification step (CI already ran V01)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UPSTREAM_DIR="${REPO_ROOT}/platform-hypit/upstream"
MANIFEST="${REPO_ROOT}/platform-hypit/upstream-manifest.json"
# Overridable for tests that must not disturb the shared G (e.g. patch-replay
# builds into a throwaway root); production always materializes the real G.
GENERATED_ROOT="${HYPIT_GENERATED_ROOT:-${REPO_ROOT}/platform-hypit/.generated/hypit}"
# Overridable for tamper tests; production always uses the committed patches dir.
PATCHES_DIR="${HYPIT_PATCHES_DIR:-${REPO_ROOT}/platform-hypit/patches}"
PATCH_MANIFEST="${PATCHES_DIR}/manifest.json"

DO_INSTALL=1
DO_VERIFY=1
for arg in "$@"; do
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --skip-verify) DO_VERIFY=0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [[ "${DO_VERIFY}" == 1 ]]; then
  bash "${REPO_ROOT}/scripts/acceptance/verify-107-upstream.sh"
fi

# --- recreate G from the manifest's tracked set (preserves mode + symlinks) ---
rm -rf "${GENERATED_ROOT}"
node --input-type=module - "${MANIFEST}" "${UPSTREAM_DIR}" "${GENERATED_ROOT}" <<'NODE'
import { chmodSync, copyFileSync, mkdirSync, readFileSync, readlinkSync, rmSync, symlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
const [manifestPath, upstreamDir, generatedRoot] = process.argv.slice(2);
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
rmSync(generatedRoot, { recursive: true, force: true });
for (const file of manifest.files) {
  const target = join(generatedRoot, file.path);
  mkdirSync(dirname(target), { recursive: true });
  if (file.mode === '120000') {
    symlinkSync(readlinkSync(join(upstreamDir, file.path)), target);
  } else {
    copyFileSync(join(upstreamDir, file.path), target);
    if (file.mode === '100755') chmodSync(target, 0o755);
  }
}
console.log(`G recreated: ${manifest.files.length} tracked paths`);
NODE

# --- apply patches in order; check first, fail fast, verify manifest hashes ---
PATCH_FILES=()
while IFS= read -r line; do
  PATCH_FILES+=("${PATCHES_DIR}/${line}")
done < <(node --input-type=module - "${PATCH_MANIFEST}" <<'NODE'
import { readFileSync } from 'node:fs';
const manifest = JSON.parse(readFileSync(process.argv[2], 'utf8'));
for (const patch of manifest.patches) console.log(patch.file);
NODE
)

# G lives inside the y-1 repository; without this, git apply resolves patch paths
# against the y-1 repo root and silently skips every path outside cwd.
export GIT_CEILING_DIRECTORIES="${REPO_ROOT}"

for patch_path in "${PATCH_FILES[@]}"; do
  [[ -f "${patch_path}" ]] || { echo "patch listed in manifest but missing: ${patch_path}" >&2; exit 1; }
  echo "applying $(basename "${patch_path}")"
  (cd "${GENERATED_ROOT}" && git apply --check "${patch_path}" && git apply "${patch_path}")
done

node --input-type=module - "${PATCH_MANIFEST}" "${GENERATED_ROOT}" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
const [patchManifestPath, generatedRoot] = process.argv.slice(2);
const manifest = JSON.parse(readFileSync(patchManifestPath, 'utf8'));
let failures = 0;
for (const patch of manifest.patches) {
  for (const file of patch.files) {
    const actual = createHash('sha256').update(readFileSync(join(generatedRoot, file.path))).digest('hex');
    if (actual !== file.afterSha256) {
      console.error(`patch hash mismatch: ${patch.file} -> ${file.path}`);
      console.error(`  expected ${file.afterSha256}`);
      console.error(`  actual   ${actual}`);
      failures += 1;
    }
  }
}
if (failures > 0) process.exit(1);
console.log(`patch verification OK (${manifest.patches.length} patch(es))`);
NODE

# --- stable source digest over the G tracked set (excludes node_modules) ---
DIGEST="$(node --input-type=module - "${MANIFEST}" "${GENERATED_ROOT}" "${PATCH_MANIFEST}" <<'NODE'
import { createHash } from 'node:crypto';
import { readFileSync, readlinkSync } from 'node:fs';
import { join } from 'node:path';
const [manifestPath, generatedRoot, patchManifestPath] = process.argv.slice(2);
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const patchManifest = JSON.parse(readFileSync(patchManifestPath, 'utf8'));
const patchedAfter = new Map(patchManifest.patches.flatMap((p) => p.files.map((f) => [f.path, f.afterSha256])));
const h = createHash('sha256');
for (const file of manifest.files) {
  const expected = patchedAfter.get(file.path) ?? file.sha256;
  h.update(file.path + '\0' + expected + '\0');
  const abs = join(generatedRoot, file.path);
  const actual = file.mode === '120000'
    ? createHash('sha256').update(readlinkSync(abs)).digest('hex')
    : createHash('sha256').update(readFileSync(abs)).digest('hex');
  if (actual !== expected) {
    console.error(`G integrity mismatch at ${file.path}`);
    process.exit(1);
  }
}
console.log(h.digest('hex'));
NODE
)"
echo "G source digest: ${DIGEST}"

# --- frozen dependencies (idempotent; G keeps its own pnpm store-linked node_modules) ---
if [[ "${DO_INSTALL}" == 1 ]]; then
  if ! command -v pnpm >/dev/null 2>&1; then
    echo "pnpm not found (need pnpm 10.33.0 on Node 24.14.1)" >&2
    exit 1
  fi
  (cd "${GENERATED_ROOT}" && pnpm install --frozen-lockfile --prefer-offline)
fi

echo "engine build complete: ${GENERATED_ROOT}"
