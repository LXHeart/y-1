/**
 * C107-11 preview/bridge.ts — preview control bridge (step 3) + audio clock
 * contract. The bridge drives the upstream runtime shim's public controls
 * (`__hypitSeekFrame` / `__hypitPlayFrame` / `__hypitSetMuted` /
 * `__hypitFrameReady`); it never rewrites the document source. Audio clips
 * keep the native sample-clock attributes (data-start/media-start/media-end/
 * loop/phase/playback-rate/gain + presentation envelope), so seek produces no
 * multi-track drift and loop/fade/gain never disappear.
 */
import { DispatchError } from "../commands/dispatcher.ts";

export type PreviewControl =
  | { readonly kind: "play"; readonly frame?: number }
  | { readonly kind: "pause" }
  | { readonly kind: "seek"; readonly frame: number }
  | { readonly kind: "step"; readonly frames: number };

export function encodeControl(control: PreviewControl): string {
  switch (control.kind) {
    case "play":
      return control.frame === undefined
        ? "window.__hypitPlayFrame(currentFrame());"
        : `window.__hypitPlayFrame(${control.frame});`;
    case "pause":
      return "window.__hypitPauseFrame();";
    case "seek":
      return `window.__hypitSeekFrame(${requireInt(control.frame)});`;
    case "step":
      return `window.__hypitPlayFrame(currentFrame() + ${requireInt(control.frames)});`;
  }
}

function requireInt(value: number): number {
  if (!Number.isSafeInteger(value)) {
    throw new DispatchError("invalid_input", "frame control needs integer frames");
  }
  return value;
}

/** One audio clip's sample-clock projection (mirrors the native data-* attributes). */
export type AudioClipClock = {
  /** Composition-space window, in samples at the 48 kHz program clock. */
  readonly startSample: number;
  readonly endSampleExclusive: number;
  /** Source window inside the media file, in the same clock. */
  readonly sourceStartSample: number;
  readonly sourceEndSampleExclusive: number;
  readonly loop: boolean;
  readonly phaseSample: number;
  readonly playbackRate: number;
  readonly gain: number;
  readonly gainEnvelope?: unknown;
  readonly audibility?: unknown;
  readonly fadeInSamples?: number;
  readonly fadeOutSamples?: number;
};

const PROGRAM_CLOCK = 48_000;

/** seconds on the program timeline for a composition sample — single source of truth for tests. */
export function programSeconds(sample: number): number {
  return sample / PROGRAM_CLOCK;
}

/**
 * Map a program seek time to the media-file time of one clip. Out-of-window
 * seeks are clamped to the source bounds (the shim simply keeps those silent);
 * in-window looped clips stay inside the source window. Every clip therefore
 * lands on the same program instant at any frame (no multi-track drift).
 */
export function mediaSecondsFor(clip: AudioClipClock, programSecondsAt: number): number {
  const start = programSeconds(clip.startSample);
  const end = programSeconds(clip.endSampleExclusive);
  if (programSecondsAt < start) return programSeconds(clip.sourceStartSample);
  if (programSecondsAt >= end) return programSeconds(clip.sourceEndSampleExclusive);
  let offset = programSecondsAt - start;
  if (clip.loop) {
    const loopSpan = programSeconds(clip.sourceEndSampleExclusive - clip.sourceStartSample);
    offset = offset % loopSpan;
  }
  return programSeconds(clip.sourceStartSample) + offset * clip.playbackRate;
}

/**
 * Presentation envelope (gainEnvelope/audibility/fade) must survive the bridge
 * untouched: the preview carries it as data-presentation JSON exactly as the
 * native renderer wrote it (T11: loop/fade/gain 不丢).
 */
export function presentationEnvelope(clip: AudioClipClock): string {
  return encodeURIComponent(JSON.stringify({
    gainEnvelope: clip.gainEnvelope,
    audibility: clip.audibility,
    fadeInSamples: clip.fadeInSamples,
    fadeOutSamples: clip.fadeOutSamples,
  }));
}

// ── C107F2-22/23（§6.10 API-11 消息契约）────────────────────────────────────
// ready/frame/error 三类消息都带 type；frame 另带 frame/timeSeconds；error 只带
// 脱敏 message。父页/桥两侧共用同一 schema 判定：origin 由父页 Window 推断（§6.10
// opaque origin），本函数只校验 source 目标、sessionId、messageNonce 与字段类型——
// 不做 host 子串校验。控制消息（父→iframe）也走 encodeControlMessage 显式 nonce。

export type PreviewBridgeMessage =
  | { readonly type: "ready"; readonly sessionId: string; readonly messageNonce: string }
  | { readonly type: "frame"; readonly sessionId: string; readonly messageNonce: string;
      readonly frame: number; readonly timeSeconds: number }
  | { readonly type: "error"; readonly sessionId: string; readonly messageNonce: string;
      readonly message: string };

export type PreviewControlMessage =
  | { readonly type: "control"; readonly kind: "play"; readonly frame?: number; readonly messageNonce: string }
  | { readonly type: "control"; readonly kind: "pause"; readonly messageNonce: string }
  | { readonly type: "control"; readonly kind: "seek"; readonly frame: number; readonly messageNonce: string }
  | { readonly type: "control"; readonly kind: "step"; readonly frames: number; readonly messageNonce: string };

/** 三关校验（source 由调用方先比对 event.source===iframe.contentWindow）：schema+会话+nonce。 */
export function parseBridgeMessage(data: unknown, sessionId: string, messageNonce: string): PreviewBridgeMessage | null {
  if (data === null || typeof data !== "object") return null;
  const record = data as Record<string, unknown>;
  if (record.type !== "ready" && record.type !== "frame" && record.type !== "error") return null;
  if (record.sessionId !== sessionId || record.messageNonce !== messageNonce) return null;
  if (record.type === "ready") {
    return { type: "ready", sessionId, messageNonce };
  }
  if (record.type === "frame"
    && typeof record.frame === "number" && Number.isFinite(record.frame)
    && typeof record.timeSeconds === "number" && Number.isFinite(record.timeSeconds)) {
    return { type: "frame", sessionId, messageNonce, frame: record.frame, timeSeconds: record.timeSeconds };
  }
  if (record.type === "error" && typeof record.message === "string") {
    return { type: "error", sessionId, messageNonce, message: record.message.slice(0, 300) };
  }
  return null;
}

/** 控制消息编码（父→iframe；opaque origin 下以 "*" 发送，消息内不得含秘密）。 */
export function encodeControlMessage(control: PreviewControl, messageNonce: string): string {
  const base: Record<string, unknown> = { type: "control", messageNonce };
  switch (control.kind) {
    case "play":
      return JSON.stringify(control.frame === undefined
        ? base
        : { ...base, kind: "play", frame: requireInt(control.frame) });
    case "pause":
      return JSON.stringify({ ...base, kind: "pause" });
    case "seek":
      return JSON.stringify({ ...base, kind: "seek", frame: requireInt(control.frame) });
    case "step":
      return JSON.stringify({ ...base, kind: "step", frames: requireInt(control.frames) });
  }
}
