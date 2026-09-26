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
