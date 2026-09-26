// media.ts — C107-05 (task-107) media tools on the trusted broker.
//
// Faithful re-implementation of the upstream video-cli media surface
// (probe/cut/frames/tile/tiles/boundaries/fetch/prepare-fetch) against
// ffprobe/ffmpeg/yt-dlp with argv arrays only — no shell, no string-built
// commands. Sources come from registered resource handles or
// workspace-relative paths; outputs land under the broker's resources root
// and are returned as new handles, never as raw local paths (K08/K09).
//
// Differences from upstream, kept on purpose:
// - grids are composed by ffmpeg hstack/vstack (no sharp dependency here);
//   per-cell time metadata rides in the returned frames[] list, not bitmaps.
// - fetch runs a policy-checked manual redirect loop (url-policy) before
//   handing the final URL to pinned yt-dlp; the per-hop check covers the
//   user-controllable redirect prefix (site CDN internals after handoff are
//   outside it and documented as such).
import { execFile, spawn, spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { copyFile, mkdir, mkdtemp, readdir, readFile, rename, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

import { assertFetchableUrl, checkRedirectLocation, safeFetchTargetName, UrlPolicyError } from "../resources/url-policy.ts";
import { openHandleRegistry, registerResource, resolveResource, type HandleRegistry } from "../resources/handles.ts";

export const mediaTools = [
  "media.probe", "media.cut", "media.frames", "media.tile", "media.tiles",
  "media.boundaries", "media.fetch", "media.prepare-fetch",
] as const;
export type MediaTool = typeof mediaTools[number];

export class MediaToolError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "MediaToolError";
  }
}

function assert(condition: unknown, code: string, message: string): asserts condition {
  if (!condition) throw new MediaToolError(code, message);
}

function run(file: string, args: readonly string[], timeoutMs = 120_000): Promise<{ stdout: string; stderr: string }> {
  return new Promise((resolvePromise, rejectPromise) => {
    execFile(file, [...args], { encoding: "utf8", windowsHide: true, timeout: timeoutMs, maxBuffer: 32 * 1024 * 1024 },
      (error, stdout, stderr) => {
        if (error !== null) rejectPromise(Object.assign(error, { stderr: String(stderr) }));
        else resolvePromise({ stdout: String(stdout), stderr: String(stderr) });
      });
  });
}

// ---------------------------------------------------------------------------
// probe
// ---------------------------------------------------------------------------

export type ProbeStream = {
  readonly index: number;
  readonly codecType: string;
  readonly codecName: string;
  readonly width?: number | undefined;
  readonly height?: number | undefined;
  readonly attachedPic: boolean;
  readonly sampleRate?: number | undefined;
  readonly channels?: number | undefined;
  readonly disposition?: Readonly<Record<string, number>> | undefined;
};

export type MediaProbeResult = {
  readonly duration: number;
  readonly hasVideo: boolean;
  readonly hasAudio: boolean;
  readonly width: number | null;
  readonly height: number | null;
  readonly frameRate: number | null;
  readonly videoStreams: readonly ProbeStream[];
  readonly audioStreams: readonly ProbeStream[];
  readonly attachedPicStreams: readonly ProbeStream[];
  readonly streams: readonly ProbeStream[];
};

type FfprobeStream = {
  index?: number;
  codec_type?: string;
  codec_name?: string;
  width?: number;
  height?: number;
  sample_rate?: string;
  channels?: number;
  disposition?: Record<string, number>;
  avg_frame_rate?: string;
};

