// export.ts — C107-20 (task-107) project export: build a portable bundle.
//
// The export walks the project's work tree, hashes every carryable file and
// writes the bundle into the broker artifacts root with the manifest. Hard
// rules: secret-shaped files and internal state directories are REFUSED (not
// silently skipped — silence would be a leak vector); result references are
// carried as repository-relative pointers, never as copied result bytes; and
// any workspace asset missing from disk fails the export instead of being
// "omitted" quietly.
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

import { streamCopy, streamHash } from "./binary-staging.ts";
import { isTransferId, packBundleZip, recordTransferMeta } from "./transfer.ts";
import { MAX_BUNDLE_FILES, MAX_BUNDLE_TOTAL_BYTES } from "./manifest.ts";
import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { readHead } from "../workspace/transactions.ts";
import {
  PROJECT_PACKAGE_FORMAT,
  isForbiddenPath,
  type ManifestFile,
  type ManifestPackage,
  type ManifestResult,
  type ProjectPackageManifest,
} from "./manifest.ts";

export type ExportContext = {
  readonly projectsRoot: string;
  readonly distributionRoot: string;
  readonly sourceCommit: string;
};

export type ExportReceipt = {
  readonly artifactRoot: string;
  /** C107F-01 (D-02): carried file count = manifest.files.length; the Java orchestration reads it. */
  readonly fileCount: number;
  readonly manifest: ProjectPackageManifest;
  /** C107F2-30: present when exportId was provided (browser download packing). */
  readonly zipSha256?: string;
  readonly zipSizeBytes?: number;
  readonly zipName?: string;
};

function roleFor(relativePath: string): ManifestFile["role"] {
  if (relativePath.endsWith(".svrun")) return "run";
  if (relativePath.endsWith(".svs")) return "recipe";
  if (relativePath.startsWith("packages/")) return "package";
  if (relativePath === "hypit.runtime.json") return "document";
  if (relativePath.endsWith(".md") || relativePath.endsWith(".json")) return "document";
  if (/\.(png|jpg|jpeg|webp|gif|mp4|mov|webm|wav|mp3|m4a|woff2?|ttf|otf)$/iu.test(relativePath)) return "asset";
  return "source";
}

