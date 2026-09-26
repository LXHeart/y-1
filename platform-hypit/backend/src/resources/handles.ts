// handles.ts — C107-05 (task-107) resource-handle registry.
//
// A resource handle is the only name the outside world ever sees for a file
// the broker produced or accepted (`res-<16hex>`). Registration records owner
// project, absolute path (re-verified to stay inside the allowed roots at
// resolve time), media type, sha256, size and origin role. The durable
// path→handle mapping lives in the C04 command store (bridge.sqlite
// resource_handles); the richer metadata lives in a sidecar index next to the
// assets so the store schema stays untouched.
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { isAbsolute, join, relative, resolve } from "node:path";

import type { CommandStore } from "../commands/store.ts";

export type ResourceRecord = {
  readonly handle: string;
  readonly projectId: string | null;
  readonly absolutePath: string;
  readonly mediaType: string | null;
  readonly sha256: string;
  readonly sizeBytes: number;
  readonly role: string;
  readonly createdAt: string;
};

export class HandleError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "HandleError";
  }
}

async function sha256Of(path: string): Promise<string> {
  const hash = createHash("sha256");
  await new Promise<void>((resolvePromise, rejectPromise) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", rejectPromise);
    stream.on("end", () => resolvePromise());
  });
  return hash.digest("hex");
}

/** A handle is bound to the roots it may live under — never an arbitrary path. */
export function assertWithinRoots(roots: readonly string[], absolutePath: string): void {
  if (!isAbsolute(absolutePath)) {
    throw new HandleError("invalid_path", `resource path must be absolute: ${absolutePath}`);
  }
  const contained = roots.some((root) => {
    const rel = relative(resolve(root), absolutePath);
    return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel);
  });
  if (!contained) {
    throw new HandleError("path_outside_roots", `resource path escapes the allowed roots: ${absolutePath}`);
  }
}

type IndexShape = { readonly format: "y1.hypit.handles@1"; readonly records: Record<string, ResourceRecord> };

async function readIndex(indexPath: string): Promise<IndexShape> {
  try {
    const raw = JSON.parse(await readFile(indexPath, "utf8")) as IndexShape;
    if (raw?.format !== "y1.hypit.handles@1" || typeof raw.records !== "object" || raw.records === null) {
      throw new HandleError("index_corrupt", `${indexPath}: not a handle index`);
    }
    return raw;
  } catch (error) {
    if (error instanceof HandleError) throw error;
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return { format: "y1.hypit.handles@1", records: {} };
    }
    throw error;
  }
}

async function writeIndex(indexPath: string, index: IndexShape): Promise<void> {
  await mkdir(resolve(indexPath, ".."), { recursive: true });
  await writeFile(indexPath, `${JSON.stringify(index, undefined, 2)}\n`, "utf8");
}

export type HandleRegistry = {
  readonly indexPath: string;
  readonly allowedRoots: readonly string[];
  readonly store: CommandStore;
};

export function openHandleRegistry(allowedRoots: readonly string[], indexPath: string, store: CommandStore): HandleRegistry {
  return { indexPath, allowedRoots: allowedRoots.map((root) => resolve(root)), store };
}

/** Register a file as a resource; idempotent by content hash. */
export async function registerResource(
  registry: HandleRegistry,
  input: {
    readonly absolutePath: string;
    readonly projectId: string | null;
    readonly mediaType: string | null;
    readonly role: string;
    readonly sha256?: string;
    readonly sizeBytes?: number;
  },
): Promise<ResourceRecord> {
  assertWithinRoots(registry.allowedRoots, input.absolutePath);
  const info = await stat(input.absolutePath);
  if (!info.isFile()) throw new HandleError("invalid_path", "resource must be a regular file");
  const sha256 = input.sha256 ?? (await sha256Of(input.absolutePath));
  const sizeBytes = input.sizeBytes ?? info.size;
  const index = await readIndex(registry.indexPath);
  const existing = Object.values(index.records).find(
    (record) => record.sha256 === sha256 && record.sizeBytes === sizeBytes
      && record.absolutePath === input.absolutePath,
  );
  if (existing !== undefined) return existing;
  const handle = `res-${sha256.slice(0, 16)}-${Object.keys(index.records).length.toString(36)}`;
  const record: ResourceRecord = {
    handle,
    projectId: input.projectId,
    absolutePath: input.absolutePath,
    mediaType: input.mediaType,
    sha256,
    sizeBytes,
    role: input.role,
    createdAt: new Date().toISOString(),
  };
  index.records[handle] = record;
  await writeIndex(registry.indexPath, index);
  // Durable path mapping for command-side resolution (C04 store).
  registry.store.registerResourceHandle(handle, input.absolutePath, input.projectId, input.mediaType);
  return record;
}

/** Resolve a handle, re-verifying containment and existence at read time. */
export async function resolveResource(registry: HandleRegistry, handle: string): Promise<ResourceRecord> {
  if (!/^res-[0-9a-f]{16}-[0-9a-z]+$/u.test(handle)) {
    throw new HandleError("invalid_handle", `malformed handle ${handle}`);
  }
  const index = await readIndex(registry.indexPath);
  const record = index.records[handle];
  if (record === undefined) throw new HandleError("not_found", `unknown handle ${handle}`);
  assertWithinRoots(registry.allowedRoots, record.absolutePath);
  const info = await stat(record.absolutePath).catch(() => null);
  if (info === null || !info.isFile()) {
    throw new HandleError("not_found", `handle ${handle} no longer has a backing file`);
  }
  return record;
}

export function handleIndexRelativePath(): string {
  return join("resources", "handles.index.json");
}
