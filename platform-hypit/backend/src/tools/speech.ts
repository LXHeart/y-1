// speech.ts — C107-06 (task-107) speech tools over the managed programs (K08).
//
// speech.transcribe: canonical 16 kHz mono PCM s16 evidence (ffmpeg extraction
// when the source is not already canonical — the one conversion, done openly),
// staged inside the WhisperX input roots, POST /transcribe, then the native
// @hypit/whisperx seconds→sample interpretation. Silence yields an explicit
// empty result; language comes from the request, never hard-coded.
// speech.measure: the native @hypit/estimate policy/unit/rate stack; results
// are estimates and stay marked as such (never confused with ffprobe truth).
// speech.align: word evidence ↔ script segments through the native
// @hypit/speech-alignment algorithms (normalize/alignCharacters/alignWordGroups);
// compensation and Take adjustment keep their upstream entries untouched.
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFile, mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

import type { CommandStore } from "../commands/store.ts";
import type { HandleRegistry } from "../resources/handles.ts";
import { registerResource } from "../resources/handles.ts";
import { PROGRAM_CATALOG, type ProgramId } from "../programs/catalog.ts";
import { whisperxHealth } from "../programs/health.ts";

export const speechTools = ["speech.transcribe", "speech.measure", "speech.align"] as const;
export type SpeechTool = typeof speechTools[number];

export function isSpeechTool(value: string): value is SpeechTool {
  return (speechTools as readonly string[]).includes(value);
}

export class SpeechToolError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "SpeechToolError";
  }
}

function assert(condition: unknown, code: string, message: string): asserts condition {
  if (!condition) throw new SpeechToolError(code, message);
}

export type SpeechToolContext = {
  readonly registry: HandleRegistry;
  readonly programsRoot: string;
  readonly resolveSource: (payload: Record<string, unknown>) => Promise<string>;
};

const SAMPLE_RATE = 16_000;

type WavShape = { readonly codec: number; readonly channels: number; readonly sampleRate: number; readonly bits: number; readonly dataBytes: number };

function wavShape(bytes: Uint8Array): WavShape | undefined {
  const fourCc = (offset: number) => String.fromCharCode(...bytes.subarray(offset, offset + 4));
  if (bytes.byteLength < 44 || fourCc(0) !== "RIFF" || fourCc(8) !== "WAVE") return undefined;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let offset = 12;
  let format: Omit<WavShape, "dataBytes"> | undefined;
  let dataBytes: number | undefined;
  while (offset + 8 <= bytes.byteLength) {
    const name = fourCc(offset);
    const size = view.getUint32(offset + 4, true);
    const body = offset + 8;
    if (body + size > bytes.byteLength) return undefined;
    if (name === "fmt " && size >= 16) {
      format = { codec: view.getUint16(body, true), channels: view.getUint16(body + 2, true), sampleRate: view.getUint32(body + 4, true), bits: view.getUint16(body + 14, true) };
    } else if (name === "data") {
      dataBytes = size;
    }
    offset = body + size + (size % 2);
  }
  return format === undefined || dataBytes === undefined ? undefined : { ...format, dataBytes };
}

function isCanonical(shape: WavShape | undefined): shape is WavShape {
  return shape !== undefined && shape.codec === 1 && shape.channels === 1
    && shape.sampleRate === SAMPLE_RATE && shape.bits === 16;
}

function runFfmpeg(args: readonly string[], timeoutMs = 120_000): Promise<void> {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn("ffmpeg", [...args], { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      rejectPromise(new SpeechToolError("extract_failed", "ffmpeg audio extraction timed out"));
    }, timeoutMs);
    child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString("utf8"); });
    child.on("error", (error) => { clearTimeout(timer); rejectPromise(error); });
    child.on("exit", (code) => {
      clearTimeout(timer);
      if (code === 0) resolvePromise();
      else rejectPromise(new SpeechToolError("extract_failed", stderr.split("\n").at(-2) ?? `ffmpeg exited ${code}`));
    });
  });
}

