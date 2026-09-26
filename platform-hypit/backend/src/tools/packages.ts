// packages.ts — C107-17 (task-107) controlled project-package tool domain.
//
// Component/Provider/Companion packages live in a project workspace under
// `packages/`. This module owns the four broker command kinds:
//
//   packages.status   — read-only listing of installed project packages.
//   packages.build    — compile a STAGED COPY with the FIXED engine toolchain
//                       (exact argv, generated tsconfig, distribution
//                       node_modules, no network, no npm install). Diagnostics
//                       only; the workspace is never mutated by a build.
//   packages.pack     — bundle a package (built dist included, no
//                       node_modules, no secret-shaped files) into the broker
//                       artifacts root with a sha256 manifest.
//   packages.install  — verify + copy a packed bundle into another project's
//                       workspace as a journaled workspace change (new head
//                       revision, same CAS/journal guarantees as any edit).
//
// K10.4 boundaries: the build invocation is exact argv on a staged copy —
// author code never chooses the command; credentials never enter any payload;
// dependency resolution stays pinned to the distribution's own node_modules
// (`@hypit/*` names from a project package are a hard refuse: project scope
// must not shadow the distribution namespace, step 17.4).
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { relative, resolve, join } from "node:path";

import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { resolveWithinWorkspace } from "../workspace/paths.ts";
import { applyWorkspaceChanges, type FileChange } from "../workspace/transactions.ts";

export type PackageToolContext = {
  readonly projectsRoot: string;
  /** G root: source of the fixed tsc toolchain and the @hypit type mapping. */
  readonly distributionRoot: string;
};

const PACKAGE_KINDS = new Set(["packages.status", "packages.build", "packages.pack", "packages.install"]);

export function isPackageTool(kind: string): boolean {
  return PACKAGE_KINDS.has(kind);
}

/** Caps aligned with the platform change-set limits (§9.4: 2 MiB file / 16 MiB set). */
const MAX_PACKAGE_FILES = 200;
const MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_PACKAGE_BYTES = 16 * 1024 * 1024;
const WORKSPACE_PACKAGES_DIR = "packages";

/** Broker artifacts root: sibling of projects, holds packed bundles. */
export function packageArtifactsRoot(projectsRoot: string): string {
  return resolve(projectsRoot, "../package-artifacts");
}

/** Directory name a package occupies inside a workspace `packages/` root. */
export function workspacePackageDirName(name: string): string {
  return name.replace("@", "").replace("/", "-");
}

type PackageJson = {
  readonly name?: unknown;
  readonly version?: unknown;
  readonly hypit?: unknown;
};

function readPackageJson(dir: string): PackageJson {
  const file = join(dir, "package.json");
  if (!existsSync(file)) {
    throw new DispatchError("invalid_input", `package dir has no package.json: ${dir}`);
  }
  return JSON.parse(readFileSync(file, "utf8")) as PackageJson;
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new DispatchError("invalid_input", `package.json ${field} must be a non-empty string`);
  }
  return value.trim();
}

/** Project-scope names only: shadowing the distribution namespace is refused. */
function requireProjectScopeName(name: string): string {
  if (name.startsWith("@hypit/") || name === "hypit") {
    throw new DispatchError("invalid_input", `package name "${name}" may not shadow the @hypit namespace`);
  }
  if (!/^@[a-z0-9][a-z0-9-]*\/[a-z0-9][a-z0-9-]*$/u.test(name) && !/^[a-z0-9][a-z0-9-]*$/u.test(name)) {
    throw new DispatchError("invalid_input", `package name "${name}" is not a valid npm scope/name`);
  }
  return name;
}

async function workspacePackageDir(projectsRoot: string, projectId: string, packagePath: string): Promise<string> {
  const projectRoot = projectRootFor(projectsRoot, projectId);
  const workRoot = join(projectRoot, "work");
  const resolved = await resolveWithinWorkspace(workRoot, packagePath);
  if (!existsSync(join(resolved, "package.json"))) {
    throw new DispatchError("not_found", `no package.json under workspace path "${packagePath}"`);
  }
  return resolved;
}

function isPackExcluded(relativePath: string): boolean {
  // dist/ travels WITH the bundle: the activation entry points at the built
  // output, so an installed package is loadable without a rebuild.
  return relativePath === "node_modules" || relativePath.startsWith("node_modules/")
    || relativePath.startsWith(".");
}