export async function exportProjectPackage(
  ctx: ExportContext,
  projectId: string,
  options: {
    readonly title?: string | undefined;
    readonly selectedRun?: string | undefined;
    /** C107F2-30: Java command uuid — when present the bundle is also packed as a
     * browser-downloadable zip under the transfer root keyed by this id. */
    readonly exportId?: string | undefined;
  } = {},
): Promise<ExportReceipt> {
  const projectRoot = projectRootFor(ctx.projectsRoot, projectId);
  const workRoot = join(projectRoot, "work");
  if (!existsSync(workRoot)) {
    throw new DispatchError("not_found", "project workspace does not exist");
  }
  const head = await readHead(projectRoot);
  if (head === null) {
    throw new DispatchError("not_provisioned", "workspace has no published head");
  }
  const selectedRun = options.selectedRun ?? "main.svrun";
  const files: ManifestFile[] = [];
  const packages: ManifestPackage[] = [];
  const results: ManifestResult[] = [];
  let total = 0;
  const walk = async (dir: string): Promise<void> => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      const rel = relative(workRoot, full).split("\\").join("/");
      if (entry.isDirectory()) {
        if (rel === "node_modules" || rel.startsWith("node_modules/") || rel === ".journal"
          || rel.startsWith(".journal/") || rel === ".hypit" || rel.startsWith(".hypit/")) continue;
        await walk(full);
        continue;
      }
      if (!entry.isFile()) continue;
      if (isForbiddenPath(rel)) {
        // A secret-shaped file inside the project tree is an export REFUSAL —
        // packing it would leak; skipping it silently would corrupt closure.
        throw new DispatchError("invalid_input", `project contains a non-exportable file: ${rel}`);
      }
      // C107F2-28: hash while streaming — byte count and digest come from the
      // same bounded-buffer pass (no full-file reads for multi-GiB assets).
      const digest = await streamHash(full);
      total += digest.sizeBytes;
      if (total > MAX_BUNDLE_TOTAL_BYTES) {
        throw new DispatchError("too_large", "export exceeds the 4 GiB bundle limit");
      }
      files.push({
        path: rel,
        sha256: digest.sha256,
        sizeBytes: digest.sizeBytes,
        role: roleFor(rel),
      });
      if (files.length > MAX_BUNDLE_FILES) {
        throw new DispatchError("too_large", `export exceeds ${MAX_BUNDLE_FILES} files`);
      }
    }
  };
  await walk(workRoot);

  // C107F2-28: the export freezes a revision — if the head moved while the
  // tree was being walked the bundle would be torn; refuse instead.
  const headAfter = await readHead(projectRoot);
  if (headAfter === null || headAfter.revision !== head.revision
    || headAfter.manifestHash !== head.manifestHash) {
    throw new DispatchError("conflict", "workspace changed during export; retry on a quiescent head");
  }

  // The selected Run must be part of the frozen file set — an export without
  // its entry Run would import into a project that cannot render.
  if (!files.some((file) => file.path === selectedRun)) {
    throw new DispatchError("invalid_input", `selected run missing from workspace: ${selectedRun}`);
  }

  // Component packages ride along with their declared identity (provenance).
  const packagesDir = join(workRoot, "packages");
  if (existsSync(packagesDir)) {
    for (const entry of readdirSync(packagesDir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const manifestFile = join(packagesDir, entry.name, "package.json");
      if (!existsSync(manifestFile)) continue;
      const manifest = JSON.parse(readFileSync(manifestFile, "utf8")) as { name?: string; version?: string };
      packages.push({
        name: String(manifest.name ?? entry.name),
        version: String(manifest.version ?? "0.0.0"),
        source: "project",
      });
    }
  }

  // Referenced Results: carried as repository-relative pointers from the
  // project's own results repository (bytes stay out of the bundle).
  const resultsDir = join(projectRoot, "results");
  if (existsSync(resultsDir)) {
    for (const entry of readdirSync(resultsDir, { withFileTypes: true })) {
      if (entry.isDirectory() && /^[0-9a-f-]{36}$/u.test(entry.name)) {
        results.push({ engineBuildId: entry.name, repositoryRelativePath: `results/${entry.name}` });
      }
      if (entry.isFile() && entry.name.endsWith(".svrun")) continue;
    }
  }

  const manifest: ProjectPackageManifest = {
    format: PROJECT_PACKAGE_FORMAT,
    sourceCommit: ctx.sourceCommit,
    project: { title: options.title ?? "Exported project", revision: head.revision, selectedRun },
    files,
    packages,
    results,
    omitted: [],
  };

  // Write the bundle into the broker artifacts root (sibling of projects).
  const artifactsRoot = resolve(ctx.projectsRoot, "../project-exports");
  const bundleRoot = join(artifactsRoot, projectId, `rev-${head.revision}`);
  rmAndMkdir(bundleRoot);
  for (const file of files) {
    const target = join(bundleRoot, file.path);
    mkdirSync(resolve(target, ".."), { recursive: true });
    await streamCopy(join(workRoot, file.path), target);
  }
  writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  // C107F2-30: pack the verified bundle into a real zip (streamed, store-only)
  // under the transfer key Java will serve from — never expose artifactRoot.
  if (options.exportId !== undefined) {
    if (!isTransferId(options.exportId)) {
      throw new DispatchError("invalid_input", "exportId must be a uuid");
    }
    // C107F2-37：zip 与 meta.json 必须落在统一 staging 布局
    // <stagingRoot>/package-transfers/<id>/（recordTransferMeta 的入参是
    // stagingRoot，内部再拼 package-transfers——此前传 transferRoot 会双重
    // 嵌套，且 packBundleZip 回导的 transferId 是文件名而非目录键）。
    const stagingRoot = resolve(ctx.projectsRoot, "..");
    // C107F2-37（缺陷 I）：zip 必须随包附上 hypit-project.json 本体——导入端
    // extractBundleZip 解包后以该文件为 manifest 门禁入口；只打 manifest.files
    // 的下载 zip 缺它，任何再导入一律「bundle has no hypit-project.json
    // manifest」。manifest 不能进自身的 files 清单（自引用哈希），作为附加条目打包。
    const manifestBytes = readFileSync(join(bundleRoot, "hypit-project.json"));
    const stored = await packBundleZip(bundleRoot,
      [...files, { path: "hypit-project.json", sizeBytes: manifestBytes.byteLength }],
      join(stagingRoot, "package-transfers", options.exportId, "package.zip"));
    await recordTransferMeta(stagingRoot, stored);
    return {
      artifactRoot: relative(resolve(ctx.projectsRoot, ".."), bundleRoot).split("\\").join("/"),
      fileCount: files.length,
      manifest,
      zipSha256: stored.sha256,
      zipSizeBytes: stored.sizeBytes,
      zipName: `hypit-project-${options.exportId.slice(0, 8)}.zip`,
    };
  }
  return {
    artifactRoot: relative(resolve(ctx.projectsRoot, ".."), bundleRoot).split("\\").join("/"),
    fileCount: files.length,
    manifest,
  };
}

function rmAndMkdir(dir: string): void {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}
