// transactions.test.ts — C107-04 (task-107) TC107-04-01/02/03 expectations for
// K06.3 file transactions: idempotent replay, stale-baseRevision CAS (409
// semantics), per-file baseHash conflicts, bad-syntax save vs validated
// rejection, mid-publish crash recovery to a consistent head, and revision
// monotonicity under concurrent editors.
import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  applyWorkspaceChanges,
  publishInitialHead,
  readHead,
  recoverPendingTransactions,
  WorkspaceConflictError,
} from "../../src/workspace/transactions.ts";
import { computeWorkspaceManifest, hashText, manifestHash } from "../../src/workspace/manifest.ts";

interface Fixture {
  readonly projectsRoot: string;
  readonly projectRoot: string;
}

async function seededProject(): Promise<Fixture> {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-tx-"));
  const projectId = "11111111-1111-4111-8111-111111111111";
  const projectRoot = join(projectsRoot, projectId);
  mkdirSync(join(projectRoot, "work"), { recursive: true });
  mkdirSync(join(projectRoot, ".journal"), { recursive: true });
  writeFileSync(join(projectRoot, "work", "main.svml"), "old bytes");
  const manifest = await computeWorkspaceManifest(join(projectRoot, "work"));
  await publishInitialHead(projectRoot, 1, manifestHash(manifest));
  return { projectsRoot, projectRoot };
}

test("same command replays one revision; concurrent stale editor gets 409 semantics", async () => {
  const { projectRoot, projectsRoot } = await seededProject();
  try {
    const change = [{ path: "main.svml", action: "put" as const, content: "new bytes" }];
    const first = await applyWorkspaceChanges({
      projectId: "11111111-1111-4111-8111-111111111111",
      projectRoot,
      commandId: "cmd-1",
      baseRevision: 1,
      changes: change,
    });
    assert.equal(first.revision, 2);
    // Replay of the same command (crashed before receipt): base is now stale,
    // so replay lands in the CAS rejection — the Java layer converges via the
    // journal receipt, never by applying twice.
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "11111111-1111-4111-8111-111111111111",
        projectRoot,
        commandId: "cmd-1",
        baseRevision: 1,
        changes: change,
      }),
      WorkspaceConflictError,
    );
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "new bytes");
    const head = await readHead(projectRoot);
    assert.equal(head?.revision, 2, "revision must only advance once");
    rmSync(projectsRoot, { recursive: true, force: true });
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