/** Collect text files under the package dir, refusing overflow and secrets. */
function collectPackageFiles(packageDir: string): { path: string; bytes: Buffer }[] {
  const files: { path: string; bytes: Buffer }[] = [];
  let total = 0;
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      const rel = relative(packageDir, full).split("\\").join("/");
      if (entry.isDirectory()) {
        if (!isPackExcluded(rel)) walk(full);
        continue;
      }
      if (!entry.isFile() || isPackExcluded(rel)) continue;
      if (/(^|\/)(credentials?|secrets?)(\.|$)/iu.test(rel) || /\.pem$|\.key$|\.env($|\.)/iu.test(rel)) {
        throw new DispatchError("invalid_input", `refusing to pack secret-shaped file: ${rel}`);
      }
      const bytes = readFileSync(full);
      if (bytes.byteLength > MAX_FILE_BYTES) {
        throw new DispatchError("too_large", `package file exceeds 2 MiB: ${rel}`);
      }
      total += bytes.byteLength;
      if (total > MAX_PACKAGE_BYTES) {
        throw new DispatchError("too_large", "package exceeds the 16 MiB pack limit");
      }
      files.push({ path: rel, bytes });
      if (files.length > MAX_PACKAGE_FILES) {
        throw new DispatchError("too_large", `package exceeds ${MAX_PACKAGE_FILES} files`);
      }
    }
  };
  walk(packageDir);
  return files;
}

// -------------------------------------------------------------------------------------------------
// packages.status

function runStatus(ctx: PackageToolContext, payload: Record<string, unknown>): unknown {
  const projectId = typeof payload.projectId === "string" ? payload.projectId : "";
  if (projectId.length === 0) throw new DispatchError("invalid_input", "packages.status needs projectId");
  const projectRoot = projectRootFor(ctx.projectsRoot, projectId);
  const packagesDir = join(projectRoot, "work", WORKSPACE_PACKAGES_DIR);
  const installed: { name: string; version: string; facets: string[]; path: string }[] = [];
  if (existsSync(packagesDir)) {
    for (const entry of readdirSync(packagesDir, { withFileTypes: true })) {
      if (!entry.isDirectory() || !existsSync(join(packagesDir, entry.name, "package.json"))) continue;
      const dir = join(packagesDir, entry.name);
      const manifest = readPackageJson(dir);
      const facets: string[] = [];
      if (manifest.hypit !== null && typeof manifest.hypit === "object") {
        for (const [key, value] of Object.entries(manifest.hypit as Record<string, unknown>)) {
          if (typeof value === "string") facets.push(key);
        }
      }
      installed.push({
        name: requireText(manifest.name, "name"),
        version: requireText(manifest.version, "version"),
        facets,
        path: `${WORKSPACE_PACKAGES_DIR}/${entry.name}`,
      });
    }
  }
  return { packages: installed };
}

// -------------------------------------------------------------------------------------------------
// packages.build — controlled compile of a staged copy.

/**
 * The staged build resolves `@hypit/*` through per-package symlinks into the
 * distribution's own node_modules (`@hypit/hypit` is the distribution ROOT
 * package, whose subpath exports re-export every domain kit): normal NodeNext
 * resolution with the real exports maps, pinned to the exact tree the engine
 * runs — never a fresh install, never the network.
 */
function stageModuleLink(stagedDir: string, distributionRoot: string): void {
  const linkDir = join(stagedDir, "node_modules", "@hypit");
  mkdirSync(linkDir, { recursive: true });
  const sourceDir = join(distributionRoot, "node_modules", "@hypit");
  for (const entry of readdirSync(sourceDir, { withFileTypes: true })) {
    const target = entry.isDirectory() && !entry.isSymbolicLink() ? join(sourceDir, entry.name) + "/" : join(sourceDir, entry.name);
    try {
      symlinkSync(target, join(linkDir, entry.name), entry.isDirectory() ? "dir" : "file");
    } catch (error) {
      if ((error as { code?: string }).code !== "EEXIST") throw error;
    }
  }
  try {
    symlinkSync(distributionRoot + "/", join(linkDir, "hypit"), "dir");
  } catch (error) {
    if ((error as { code?: string }).code !== "EEXIST") throw error;
  }
  // Node built-in types for dependency sources that import node:* modules.
  const typesDir = join(distributionRoot, "node_modules", "@types");
  if (existsSync(typesDir)) {
    try {
      symlinkSync(typesDir, join(stagedDir, "node_modules", "@types"), "dir");
    } catch (error) {
      if ((error as { code?: string }).code !== "EEXIST") throw error;
    }
  }
}