/** The one evidence conversion: passthrough when already canonical, ffmpeg otherwise. */
async function canonicalEvidence(source: string): Promise<{ readonly path: string; readonly sampleFrames: number; readonly extracted: boolean }> {
  const bytes = new Uint8Array(await readFile(source));
  const shape = wavShape(bytes);
  if (isCanonical(shape)) return { path: source, sampleFrames: shape.dataBytes / 2, extracted: false };
  const scratch = await mkdtemp(join(tmpdir(), "hypit-speech-"));
  try {
    const target = join(scratch, "evidence.wav");
    await runFfmpeg(["-hide_banner", "-loglevel", "error", "-y", "-i", source, "-map", "0:a:0", "-vn",
      "-ac", "1", "-ar", String(SAMPLE_RATE), "-c:a", "pcm_s16le", "-bitexact", target]);
    const converted = new Uint8Array(await readFile(target));
    const final = wavShape(converted);
    assert(isCanonical(final), "extract_failed", "ffmpeg did not produce canonical 16 kHz mono PCM audio");
    const stable = join(scratch, "canonical.wav");
    await copyFile(target, stable);
    return { path: stable, sampleFrames: final.dataBytes / 2, extracted: true };
  } finally {
    // scratch removed by caller path below when staged
  }
}

async function stageEvidence(context: SpeechToolContext, source: string): Promise<{ readonly path: string; readonly sampleFrames: number; readonly extracted: boolean; readonly scratch: string | null }> {
  const evidence = await canonicalEvidence(source);
  if (!evidence.extracted) {
    return { ...evidence, scratch: null };
  }
  const staged = join(context.programsRoot, "whisperx.local", "input");
  await mkdir(staged, { recursive: true });
  const bytes = await readFile(evidence.path);
  const name = `${createHash("sha256").update(bytes).digest("hex").slice(0, 24)}.wav`;
  const target = join(staged, name);
  await writeFile(target, bytes);
  return { path: target, sampleFrames: evidence.sampleFrames, extracted: true, scratch: dirname(evidence.path) };
}

type WireWord = { readonly word?: unknown; readonly text?: unknown; readonly start?: unknown; readonly end?: unknown; readonly score?: unknown };
type WireSegment = { readonly start?: unknown; readonly end?: unknown; readonly words?: readonly WireWord[] };
type WireResponse = { readonly language?: unknown; readonly segments?: readonly WireSegment[] };

async function whisperxTranscribe(context: SpeechToolContext, audioPath: string, language: string | null): Promise<WireResponse> {
  const spec = PROGRAM_CATALOG["whisperx.local"];
  const probe = await whisperxHealth(spec);
  assert(probe.state === "ready", "program_not_ready",
    probe.state === "mismatch"
      ? `whisperx.local 服务身份不匹配：${probe.detail ?? "identity differs"}`
      : `whisperx.local 未就绪（${probe.detail ?? "service down"}）；先执行 programs prepare/up。缺哪类资源见 programs status/logs。`);
  const response = await fetch(`http://${spec.loopback.host}:${spec.loopback.port}/transcribe`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ audio_path: audioPath, ...(language === null ? {} : { language }) }),
    signal: AbortSignal.timeout(10 * 60_000),
  });
  const body = await response.text();
  assert(response.ok, "transcribe_failed", `/transcribe answered ${response.status}: ${body.slice(0, 300)}`);
  try {
    return JSON.parse(body) as WireResponse;
  } catch {
    throw new SpeechToolError("transcribe_failed", "WhisperX response was not JSON");
  }
}

/** Seconds → 16 kHz samples, the same clamp the upstream interpreter applies. */
function sampleWindow(startSec: unknown, endSec: unknown, sampleFrames: number): { startSample: number; endSampleExclusive: number } | undefined {
  if (typeof startSec !== "number" || typeof endSec !== "number" || !Number.isFinite(startSec) || !Number.isFinite(endSec)) return undefined;
  if (startSec < 0 || endSec < startSec) return undefined;
  const startSample = Math.round(startSec * SAMPLE_RATE);
  const endSampleExclusive = Math.round(endSec * SAMPLE_RATE);
  if (startSample > sampleFrames || endSampleExclusive > sampleFrames) return undefined;
  return { startSample, endSampleExclusive };
}