export async function probeMedia(source: string): Promise<MediaProbeResult> {
  const { stdout } = await run("ffprobe", [
    "-v", "error", "-print_format", "json", "-show_format", "-show_streams", source,
  ]).catch((error) => {
    throw new MediaToolError("probe_failed", `ffprobe failed for ${source}: ${String(error.stderr ?? error.message).slice(-400)}`);
  });
  const parsed = JSON.parse(stdout) as { format?: { duration?: string }; streams?: FfprobeStream[] };
  const raw = parsed.streams ?? [];
  const streams: ProbeStream[] = raw.map((item) => ({
    index: item.index ?? -1,
    codecType: item.codec_type ?? "unknown",
    codecName: item.codec_name ?? "unknown",
    width: item.width,
    height: item.height,
    attachedPic: (item.disposition?.attached_pic ?? 0) === 1,
    ...(item.sample_rate !== undefined ? { sampleRate: Number(item.sample_rate) } : {}),
    ...(item.channels !== undefined ? { channels: item.channels } : {}),
    disposition: item.disposition,
  }));
  const video = streams.filter((item) => item.codecType === "video" && !item.attachedPic);
  const attached = streams.filter((item) => item.attachedPic);
  const audio = streams.filter((item) => item.codecType === "audio");
  const rate = /^(\d+)\/(\d+)$/u.exec(raw.find((item) => item.codec_type === "video" && (item.disposition?.attached_pic ?? 0) !== 1)?.avg_frame_rate ?? "");
  const main = video[0] ?? null;
  return {
    duration: Number(parsed.format?.duration ?? 0),
    hasVideo: video.length > 0,
    hasAudio: audio.length > 0,
    width: main?.width ?? null,
    height: main?.height ?? null,
    frameRate: rate !== null && Number(rate[2]) > 0 ? Number(rate[1]) / Number(rate[2]) : null,
    videoStreams: video,
    audioStreams: audio,
    attachedPicStreams: attached,
    streams,
  };
}

// ---------------------------------------------------------------------------
// cut — at XOR (start,end); label-time accepted (time metadata in result)
// ---------------------------------------------------------------------------

export type CutResult = { readonly output: string; readonly start: number; readonly end: number; readonly durationSeconds: number };

export async function cutClip(input: {
  readonly source: string;
  readonly to: string;
  readonly at?: number | undefined;
  readonly start?: number | undefined;
  readonly end?: number | undefined;
}): Promise<CutResult> {
  const hasAt = input.at !== undefined;
  const hasRange = input.start !== undefined || input.end !== undefined;
  assert(!(hasAt && hasRange), "invalid_input", "--at and --start/--end are mutually exclusive");
  let start: number;
  let end: number;
  if (hasAt) {
    assert(Number.isFinite(input.at) && input.at >= 0, "invalid_input", "at must be a non-negative second");
    start = input.at!;
    end = input.at! + 1;
  } else {
    assert(input.start !== undefined && input.end !== undefined, "invalid_input", "cut needs either at or both start and end");
    assert(Number.isFinite(input.start) && Number.isFinite(input.end) && input.end > input.start,
      "invalid_input", "cut range must satisfy end > start");
    start = input.start;
    end = input.end;
  }
  const existing = await stat(input.to).then(() => true, () => false);
  assert(!existing, "target_exists", `destination ${input.to} already exists`);
  await mkdir(dirname(input.to), { recursive: true });
  await run("ffmpeg", [
    "-hide_banner", "-loglevel", "error", "-y",
    "-ss", start.toFixed(6), "-i", input.source, "-t", (end - start).toFixed(6),
    "-c", "copy", input.to,
  ]);
  const probe = await probeMedia(input.to);
  return { output: input.to, start, end, durationSeconds: probe.duration };
}

// ---------------------------------------------------------------------------
// frames — explicit times, or everyFrame inside ranges; pagination
// ---------------------------------------------------------------------------

export type FrameSample = { readonly file: string; readonly timestampSeconds: number };

export async function extractFrames(input: {
  readonly source: string;
  readonly toDir: string;
  readonly times: readonly number[];
  readonly offset?: number | undefined;
  readonly limit?: number | undefined;
}): Promise<{ readonly frames: readonly FrameSample[]; readonly totalTimes: number }> {
  assert(input.times.length > 0, "invalid_input", "frame extraction needs at least one time");
  for (const time of input.times) {
    assert(Number.isFinite(time) && time >= 0, "invalid_input", "frame times must be non-negative seconds");
  }
  const sorted = [...input.times].sort((a, b) => a - b);
  const offset = input.offset ?? 0;
  const limit = input.limit ?? sorted.length;
  const page = sorted.slice(offset, offset + limit);
  await mkdir(input.toDir, { recursive: true });
  const frames: FrameSample[] = [];
  for (const [index, time] of page.entries()) {
    const file = join(input.toDir, `frame-${String(index).padStart(4, "0")}-${time.toFixed(3)}.png`);
    await run("ffmpeg", [
      "-hide_banner", "-loglevel", "error", "-y",
      "-ss", time.toFixed(6), "-i", input.source, "-frames:v", "1", file,
    ]);
    frames.push({ file, timestampSeconds: time });
  }
  return { frames, totalTimes: sorted.length };
}

