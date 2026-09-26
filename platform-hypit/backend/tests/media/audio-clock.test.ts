// audio-clock.test.ts — C107-11 (task-107) audio clock contract for the
// preview bridge: the 48 kHz program clock mapping is drift-free at any seek
// frame, loop stays inside the source window, and the native presentation
// envelope (gainEnvelope/audibility/fade) survives the bridge verbatim
// (audioEnvelopeGainAt from @hypit/composition is the reference evaluator).
import { strict as assert } from "node:assert";
import test from "node:test";

import { audioEnvelopeGainAt } from "@hypit/composition";
import {
  mediaSecondsFor, programSeconds, presentationEnvelope,
  type AudioClipClock,
} from "../../src/preview/bridge.ts";

const clip: AudioClipClock = {
  startSample: 48_000, // program second 1
  endSampleExclusive: 4 * 48_000, // program second 4
  sourceStartSample: 96_000, // media second 2
  sourceEndSampleExclusive: 240_000, // media second 5
  loop: true,
  phaseSample: 0,
  playbackRate: 1,
  gain: 0.8,
  gainEnvelope: [{ gain: 0, sample: 0 }, { gain: 1, sample: 2400 }],
  audibility: { min: 0, max: 1 },
  fadeInSamples: 2400,
  fadeOutSamples: 4800,
};

test("seek maps every clip to one program instant with no multi-track drift", () => {
  // mid-window seek: program second 2 is source second 3
  assert.equal(mediaSecondsFor(clip, 2), 3);
  // window start maps to the source start exactly
  assert.equal(mediaSecondsFor(clip, 1), 2);
  // first/last frame checks: 1 frame before start stays at source start
  assert.equal(mediaSecondsFor(clip, programSeconds(clip.startSample - 1)), programSeconds(clip.sourceStartSample));
  assert.equal(mediaSecondsFor(clip, programSeconds(clip.endSampleExclusive)), programSeconds(clip.sourceEndSampleExclusive));
});

test("loop keeps playback inside the source window instead of running past it", () => {
  // window is 3 program seconds; source window is 3 media seconds (2..5)
  // program second 3.5 => 2.5s into the clip => source second 4.5 (inside)
  assert.equal(mediaSecondsFor(clip, 3.5), 4.5);
  // program second 3.9 => 2.9s in => still inside (source 4.9)
  assert.equal(mediaSecondsFor(clip, 3.9), 4.9);
  // a longer window wraps: 6s program window over the same 3s source loop,
  // offset 3.5s into the clip lands at source 2.5s again (one full loop + 0.5)
  const looping: AudioClipClock = { ...clip, endSampleExclusive: 7 * 48_000 };
  assert.equal(mediaSecondsFor(looping, 4.5), 2.5);
  assert.equal(mediaSecondsFor(looping, 1.5), mediaSecondsFor(looping, 4.5));
});

test("playback-rate rescales the media position affinely", () => {
  const fast: AudioClipClock = { ...clip, loop: false, playbackRate: 2 };
  // 1 program second into the clip = 2 media seconds, starting at source 2s
  assert.equal(mediaSecondsFor(fast, 2), 4);
});

test("presentation envelope survives the bridge verbatim and evaluates via the native gain", () => {
  const encoded = decodePresentation(presentationEnvelope(clip));
  assert.deepEqual(encoded.gainEnvelope, clip.gainEnvelope);
  assert.deepEqual(encoded.audibility, clip.audibility);
  assert.equal(encoded.fadeInSamples, clip.fadeInSamples);
  assert.equal(encoded.fadeOutSamples, clip.fadeOutSamples);
  // the reference native evaluator reads the same envelope shape
  const gain = audioEnvelopeGainAt(encoded.gainEnvelope as never, 1200);
  assert.ok(gain > 0 && gain <= 1, `fading gain ${gain}`);
  assert.equal(audioEnvelopeGainAt(undefined, 100), 1, "absent envelope means unity gain");
});

function decodePresentation(encoded: string): Record<string, unknown> {
  return JSON.parse(decodeURIComponent(encoded)) as Record<string, unknown>;
}