function generatedTsconfig(): string {
  return `${JSON.stringify(
    {
      compilerOptions: {
        strict: true,
        target: "es2022",
        module: "nodenext",
        moduleResolution: "nodenext",
        skipLibCheck: true,
        noEmitOnError: true,
        baseUrl: ".",
        outDir: "dist",
        rootDir: "src",
        declaration: true,
      },
      include: ["src/**/*"],
    },
    null,
    2,
  )}\n`;
}

async function runBuild(ctx: PackageToolContext, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = typeof payload.projectId === "string" ? payload.projectId : "";
  const packagePath = typeof payload.packagePath === "string" ? payload.packagePath : "";
  if (projectId.length === 0 || packagePath.length === 0) {
    throw new DispatchError("invalid_input", "packages.build needs projectId and packagePath");
  }
  const sourceDir = await workspacePackageDir(ctx.projectsRoot, projectId, packagePath);
  const tsc = join(ctx.distributionRoot, "node_modules", ".bin", "tsc");
  if (!existsSync(tsc)) {
    throw new DispatchError("engine_unavailable", "distribution toolchain has no tsc binary");
  }
  const staged = join(projectRootFor(ctx.projectsRoot, projectId), ".journal", `pkgbuild-${Date.now().toString(36)}`);
  try {
    mkdirSync(staged, { recursive: true });
    const distAbs = join(sourceDir, "dist");
    cpSync(sourceDir, staged, { recursive: true, filter: (entry) => entry !== distAbs && !entry.startsWith(distAbs + "/") && !entry.includes("/node_modules/") });
    stageModuleLink(staged, ctx.distributionRoot);
    writeFileSync(join(staged, "tsconfig.build.json"), generatedTsconfig());
    // Exact argv, fixed cwd, fixed toolchain — never author-supplied flags.
    const result = spawnSync(tsc, ["-p", "tsconfig.build.json"], { cwd: staged, encoding: "utf8", timeout: 120_000 });
    const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
    const diagnostics = output.split("\n").map((line) => line.trim()).filter((line) => line.length > 0).slice(0, 50);
    const ok = result.status === 0 && existsSync(join(staged, "dist"));
    return { ok, diagnostics, tool: "tsc", exitCode: result.status ?? -1 };
  } finally {
    rmSync(staged, { recursive: true, force: true });
  }
}

// -------------------------------------------------------------------------------------------------
// packages.pack — sha256-manifested bundle into the broker artifacts root.

async function runPack(ctx: PackageToolContext, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = typeof payload.projectId === "string" ? payload.projectId : "";
  const packagePath = typeof payload.packagePath === "string" ? payload.packagePath : "";
  if (projectId.length === 0 || packagePath.length === 0) {
    throw new DispatchError("invalid_input", "packages.pack needs projectId and packagePath");
  }
  const sourceDir = await workspacePackageDir(ctx.projectsRoot, projectId, packagePath);
  const manifest = readPackageJson(sourceDir);
  const name = requireProjectScopeName(requireText(manifest.name, "name"));
  const version = requireText(manifest.version, "version");

  const files = collectPackageFiles(sourceDir).map(({ path, bytes }) => ({
    path,
    bytes: bytes.byteLength,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    content: bytes.toString("utf8"),
  }));
  for (const file of files) {
    if (Buffer.byteLength(file.content, "utf8") !== file.bytes) {
      throw new DispatchError("invalid_input", `package file is not valid UTF-8 text: ${file.path}`);
    }
  }

  const artifactsRoot = packageArtifactsRoot(ctx.projectsRoot);
  const artifactRoot = join(artifactsRoot, projectId, `${workspacePackageDirName(name)}-${version}`);
  rmSync(artifactRoot, { recursive: true, force: true });
  for (const file of files) {
    const target = join(artifactRoot, file.path);
    mkdirSync(resolve(target, ".."), { recursive: true });
    writeFileSync(target, file.content);
  }
  const bundleManifest = {
    format: "y1.hypit-package-bundle@1",
    name,
    version,
    sourceProjectId: projectId,
    packedAt: new Date().toISOString(),
    files: files.map(({ path, bytes, sha256 }) => ({ path, bytes, sha256 })),
  };
  writeFileSync(join(artifactRoot, "hypit-package-bundle.json"), `${JSON.stringify(bundleManifest, null, 2)}\n`);
  return {
    name,
    version,
    artifactRoot: relative(resolve(ctx.projectsRoot, ".."), artifactRoot).split("\\").join("/"),
    fileCount: files.length,
  };
}