/** intervalSamples mirrors upstream: inclusive samples every `every` seconds. */
export function intervalSamples(start: number, end: number, every: number): readonly number[] {
  assert(Number.isFinite(start) && Number.isFinite(end) && end > start && every > 0,
    "invalid_input", "interval needs end > start and every > 0");
  const samples: number[] = [];
  for (let time = start; time <= end + 1e-9; time += every) samples.push(Number(time.toFixed(6)));
  return samples;
}

/** tileSampleTimes mirrors upstream clamp(round(seconds*1.5), 4, 9). */
export function tileSampleTimes(start: number, end: number, frameCount: number): readonly number[] {
  assert(end > start && frameCount >= 1, "invalid_input", "tile sampling needs end > start");
  const span = end - start;
  const inner = frameCount > 1 ? span / (frameCount - 1) : 0;
  return Array.from({ length: frameCount }, (_, index) => Number((start + inner * index).toFixed(6)));
}

export function tileFrameCount(seconds: number): number {
  return Math.max(4, Math.min(9, Math.round(seconds * 1.5)));
}

// ---------------------------------------------------------------------------
// transcript phrase matching (upstream semantics, punctuation-insensitive)
// ---------------------------------------------------------------------------

export type TranscriptWord = { readonly text: string; readonly start?: number | undefined; readonly end?: number | undefined };

function searchable(text: string): string {
  return text.normalize("NFKC").toLowerCase().replace(/[\p{P}\p{Z}\s]/gu, "");
}

export function readTranscript(path: string): Promise<readonly TranscriptWord[]> {
  return readFile(path, "utf8").then((raw) => {
    const parsed = JSON.parse(raw) as { format?: string; passages?: { words?: Record<string, unknown>[] }[] };
    assert(parsed?.format === "hypit.transcript@1" && Array.isArray(parsed.passages), "invalid_input", `${path}: expected hypit.transcript@1`);
    return parsed.passages!.flatMap((passage) =>
      (passage.words ?? []).map((word) => ({
        text: String(word.text ?? ""),
        ...(typeof word.start_seconds === "number" ? { start: word.start_seconds } : {}),
        ...(typeof word.end_seconds === "number" ? { end: word.end_seconds } : {}),
      })),
    );
  });
}

export function phraseRanges(words: readonly TranscriptWord[], text: string): readonly { start: number; end: number }[] {
  const query = searchable(text);
  assert(query.length > 0, "invalid_input", "around needs a word or phrase");
  const matches: { start: number; end: number }[] = [];
  for (let first = 0; first < words.length; first += 1) {
    if (searchable(words[first]!.text).length === 0) continue;
    let joined = "";
    for (let last = first; last < words.length && joined.length < query.length; last += 1) {
      joined += searchable(words[last]!.text);
      if (joined !== query) continue;
      const start = words[first]!.start;
      const end = words[last]!.end;
      assert(start !== undefined && end !== undefined, "invalid_input",
        "the phrase has missing boundary times; choose start/end explicitly");
      matches.push({ start, end });
    }
  }
  return matches;
}

/** The window a tile covers: explicit range or around the nth occurrence. */
export function resolveTileWindow(input: {
  readonly start?: number | undefined;
  readonly end?: number | undefined;
  readonly transcriptWords?: readonly TranscriptWord[] | undefined;
  readonly around?: string | undefined;
  readonly occurrence?: number | undefined;
  readonly padding?: number | undefined;
}): { start: number; end: number } {
  if (input.around !== undefined) {
    assert(input.transcriptWords !== undefined, "invalid_input", "around requires a transcript");
    const ranges = phraseRanges(input.transcriptWords, input.around);
    const occurrence = input.occurrence ?? 1;
    assert(occurrence >= 1 && occurrence <= ranges.length, "occurrence_not_found",
      `occurrence ${occurrence} of the phrase not found (${ranges.length} total)`);
    const chosen = ranges[occurrence - 1]!;
    const padding = input.padding ?? 0;
    return { start: Math.max(0, chosen.start - padding), end: chosen.end + padding };
  }
  assert(input.start !== undefined && input.end !== undefined && input.end > input.start,
    "invalid_input", "tile needs start/end or around");
  return { start: input.start, end: input.end };
}

