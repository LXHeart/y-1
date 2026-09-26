// revisions.ts — C107-04 (task-107) immutable revision snapshots.
//
// A snapshot freezes the read-only byte closure of one revision under
// revisions/<n>/ (hardlink when the filesystem allows, durable copy otherwise).
// Later head writes publish NEW inodes via rename (transactions.ts), so a
// hardlinked snapshot keeps observing the old bytes — compiling revision A is
// unaffected by concurrent head edits (04.7 / K02 Snapshot 与 Results).
// The stable, project-owned Result repository lives outside revisions/ and is
// never forked per snapshot.
import { chmod, copyFile, link, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";

import { computeWorkspaceManifest, hashFile, manifestHash, readManifestFile, writeManifestFile } from "./manifest.ts";
import { readHead, type HeadState } from "./transactions.ts";

export type RevisionSnapshot = {
  readonly revision: number;
  readonly manifestHash: string;
  readonly snapshotDir: string;
};

export type SnapshotVerification = {
  readonly revision: number;
  readonly ok: boolean;
  readonly mismatches: readonly string[];
};

/**
 * Freeze the current work/ state as revisions/<number>. Verifies every copied
 * byte against the freshly computed manifest before returning, so a failing
 * disk never yields a "ready" snapshot.
 */
export async function snapshotRevision(
  projectRoot: string,
  expectedRevision: number,
): Promise<RevisionSnapshot> {
  const head = await readHead(projectRoot);
  if (head === null) throw new Error("cannot snapshot a workspace without a published head");
  if (head.revision !== expectedRevision) {
    throw new Error(`revision moved during snapshot: expected ${expectedRevision}, head is ${head.revision}`);
  }
  const workDir = join(projectRoot, "work");
  const manifest = await computeWorkspaceManifest(workDir);
  const actualHash = manifestHash(manifest);
  if (actualHash !== head.manifestHash) {
    throw new Error("workspace drifted from head manifest; refusing to snapshot");
  }
  const snapshotDir = join(projectRoot, "revisions", String(head.revision));
  // Idempotent: an existing, verifiable snapshot for this revision is returned
  // as-is (retries and crash recovery may re-enter this path).
  if (existsSync(join(snapshotDir, "revision.json"))) {
    const existing = await verifySnapshot(projectRoot, head.revision);
    if (existing.ok) {
      return { revision: head.revision, manifestHash: actualHash, snapshotDir };
    }
    await rm(snapshotDir, { recursive: true, force: true });
  }
  await mkdir(snapshotDir, { recursive: true });
  for (const entry of manifest.entries) {
    const source = join(workDir, entry.path);
    const target = join(snapshotDir, entry.path);
    await mkdir(dirname(target), { recursive: true });
    try {
      await link(source, target);
    } catch {
      // Cross-device or unsupported: durable copy is equally correct because
      // head writes always rename onto a fresh inode.
      await copyFile(source, target);
    }
    const copied = await hashFile(target);
    if (copied !== entry.sha256) {
      throw new Error(`snapshot byte mismatch for ${entry.path}`);
    }
    await chmod(target, 0o444);
  }
  await writeManifestFile(join(snapshotDir, "manifest.json"), manifest);
  await writeFile(
    join(snapshotDir, "revision.json"),
    `${JSON.stringify({ revision: head.revision, manifestHash: actualHash }, null, 2)}\n`,
    "utf8",
  );
  return { revision: head.revision, manifestHash: actualHash, snapshotDir };
}

/** Recompute every hash in a snapshot and compare against its manifest. */
export async function verifySnapshot(projectRoot: string, revision: number): Promise<SnapshotVerification> {
  const snapshotDir = join(projectRoot, "revisions", String(revision));
  const manifest = await readManifestFile(join(snapshotDir, "manifest.json"));
  const mismatches: string[] = [];
  for (const entry of manifest.entries) {
    const target = join(snapshotDir, entry.path);
    try {
      const info = await stat(target);
      if (info.size !== entry.sizeBytes || await hashFile(target) !== entry.sha256) {
        mismatches.push(entry.path);
      }
    } catch {
      mismatches.push(entry.path);
    }
  }
  return { revision, ok: mismatches.length === 0, mismatches };
}

/** Read back one file's bytes from a frozen revision (compile closure source). */
export async function readRevisionFile(
  projectRoot: string,
  revision: number,
  relative: string,
): Promise<string> {
  return await readFile(join(projectRoot, "revisions", String(revision), relative), "utf8");
}

/**
 * The project's stable Result repository location (K02: all revisions' Results
 * point here; never `.revisions/<n>/.hypit/results`). Idempotent.
 */
export async function ensureResultsRepository(
  projectRoot: string,
  projectId: string,
): Promise<string> {
  const resultsDir = join(projectRoot, "results");
  await mkdir(resultsDir, { recursive: true });
  const marker = join(resultsDir, "repository.json");
  try {
    const existing = JSON.parse(await readFile(marker, "utf8")) as { readonly projectId?: string };
    if (existing.projectId !== projectId) {
      throw new Error(`results repository marker belongs to ${String(existing.projectId)}`);
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      await writeFile(
        marker,
        `${JSON.stringify({ format: "y1.hypit-results@1", projectId }, null, 2)}\n`,
        { encoding: "utf8", flag: "wx" },
      );
    } else if (!(error instanceof Error) || !error.message.startsWith("results repository marker")) {
      throw error;
    }
  }
  return resultsDir;
}

export type HeadView = HeadState | null;

export async function currentHead(projectRoot: string): Promise<HeadView> {
  return await readHead(projectRoot);
}
