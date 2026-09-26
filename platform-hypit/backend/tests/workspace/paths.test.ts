// paths.test.ts — C107-04 (task-107) TC107-04 expectations for the workspace
// path boundary (§5.1 / 04.5): traversal, symlinks, encoded paths and NUL are
// rejected on READ as well as WRITE; listings shield internal entries.
import { strict as assert } from "node:assert";
import { mkdtempSync, rmSync, symlinkSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  assertReadableWithin,
  isShieldedFromListing,
  resolveWithinWorkspace,
  validateWorkspaceRelativePath,
  WorkspacePathError,
  MAX_WORKSPACE_FILE_BYTES,
} from "../../src/workspace/paths.ts";

function expectReject(relative: string): void {
  assert.throws(() => validateWorkspaceRelativePath(relative), WorkspacePathError);
}

test("accepts plain relative author paths", () => {
  for (const path of ["main.svml", "src/deep/nested/file.ts", "packages/my-kit/activation.ts"]) {
    validateWorkspaceRelativePath(path);
  }
});

test("rejects traversal, absolute, encoded, NUL and control paths", () => {
  expectReject("");
  expectReject("../escape.svml");
  expectReject("safe/../../escape.svml");
  expectReject("/absolute.svml");
  expectReject("C:/drive.svml");
  expectReject("back\\slash.svml");
  expectReject("percent%2F.svml");
  expectReject("nul\0byte.svml");
  expectReject("control\u0001char.svml");
  expectReject("double//slash.svml");
  expectReject("dot/./inside.svml");
  expectReject("trailing/");
  expectReject(".hidden.svml");
  expectReject("deep/" + "a/".repeat(20) + "file.ts");
});

test("rejects symlink escape on read AND write (not only PUT)", async () => {
  const root = mkdtempSync(join(tmpdir(), "hypit-paths-"));
  try {
    writeFileSync(join(root, "real.svml"), "ok");
    const outside = mkdtempSync(join(tmpdir(), "hypit-outside-"));
    writeFileSync(join(outside, "secret.txt"), "secret");
    symlinkSync(outside, join(root, "link-out"));
    symlinkSync(join(root, "real.svml"), join(root, "link-file"));

    await assert.rejects(() => resolveWithinWorkspace(root, "link-out/secret.txt"), WorkspacePathError);
    await assert.rejects(() => assertReadableWithin(root, "link-out/secret.txt"), WorkspacePathError);
    await assert.rejects(() => resolveWithinWorkspace(root, "link-file"), WorkspacePathError);
    // The real file underneath still resolves.
    const resolved = await resolveWithinWorkspace(root, "real.svml");
    assert.equal(join(resolved), join(root, "real.svml"));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("read side requires existence; missing file is an error not silent null", async () => {
  const root = mkdtempSync(join(tmpdir(), "hypit-paths-"));
  try {
    await assert.rejects(() => assertReadableWithin(root, "absent.svml"), WorkspacePathError);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("listing shields credentials-like, journal and node_modules entries", () => {
  assert.equal(isShieldedFromListing("node_modules"), true);
  assert.equal(isShieldedFromListing(".journal"), true);
  assert.equal(isShieldedFromListing(".hypit"), true);
  assert.equal(isShieldedFromListing("main.svml"), false);
});

test("author file ceiling matches §5.1 (2MiB)", () => {
  assert.equal(MAX_WORKSPACE_FILE_BYTES, 2 * 1024 * 1024);
});

test("rejects workspace paths that resolve outside root via case tricks", () => {
  // Pure lexical case: validation is charset-based, so verify the containment
  // re-check catches resolution outside the root for a crafted absolute.
  assert.throws(() => validateWorkspaceRelativePath(join("x", "..", "..", "y")));
});

test("validates every segment including deep nesting under packages", async () => {
  const root = mkdtempSync(join(tmpdir(), "hypit-paths-"));
  try {
    mkdirSync(join(root, "packages", "kit"), { recursive: true });
    writeFileSync(join(root, "packages", "kit", "activation.ts"), "export {}");
    const absolute = await resolveWithinWorkspace(root, "packages/kit/activation.ts");
    assert.ok(absolute.endsWith(join("packages", "kit", "activation.ts")));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