// ---------------------------------------------------------------------------
// boundaries — scene-cut scores via ffmpeg scene detection (deterministic)
// ---------------------------------------------------------------------------

export type VisualBoundary = { readonly at: number; readonly score: number };

export async function visualBoundaries(input: {
  readonly source: string;
  readonly start?: number | undefined;
  readonly end?: number | undefined;
  readonly threshold?: number | undefined;
}): Promise<readonly VisualBoundary[]> {
  const threshold = input.threshold ?? 0.3;
  const args = ["-hide_banner", "-nostats"];
  if (input.start !== undefined) args.push("-ss", String(input.start));
  if (input.end !== undefined) args.push("-to", String(input.end));
  // no shell here: quotes stay literal, and the comma inside gte() must be
  // escaped for the filtergraph parser (it otherwise splits filters).
  args.push("-i", input.source, "-vf", `select=gte(scene\\,${threshold.toFixed(2)}),metadata=print:file=-`, "-f", "null", "-");
  const { stdout } = await run("ffmpeg", args).catch((error) => {
    throw new MediaToolError("boundaries_failed", `ffmpeg scene detection failed: ${String(error.stderr ?? error.message).slice(-400)}`);
  });
  const boundaries: VisualBoundary[] = [];
  let pendingTime: number | null = null;
  for (const line of stdout.split("\n")) {
    // metadata=print emits "frame:N pts:P pts_time:T" — pts_time is mid-line.
    const timeMatch = /pts_time:([\d.]+)/u.exec(line);
    if (timeMatch !== null) {
      pendingTime = Number(timeMatch[1]);
      continue;
    }
    const scoreMatch = /^lavfi\.scene_score=([\d.]+)/u.exec(line.trim());
    if (scoreMatch !== null && pendingTime !== null) {
      boundaries.push({ at: Number(pendingTime.toFixed(3)), score: Number(Number(scoreMatch[1]).toFixed(3)) });
      pendingTime = null;
    }
  }
  return boundaries;
}

// ---------------------------------------------------------------------------
// grids (ffmpeg hstack/vstack; metadata in the result, not burned labels)
// ---------------------------------------------------------------------------

export type GridResult = {
  readonly grid: string;
  readonly frames: readonly FrameSample[];
  readonly columns: number;
};

export async function composeGrid(frames: readonly FrameSample[], to: string, columns = 3): Promise<GridResult> {
  assert(frames.length > 0, "invalid_input", "a grid needs at least one frame");
  assert(columns >= 1, "invalid_input", "columns must be >= 1");
  assert(!await stat(to).then(() => true, () => false), "target_exists", `destination ${to} already exists`);
  await mkdir(dirname(to), { recursive: true });
  const inputs = frames.flatMap((frame) => ["-i", frame.file]);
  const cellWidth = 480;
  const rows = Math.ceil(frames.length / columns);
  const parts: string[] = [];
  frames.forEach((_, index) => {
    parts.push(`[${index}:v]scale=${cellWidth}:-1[c${index}]`);
  });
  const rowRefs: string[] = [];
  for (let row = 0; row < rows; row += 1) {
    const start = row * columns;
    const inRow = Math.min(columns, frames.length - start);
    const cellRefs = Array.from({ length: inRow }, (_, index) => `[c${start + index}]`);
    const rowRef = `[r${row}]`;
    parts.push(inRow === 1 ? `${cellRefs[0]}null${rowRef}` : `${cellRefs.join("")}hstack=inputs=${inRow}${rowRef}`);
    rowRefs.push(rowRef);
  }
  parts.push(rowRefs.length === 1 ? `${rowRefs[0]}null[outv]` : `${rowRefs.join("")}vstack=inputs=${rowRefs.length}[outv]`);
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y", ...inputs,
    "-filter_complex", parts.join(";"), "-map", "[outv]", "-frames:v", "1", to]);
  return { grid: to, frames, columns };
}

