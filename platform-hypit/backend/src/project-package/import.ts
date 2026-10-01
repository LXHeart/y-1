// import.ts — C107-20 (task-107) project import: verify-then-create.
// C107F2-28 (W176): binary-fidelity rewrite.
//
// A bundle is only imported after EVERY gate passes: manifest format/schema
// (shared caps from the manifest itself — 413 before any disk touch), per-file
// streaming sha256/size against the manifest, hostile-entry refusal (symlink,
// hardlink, traversal, secret paths), and a parsed-and-checked Run via the
// isolated runner — only then do files land as ONE journaled change where
// TEXT edits keep their 2MiB/16MiB budgets and BINARY entries stream through
// a bounded buffer (peak memory does not grow with bundle size). After
// landing, every byte is re-hashed on disk (二次核对) before the receipt.
// A rejected import leaves no ready project and no new head.
import { existsSync, lstatSync, readFileSync, rmSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

import { streamHash } from "./binary-staging.ts";
import { extractBundleZip } from "./transfer.ts";
import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor, provisionFromTemplate } from "../workspace/provision.ts";
import { applyWorkspaceChanges, readHead, type FileChange } from "../workspace/transactions.ts";
import { computeWorkspaceManifest } from "../workspace/manifest.ts";
import { snapshotRevision } from "../workspace/revisions.ts";
import {
  MAX_BUNDLE_FILE_BYTES,
  MAX_BUNDLE_FILES,
  MAX_BUNDLE_TOTAL_BYTES,
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
  /**
   * C107F2-28: isolated-runner executor for the import parse/check gate.
   * Broker dispatch always provides it (fail-closed at the dispatcher);
   * direct legacy callers without one skip the engine gate.
   */
  readonly engineExecutor?: ((kind: string, payload: unknown) => Promise<unknown>) | undefined;
};

export type ImportReceipt = {
  readonly projectId: string;
  readonly revision: number;
  readonly manifestHash: string;
  readonly fileCount: number;
};

/**
 * Import a staged bundle directory (bundleRoot/hypit-project.json + files).
 * New project ids are broker-generated; the caller's requestId only keys the
 * idempotent command. Refusals never touch the new workspace; a failure
 * AFTER provisioning removes the freshly provisioned shell — only the
 * (cleanable) staging tree survives, never a ready head.
 */
