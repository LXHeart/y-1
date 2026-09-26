// patch-replay.test.ts — C107-02 (task-107) V03 contract (TC107-02-02).
//
// Two clean G builds from the same U + patch list produce identical running
// source digests; U stays untouched; a corrupted patch fails the build fast.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

const repoRoot = join(import.meta.dirname, "../../../..");
const buildScript = join(repoRoot, "scripts/acceptance/build-107-engine.sh");
const verifyScript = join(repoRoot, "scripts/acceptance/verify-107-upstream.sh");
const patchesDir = join(repoRoot, "platform-hypit/patches");

// Build into throwaway generated roots: the shared G (and its installed
// dependencies) must survive test runs untouched.
function runBuild(env: NodeJS.ProcessEnv = process.env): { status: number; stdout: string; stderr: string } {
  const generatedRoot = mkdtempSync(join(tmpdir(), "hypit-generated-"));
  const scopedEnv = { ...process.env, ...env, HYPIT_GENERATED_ROOT: generatedRoot };
  const result = spawnSync("bash", [buildScript, "--skip-verify", "--no-install"], {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: 300_000,
    env: scopedEnv,
  });
  rmSync(generatedRoot, { recursive: true, force: true });
  return { status: result.status ?? -1, stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
}

function digestOf(output: string): string {
  const match = output.match(/^G source digest: ([0-9a-f]{64})$/m);
  assert.ok(match, `digest line missing in build output:\n${output.slice(-2000)}`);
  return match[1]!;
}

test("two clean G builds are byte-stable and leave U unchanged", { timeout: 600_000 }, () => {
  const first = runBuild();
  assert.equal(first.status, 0, first.stderr);
  const second = runBuild();
  assert.equal(second.status, 0, second.stderr);
  assert.equal(
    digestOf(first.stdout),
    digestOf(second.stdout),
    "consecutive builds of the same U + patches must produce identical source digests",
  );

  const verify = spawnSync("bash", [verifyScript], { cwd: repoRoot, encoding: "utf8" });
  assert.equal(verify.status, 0, `U must remain untouched by G builds:\n${verify.stderr}`);
});

test("a corrupted patch fails the build immediately", { timeout: 300_000 }, () => {
  const tempPatches = mkdtempSync(join(tmpdir(), "hypit-patches-"));
  try {
    cpSync(patchesDir, tempPatches, { recursive: true });
    // Corrupt the patch content: the recorded after-hash no longer matches.
    const patchPath = join(tempPatches, "0000-engine-bridge.patch");
    writeFileSync(patchPath, `${readFileSync(patchPath, "utf8")}\n+corrupt hunk\n@@ -999,1 +999,1 @@\n+nonsense\n`);

    const result = runBuild({ ...process.env, HYPIT_PATCHES_DIR: tempPatches });
    assert.notEqual(result.status, 0, "a corrupt patch must fail the build");
    assert.match(
      `${result.stderr}${result.stdout}`,
      /failed|error|mismatch|does not apply/i,
      "the failure must say why",
    );
  } finally {
    rmSync(tempPatches, { recursive: true, force: true });
  }
});

test("the committed patch manifest matches the actual patch file bytes", () => {
  const manifest = JSON.parse(readFileSync(join(patchesDir, "manifest.json"), "utf8")) as {
    readonly patches: readonly { readonly file: string; readonly sha256: string }[];
  };
  assert.ok(manifest.patches.length >= 1);
  for (const patch of manifest.patches) {
    const actual = createHash("sha256")
      .update(readFileSync(join(patchesDir, patch.file)))
      .digest("hex");
    assert.equal(actual, patch.sha256, `patch ${patch.file} bytes drifted from manifest`);
  }
});