// ---------------------------------------------------------------------------
// pinned yt-dlp environment (prepare-fetch / fetch)
// ---------------------------------------------------------------------------

export type YtDlpEnvironment = {
  readonly serviceProject: string;
  readonly version: string;
  readonly environment: string;
  readonly executable: string;
};

/** Resolve the pinned environment from the distribution's services/yt-dlp. */
export function videoDownloadEnvironment(distributionRoot: string, programsRoot: string): YtDlpEnvironment {
  const serviceProject = resolve(distributionRoot, "services", "yt-dlp");
  const pyproject = readFileSync(join(serviceProject, "pyproject.toml"), "utf8");
  const version = /"yt-dlp(?:\[[^\]]+\])?==([^"]+)"/u.exec(pyproject)?.[1];
  assert(version !== undefined, "environment_invalid", "yt-dlp service must declare an exact pinned version");
  const environment = resolve(programsRoot, "yt-dlp", version, ".venv");
  return { serviceProject, version, environment, executable: join(environment, "bin", "yt-dlp") };
}

function release(value: string): string {
  return value.trim().split(".").map((part) => part.replace(/^0+(?=\d)/u, "")).join(".");
}

export function requireVideoDownload(environment: YtDlpEnvironment): string {
  const result = spawnSyncVersion(environment.executable);
  if (result.status !== 0 || release(result.stdout) !== release(environment.version)) {
    throw new MediaToolError("environment_not_prepared",
      `yt-dlp ${environment.version} is not ready at ${environment.executable}; run prepare-fetch first. ${result.stderr.trim()}`);
  }
  return environment.executable;
}