export type StandardWord = { readonly text: string; readonly startSample?: number; readonly endSampleExclusive?: number; readonly score?: number };
export type StandardPassage = { readonly startSample?: number; readonly endSampleExclusive?: number; readonly words: readonly StandardWord[] };

/** Normalize the wire response once into the standard evidence shape (K08 transcribe row). */
export function standardEvidence(response: WireResponse, sampleFrames: number): { language: string; passages: StandardPassage[] } {
  const language = typeof response.language === "string" && response.language.length > 0 ? response.language : "und";
  const segments = Array.isArray(response.segments) ? response.segments : [];
  const passages: StandardPassage[] = [];
  for (const segment of segments) {
    const words: StandardWord[] = [];
    for (const raw of segment.words ?? []) {
      const text = typeof raw.text === "string" ? raw.text.trim() : typeof raw.word === "string" ? raw.word.trim() : "";
      if (text.length === 0) continue;
      const window = sampleWindow(raw.start, raw.end, sampleFrames);
      const score = typeof raw.score === "number" && Number.isFinite(raw.score) && raw.score >= 0 && raw.score <= 1 ? raw.score : undefined;
      words.push({ text, ...(window ?? {}), ...(score === undefined ? {} : { score }) });
    }
    if (words.length === 0) continue;
    const window = sampleWindow(segment.start, segment.end, sampleFrames);
    passages.push({ ...(window ?? {}), words });
  }
  return { language, passages };
}

/** Observation check: word times must be ordered and inside the evidence audio. */
export function evidenceDiagnostics(passages: readonly StandardPassage[], sampleFrames: number): string[] {
  const diagnostics: string[] = [];
  for (const [index, passage] of passages.entries()) {
    let previousEnd = -1;
    for (const word of passage.words) {
      if (word.startSample === undefined || word.endSampleExclusive === undefined) {
        diagnostics.push(`passage ${index}: word "${word.text}" has no acoustic window`);
        continue;
      }
      if (word.startSample < previousEnd) {
        diagnostics.push(`passage ${index}: word "${word.text}" starts before the previous word ends (not monotonic)`);
      }
      if (word.endSampleExclusive > sampleFrames) {
        diagnostics.push(`passage ${index}: word "${word.text}" ends beyond the evidence audio`);
      }
      previousEnd = word.endSampleExclusive;
    }
  }
  return diagnostics;
}

export async function runSpeechTool(
  tool: SpeechTool,
  payload: Record<string, unknown>,
  context: SpeechToolContext,
): Promise<Record<string, unknown>> {
  switch (tool) {
    case "speech.transcribe":
      return await transcribeTool(payload, context);
    case "speech.measure":
      return await measureTool(payload);
    case "speech.align":
      return await alignTool(payload);
  }
}

async function transcribeTool(payload: Record<string, unknown>, context: SpeechToolContext): Promise<Record<string, unknown>> {
  const languageRaw = payload.language;
  assert(languageRaw === undefined || typeof languageRaw === "string", "invalid_input", "language must be a string when present");
  const language = typeof languageRaw === "string" && languageRaw.length > 0 ? languageRaw : null;
  const source = await context.resolveSource(payload);
  const staged = await stageEvidence(context, source);
  try {
    const response = await whisperxTranscribe(context, staged.path, language);
    const standard = standardEvidence(response, staged.sampleFrames);
    const diagnostics = evidenceDiagnostics(standard.passages, staged.sampleFrames);
    const evidenceJson = JSON.stringify({ format: "y1.hypit.speech-evidence@1", sampleFrames: staged.sampleFrames, ...standard }, undefined, 2);
    // Evidence artifacts live inside a registry-allowed root (containment is
    // enforced at registration); the first allowed root is the resources root.
    const artifactDir = join(context.registry.allowedRoots[0] ?? join(context.programsRoot, "resources"), "speech-evidence");
    await mkdir(artifactDir, { recursive: true });
    const digest = createHash("sha256").update(evidenceJson).digest("hex");
    const artifactPath = join(artifactDir, `${digest.slice(0, 24)}.json`);
    await writeFile(artifactPath, evidenceJson, "utf8");
    const record = await registerResource(context.registry, {
      absolutePath: artifactPath,
      projectId: typeof payload.projectId === "string" ? payload.projectId : null,
      mediaType: "application/json",
      role: "transcript",
    });
    return {
      language: standard.language,
      sampleFrames: staged.sampleFrames,
      durationSec: staged.sampleFrames / SAMPLE_RATE,
      extracted: staged.extracted,
      passages: standard.passages,
      // 空语音显式空结果：passages=[] 是「测过且没有语音」，不是失败。
      diagnostics,
      evidenceHandle: record.handle,
    };
  } finally {
    if (staged.scratch !== null) await rm(staged.scratch, { recursive: true, force: true }).catch(() => {});
  }
}

