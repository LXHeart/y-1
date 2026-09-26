// manifest.ts — C107-04 (task-107) workspace manifest computation.
//
// The head manifest is the verifiable truth about author workspace bytes
// (K05: hypit_project.head_manifest_hash / hypit_revision.manifest_hash).
// Entries hash file CONTENT; canonical serialization is sorted-by-path JSON
// with fixed key order so the hash is stable across processes and platforms.
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { opendir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { isShieldedFromListing } from "./paths.ts";

export type ManifestEntry = {
  readonly path: string;
  readonly sha256: string;
  readonly sizeBytes: number;
};

export type WorkspaceManifest = {
  readonly format: "y1.hypit-workspace-manifest@1";
  readonly entries: readonly ManifestEntry[];
};

const MANIFEST_FORMAT = "y1.hypit-workspace-manifest@1";

/** Directory names never included in the author manifest (runtime/deps state). */
const MANIFEST_EXCLUDED_DIRS = new Set(["node_modules", ".hypit", ".journal", ".git"]);

export async function hashFile(path: string): Promise<string> {
  const hash = createHash("sha256");
  await new Promise<void>((resolvePromise, reject) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", () => resolvePromise());
  });
  return hash.digest("hex");
}

export async function hashText(text: string): Promise<string> {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

/** Walk the workspace and hash every non-excluded file. Sorted by path. */
export async function computeWorkspaceManifest(workDir: string): Promise<WorkspaceManifest> {
  const entries: ManifestEntry[] = [];
  await walk(workDir, "", entries);
  entries.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { format: MANIFEST_FORMAT, entries };
}

async function walk(root: string, prefix: string, entries: ManifestEntry[]): Promise<void> {
  let directory;
  try {
    directory = await opendir(join(root, prefix));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }
  for await (const item of directory) {
    const relative = prefix.length === 0 ? item.name : `${prefix}/${item.name}`;
    if (item.isDirectory()) {
      if (MANIFEST_EXCLUDED_DIRS.has(item.name)) continue;
      await walk(root, relative, entries);
      continue;
    }
    if (!item.isFile()) continue;
    const absolute = join(root, relative);
    const { stat } = await import("node:fs/promises");
    const info = await stat(absolute);
    entries.push({ path: relative, sha256: await hashFile(absolute), sizeBytes: info.size });
  }
}

/** Canonical manifest hash: stable JSON over sorted entries (no timestamps). */
export function manifestHash(manifest: WorkspaceManifest): string {
  const canonical = JSON.stringify({
    format: manifest.format,
    entries: manifest.entries.map((entry) => [entry.path, entry.sha256, entry.sizeBytes]),
  });
  return createHash("sha256").update(canonical, "utf8").digest("hex");
}

export async function readManifestFile(path: string): Promise<WorkspaceManifest> {
  const parsed = JSON.parse(await readFile(path, "utf8")) as WorkspaceManifest;
  if (parsed.format !== MANIFEST_FORMAT) {
    throw new Error(`unknown manifest format: ${String(parsed.format)}`);
  }
  return parsed;
}

export async function writeManifestFile(path: string, manifest: WorkspaceManifest): Promise<void> {
  await writeFile(path, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

/** Listing payload shape for GET P/files — shielded entries never appear. */
export type FileListingEntry = {
  readonly path: string;
  readonly sha256: string;
  readonly sizeBytes: number;
};

export function listingEntries(manifest: WorkspaceManifest): readonly FileListingEntry[] {
  return manifest.entries
    .filter((entry) => !entry.path.split("/").some(isShieldedFromListing))
    .map((entry) => ({ path: entry.path, sha256: entry.sha256, sizeBytes: entry.sizeBytes }));
}