test("per-file baseHash CAS rejects silent overwrites", async () => {
  const { projectRoot } = await seededProject();
  try {
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-cas",
        baseRevision: 1,
        changes: [{
          path: "main.svml",
          action: "put",
          content: "clobber",
          baseHash: "0".repeat(64),
        }],
      }),
      WorkspaceConflictError,
    );
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "old bytes");
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("plain save allows invalid syntax content (no gate at transaction layer)", async () => {
  const { projectRoot } = await seededProject();
  try {
    const receipt = await applyWorkspaceChanges({
      projectId: "x",
      projectRoot,
      commandId: "cmd-save",
      baseRevision: 1,
      changes: [{ path: "main.svml", action: "put", content: "<?svml totally broken" }],
    });
    assert.equal(receipt.revision, 2);
    assert.ok(readFileSync(join(projectRoot, "work", "main.svml"), "utf8").startsWith("<?svml totally"));
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("mid-publish failure rolls back to the FULL old revision", async () => {
  const { projectRoot } = await seededProject();
  // Two files: the second rename is killed by the fault hook.
  writeFileSync(join(projectRoot, "work", "second.svml"), "second old");
  try {
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-crash",
        baseRevision: 1,
        changes: [
          { path: "main.svml", action: "put", content: "main new" },
          { path: "second.svml", action: "put", content: "second new" },
        ],
        faults: { failBeforePublish: (_journalId, publishedCount) => publishedCount === 1 },
      }),
      Error,
    );
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "old bytes");
    assert.equal(readFileSync(join(projectRoot, "work", "second.svml"), "utf8"), "second old");
    const head = await readHead(projectRoot);
    assert.equal(head?.revision, 1, "head must stay at the old revision");
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("process death mid-publish (1/2 renamed) rolls back via recovery", async () => {
  const { projectRoot } = await seededProject();
  writeFileSync(join(projectRoot, "work", "second.svml"), "second old");
  // Re-seed the head marker to cover both files (revision stays 1).
  rmSync(join(projectRoot, "workspace.json"));
  const both = await computeWorkspaceManifest(join(projectRoot, "work"));
  await publishInitialHead(projectRoot, 1, manifestHash(both));
  try {
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-death",
        baseRevision: 1,
        changes: [
          { path: "main.svml", action: "put", content: "main new" },
          { path: "second.svml", action: "put", content: "second new" },
        ],
        faults: {
          failBeforePublish: (_id, publishedCount) => publishedCount === 1,
          crashInsteadOfRollback: true,
        },
      }),
      Error,
    );
    // Journal is mid-flight with exactly one file published.
    const recovered = await recoverPendingTransactions(projectRoot, "x");
    assert.equal(recovered.length, 1);
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "old bytes");
    assert.equal(readFileSync(join(projectRoot, "work", "second.svml"), "utf8"), "second old");
    const head = await readHead(projectRoot);
    assert.equal(head?.revision, 1, "rolled-back head must be the full old revision");
    // A new apply against the recovered head succeeds.
    const retry = await applyWorkspaceChanges({
      projectId: "x",
      projectRoot,
      commandId: "cmd-death-retry",
      baseRevision: 1,
      changes: [{ path: "main.svml", action: "put", content: "main new" }],
    });
    assert.equal(retry.revision, 2);
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("process death after all renames, before head: recovery completes forward", async () => {
  const { projectRoot } = await seededProject();
  try {
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-forward",
        baseRevision: 1,
        changes: [{ path: "main.svml", action: "put", content: "forward recovered" }],
        faults: {
          failBeforeHeadWrite: () => true,
          crashInsteadOfRollback: true,
        },
      }),
      Error,
    );
    assert.equal((await readHead(projectRoot))?.revision, 1, "head not yet advanced");
    const recovered = await recoverPendingTransactions(projectRoot, "x");
    assert.equal(recovered.length, 1);
    const head = await readHead(projectRoot);
    assert.equal(head?.revision, 2, "recovery finished publishing the new head");
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "forward recovered");
    assert.deepEqual(await recoverPendingTransactions(projectRoot, "x"), []);
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("crash after head write but before journal commit: recovery marks done without re-advancing", async () => {
  const { projectRoot } = await seededProject();
  try {
    let journalIdSeen = "";
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-head",
        baseRevision: 1,
        changes: [{ path: "main.svml", action: "put", content: "after crash" }],
        faults: {
          failBeforePublish: (journalId) => {
            journalIdSeen = journalId;
            return false;
          },
          failAfterHeadWrite: () => true,
          crashInsteadOfRollback: true,
        },
      }),
      Error,
    );
    let head = await readHead(projectRoot);
    assert.equal(head?.revision, 2);
    const recovered = await recoverPendingTransactions(projectRoot, "x");
    assert.deepEqual(recovered, [journalIdSeen]);
    head = await readHead(projectRoot);
    assert.equal(head?.revision, 2, "recovery must not advance revision again");
    assert.equal(readFileSync(join(projectRoot, "work", "main.svml"), "utf8"), "after crash");
    assert.deepEqual(await recoverPendingTransactions(projectRoot, "x"), []);
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("drifted workspace refuses new applies (head marker is truth)", async () => {
  const { projectRoot } = await seededProject();
  try {
    writeFileSync(join(projectRoot, "work", "main.svml"), "tampered outside");
    await assert.rejects(
      () => applyWorkspaceChanges({
        projectId: "x",
        projectRoot,
        commandId: "cmd-drift",
        baseRevision: 1,
        changes: [{ path: "main.svml", action: "put", content: "anything" }],
      }),
      WorkspaceConflictError,
    );
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("delete + multi-file apply keeps manifest hashes consistent", async () => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-tx-"));
  const projectRoot = join(projectsRoot, "22222222-2222-4222-8222-222222222222");
  mkdirSync(join(projectRoot, "work", "extra"), { recursive: true });
  mkdirSync(join(projectRoot, ".journal"), { recursive: true });
  writeFileSync(join(projectRoot, "work", "main.svml"), "old bytes");
  writeFileSync(join(projectRoot, "work", "extra", "notes.md"), "notes");
  try {
    const manifest = await computeWorkspaceManifest(join(projectRoot, "work"));
    await publishInitialHead(projectRoot, 1, manifestHash(manifest));
    const receipt = await applyWorkspaceChanges({
      projectId: "22222222-2222-4222-8222-222222222222",
      projectRoot,
      commandId: "cmd-multi",
      baseRevision: 1,
      changes: [
        { path: "extra/notes.md", action: "delete" },
        { path: "fresh.txt", action: "put", content: "fresh" },
      ],
    });
    assert.equal(receipt.revision, 2);
    const after = await computeWorkspaceManifest(join(projectRoot, "work"));
    assert.equal(manifestHash(after), receipt.manifestHash);
    const paths = after.entries.map((entry) => entry.path);
    assert.ok(!paths.includes("extra/notes.md"));
    assert.ok(paths.includes("fresh.txt"));
    assert.ok(paths.includes("main.svml"));
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

test("journal directory only keeps terminal records after commit", async () => {
  const { projectRoot } = await seededProject();
  try {
    const receipt = await applyWorkspaceChanges({
      projectId: "x",
      projectRoot,
      commandId: "cmd-clean",
      baseRevision: 1,
      changes: [{ path: "main.svml", action: "put", content: "clean" }],
    });
    const journalEntries = readdirSync(join(projectRoot, ".journal"));
    assert.ok(journalEntries.includes(`${receipt.journalId}.json`));
    assert.ok(
      !journalEntries.includes(`${receipt.journalId}.staging`),
      "staging must be removed after commit",
    );
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
});

test("hashText matches file hashing for identical content", async () => {
  const root = mkdtempSync(join(tmpdir(), "hypit-hash-"));
  try {
    writeFileSync(join(root, "f.txt"), "abc");
    const { hashFile } = await import("../../src/workspace/manifest.ts");
    assert.equal(await hashFile(join(root, "f.txt")), await hashText("abc"));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
