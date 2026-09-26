// transactions.ts — C107-04 (task-107) K06.3 multi-file workspace transactions.
//
// Per-project serial write lock + durable journal + atomic per-file rename.
// The head marker (workspace.json sibling of work/) is republished ONLY after
// every file rename succeeded, so a reader either sees the full old revision
// or the full new one — never half of each. Journals are written BEFORE any
// destructive step and updated after each published file; recovery finishes
// publishing or restores backups, and refuses nothing less than a consistent
// head. PG-side convergence happens by replaying the same commandId and
// reading the journal receipt (K06.3.4 / card 04 事务失败恢复).
import { createHash } from "node:crypto";
import { mkdir, open, readdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

import {
  MAX_CHANGESET_BYTES,
  MAX_WORKSPACE_FILE_BYTES,
  resolveWithinWorkspace,
  validateWorkspaceRelativePath,
} from "./paths.ts";
import { computeWorkspaceManifest, hashText, manifestHash, type WorkspaceManifest } from "./manifest.ts";

export type FileChange = {
  readonly path: string;
  readonly action: "put" | "delete";
  readonly content?: string;
  readonly baseHash?: string;
};

export type HeadState = {
  readonly revision: number;
  readonly manifestHash: string;
  readonly updatedAt: string;
};

export type ApplyReceipt = {
  readonly journalId: string;
  readonly revision: number;
  readonly manifestHash: string;
  readonly manifest: WorkspaceManifest;
  readonly appliedPaths: readonly string[];
};

export type JournalState =
  | "prepared"
  | "publishing"
  | "committed"
  | "rolled_back";

export type JournalRecord = {
  readonly format: "y1.hypit-workspace-journal@1";
  readonly journalId: string;
  readonly commandId: string | null;
  readonly projectId: string;
  readonly baseRevision: number;
  readonly oldHead: HeadState | null;
  readonly newHead: HeadState;
  readonly files: readonly JournalFileEntry[];
  state: JournalState;
  readonly createdAt: string;
  updatedAt: string;
};

export type JournalFileEntry = {
  readonly path: string;
  readonly action: "put" | "delete";
  readonly oldHash: string | null;
  readonly newHash: string | null;
  published: boolean;
};

export class WorkspaceConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "WorkspaceConflictError";
  }
}

export class WorkspaceRecoveryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "WorkspaceRecoveryError";
  }
}

/** Failure-injection hooks so tests can kill the publish mid-flight (TC107-04-03). */
export type TransactionFaults = {
  readonly failBeforePublish?: (journalId: string, publishedCount: number) => boolean;
  readonly failBeforeHeadWrite?: (journalId: string) => boolean;
  readonly failAfterHeadWrite?: (journalId: string) => boolean;
  /**
   * Simulate process death: rethrow WITHOUT the in-process rollback so the
   * journal stays mid-flight and recovery must converge (TC107-04-03).
   */
  readonly crashInsteadOfRollback?: boolean;
};

export type ProjectLayout = {
  readonly projectId: string;
  readonly projectRoot: string;
  readonly workDir: string;
  readonly journalDir: string;
};

export function projectLayout(projectRoot: string, projectId: string): ProjectLayout {
  return {
    projectId,
    projectRoot,
    workDir: join(projectRoot, "work"),
    journalDir: join(projectRoot, ".journal"),
  };
}

const HEAD_FILE = "workspace.json";

// ---------------------------------------------------------------------------
// Per-project serial write lock (K06.3.1). One process owns the sidecar, so an
// in-process mutex is the workspace lock; the journal covers cross-restart.
// ---------------------------------------------------------------------------
const projectLocks = new Map<string, Promise<unknown>>();

export async function withProjectLock<T>(projectRoot: string, operation: () => Promise<T>): Promise<T> {
  const previous = projectLocks.get(projectRoot) ?? Promise.resolve();
  const own = previous.then(operation, operation);
  const gate = own.then(() => undefined, () => undefined);
  projectLocks.set(projectRoot, gate);
  try {
    return await own;
  } finally {
    if (projectLocks.get(projectRoot) === gate) projectLocks.delete(projectRoot);
  }
}

// ---------------------------------------------------------------------------
// Head marker
// ---------------------------------------------------------------------------

