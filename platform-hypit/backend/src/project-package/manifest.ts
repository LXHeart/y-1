// manifest.ts — C107-20 (task-107) project-package manifest schema (NEW per card).
//
// The export manifest is the single description of a portable project bundle:
// format, provenance commit, selected revision/run, every carried file with a
// sha256, component-package provenance, referenced Results and — critically —
// an explicit `omitted` list. Omitting a resource that is REQUIRED for the
// project to stay editable/reusable fails the export (card step: "缺必要资源
// 直接导出失败"), so a manifest never lies about completeness.
export const PROJECT_PACKAGE_FORMAT = "y1.hypit-project@1";

export type ManifestFile = {
  readonly path: string;
  readonly sha256: string;
  readonly sizeBytes: number;
  readonly role: "source" | "run" | "recipe" | "package" | "asset" | "result-ref" | "document";
};

export type ManifestPackage = { readonly name: string; readonly version: string; readonly source: "project" | "distribution" };

export type ManifestResult = { readonly engineBuildId: string; readonly repositoryRelativePath: string };

export type ManifestOmission = { readonly kind: string; readonly reason: string };

export type ProjectPackageManifest = {
  readonly format: typeof PROJECT_PACKAGE_FORMAT;
  readonly sourceCommit: string;
  readonly project: { readonly title: string; readonly revision: number; readonly selectedRun: string };
  readonly files: readonly ManifestFile[];
  readonly packages: readonly ManifestPackage[];
  readonly results: readonly ManifestResult[];
  readonly omitted: readonly ManifestOmission[];
};

/** Path segments never allowed inside a bundle (secrets / internal state). */
export function isForbiddenPath(path: string): boolean {
  return path.includes("..") || path.startsWith("/") || path.includes("\\")
    || /(^|\/)(node_modules|\.journal|\.hypit|\.git)(\/|$)/u.test(path)
    || /(^|\/)(credentials?|secrets?)(\.|$)/iu.test(path)
    || /\.pem$|\.key$|\.env($|\.)/iu.test(path);
}

export function validateManifest(value: unknown): ProjectPackageManifest {
  const record = (value ?? {}) as Record<string, unknown>;
  if (record.format !== PROJECT_PACKAGE_FORMAT) {
    throw new Error(`manifest format must be ${PROJECT_PACKAGE_FORMAT}`);
  }
  if (typeof record.sourceCommit !== "string" || record.sourceCommit.length !== 40) {
    throw new Error("manifest sourceCommit must be the 40-char pinned commit");
  }
  const project = record.project as ProjectPackageManifest["project"] | undefined;
  if (project === undefined || typeof project.title !== "string" || typeof project.selectedRun !== "string"
    || typeof project.revision !== "number" || !Number.isSafeInteger(project.revision) || project.revision < 0) {
    throw new Error("manifest.project needs title, revision and selectedRun");
  }
  if (!Array.isArray(record.files) || record.files.length === 0) {
    throw new Error("manifest.files must be a non-empty array");
  }
  const files: ManifestFile[] = [];
  const seen = new Set<string>();
  for (const entry of record.files as Record<string, unknown>[]) {
    if (typeof entry.path !== "string" || typeof entry.sha256 !== "string" || entry.sha256.length !== 64
      || typeof entry.sizeBytes !== "number" || !Number.isSafeInteger(entry.sizeBytes) || entry.sizeBytes < 0
      || typeof entry.role !== "string") {
      throw new Error(`manifest file entry incomplete: ${JSON.stringify(entry).slice(0, 120)}`);
    }
    if (isForbiddenPath(entry.path)) {
      throw new Error(`manifest carries a forbidden path: ${entry.path}`);
    }
    if (seen.has(entry.path)) throw new Error(`duplicate manifest path: ${entry.path}`);
    seen.add(entry.path);
    files.push({ path: entry.path, sha256: entry.sha256, sizeBytes: entry.sizeBytes, role: entry.role as ManifestFile["role"] });
  }
  const packages = Array.isArray(record.packages) ? record.packages as ManifestPackage[] : [];
  const results = Array.isArray(record.results) ? record.results as ManifestResult[] : [];
  const omitted = Array.isArray(record.omitted) ? record.omitted as ManifestOmission[] : [];
  return {
    format: PROJECT_PACKAGE_FORMAT,
    sourceCommit: record.sourceCommit,
    project,
    files,
    packages,
    results,
    omitted,
  };
}