async function measureTool(payload: Record<string, unknown>): Promise<Record<string, unknown>> {
  const text = payload.text;
  assert(typeof text === "string" && text.trim().length > 0, "invalid_input", "text is required");
  const language = payload.language;
  const pace = payload.pace;
  const rate = payload.rate;
  const rounding = payload.rounding;
  const padding = payload.padding;
  assert(pace === undefined || rate === undefined, "invalid_input", "measure takes either pace or rate, not both");
  const estimate = await import("@hypit/estimate");
  const textModule = await import("@hypit/text");
  const policy = estimate.speechEstimatePolicyFromAttributes({
    language: typeof language === "string" ? language : "auto",
    ...(rate === undefined ? { pace: typeof pace === "string" ? pace : "normal" } : {}),
    ...(rate !== undefined ? { rate: rate as number } : {}),
    rounding: typeof rounding === "string" ? rounding : "none",
    ...(padding === undefined ? {} : { paddingSec: padding as number }),
  }, "hypit speech.measure");
  const resolvedLanguage = estimate.resolveSpeechEstimateLanguage(text, policy.language);
  const units = estimate.countSpeechEstimateUnits(text, resolvedLanguage);
  const seconds = estimate.estimateSpeechDuration(textModule.sealText(text), policy);
  const secondsValue = typeof seconds === "number" ? seconds : (seconds as { value: number }).value;
  const resolvedRate = estimate.resolveSpeechEstimateRate(policy, resolvedLanguage);
  return {
    estimated: true,
    basis: "speech-unit rate model (not measured audio duration)",
    characters: text.length,
    units,
    language: resolvedLanguage,
    rate: resolvedRate,
    policy,
    seconds: secondsValue,
  };
}

// ---------------------------------------------------------------------------
// speech.align: native @hypit/speech-alignment over broker-level segments.
// ---------------------------------------------------------------------------

type AlignToken = { readonly id: string; readonly text: string; readonly normalized?: string };
type AlignWord = { readonly text: string; readonly startSample?: number; readonly endSampleExclusive?: number };

/** Word-split for latin scripts, per-character for CJK — the domains the native normalizer covers. */
function segmentTokens(text: string, segmentId: string): AlignToken[] {
  const trimmed = text.trim();
  if (trimmed.length === 0) return [];
  const isCjk = /[\p{Script=Han}\u3040-\u30ff]/u.test(trimmed);
  const pieces = isCjk
    ? Array.from(trimmed.matchAll(/[\p{Script=Han}\u3040-\u30ff]|[^\s]/gu), (match) => match[0])
    : trimmed.split(/\s+/u);
  return pieces
    .filter((piece) => piece.length > 0)
    .map((piece, index) => ({ id: `${segmentId}#${index}`, text: piece }));
}