function spawnSyncVersion(executable: string): { status: number | null; stdout: string; stderr: string } {
  const result = spawnSync(executable, ["--ignore-config", "--version"], { encoding: "utf8", timeout: 15_000 });
  return { status: result.status, stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
}

/** prepare-fetch: uv sync --frozen into the pinned environment, with logging. */
export async function prepareVideoDownload(environment: YtDlpEnvironment): Promise<{ version: string; executable: string }> {
  const result = await new Promise<{ status: number | null; stderr: string }>((resolvePromise) => {
    const child = spawn("uv", ["sync", "--project", environment.serviceProject, "--frozen", "--no-dev"], {
      env: { ...process.env, UV_PROJECT_ENVIRONMENT: environment.environment },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += String(chunk);
    });
    child.stdout.resume();
    child.on("close", (status) => resolvePromise({ status, stderr }));
    child.on("error", () => resolvePromise({ status: null, stderr: "uv is not installed" }));
  });
  assert(result.status === 0, "prepare_failed", `uv sync failed (${result.status}): ${result.stderr.slice(-400)}`);
  return { version: environment.version, executable: requireVideoDownload(environment) };
}

const redirectStatuses = new Set([301, 302, 303, 307, 308]);

/**
 * Policy-checked redirect prefix: follow at most 5 hops manually, re-running
 * the full URL policy on each Location. Returns the final URL yt-dlp receives.
 */
export async function resolveFetchUrl(rawUrl: string): Promise<string> {
  try {
    return await resolveFetchUrlInner(rawUrl);
  } catch (error) {
    if (error instanceof UrlPolicyError) throw new MediaToolError(error.code, error.message);
    throw error;
  }
}

async function resolveFetchUrlInner(rawUrl: string): Promise<string> {
  let current = assertFetchableUrl(rawUrl);
  for (let hop = 0; hop < 5; hop += 1) {
    const response = await fetch(current, { redirect: "manual", method: "GET", headers: { range: "bytes=0-0" } })
      .catch(() => { throw new MediaToolError("fetch_unreachable", `cannot reach ${current.toString()}`); });
    await response.body?.cancel().catch(() => {});
    if (redirectStatuses.has(response.status)) {
      const location = response.headers.get("location");
      assert(location !== null, "redirect_invalid", `redirect without Location from ${current.toString()}`);
      try {
        current = checkRedirectLocation(new URL(location, current).toString());
      } catch (error) {
        if (error instanceof UrlPolicyError) throw new MediaToolError(error.code, error.message);
        throw error;
      }
      continue;
    }
    if (response.status >= 200 && response.status < 400) {
      return current.toString();
    }
    throw new MediaToolError("fetch_unreachable", `${current.toString()} answered ${response.status}`);
  }
  throw new MediaToolError("too_many_redirects", "more than 5 redirect hops");
}

/**
 * Fetch one linked video into the broker's resources root. The output file
 * name is server-controlled (safeFetchTargetName); staging is removed on any
 * failure; the result is probed and registered as a resource handle.
 */
export async function downloadWithYtDlp(url: string, target: string, executable: string): Promise<void> {
  const container = target.split(".").at(-1)!.toLowerCase();
  assert(["mp4", "mkv", "webm", "mov"].includes(container), "invalid_input", `${target} must end in .mp4, .mkv, .webm or .mov`);
  const work = await mkdtemp(join(tmpdir(), "hypit-fetch-"));
  try {
    await run(executable, [
      "--ignore-config", "--no-update", "--no-remote-components", "--no-plugin-dirs",
      "--no-js-runtimes", "--js-runtimes", `node:${process.execPath}`,
      // Direct connection always: urllib would otherwise honor macOS *system*
      // proxies and route even loopback fixtures through the gateway (502), and
      // a machine proxy would also see/modify broker fetch traffic.
      "--proxy", "",
      "--no-playlist", "--no-progress", "--quiet",
      "--format", "bv*+ba/b",
      "--merge-output-format", container,
      "--format-sort", "res:1080,vcodec:h264",
      "--output", join(work, "video.%(ext)s"),
      url,
    ], 900_000);
    const finished = (await readdir(work)).filter((name) => !name.endsWith(".part"));
    const [file] = finished.sort();
    assert(file !== undefined, "fetch_empty", "yt-dlp reported success but wrote no file");
    await mkdir(dirname(target), { recursive: true });
    try {
      await rename(join(work, file!), target);
    } catch (cause) {
      if ((cause as NodeJS.ErrnoException).code !== "EXDEV") throw cause;
      await copyFile(join(work, file!), target);
    }
  } catch (error) {
    await rm(target, { force: true });
    if (error instanceof MediaToolError) throw error;
    throw new MediaToolError("fetch_failed", `yt-dlp could not fetch ${url}: ${String((error as { stderr?: string; message?: string }).stderr ?? (error as Error).message ?? error).slice(-400)}`);
  } finally {
    await rm(work, { recursive: true, force: true }).catch(() => {});
  }
}

export async function fetchVideo(input: {
  readonly url: string;
  readonly fileName: string;
  readonly outputRoot: string;
  readonly environment: YtDlpEnvironment;
  readonly registry: HandleRegistry;
  readonly projectId: string | null;
}): Promise<{ readonly handle: string; readonly sha256: string; readonly sizeBytes: number; readonly probe: MediaProbeResult; readonly finalUrl: string }> {
  const finalUrl = await resolveFetchUrl(input.url);
  const fileName = safeFetchTargetName(input.fileName);
  assert(/\.(mp4|mkv|webm|mov)$/u.test(fileName), "invalid_input", "fetch target must end in .mp4/.mkv/.webm/.mov");
  const executable = requireVideoDownload(input.environment);
  const target = join(input.outputRoot, fileName);
  try {
    await downloadWithYtDlp(finalUrl, target, executable);
  } catch (error) {
    if (error instanceof MediaToolError) throw error;
    if (error instanceof UrlPolicyError) throw new MediaToolError(error.code, error.message);
    throw error;
  }
  const probe = await probeMedia(target);
  const record = await registerResource(input.registry, {
    absolutePath: target,
    projectId: input.projectId,
    mediaType: `video/${fileName.split(".").at(-1)}`,
    role: "fetch",
  });
  return { handle: record.handle, sha256: record.sha256, sizeBytes: record.sizeBytes, probe, finalUrl };
}

export function mediaHandleRegistry(store: HandleRegistry["store"], resourcesRoot: string, projectsRoot: string): HandleRegistry {
  return openHandleRegistry([resourcesRoot, projectsRoot], join(resourcesRoot, "handles.index.json"), store);
}

export type MediaToolContext = {
  readonly registry: HandleRegistry;
  readonly distributionRoot: string;
  readonly programsRoot: string;
  readonly resourcesRoot: string;
  /** Resolve payload.source ({handle} or {projectId,path}) to an absolute path. */
  readonly resolveSource: (payload: Record<string, unknown>) => Promise<string>;
};

export function isMediaTool(value: string): value is MediaTool {
  return (mediaTools as readonly string[]).includes(value);
}

/**
 * One entry point for the dispatcher: `media.<tool>` payloads. Every source is
 * resolved through handles/workspace paths by the caller; every artifact is
 * registered and returned as a handle — raw local paths never leave the broker.
 */
export async function runMediaTool(
  tool: MediaTool,
  payload: Record<string, unknown>,
  context: MediaToolContext,
): Promise<Record<string, unknown>> {
  const numberOrUndefined = (key: string): number | undefined => {
    const value = payload[key];
    if (value === undefined) return undefined;
    assert(typeof value === "number" && Number.isFinite(value), "invalid_input", `${key} must be a number`);
    return value;
  };
  switch (tool) {
    case "media.probe": {
      const source = await context.resolveSource(payload);
      const probe = await probeMedia(source);
      return { probe: { ...probe } };
    }
    case "media.cut": {
      const source = await context.resolveSource(payload);
      const to = await newArtifact(context, artifactName(payload, "cut", "mp4"));
      const result = await cutClip({
        source,
        to,
        at: numberOrUndefined("at"),
        start: numberOrUndefined("start"),
        end: numberOrUndefined("end"),
      });
      return await registerArtifact(context, to, payload, {
        output: result.output,
        start: result.start,
        end: result.end,
        durationSeconds: result.durationSeconds,
      }, "video/mp4");
    }
    case "media.frames": {
      const source = await context.resolveSource(payload);
      const times = payload.times;
      assert(Array.isArray(times) && times.every((time) => typeof time === "number"), "invalid_input",
        "frames needs times[] (explicit) — use tiles for interval sampling");
      const dir = join(context.resourcesRoot, "frames", `${Date.now().toString(36)}`);
      const result = await extractFrames({
        source,
        toDir: dir,
        times: times as number[],
        offset: numberOrUndefined("offset"),
        limit: numberOrUndefined("limit"),
      });
      const registered = await Promise.all(result.frames.map((frame) =>
        registerResource(context.registry, {
          absolutePath: frame.file,
          projectId: projectIdOf(payload),
          mediaType: "image/png",
          role: "frame",
        })));
      return {
        frames: result.frames.map((frame, index) => ({ handle: registered[index]!.handle, timestampSeconds: frame.timestampSeconds })),
        totalTimes: result.totalTimes,
      };
    }
    case "media.tile": {
      const source = await context.resolveSource(payload);
      const transcript = await transcriptWordsOf(payload);
      const window = resolveTileWindow({
        start: numberOrUndefined("start"),
        end: numberOrUndefined("end"),
        transcriptWords: transcript,
        around: typeof payload.around === "string" ? payload.around : undefined,
        occurrence: numberOrUndefined("occurrence"),
        padding: numberOrUndefined("padding"),
      });
      return await tileOne(context, payload, source, window);
    }
    case "media.tiles": {
      const source = await context.resolveSource(payload);
      const ranges = payload.ranges;
      assert(Array.isArray(ranges) && ranges.every((range) =>
        typeof range === "object" && range !== null
        && typeof (range as { start?: unknown }).start === "number"
        && typeof (range as { end?: unknown }).end === "number"), "invalid_input",
        "tiles needs ranges[] of {start,end}");
      const page = numberOrUndefined("page") ?? 1;
      const pageSize = numberOrUndefined("pageSize") ?? 10;
      assert(page >= 1 && pageSize >= 1 && pageSize <= 50, "invalid_input", "page >= 1 and 1 <= pageSize <= 50");
      const window = (ranges as { start: number; end: number }[]).slice((page - 1) * pageSize, page * pageSize);
      const grids = [];
      for (const range of window) {
        grids.push(await tileOne(context, payload, source, range));
      }
      return { grids, page, pageSize, totalRanges: (ranges as unknown[]).length };
    }
    case "media.boundaries": {
      const source = await context.resolveSource(payload);
      const boundaries = await visualBoundaries({
        source,
        start: numberOrUndefined("start"),
        end: numberOrUndefined("end"),
        threshold: numberOrUndefined("threshold"),
      });
      return { boundaries };
    }
    case "media.prepare-fetch": {
      const url = payload.url;
      assert(typeof url === "string", "invalid_input", "prepare-fetch needs url");
      assertFetchableUrl(url); // plan fails fast on disallowed URLs; no network here
      const environment = videoDownloadEnvironment(context.distributionRoot, context.programsRoot);
      const prepared = await prepareVideoDownload(environment);
      return { prepared: true, version: prepared.version };
    }
    case "media.fetch": {
      const url = payload.url;
      const fileName = payload.fileName;
      assert(typeof url === "string" && typeof fileName === "string", "invalid_input", "fetch needs url and fileName");
      const environment = videoDownloadEnvironment(context.distributionRoot, context.programsRoot);
      const result = await fetchVideo({
        url,
        fileName,
        outputRoot: join(context.resourcesRoot, "fetch"),
        environment,
        registry: context.registry,
        projectId: projectIdOf(payload),
      });
      return {
        handle: result.handle,
        sha256: result.sha256,
        sizeBytes: result.sizeBytes,
        finalUrl: result.finalUrl,
        probe: result.probe,
      };
    }
  }
}

async function transcriptWordsOf(payload: Record<string, unknown>): Promise<readonly TranscriptWord[] | undefined> {
  const path = payload.transcript;
  if (path === undefined) return undefined;
  assert(typeof path === "string", "invalid_input", "transcript must be a path");
  const words = await readTranscript(path);
  return words;
}

function projectIdOf(payload: Record<string, unknown>): string | null {
  return typeof payload.projectId === "string" ? (payload.projectId as string) : null;
}

async function newArtifact(context: MediaToolContext, name: string): Promise<string> {
  await mkdir(join(context.resourcesRoot, "artifacts"), { recursive: true });
  return join(context.resourcesRoot, "artifacts", `${Date.now().toString(36)}-${name}`);
}

function artifactName(payload: Record<string, unknown>, kind: string, ext: string): string {
  const suffix = typeof payload.name === "string" && /^[a-z0-9-]{1,40}$/u.test(payload.name as string)
    ? `-${payload.name as string}`
    : "";
  return `${kind}${suffix}.${ext}`;
}

async function registerArtifact(
  context: MediaToolContext,
  absolutePath: string,
  payload: Record<string, unknown>,
  facts: Record<string, unknown>,
  mediaType: string,
): Promise<Record<string, unknown>> {
  const record = await registerResource(context.registry, {
    absolutePath,
    projectId: projectIdOf(payload),
    mediaType,
    role: "media-tool",
  });
  return { handle: record.handle, sha256: record.sha256, sizeBytes: record.sizeBytes, ...facts };
}

async function tileOne(
  context: MediaToolContext,
  payload: Record<string, unknown>,
  source: string,
  window: { start: number; end: number },
): Promise<Record<string, unknown>> {
  const every = typeof payload.every === "number" && payload.every > 0 ? (payload.every as number) : undefined;
  const count = Math.max(4, Math.min(9, Math.round((window.end - window.start) * 1.5)));
  const times = every === undefined
    ? tileSampleTimes(window.start, window.end, count)
    : intervalSamples(window.start, window.end, every);
  const dir = join(context.resourcesRoot, "tiles", `${Date.now().toString(36)}`);
  const extracted = await extractFrames({ source, toDir: dir, times });
  const gridPath = `${dir}/grid.png`;
  const grid = await composeGrid(extracted.frames, gridPath, typeof payload.columns === "number" ? payload.columns as number : 3);
  const record = await registerResource(context.registry, {
    absolutePath: gridPath,
    projectId: projectIdOf(payload),
    mediaType: "image/png",
    role: "tile",
  });
  return {
    handle: record.handle,
    window,
    frames: grid.frames.map((frame) => ({ file: frame.file.split("/").at(-1), timestampSeconds: frame.timestampSeconds })),
    columns: grid.columns,
  };
}
