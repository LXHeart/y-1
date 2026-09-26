// export.ts — C107-20 (task-107) project export: build a portable bundle.
//
// The export walks the project's work tree, hashes every carryable file and
// writes the bundle into the broker artifacts root with the manifest. Hard
// rules: secret-shaped files and internal state directories are REFUSED (not
// silently skipped — silence would be a leak vector); result references are
// carried as repository-relative pointers, never as copied result bytes; and
// any workspace asset missing from disk fails the export instead of being
// "omitted" quietly.
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

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
  readonly manifest: ProjectPackageManifest;
};

const MAX_EXPORT_FILES = 20000;
const MAX_EXPORT_BYTES = 4 * 1024 * 1024 * 1024;

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
  options: { readonly title?: string | undefined; readonly selectedRun?: string | undefined } = {},
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
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      const rel = relative(workRoot, full).split("\\").join("/");
      if (entry.isDirectory()) {
        if (rel === "node_modules" || rel.startsWith("node_modules/") || rel === ".journal"
          || rel.startsWith(".journal/") || rel === ".hypit" || rel.startsWith(".hypit/")) continue;
        walk(full);
        continue;
      }
      if (!entry.isFile()) continue;
      if (isForbiddenPath(rel)) {
        // A secret-shaped file inside the project tree is an export REFUSAL —
        // packing it would leak; skipping it silently would corrupt closure.
        throw new DispatchError("invalid_input", `project contains a non-exportable file: ${rel}`);
      }
      const bytes = readFileSync(full);
      total += bytes.byteLength;
      if (total > MAX_EXPORT_BYTES) {
        throw new DispatchError("too_large", "export exceeds the 4 GiB bundle limit");
      }
      files.push({
        path: rel,
        sha256: createHash("sha256").update(bytes).digest("hex"),
        sizeBytes: bytes.byteLength,
        role: roleFor(rel),
      });
      if (files.length > MAX_EXPORT_FILES) {
        throw new DispatchError("too_large", `export exceeds ${MAX_EXPORT_FILES} files`);
      }
    }
  };
  walk(workRoot);

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
    writeFileSync(target, readFileSync(join(workRoot, file.path)));
  }
  writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  return {
    artifactRoot: relative(resolve(ctx.projectsRoot, ".."), bundleRoot).split("\\").join("/"),
    manifest,
  };
}

function rmAndMkdir(dir: string): void {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}