async function alignTool(payload: Record<string, unknown>): Promise<Record<string, unknown>> {
  const evidence = payload.evidence;
  assert(typeof evidence === "object" && evidence !== null, "invalid_input", "evidence is required (speech.transcribe result)");
  const script = payload.script;
  assert(Array.isArray(script), "invalid_input", "script must be an array of {id, text}");
  const alignment = await import("@hypit/speech-alignment");
  const normalize = alignment.normalizeForAlignment as (value: string) => string;
  const words: AlignWord[] = [];
  for (const passage of (evidence as { passages?: unknown }).passages as unknown as Array<{ words?: unknown }> | undefined ?? []) {
    for (const word of Array.isArray(passage.words) ? passage.words : []) {
      const candidate = word as AlignWord;
      if (typeof candidate.text === "string" && candidate.text.trim().length > 0) {
        words.push({ text: candidate.text.trim(), ...(candidate.startSample === undefined ? {} : { startSample: candidate.startSample }), ...(candidate.endSampleExclusive === undefined ? {} : { endSampleExclusive: candidate.endSampleExclusive }) });
      }
    }
  }
  const segments: Array<Record<string, unknown>> = [];
  const counters = { exact: 0, split: 0, merge: 0, replacement: 0, "source-omission": 0, "evidence-insertion": 0 };
  for (const entry of script) {
    assert(typeof entry === "object" && entry !== null && typeof (entry as { id?: unknown }).id === "string"
      && typeof (entry as { text?: unknown }).text === "string", "invalid_input", "each script segment needs id and text");
    const segmentId = (entry as { id: string }).id;
    const segmentText = (entry as { text: string }).text;
    const tokens = segmentTokens(segmentText, segmentId);
    const narrativeTokens = tokens.map((token) => ({
      id: token.id, segmentId, startAnchorId: `${token.id}:s`, endAnchorId: `${token.id}:e`,
      text: token.text, normalized: normalize(token.text),
    }));
    // Each script segment aligns independently against the full evidence —
    // the upstream model aligns ONE explicit Segment per SemanticTake; there
    // is no authored basis here to partition evidence, so a cursor that lets
    // an early segment absorb later words would be a fabrication.
    const groups = alignment.alignWordGroups(segmentId, narrativeTokens, words.map((word) => ({
      text: word.text,
      ...(word.startSample === undefined ? {} : { startSample: word.startSample }),
      ...(word.endSampleExclusive === undefined ? {} : { endSampleExclusive: word.endSampleExclusive }),
    })) as Parameters<typeof alignment.alignWordGroups>[2]);
    const timedTokens: Array<Record<string, unknown>> = [];
    let segmentStart: number | undefined;
    let segmentEnd: number | undefined;
    for (const group of groups) {
      const relation = group.relation as keyof typeof counters;
      if (relation in counters) counters[relation] += 1;
      const windowStart = words[group.evidenceWordStart]?.startSample;
      const windowEnd = words[group.evidenceWordEndExclusive - 1]?.endSampleExclusive;
      for (const tokenId of group.sourceTokenIds) {
        const token = narrativeTokens.find((candidate) => candidate.id === tokenId)!;
        timedTokens.push({
          text: token.text,
          relation: group.relation,
          ...(windowStart === undefined ? {} : { startSample: windowStart }),
          ...(windowEnd === undefined ? {} : { endSampleExclusive: windowEnd }),
        });
        if (windowStart !== undefined) segmentStart = segmentStart === undefined ? windowStart : Math.min(segmentStart, windowStart);
        if (windowEnd !== undefined) segmentEnd = segmentEnd === undefined ? windowEnd : Math.max(segmentEnd, windowEnd);
      }
    }
    segments.push({
      id: segmentId,
      text: segmentText,
      ...(segmentStart === undefined ? {} : { startSample: segmentStart }),
      ...(segmentEnd === undefined ? {} : { endSampleExclusive: segmentEnd }),
      tokens: timedTokens,
      // 无证据 token 无时间——不造秒数（对齐可解释性要求）。
      hasEvidence: segmentStart !== undefined,
    });
  }
  return {
    format: "y1.hypit.speech-align@1",
    segments,
    relations: counters,
    note: "Native @hypit/speech-alignment (normalize/alignWordGroups); compensation and Take adjustment remain upstream entries.",
  };
}
