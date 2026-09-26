/**
 * C107-11 tools/snapshot.ts — exact-frame and paginated-grid capture from the
 * CURRENT Studio document (step 5). Snapshots photograph the same compiled
 * picture the final encoder photographs — via the native
 * `renderHyperframesFrames` provider path with the render Headless Shell — so
 * capturing never creates a new export Build and exact frames stay exact.
 *
 * C107F-03 (D-05): the kind channel. The document comes from the preview
 * session registry (never a caller URL): the bound (project, runFile, revision)
 * is compiled through the SAME engine pipeline the Studio server uses
 * (loadStudioDomain → loadStudioRun → readStudioSession), then the frames are
 * photographed with the native renderer and land as resource handles.
 */
import { renderHyperframesFrames } from "@hypit/provider-hyperframes-local";
import type { HyperframesDocument } from "@hypit/hyperframes";
import { writeFile, mkdir, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { requireSession } from "../preview/sessions.ts";
import { ensureRuntimeProfile } from "../engine/runtime-adapter.ts";
import { loadHypit } from "../engine/hypit-bootstrap.ts";

export type SnapshotRequest = {
  readonly document: HyperframesDocument;
  /** Exact frame indices to photograph (frame 0 = first frame). */
  readonly frames?: readonly number[];
  /** Grid pagination: every grid page samples this many frames. */
  readonly framesPerPage?: number;
  readonly canvas?: { readonly width: number; readonly height: number };
};

export type SnapshotPage = {
  readonly page: number;
  readonly frameIndices: readonly number[];
};

/** Deterministic exact-frame/grid schedule (pure — unit tested without a browser). */
export function snapshotSchedule(request: SnapshotRequest): {
  readonly frames: readonly number[];
  readonly pages: readonly SnapshotPage[];
} {
  const frameCount = request.document.frameCount;
  if (frameCount <= 0) throw new DispatchError("invalid_input", "document has no frames");
  const explicit = request.frames ?? [];
  for (const frame of explicit) {
    if (!Number.isSafeInteger(frame) || frame < 0 || frame >= frameCount) {
      throw new DispatchError("invalid_input", `frame ${frame} is outside 0..${frameCount - 1}`);
    }
  }
  const frames = explicit.length > 0 ? [...explicit] : [0, Math.floor((frameCount - 1) / 2), frameCount - 1];
  const perPage = request.framesPerPage ?? frames.length;
  const pages: SnapshotPage[] = [];
  for (let index = 0; index < frames.length; index += perPage) {
    pages.push({ page: pages.length, frameIndices: frames.slice(index, index + perPage) });
  }
  return { frames, pages };
}

/**
 * Photograph exact frames through the SAME native render pipeline as the final
 * encode (render Headless Shell, chromePath resolved by the provider).
 */
export async function snapshotFrames(
  request: SnapshotRequest,
  options: Parameters<typeof renderHyperframesFrames>[1],
): Promise<Record<number, Uint8Array>> {
  const schedule = snapshotSchedule(request);
  const rendered = await renderHyperframesFrames({
    document: request.document,
    frames: schedule.frames,
  } as Parameters<typeof renderHyperframesFrames>[0], options);
  const byFrame: Record<number, Uint8Array> = {};
  const frames = (rendered as { readonly frames?: readonly { readonly frame: number; readonly bytes: Uint8Array }[] })
    .frames ?? [];
  for (const entry of frames) {
    byFrame[entry.frame] = entry.bytes;
  }
  return byFrame;
}

// ---------------------------------------------------------------------------
// C107F-03: the snapshot TOOL channel (D-05).
// ---------------------------------------------------------------------------

export const snapshotTools = ["snapshot"] as const;
export type SnapshotTool = typeof snapshotTools[number];

export function isSnapshotTool(value: string): value is SnapshotTool {
  return (snapshotTools as readonly string[]).includes(value);
}

export type SnapshotToolContext = {
  readonly distributionRoot: string;
  /** Frame PNGs land here before registration (inside the resource roots). */
  readonly framesRoot: string;
  /** Registers a materialized file as a resource handle (media assets area). */
  readonly registerResource: (input: {
    readonly absolutePath: string;
    readonly projectId: string | null;
    readonly mediaType: string;
    readonly role: string;
  }) => Promise<string>;
};

export type SnapshotToolResult = {
  readonly frames: ReadonlyArray<{ readonly assetId: string; readonly frameIndex: number }>;
  readonly pages: number;
};

const MAX_FRAMES_PER_PAGE = 50;

/**
 * ranges[] → explicit frame indices: each entry {start?, endExclusive?, every?}
 * with non-negative safe integers and every ≥ 1; window bounds are validated
 * against the document by snapshotSchedule once it is known.
 */
function expandRanges(ranges: readonly unknown[]): number[] {
  const frames: number[] = [];
  for (const entry of ranges) {
    if (typeof entry !== "object" || entry === null) {
      throw new DispatchError("invalid_input", "each range must be an object");
    }
    const record = entry as { readonly start?: unknown; readonly endExclusive?: unknown; readonly every?: unknown };
    // present-but-wrong-typed bounds are refused, never silently defaulted
    if (record.start !== undefined && (typeof record.start !== "number" || !Number.isSafeInteger(record.start) || record.start < 0)) {
      throw new DispatchError("invalid_input", "range start must be a non-negative safe integer");
    }
    if (record.every !== undefined && (typeof record.every !== "number" || !Number.isSafeInteger(record.every) || record.every < 1)) {
      throw new DispatchError("invalid_input", "range every must be a safe integer >= 1");
    }
    const start = typeof record.start === "number" ? record.start : 0;
    const every = typeof record.every === "number" ? record.every : 1;
    const endExclusive = record.endExclusive;
    for (const value of [start, every]) {
      if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
        throw new DispatchError("invalid_input", "range bounds must be non-negative safe integers");
      }
    }
    if (every < 1) throw new DispatchError("invalid_input", "range every must be >= 1");
    if (endExclusive === undefined) {
      frames.push(start);
      continue;
    }
    if (typeof endExclusive !== "number" || !Number.isSafeInteger(endExclusive) || endExclusive < 0) {
      throw new DispatchError("invalid_input", "range endExclusive must be a non-negative safe integer");
    }
    if (endExclusive <= start) {
      throw new DispatchError("invalid_input", "range endExclusive must exceed start");
    }
    for (let frame = start; frame < endExclusive; frame += every) {
      frames.push(frame);
    }
  }
  return frames;
}