export async function importProjectPackage(
  ctx: ImportContext,
  bundleRootRaw: string,
  options: {
    readonly newProjectId: string;
    readonly requestId: string;
    readonly title?: string | undefined;
    /** C107F2-30: staged browser upload — extract the zip, then run the same gates. */
    readonly transferId?: string | undefined;
  },
): Promise<ImportReceipt> {
  const stagingRoot = resolve(ctx.stagingRoot);
  let bundleRoot: string;
  if (options.transferId !== undefined) {
    // 浏览器上传链：受控 transfer staging 解包（hostile-input 门禁在 extractor 内），
    // 之后的 manifest/hash/落地门禁与 artifactRoot 链完全同一条路。
    bundleRoot = (await extractBundleZip(stagingRoot, options.transferId)).dir;
  } else {
    bundleRoot = resolve(stagingRoot, bundleRootRaw);
  }
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

  // Caps from the MANIFEST first (413 before any byte moves). Single-file and
  // total caps mirror the export side; the count cap bounds the walk.
  if (manifest.files.length > MAX_BUNDLE_FILES) {
    throw new DispatchError("too_large", `bundle exceeds ${MAX_BUNDLE_FILES} files`);
  }
  let declaredTotal = 0;
  for (const file of manifest.files) {
    if (file.sizeBytes > MAX_BUNDLE_FILE_BYTES) {
      throw new DispatchError("too_large", `bundle entry exceeds the single-file cap: ${file.path}`);
    }
    declaredTotal += file.sizeBytes;
  }
  if (declaredTotal > MAX_BUNDLE_TOTAL_BYTES) {
    throw new DispatchError("too_large", "bundle exceeds the 4 GiB import cap");
  }

  // Per-entry hostile-shape gates: forbidden paths (traversal / secrets),
  // regular-file only (symlinks refused), hardlinks refused, and a STREAMING
  // size+hash verification against the manifest (no full-file reads).
  for (const file of manifest.files) {
    if (isForbiddenPath(file.path)) {
      throw new DispatchError("invalid_input", `bundle manifest carries a forbidden path: ${file.path}`);
    }
    const source = join(bundleRoot, file.path);
    if (!existsSync(source)) {
      throw new DispatchError("invalid_input", `bundle missing file: ${file.path}`);
    }
    const info = lstatSync(source);
    if (!info.isFile()) {
      // Symlink indirection (inside the bundle pointing anywhere) is refused:
      // the bundle is a plain-file container, never a link farm.
      throw new DispatchError("invalid_input", `bundle entry is not a regular file: ${file.path}`);
    }
    if (statSync(source).nlink > 1) {
      throw new DispatchError("invalid_input", `bundle entry is a hardlink: ${file.path}`);
    }
    const actual = await streamHash(source);
    if (actual.sizeBytes !== file.sizeBytes) {
      throw new DispatchError("invalid_input", `size mismatch for ${file.path}`);
    }
    if (actual.sha256 !== file.sha256) {
      throw new DispatchError("invalid_input", `hash mismatch for ${file.path}`);
    }
  }
  // The Run selected by the manifest must exist inside the bundle.
  if (!manifest.files.some((file) => file.path === manifest.project.selectedRun)) {
    throw new DispatchError("invalid_input", `selected run missing: ${manifest.project.selectedRun}`);
  }

  // Gates passed: provision the new project and land the files as ONE change.
  // Text entries ride the normal path; binary entries stream via contentPath.
  const newId = options.newProjectId;
  await provisionFromTemplate(ctx.projectsRoot, newId, ctx.provisionTemplateDir, [...ctx.provisionTemplateFiles]);
  const projectRoot = projectRootFor(ctx.projectsRoot, newId);
  const failImport = (error: unknown): never => {
    // No ready project pretending to contain the import: remove the fresh
    // shell; the journaled apply already rolled its own head back.
    rmSync(projectRoot, { recursive: true, force: true });
    throw error instanceof DispatchError ? error : new DispatchError("engine_error", String((error as Error).message));
  };
  try {
    const head = await readHead(projectRoot);
    if (head === null) {
      throw failImport(new DispatchError("engine_unavailable", "import provisioning produced no head"));
    }
    const changes: FileChange[] = manifest.files.map((file) => ({
      path: file.path,
      action: "put" as const,
      contentPath: join(bundleRoot, file.path),
    }));
    const applied = await applyWorkspaceChanges({
      projectId: newId,
      projectRoot,
      commandId: options.requestId,
      baseRevision: head.revision,
      changes,
    });

    // 落盘二次核对：landed bytes must match the manifest hashes exactly.
    const landed = await computeWorkspaceManifest(join(projectRoot, "work"));
    const landedByPath = new Map(landed.entries.map((entry) => [entry.path, entry]));
    for (const file of manifest.files) {
      const entry = landedByPath.get(file.path);
      if (entry === undefined || entry.sha256 !== file.sha256 || entry.sizeBytes !== file.sizeBytes) {
        failImport(new DispatchError("engine_error", `post-landing hash mismatch for ${file.path}`));
      }
    }
    // 成功原子发布完整 revision：冻结不可变快照（逐字节验证后的 revisions/<n>），
    // 后续读侧（preview/再导出）只消费冻结事实。
    try {
      await snapshotRevision(projectRoot, applied.revision);
    } catch (error) {
      failImport(new DispatchError("engine_error", `import snapshot failed: ${(error as Error).message}`));
    }

    // 原生 runner parse/check the selected Run against the LANDED workspace.
    if (ctx.engineExecutor !== undefined) {
      const check = await ctx.engineExecutor("check", {
        sourceDir: join(projectRoot, "work"),
        entryFile: manifest.project.selectedRun,
      }) as { ok?: boolean; diagnostics?: readonly { severity?: string }[] };
      const failed = check.ok !== true
        || (check.diagnostics ?? []).some((diagnostic) => diagnostic.severity === "error");
      if (failed) {
        failImport(new DispatchError("compile_failed",
          `imported run failed check: ${manifest.project.selectedRun}`));
      }
    }
    return { projectId: newId, revision: applied.revision, manifestHash: applied.manifestHash, fileCount: changes.length };
  } catch (error) {
    throw failImport(error);
  }
}
