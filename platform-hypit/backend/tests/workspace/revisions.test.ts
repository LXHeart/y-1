// revisions.test.ts — C107-04 (task-107) 04.7 expectations: snapshots freeze
// read-only bytes; later head writes (atomic replace) never mutate a frozen
// revision; the stable results repository is project-owned, not per-revision;
// verification catches tampering; template provisioning publishes revision 1
// only after byte verification.
import { strict as assert } from "node:assert";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { applyWorkspaceChanges, publishInitialHead, readHead } from "../../src/workspace/transactions.ts";
import { computeWorkspaceManifest, manifestHash } from "../../src/workspace/manifest.ts";
import {
  ensureResultsRepository,
  readRevisionFile,
  snapshotRevision,
  verifySnapshot,
} from "../../src/workspace/revisions.ts";
import { provisionFromTemplate, provisionWorkspace } from "../../src/workspace/provision.ts";

const PROJECT_ID = "33333333-3333-4333-8333-333333333333";

async function seededProject(): Promise<string> {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-rev-"));
  const projectRoot = join(projectsRoot, PROJECT_ID);
  mkdirSync(join(projectRoot, "work"), { recursive: true });
  mkdirSync(join(projectRoot, ".journal"), { recursive: true });
  writeFileSync(join(projectRoot, "work", "main.svml"), "revision one content");
  const manifest = await computeWorkspaceManifest(join(projectRoot, "work"));
  await publishInitialHead(projectRoot, 1, manifestHash(manifest));
  return projectRoot;
}

test("snapshot survives later head writes (compile A unaffected by head B)", async () => {
  const projectRoot = await seededProject();
  try {
    const first = await snapshotRevision(projectRoot, 1);
    assert.equal(first.revision, 1);

    await applyWorkspaceChanges({
      projectId: PROJECT_ID,
      projectRoot,
      commandId: "cmd-2",
      baseRevision: 1,
      changes: [{ path: "main.svml", action: "put", content: "revision two content" }],
    });
    const second = await snapshotRevision(projectRoot, 2);
    assert.equal(second.revision, 2);

    assert.equal(await readRevisionFile(projectRoot, 1, "main.svml"), "revision one content");
    assert.equal(await readRevisionFile(projectRoot, 2, "main.svml"), "revision two content");
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "revision two content");
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("snapshot verification passes clean and catches tampering", async () => {
  const projectRoot = await seededProject();
  try {
    await snapshotRevision(projectRoot, 1);
    assert.equal((await verifySnapshot(projectRoot, 1)).ok, true);
    const frozen = join(projectRoot, "revisions", "1", "main.svml");
    chmodSync(frozen, 0o644);
    writeFileSync(frozen, "tampered");
    const verification = await verifySnapshot(projectRoot, 1);
    assert.equal(verification.ok, false);
    assert.deepEqual(verification.mismatches, ["main.svml"]);
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("snapshot refuses drifted head and wrong revision", async () => {
  const projectRoot = await seededProject();
  try {
    await assert.rejects(() => snapshotRevision(projectRoot, 5));
    writeFileSync(join(projectRoot, "work", "main.svml"), "drift");
    await assert.rejects(() => snapshotRevision(projectRoot, 1));
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("results repository is project-owned and idempotent, never per-revision", async () => {
  const projectRoot = await seededProject();
  try {
    const first = await ensureResultsRepository(projectRoot, PROJECT_ID);
    const second = await ensureResultsRepository(projectRoot, PROJECT_ID);
    assert.equal(first, second);
    assert.ok(existsSync(join(first, "repository.json")));
    assert.equal((await readHead(projectRoot))?.revision, 1);
    await snapshotRevision(projectRoot, 1);
    assert.ok(
      !existsSync(join(projectRoot, "revisions", "1", "results")),
      "snapshots must not fork the results repository",
    );
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("provision is idempotent per projectId and validates the boundary", async () => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-prov-"));
  try {
    const first = await provisionWorkspace(projectsRoot, PROJECT_ID);
    assert.equal(first.state, "created");
    const second = await provisionWorkspace(projectsRoot, PROJECT_ID);
    assert.equal(second.state, "existing");
    assert.equal(first.projectRoot, second.projectRoot);
    for (const dir of ["work", "revisions", "results", "assets", ".journal"]) {
      assert.ok(existsSync(join(first.projectRoot, dir)), `missing ${dir}`);
    }
    await assert.rejects(() => provisionWorkspace(projectsRoot, "not-a-uuid"));
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

test("template provisioning publishes manifest + revision 1, retry converges", async () => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-tpl-"));
  const templateDir = mkdtempSync(join(tmpdir(), "hypit-tpl-src-"));
  const templateFiles = ["package.json", "main.svml", "main.svrun"];
  writeFileSync(join(templateDir, "package.json"), '{"name":"t","version":"0.0.0","private":true,"type":"module"}');
  writeFileSync(join(templateDir, "main.svml"), "<svml></svml>");
  writeFileSync(join(templateDir, "main.svrun"), "<svrun version=\"1\"></svrun>");
  try {
    const receipt = await provisionFromTemplate(projectsRoot, PROJECT_ID, templateDir, templateFiles);
    assert.ok(receipt.head !== null);
    assert.equal(receipt.head?.revision, 1);
    const snapshot = await snapshotRevision(join(projectsRoot, PROJECT_ID), 1);
    assert.equal(snapshot.revision, 1);
    assert.equal((await verifySnapshot(join(projectsRoot, PROJECT_ID), 1)).ok, true);
    // Idempotent retry of the same provisioning command converges.
    const retry = await provisionFromTemplate(projectsRoot, PROJECT_ID, templateDir, templateFiles);
    assert.equal(retry.head?.revision, 1);
    assert.equal(retry.head?.manifestHash, receipt.head?.manifestHash);
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
    rmSync(templateDir, { recursive: true, force: true });
  }
});