/**
 * The snapshot kind body: session-bound document, deterministic schedule,
 * native renderer, resource handles. URLs are never accepted (D-05); an
 * expired/foreign session is a 409 hypit_session_expired.
 */
export async function runSnapshotTool(
  ctx: SnapshotToolContext,
  payload: {
    readonly previewSessionId?: unknown;
    readonly at?: unknown;
    readonly ranges?: unknown;
    readonly pageSize?: unknown;
  },
): Promise<SnapshotToolResult> {
  const sessionId = payload.previewSessionId;
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    throw new DispatchError("invalid_input", "snapshot needs previewSessionId");
  }
  if (payload.at !== undefined && payload.ranges !== undefined) {
    throw new DispatchError("invalid_input", "snapshot takes at[] or ranges[], not both");
  }
  let at: readonly number[] | undefined;
  if (payload.at !== undefined) {
    if (!Array.isArray(payload.at)) throw new DispatchError("invalid_input", "at must be an array of frame indices");
    at = payload.at as readonly number[];
  }
  let expanded: readonly number[] | undefined;
  if (payload.ranges !== undefined) {
    if (!Array.isArray(payload.ranges) || payload.ranges.length === 0) {
      throw new DispatchError("invalid_input", "ranges must be a non-empty array");
    }
    expanded = expandRanges(payload.ranges as readonly unknown[]);
  }
  let framesPerPage = at?.length ?? expanded?.length ?? 3;
  if (payload.pageSize !== undefined) {
    if (typeof payload.pageSize !== "number" || !Number.isSafeInteger(payload.pageSize)
      || payload.pageSize < 1 || payload.pageSize > MAX_FRAMES_PER_PAGE) {
      throw new DispatchError("invalid_input", `pageSize must be 1..${MAX_FRAMES_PER_PAGE}`);
    }
    framesPerPage = payload.pageSize;
  }
  const totalRequested = (at ?? expanded)?.length ?? 0;
  if (totalRequested > MAX_FRAMES_PER_PAGE * 64) {
    throw new DispatchError("invalid_input", "too many frames requested");
  }

  let session;
  try {
    session = requireSession(sessionId);
  } catch {
    throw new DispatchError("session_expired", `preview session ${sessionId} is not active`);
  }

  const compiled = await compileSessionDocument(ctx.distributionRoot, session);
  const explicit = at ?? expanded;
  const schedule = snapshotSchedule({
    document: compiled.document,
    ...(explicit === undefined ? {} : { frames: explicit }),
    framesPerPage,
  });
  const byFrame = await photographFrames(compiled, schedule.frames);
  const registered: Array<{ assetId: string; frameIndex: number }> = [];
  for (const frameIndex of schedule.frames) {
    const bytes = byFrame[frameIndex];
    if (bytes === undefined) {
      throw new DispatchError("engine_error", `renderer produced no bytes for frame ${frameIndex}`);
    }
    const absolutePath = join(ctx.framesRoot, `${session.id.replace(/[^a-z0-9-]/giu, "")}-${frameIndex}.png`);
    await mkdir(dirname(absolutePath), { recursive: true });
    await writeFile(absolutePath, bytes);
    const handle = await ctx.registerResource({
      absolutePath,
      projectId: session.projectId,
      mediaType: "image/png",
      role: "footage",
    });
    registered.push({ assetId: handle, frameIndex });
  }
  return { frames: registered, pages: schedule.pages.length };
}

