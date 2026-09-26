// import.ts — C107-20 (task-107) project import: verify-then-create.
//
// A bundle is only imported after EVERY gate passes: manifest format/schema,
// per-file sha256 against the manifest, forbidden-path and count/size caps,
// and a parsed-and-checked Run entry — only then does the new project's
// workspace receive the files as a journaled change (the same CAS/journal
// guarantees as any edit). A rejected import leaves NO half-ready project.
import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor, provisionFromTemplate } from "../workspace/provision.ts";
import { applyWorkspaceChanges, readHead, type FileChange } from "../workspace/transactions.ts";
import {
  PROJECT_PACKAGE_FORMAT,
  isForbiddenPath,
  validateManifest,
  type ProjectPackageManifest,
} from "./manifest.ts";

export type ImportContext = {
  readonly projectsRoot: string;
  /** Where incoming bundles are staged (broker artifacts root). */
  readonly stagingRoot: string;
  /** Provisioning template shipped with the broker (minimal-local fixture). */
  readonly provisionTemplateDir: string;
  readonly provisionTemplateFiles: readonly string[];
};

const MAX_IMPORT_FILES = 20000;
const MAX_IMPORT_BYTES = 4 * 1024 * 1024 * 1024;

export type ImportReceipt = {
  readonly projectId: string;
  readonly revision: number;
  readonly manifestHash: string;
  readonly fileCount: number;
};

/**
 * Import a staged bundle directory (bundleRoot/hypit-project.json + files).
 * New project ids are broker-generated; the caller's requestId only keys the
 * idempotent command. Refusals never touch the new workspace.
 */
export async function importProjectPackage(
  ctx: ImportContext,
  bundleRootRaw: string,
  options: { readonly newProjectId: string; readonly requestId: string; readonly title?: string | undefined },
): Promise<ImportReceipt> {
  const stagingRoot = resolve(ctx.stagingRoot);
  const bundleRoot = resolve(stagingRoot, bundleRootRaw);
  if (!bundleRoot.startsWith(stagingRoot + "/") || !existsSync(bundleRoot)) {
    throw new DispatchError("not_found", "bundle must exist inside the import staging root");
  }
  const manifestFile = join(bundleRoot, "hypit-project.json");
  if (!existsSync(manifestFile)) {
    throw new DispatchError("invalid_input", "bundle has no hypit-project.json manifest");
  }
  let manifest: ProjectPackageManifest;
  try {
    manifest = validateManifest(JSON.parse(readFileSync(manifestFile, "utf8")));
  } catch (error) {
    throw new DispatchError("invalid_input", `manifest rejected: ${(error as Error).message}`);
  }
  if (manifest.format !== PROJECT_PACKAGE_FORMAT) {
    throw new DispatchError("invalid_input", "unsupported bundle format");
  }

  // Caps before any copy.
  if (manifest.files.length > MAX_IMPORT_FILES) {
    throw new DispatchError("too_large", `bundle exceeds ${MAX_IMPORT_FILES} files`);
  }
  let total = 0;
  for (const file of manifest.files) {
    if (isForbiddenPath(file.path)) {
      throw new DispatchError("invalid_input", `bundle manifest carries a forbidden path: ${file.path}`);
    }
    const source = join(bundleRoot, file.path);
    if (!existsSync(source)) {
      throw new DispatchError("invalid_input", `bundle missing file: ${file.path}`);
    }
    // Symlink indirection (inside the bundle pointing anywhere) is refused:
    // the bundle is a plain-file container, never a link farm.
    if (!lstatSync(source).isFile()) {
      throw new DispatchError("invalid_input", `bundle entry is not a regular file: ${file.path}`);
    }
    const bytes = readFileSync(source);
    if (bytes.byteLength !== file.sizeBytes) {
      throw new DispatchError("invalid_input", `size mismatch for ${file.path}`);
    }
    const digest = createHash("sha256").update(bytes).digest("hex");
    if (digest !== file.sha256) {
      throw new DispatchError("invalid_input", `hash mismatch for ${file.path}`);
    }
    total += bytes.byteLength;
    if (total > MAX_IMPORT_BYTES) {
      throw new DispatchError("too_large", "bundle exceeds the import size cap");
    }
  }
  // The Run selected by the manifest must exist inside the bundle.
  if (!manifest.files.some((file) => file.path === manifest.project.selectedRun)) {
    throw new DispatchError("invalid_input", `selected run missing: ${manifest.project.selectedRun}`);
  }

  // Gates passed: provision the new project and land the files as ONE change.
  const newId = options.newProjectId;
  await provisionFromTemplate(ctx.projectsRoot, newId, ctx.provisionTemplateDir, [...ctx.provisionTemplateFiles]);
  const projectRoot = projectRootFor(ctx.projectsRoot, newId);
  const head = await readHead(projectRoot);
  if (head === null) {
    throw new DispatchError("engine_unavailable", "import provisioning produced no head");
  }
  const changes: FileChange[] = manifest.files.map((file) => ({
    path: file.path,
    action: "put" as const,
    content: readFileSync(join(bundleRoot, file.path), "utf8"),
  }));
  try {
    const applied = await applyWorkspaceChanges({
      projectId: newId,
      projectRoot,
      commandId: options.requestId,
      baseRevision: head.revision,
      changes,
    });
    return { projectId: newId, revision: applied.revision, manifestHash: applied.manifestHash, fileCount: changes.length };
  } catch (error) {
    // A failed landing leaves the freshly provisioned shell but NO ready
    // project pretending to contain the import; the caller sees the error.
    throw error instanceof DispatchError ? error : new DispatchError("engine_error", String((error as Error).message));
  }
}