// -------------------------------------------------------------------------------------------------
// packages.install — verified bundle → journaled workspace change.

async function runInstall(ctx: PackageToolContext, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = typeof payload.projectId === "string" ? payload.projectId : "";
  const artifactRootRaw = typeof payload.artifactRoot === "string" ? payload.artifactRoot : "";
  const baseRevision = payload.baseRevision;
  if (projectId.length === 0 || artifactRootRaw.length === 0) {
    throw new DispatchError("invalid_input", "packages.install needs projectId and artifactRoot");
  }
  if (typeof baseRevision !== "number" || !Number.isSafeInteger(baseRevision) || baseRevision < 0) {
    throw new DispatchError("invalid_input", "packages.install needs a safe non-negative baseRevision");
  }
  const artifactsRoot = packageArtifactsRoot(ctx.projectsRoot);
  // artifactRoot is broker-relative (reported by packages.pack): relative to
  // the parent of projectsRoot, which is exactly where the artifacts root sits.
  const hostRoot = resolve(ctx.projectsRoot, "..");
  const artifactRoot = resolve(hostRoot, artifactRootRaw);
  if (!artifactRoot.startsWith(artifactsRoot + "/") || !existsSync(artifactRoot)) {
    throw new DispatchError("not_found", "artifactRoot must exist inside the broker artifacts root");
  }
  const manifestFile = join(artifactRoot, "hypit-package-bundle.json");
  if (!existsSync(manifestFile)) {
    throw new DispatchError("invalid_input", "artifact bundle has no hypit-package-bundle.json manifest");
  }
  const bundle = JSON.parse(readFileSync(manifestFile, "utf8")) as {
    name?: unknown;
    version?: unknown;
    files?: { path?: unknown; sha256?: unknown }[];
  };
  const name = requireProjectScopeName(requireText(bundle.name, "name"));
  const version = requireText(bundle.version, "version");
  if (!Array.isArray(bundle.files) || bundle.files.length === 0) {
    throw new DispatchError("invalid_input", "bundle manifest lists no files");
  }
  const changes: FileChange[] = [];
  for (const file of bundle.files) {
    if (typeof file.path !== "string" || typeof file.sha256 !== "string") {
      throw new DispatchError("invalid_input", "bundle manifest file entry needs path and sha256");
    }
    const bytes = readFileSync(join(artifactRoot, file.path));
    const digest = createHash("sha256").update(bytes).digest("hex");
    if (digest !== file.sha256) {
      throw new DispatchError("invalid_input", `bundle file failed hash verification: ${file.path}`);
    }
    changes.push({
      path: `${WORKSPACE_PACKAGES_DIR}/${workspacePackageDirName(name)}/${file.path}`,
      action: "put",
      content: bytes.toString("utf8"),
    });
  }
  const projectRoot = projectRootFor(ctx.projectsRoot, projectId);
  const commandId = typeof payload.commandId === "string" && payload.commandId.length > 0
    ? payload.commandId
    : `pkg-install-${Date.now().toString(36)}`;
  const applied = await applyWorkspaceChanges({ projectId, projectRoot, commandId, baseRevision, changes });
  return { installed: { name, version }, files: changes.length, revision: applied.revision, manifestHash: applied.manifestHash };
}

// -------------------------------------------------------------------------------------------------

export async function runPackageTool(
  ctx: PackageToolContext,
  kind: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  switch (kind) {
    case "packages.status":
      return runStatus(ctx, payload);
    case "packages.build":
      return await runBuild(ctx, payload);
    case "packages.pack":
      return await runPack(ctx, payload);
    case "packages.install":
      return await runInstall(ctx, payload);
    default:
      throw new DispatchError("unknown_kind", `unknown package tool "${kind}"`);
  }
}