/** The (project, runFile, revision) document through the engine's own Studio pipeline. */
async function compileSessionDocument(
  distributionRoot: string,
  session: { readonly runFile: string; readonly revision: number; readonly workspaceRoot: string },
): Promise<{
  readonly document: HyperframesDocument;
  readonly material: ReadonlyMap<string, { readonly mediaType: string; readonly bytes: Uint8Array }>;
  readonly renderOptions: RenderOptions;
}> {
  const engine = await loadHypit(distributionRoot);
  // Deep Studio modules resolve through the distribution URL (the tsconfig
  // path map only exposes each package's index).
  const { pathToFileURL } = await import("node:url");
  const { relative } = await import("node:path");
  const distribution = pathToFileURL(`${engine.distributionRoot}/`);
  const studioModule = (name: string): string => new URL(`packages/studio/src/${name}.ts`, distribution).href;
  const { videoCliDistribution, videoStudioCompanionPackages } = await import("@hypit/video-cli");
  const { loadStudioDomain } = await import(studioModule("domain")) as {
    loadStudioDomain: (input: { readonly run: string; readonly workspaceRoot: string; readonly packageRoot: string }) => Promise<{
      readonly packages: readonly unknown[];
    }>;
  };
  const { loadStudioCompanionRegistry } = await import(studioModule("companion-assembly")) as {
    loadStudioCompanionRegistry: (input: {
      readonly distributionPackageRoot: string;
      readonly distributionPackages: readonly unknown[];
      readonly sourcePackages: readonly unknown[];
    }) => Promise<unknown>;
  };
  const { loadStudioRun } = await import(studioModule("run")) as {
    loadStudioRun: (input: {
      readonly run: string;
      readonly domain: unknown;
      readonly registry: unknown;
    }) => Promise<{
      readonly authorSource: string;
      readonly runPath: string;
      readonly targets: readonly string[];
    }>;
  };
  const { readStudioSession } = await import(studioModule("session")) as {
    readStudioSession: (input: {
      readonly domain: unknown;
      readonly registry: unknown;
      readonly run: unknown;
      readonly revision: number;
      readonly sourcePath?: string;
      readonly workspaceRoot: string;
    }) => Promise<{
      readonly document: HyperframesDocument;
      readonly material: ReadonlyMap<string, { readonly mediaType: string; readonly bytes: Uint8Array }>;
    }>;
  };

  // The broker's Studio convention (src/studio/*): the pipeline receives the
  // project HEAD (work/) as its workspace — that is where the run file, the
  // author source, workspace packages and hypit.runtime.json live.
  const workRoot = join(session.workspaceRoot, "work");
  const runPath = join(workRoot, session.runFile);
  await ensureRuntimeProfile(distributionRoot, workRoot);
  const domain = await loadStudioDomain({
    run: runPath,
    workspaceRoot: workRoot,
    packageRoot: workRoot,
  });
  const registry = await loadStudioCompanionRegistry({
    distributionPackageRoot: videoCliDistribution.packageRoot ?? distributionRoot,
    distributionPackages: videoStudioCompanionPackages,
    sourcePackages: domain.packages,
  });
  const run = await loadStudioRun({ run: runPath, domain, registry });
  const built = await readStudioSession({
    domain,
    registry,
    run,
    revision: session.revision,
    sourcePath: relative(workRoot, run.authorSource),
    workspaceRoot: workRoot,
  });
  return { document: built.document, material: built.material, renderOptions: await renderOptionsFor(workRoot) };
}