export async function readHead(projectRoot: string): Promise<HeadState | null> {
  try {
    const parsed = JSON.parse(await readFile(join(projectRoot, HEAD_FILE), "utf8")) as HeadState;
    if (typeof parsed.revision !== "number" || typeof parsed.manifestHash !== "string") return null;
    return parsed;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

async function publishHead(projectRoot: string, head: HeadState): Promise<void> {
  const target = join(projectRoot, HEAD_FILE);
  await writeDurableAtomic(target, `${JSON.stringify(head, null, 2)}\n`);
}

/**
 * Publish the very first head (template provisioning). Refuses to overwrite an
 * existing head so a double-delivered provisioning command cannot clobber
 * later edits.
 */
export async function publishInitialHead(
  projectRoot: string,
  revision: number,
  hash: string,
): Promise<HeadState> {
  const existing = await readHead(projectRoot);
  if (existing !== null) {
    if (existing.revision === revision && existing.manifestHash === hash) return existing;
    throw new WorkspaceConflictError("workspace already has a different published head");
  }
  const head: HeadState = { revision, manifestHash: hash, updatedAt: new Date().toISOString() };
  await publishHead(projectRoot, head);
  return head;
}

// ---------------------------------------------------------------------------
// Durable file helpers
// ---------------------------------------------------------------------------

async function writeDurableAtomic(target: string, text: string): Promise<void> {
  await mkdir(dirname(target), { recursive: true });
  const temporary = `${target}.tmp-${process.pid}-${Date.now()}`;
  const handle = await open(temporary, "w");
  try {
    await handle.writeFile(text, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, target);
}

async function copyDurable(from: string, to: string): Promise<void> {
  await mkdir(dirname(to), { recursive: true });
  const bytes = await readFile(from);
  const handle = await open(to, "w");
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

// ---------------------------------------------------------------------------
// Recovery (K06.3.5) — must run to convergence before any new write.
// ---------------------------------------------------------------------------

export async function recoverPendingTransactions(projectRoot: string, projectId: string): Promise<readonly string[]> {
  const layout = projectLayout(projectRoot, projectId);
  const recovered: string[] = [];
  let ids: string[] = [];
  try {
    ids = (await readdir(layout.journalDir)).filter((name) => name.endsWith(".json"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return recovered;
    throw error;
  }
  ids.sort();
  for (const name of ids) {
    const journalPath = join(layout.journalDir, name);
    let journal: JournalRecord;
    try {
      journal = JSON.parse(await readFile(journalPath, "utf8")) as JournalRecord;
    } catch {
      // Unreadable journal file: refuse writes rather than guess (recovery error).
      throw new WorkspaceRecoveryError(`unreadable journal: ${name}`);
    }
    if (journal.state === "committed" || journal.state === "rolled_back") continue;
    const head = await readHead(projectRoot);
    if (head !== null && head.revision === journal.newHead.revision
      && head.manifestHash === journal.newHead.manifestHash) {
      // Head already published — the only remaining step was the commit mark.
      journal.state = "committed";
      await writeJournal(journalPath, journal);
      recovered.push(journal.journalId);
      continue;
    }
    const allPublished = journal.files.every((file) => file.published);
    if (allPublished) {
      // Files are in place; finish by publishing the new head.
      await publishHead(projectRoot, journal.newHead);
      journal.state = "committed";
      journal.updatedAt = new Date().toISOString();
      await writeJournal(journalPath, journal);
      recovered.push(journal.journalId);
      continue;
    }
    // Roll back: restore every published file from its backup.
    await rollbackJournal(layout, journalPath, journal);
    recovered.push(journal.journalId);
  }
  return recovered;
}

async function rollbackJournal(
  layout: ProjectLayout,
  journalPath: string,
  journal: JournalRecord,
): Promise<void> {
  const backupRoot = join(layout.journalDir, `${journal.journalId}.backup`);
  for (const file of [...journal.files].reverse()) {
    if (!file.published) continue;
    const target = join(layout.workDir, file.path);
    const backup = join(backupRoot, file.path);
    if (file.action === "put" && file.oldHash === null) {
      await rm(target, { force: true });
      continue;
    }
    if (file.action === "delete" && file.oldHash !== null) {
      await mkdir(dirname(target), { recursive: true });
      await copyDurable(backup, target);
      continue;
    }
    if (file.oldHash !== null) {
      await mkdir(dirname(target), { recursive: true });
      await copyDurable(backup, target);
    }
  }
  if (journal.oldHead !== null) {
    await publishHead(layout.projectRoot, journal.oldHead);
  } else {
    await rm(join(layout.projectRoot, HEAD_FILE), { force: true });
  }
  journal.state = "rolled_back";
  journal.updatedAt = new Date().toISOString();
  await writeJournal(journalPath, journal);
  await rm(backupRoot, { recursive: true, force: true }).catch(() => {});
  await rm(join(layout.journalDir, `${journal.journalId}.staging`), { recursive: true, force: true }).catch(() => {});
}

async function writeJournal(path: string, journal: JournalRecord): Promise<void> {
  await writeDurableAtomic(path, `${JSON.stringify(journal, null, 2)}\n`);
}

// ---------------------------------------------------------------------------
// Apply (K06.3.1–K06.3.4)
// ---------------------------------------------------------------------------

export type ApplyOptions = {
  readonly projectId: string;
  readonly projectRoot: string;
  readonly commandId: string | null;
  readonly baseRevision: number;
  readonly changes: readonly FileChange[];
  readonly faults?: TransactionFaults;
};

export async function applyWorkspaceChanges(options: ApplyOptions): Promise<ApplyReceipt> {
  const layout = projectLayout(options.projectRoot, options.projectId);
  return await withProjectLock(options.projectRoot, async () => {
    await recoverPendingTransactions(options.projectRoot, options.projectId);
    return await applyLocked(layout, options);
  });
}

async function applyLocked(layout: ProjectLayout, options: ApplyOptions): Promise<ApplyReceipt> {
  if (options.changes.length === 0) {
    throw new WorkspaceConflictError("changeset is empty");
  }
  const head = await readHead(layout.projectRoot);
  if (head === null) {
    throw new WorkspaceConflictError("workspace has no published head; provision first");
  }
  if (head.revision !== options.baseRevision) {
    throw new WorkspaceConflictError(
      `base revision ${options.baseRevision} is stale; current head is ${head.revision}`,
    );
  }
  // Drift check: the head marker must describe the bytes actually on disk.
  const currentManifest = await computeWorkspaceManifest(layout.workDir);
  if (manifestHash(currentManifest) !== head.manifestHash) {
    throw new WorkspaceConflictError("workspace drifted from recorded head manifest");
  }

  let totalBytes = 0;
  const seen = new Set<string>();
  for (const change of options.changes) {
    validateWorkspaceRelativePath(change.path);
    if (seen.has(change.path)) {
      throw new WorkspaceConflictError(`duplicate change path: ${change.path}`);
    }
    seen.add(change.path);
    if (change.action === "put") {
      if (typeof change.content !== "string") {
        throw new WorkspaceConflictError(`put change missing content: ${change.path}`);
      }
      const size = Buffer.byteLength(change.content, "utf8");
      if (size > MAX_WORKSPACE_FILE_BYTES) {
        throw new WorkspaceConflictError(`file exceeds 2MiB author limit: ${change.path}`);
      }
      totalBytes += size;
    } else if (change.action !== "delete") {
      throw new WorkspaceConflictError(`unknown change action: ${String(change.action)}`);
    }
    if (totalBytes > MAX_CHANGESET_BYTES) {
      throw new WorkspaceConflictError("changeset exceeds 16MiB limit");
    }
  }

  // Resolve against the live filesystem (read-side symlink containment).
  for (const change of options.changes) {
    await resolveWithinWorkspace(layout.workDir, change.path);
  }

  const journalId = `${Date.now().toString(36)}-${createHash("sha256")
    .update(options.commandId ?? `${options.baseRevision}:${[...seen].sort().join("\n")}`)
    .digest("hex").slice(0, 12)}`;
  const stagingRoot = join(layout.journalDir, `${journalId}.staging`);
  const backupRoot = join(layout.journalDir, `${journalId}.backup`);
  const journalPath = join(layout.journalDir, `${journalId}.json`);
  await mkdir(stagingRoot, { recursive: true });
  await mkdir(backupRoot, { recursive: true });

  const files: JournalFileEntry[] = [];
  // 1. Stage new content + back up current bytes; verify per-file baseHash CAS.
  for (const change of options.changes) {
    const absolute = join(layout.workDir, change.path);
    const existing = await fileHashOrNull(absolute);
    if (change.baseHash !== undefined && change.baseHash !== existing) {
      throw new WorkspaceConflictError(
        `base hash mismatch for ${change.path}: expected ${change.baseHash ?? "absent"}, found ${existing ?? "absent"}`,
      );
    }
    let newHash: string | null = null;
    if (change.action === "put") {
      newHash = await hashText(change.content as string);
      const staged = join(stagingRoot, change.path);
      await mkdir(dirname(staged), { recursive: true });
      const handle = await open(staged, "w");
      try {
        await handle.writeFile(change.content as string, "utf8");
        await handle.sync();
      } finally {
        await handle.close();
      }
    }
    if (existing !== null) {
      await mkdir(dirname(join(backupRoot, change.path)), { recursive: true });
      await copyDurable(absolute, join(backupRoot, change.path));
    }
    files.push({
      path: change.path,
      action: change.action,
      oldHash: existing,
      newHash,
      published: false,
    });
  }

  // 2. Projected manifest (post-apply state) → new revision number + hash.
  const projected = await projectedManifest(layout.workDir, options.changes);
  const newRevision = head.revision + 1;
  const newHash = manifestHash(projected);
  const now = new Date().toISOString();
  const journal: JournalRecord = {
    format: "y1.hypit-workspace-journal@1",
    journalId,
    commandId: options.commandId,
    projectId: options.projectId,
    baseRevision: head.revision,
    oldHead: head,
    newHead: { revision: newRevision, manifestHash: newHash, updatedAt: now },
    files,
    state: "prepared",
    createdAt: now,
    updatedAt: now,
  };
  await writeJournal(journalPath, journal);

  // 3. Publish file by file; journal marks progress after each rename.
  try {
    for (let index = 0; index < journal.files.length; index += 1) {
      const file = journal.files[index];
      if (file === undefined) throw new Error("journal file entry missing");
      if (options.faults?.failBeforePublish?.(journalId, index) === true) {
        throw new Error(`injected failure before publishing ${file.path}`);
      }
      const target = join(layout.workDir, file.path);
      await mkdir(dirname(target), { recursive: true });
      if (file.action === "put") {
        await rename(join(stagingRoot, file.path), target);
      } else {
        await rm(target, { force: true });
      }
      file.published = true;
      journal.state = "publishing";
      journal.updatedAt = new Date().toISOString();
      await writeJournal(journalPath, journal);
    }
    // 4. All files in place → publish head, then commit the journal.
    if (options.faults?.failBeforeHeadWrite?.(journalId) === true) {
      throw new Error("injected failure before head write");
    }
    await publishHead(layout.projectRoot, journal.newHead);
    if (options.faults?.failAfterHeadWrite?.(journalId) === true) {
      throw new Error("injected failure after head write");
    }
    journal.state = "committed";
    journal.updatedAt = new Date().toISOString();
    await writeJournal(journalPath, journal);
  } catch (error) {
    if (options.faults?.crashInsteadOfRollback === true) {
      // Process-death simulation: leave the journal exactly as it is; the
      // next recovery (or the next writer's pre-apply recovery) converges.
      throw error;
    }
    // Best-effort immediate rollback; leftovers converge via recovery.
    await rollbackJournal(layout, journalPath, journal).catch(() => {});
    if (error instanceof WorkspaceConflictError) throw error;
    throw error;
  } finally {
    await rm(stagingRoot, { recursive: true, force: true }).catch(() => {});
    if (journal.state === "committed") {
      await rm(backupRoot, { recursive: true, force: true }).catch(() => {});
    }
  }
  return {
    journalId,
    revision: newRevision,
    manifestHash: newHash,
    manifest: projected,
    appliedPaths: journal.files.map((file) => file.path),
  };
}

async function projectedManifest(
  workDir: string,
  changes: readonly FileChange[],
): Promise<WorkspaceManifest> {
  const current = await computeWorkspaceManifest(workDir);
  const byPath = new Map(current.entries.map((entry) => [entry.path, { ...entry }]));
  for (const change of changes) {
    if (change.action === "delete") {
      byPath.delete(change.path);
      continue;
    }
    const size = Buffer.byteLength(change.content as string, "utf8");
    byPath.set(change.path, {
      path: change.path,
      sha256: await hashText(change.content as string),
      sizeBytes: size,
    });
  }
  const entries = [...byPath.values()].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { format: "y1.hypit-workspace-manifest@1", entries };
}

async function fileHashOrNull(absolute: string): Promise<string | null> {
  try {
    const info = await stat(absolute);
    if (!info.isFile()) return null;
    const { createHash } = await import("node:crypto");
    const bytes = await readFile(absolute);
    return createHash("sha256").update(bytes).digest("hex");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

// ---------------------------------------------------------------------------
// Read APIs shared with the dispatcher (04.8)
// ---------------------------------------------------------------------------

export async function readWorkspaceFile(
  projectRoot: string,
  projectId: string,
  relative: string,
): Promise<{ readonly path: string; readonly content: string; readonly hash: string; readonly revision: number }> {
  const layout = projectLayout(projectRoot, projectId);
  const absolute = await resolveWithinWorkspace(layout.workDir, relative);
  const content = await readFile(absolute, "utf8");
  const head = await readHead(projectRoot);
  return {
    path: relative,
    content,
    hash: await hashText(content),
    revision: head?.revision ?? 0,
  };
}
