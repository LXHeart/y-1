// paths.ts — C107-04 (task-107) workspace path boundary.
//
// A Hypit project lives at <hostRoot>/projects/<projectId>/ with the author
// workspace in work/ and lifecycle siblings outside it (§5.1/K06.3). Every
// path that crosses this boundary — read or write — must pass validate() plus
// a real-filesystem containment check: traversal, encoded paths, NUL bytes,
// control characters and symlink escapes are rejected on BOTH read and write,
// never only on PUT (TC107-04 expectations / §5.1).
import { lstat } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";

/** Workspace-relative file size ceilings from §5.1. */
export const MAX_WORKSPACE_FILE_BYTES = 2 * 1024 * 1024;
export const MAX_CHANGESET_BYTES = 16 * 1024 * 1024;

/** Path segments never shown in file listings (04.5: credentials, journal, deps). */
const LISTING_SHIELDED_SEGMENTS = new Set(["node_modules", ".journal", ".hypit", ".git"]);

/** Conservative author-file charset: ASCII letters/digits and safe punctuation. */
const SEGMENT_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 ._@()+-]*$/;

export class WorkspacePathError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "WorkspacePathError";
  }
}

export type WorkspaceValidation = {
  readonly segments: readonly string[];
};

/**
 * Validate a workspace-relative path. Rejects: empty, absolute, backslashes,
 * NUL/control bytes, percent-encoding, empty/`.`/`..` segments, leading-dot
 * segments (hidden files are internal state), over-deep or over-long paths,
 * and characters outside the author charset.
 */
export function validateWorkspaceRelativePath(relative: string): WorkspaceValidation {
  if (typeof relative !== "string" || relative.length === 0) {
    throw new WorkspacePathError("workspace path must be a non-empty string");
  }
  if (relative.length > 255) {
    throw new WorkspacePathError("workspace path longer than 255 characters");
  }
  if (relative.includes("\0")) {
    throw new WorkspacePathError("workspace path contains NUL byte");
  }
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(relative)) {
    throw new WorkspacePathError("workspace path contains control characters");
  }
  if (relative.includes("%")) {
    throw new WorkspacePathError("workspace path contains percent-encoding; pass decoded paths only");
  }
  if (relative.includes("\\")) {
    throw new WorkspacePathError("workspace path contains backslash");
  }
  if (relative.startsWith("/") || /^[A-Za-z]:/.test(relative)) {
    throw new WorkspacePathError("workspace path must be relative");
  }
  const segments = relative.split("/");
  if (segments.length > 16) {
    throw new WorkspacePathError("workspace path deeper than 16 segments");
  }
  for (const segment of segments) {
    if (segment.length === 0) {
      throw new WorkspacePathError("workspace path contains empty segment");
    }
    if (segment === "." || segment === "..") {
      throw new WorkspacePathError("workspace path contains relative segment");
    }
    if (segment.startsWith(".")) {
      throw new WorkspacePathError(`workspace path segment is hidden/internal: ${segment}`);
    }
    if (!SEGMENT_PATTERN.test(segment)) {
      throw new WorkspacePathError(`workspace path segment has unsupported characters: ${segment}`);
    }
  }
  return { segments };
}

/**
 * Resolve a validated relative path under root and prove on the live
 * filesystem that no existing component of it is a symlink (an escape vector).
 * Missing trailing components are fine for write use.
 */
export async function resolveWithinWorkspace(root: string, relative: string): Promise<string> {
  validateWorkspaceRelativePath(relative);
  const absolute = resolve(join(root, relative));
  const rootReal = await realPathOrNull(root);
  if (rootReal === null) {
    throw new WorkspacePathError(`workspace root does not exist: ${root}`);
  }
  if (absolute !== root && !absolute.startsWith(root + sep)) {
    throw new WorkspacePathError("workspace path escapes workspace root");
  }
  // Walk the existing prefix component by component; any symlink → reject.
  let current = rootReal;
  const segments = relative.split("/");
  for (const segment of segments) {
    const candidate = join(current, segment);
    const info = await lstatOrNull(candidate);
    if (info === null) break;
    if (info.isSymbolicLink()) {
      throw new WorkspacePathError(`workspace path crosses symlink: ${segment}`);
    }
    current = candidate;
  }
  return absolute;
}

/** Read-side containment: every existing component must be a real directory/file. */
export async function assertReadableWithin(root: string, relative: string): Promise<string> {
  const absolute = await resolveWithinWorkspace(root, relative);
  const info = await lstatOrNull(absolute);
  if (info === null) {
    throw new WorkspacePathError(`workspace file does not exist: ${relative}`);
  }
  if (info.isSymbolicLink()) {
    throw new WorkspacePathError(`workspace path is a symlink: ${relative}`);
  }
  return absolute;
}

/** Whether a directory entry is shielded from project file listings (04.5). */
export function isShieldedFromListing(name: string): boolean {
  return LISTING_SHIELDED_SEGMENTS.has(name);
}

async function lstatOrNull(path: string): Promise<Awaited<ReturnType<typeof lstat>> | null> {
  try {
    return await lstat(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

async function realPathOrNull(path: string): Promise<string | null> {
  const { realpath } = await import("node:fs/promises");
  try {
    return await realpath(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

/** Parent directory helper for staged writes (validated relative path). */
export function parentOf(absolute: string): string {
  return dirname(absolute);
}