/** Render provider selection from the workspace Runtime Profile — the same
 *  browser the encoder would use for this workspace (never a silent default
 *  that ignores the profile's managed cache). */
type RenderOptions = {
  readonly browserGpu?: "auto" | "software" | "hardware";
  readonly browserCacheDirectory?: string;
  readonly chromePath?: string;
};

async function renderOptionsFor(workRoot: string): Promise<RenderOptions> {
  let profile: { readonly endpoints?: Readonly<Record<string, { readonly config?: Record<string, unknown> }>> };
  try {
    profile = JSON.parse(await readFile(join(workRoot, "hypit.runtime.json"), "utf8"));
  } catch {
    return {};
  }
  const config = profile.endpoints?.["hyperframes.local"]?.config ?? {};
  const pick = (key: string): string | undefined =>
    typeof config[key] === "string" && (config[key] as string).length > 0 ? (config[key] as string) : undefined;
  const gpu = pick("browserGpu");
  const browserGpu = gpu === "auto" || gpu === "software" || gpu === "hardware" ? gpu : undefined;
  const browserCacheDirectory = pick("browserCacheDirectory");
  const chromePath = pick("chromePath");
  return {
    ...(browserGpu === undefined ? {} : { browserGpu }),
    ...(browserCacheDirectory === undefined ? {} : { browserCacheDirectory }),
    ...(chromePath === undefined ? {} : { chromePath }),
  };
}

/** Frame bytes via the native renderer; artifacts served from the session's own material. */
async function photographFrames(
  compiled: {
    readonly document: HyperframesDocument;
    readonly material: ReadonlyMap<string, { readonly mediaType: string; readonly bytes: Uint8Array }>;
    readonly renderOptions: RenderOptions;
  },
  frames: readonly number[],
): Promise<Record<number, Uint8Array>> {
  const rendered: Record<number, Uint8Array> = {};
  const outputs = new Map<string, Uint8Array>();
  const store = {
    async get(resource: string) {
      return compiled.material.get(resource)?.bytes ?? outputs.get(resource);
    },
    async put(bytes: Uint8Array, mediaType: string) {
      const resource = `snap-${outputs.size}`;
      outputs.set(resource, bytes);
      return { kind: "blob", resource, size: bytes.byteLength, mediaType } as never;
    },
    async write() {
      throw new Error("snapshot store is read-through for session material only");
    },
    async has(resource: string) {
      return compiled.material.has(resource) || outputs.has(resource);
    },
  };
  const result = await renderHyperframesFrames(
    { document: compiled.document, frames } as Parameters<typeof renderHyperframesFrames>[0],
    { resources: store as never, ...compiled.renderOptions },
  );
  const refs = result as readonly { readonly resource: string }[];
  if (!Array.isArray(refs)) {
    throw new DispatchError("engine_error", "renderer returned no frame artifacts");
  }
  for (const [index, frame] of frames.entries()) {
    const bytes = outputs.get(refs[index]!.resource);
    if (bytes === undefined) {
      throw new DispatchError("engine_error", `renderer frame artifact ${refs[index]!.resource} has no bytes`);
    }
    rendered[frame] = bytes;
  }
  return rendered;
}
