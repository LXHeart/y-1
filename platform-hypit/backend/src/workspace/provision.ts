// provision.ts — C107-04 (task-107) project workspace provisioning (§5.3).
//
// Creates projects/<id>/{work,revisions,results,assets} plus the durable
// package boundary marker, OUTSIDE the engine distribution source. Idempotent
// per projectId: a retried provisioning command finds the same workspace and
// converges instead of creating a second project (04.2 / 事务失败恢复).
// Template provisioning copies the minimal fixture, verifies its bytes, and
// only then publishes manifest + revision 1 — a failed copy leaves NO head,
// so PG honestly stays provisioning_failed and the retry is safe.
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { createHash } from "node:crypto";

import { computeWorkspaceManifest, hashFile, manifestHash, writeManifestFile } from "./manifest.ts";
import { ensureResultsRepository, snapshotRevision } from "./revisions.ts";
import { publishInitialHead, readHead, type HeadState } from "./transactions.ts";

export const PROJECT_BOUNDARY_FORMAT = "y1.hypit-project@1";

export type ProvisionReceipt = {
  readonly projectId: string;
  readonly projectRoot: string;
  readonly state: "created" | "existing";
  readonly head: HeadState | null;
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function assertProjectId(projectId: string): void {
  if (!UUID_PATTERN.test(projectId)) {
    throw new Error(`projectId must be a lowercase UUID: ${projectId}`);
  }
}

export function projectRootFor(projectsRoot: string, projectId: string): string {
  assertProjectId(projectId);
  return join(projectsRoot, projectId);
}

/**
 * Idempotent directory provisioning. Never deletes existing state; a half
 * provisioned tree converges on retry (missing dirs are created again, the
 * boundary marker is validated rather than overwritten).
 */
export async function provisionWorkspace(
  projectsRoot: string,
  projectId: string,
): Promise<ProvisionReceipt> {
  const projectRoot = projectRootFor(projectsRoot, projectId);
  const boundaryPath = join(projectRoot, "project.json");
  let state: "created" | "existing" = "existing";
  try {
    const existing = JSON.parse(await readFile(boundaryPath, "utf8")) as {
      readonly format?: string;
      readonly projectId?: string;
    };
    if (existing.format !== PROJECT_BOUNDARY_FORMAT || existing.projectId !== projectId) {
      throw new Error(`project boundary at ${projectRoot} belongs to ${String(existing.projectId)}`);
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") {
      throw error instanceof Error && error.message.startsWith("project boundary")
        ? error
        : new Error(`unreadable project boundary at ${projectRoot}: ${String(error)}`);
    }
    state = "created";
  }
  for (const dir of ["work", "revisions", "assets", ".journal"]) {
    await mkdir(join(projectRoot, dir), { recursive: true });
  }
  await ensureResultsRepository(projectRoot, projectId);
  if (state === "created") {
    await writeFile(
      boundaryPath,
      `${JSON.stringify({ format: PROJECT_BOUNDARY_FORMAT, projectId, createdAt: new Date().toISOString() }, null, 2)}\n`,
      { encoding: "utf8", flag: "wx" },
    ).catch((error: NodeJS.ErrnoException) => {
      // Concurrent double-provision: the winner wrote the marker first.
      if (error.code !== "EEXIST") throw error;
    });
  }
  return { projectId, projectRoot, state, head: await readHead(projectRoot) };
}

/**
 * Provision + seed from a template directory (minimal-local fixture). The
 * initial manifest and revision 1 are published ONLY after every template
 * byte is verified, so a torn copy leaves no ready state (04.4: PG 不假装已
 * ready；失败 provisioning_failed 可重试).
 */
export async function provisionFromTemplate(
  projectsRoot: string,
  projectId: string,
  templateDir: string,
  templateFiles: readonly string[],
): Promise<ProvisionReceipt> {
  const receipt = await provisionWorkspace(projectsRoot, projectId);
  const head = await readHead(receipt.projectRoot);
  if (head !== null) {
    // Already converged (idempotent retry of the SAME provisioning command).
    return { ...receipt, state: "existing", head };
  }
  const workDir = join(receipt.projectRoot, "work");
  for (const name of templateFiles) {
    const source = join(templateDir, name);
    const target = join(workDir, name);
    const bytes = await readFile(source);
    // Durable staged write then verify: a torn template copy must NOT publish.
    await writeFile(target, bytes);
    const written = await hashFile(target);
    if (written !== createHash("sha256").update(bytes).digest("hex")) {
      throw new Error(`template copy verification failed for ${name}`);
    }
  }
  const manifest = await computeWorkspaceManifest(workDir);
  if (manifest.entries.length !== templateFiles.length) {
    throw new Error("template workspace has unexpected extra files");
  }
  await writeManifestFile(join(receipt.projectRoot, "revisions", "initial-manifest.json"), manifest);
  await publishInitialHead(receipt.projectRoot, 1, manifestHash(manifest));
  const snapshot = await snapshotRevision(receipt.projectRoot, 1);
  if (snapshot.revision !== 1) throw new Error("initial snapshot revision mismatch");
  return { ...receipt, state: receipt.state, head: await readHead(receipt.projectRoot) };
}

/**
 * Remove the project tree. The Java side refuses while active work exists
 * (04.8); the sidecar only performs the physical cleanup.
 */
export async function removeWorkspace(projectsRoot: string, projectId: string): Promise<void> {
  const projectRoot = projectRootFor(projectsRoot, projectId);
  await rm(projectRoot, { recursive: true, force: true });
}
