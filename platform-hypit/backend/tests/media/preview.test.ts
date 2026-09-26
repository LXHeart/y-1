// preview.test.ts — C107-11 (task-107) preview media layer over the native
// renderer pieces: the runtime shim injects before </body> without touching
// the document source, and audio clips carry the native sample-clock
// attributes (48 kHz program clock) that the bridge contract asserts.
import { strict as assert } from "node:assert";
import test from "node:test";

import { injectRuntimeShim } from "../../../.generated/hypit/packages/studio/src/preview/runtime-shim.ts";
import { programSeconds, presentationEnvelope, type AudioClipClock } from "../../src/preview/bridge.ts";

test("the runtime shim injects before </body> and exposes the frame protocol", () => {
  const html = "<html><body><div data-composition-id=\"c\"></div></body></html>";
  const injected = injectRuntimeShim(html);
  assert.ok(injected.includes("__hypitSeekFrame"));
  assert.ok(injected.includes("__hypitFrameReady"));
  assert.ok(injected.indexOf("__hypitSeekFrame") < injected.lastIndexOf("</body>"),
    "shim script injected before the closing body tag");
  // the document prefix is preserved verbatim before the injected script
  assert.ok(injected.startsWith("<html><body>"));
  assert.ok(injected.trimEnd().endsWith("</html>"));
});

test("audio clips project onto the 48 kHz program clock with envelope preserved", () => {
  const clip: AudioClipClock = {
    startSample: 96_000,
    endSampleExclusive: 240_000,
    sourceStartSample: 48_000,
    sourceEndSampleExclusive: 144_000,
    loop: false,
    phaseSample: 0,
    playbackRate: 1,
    gain: 0.8,
    fadeInSamples: 2400,
    fadeOutSamples: 4800,
  };
  // program clock: sample 96000 == second 2
  assert.equal(programSeconds(clip.startSample), 2);
  assert.equal(programSeconds(clip.endSampleExclusive), 5);
  const envelope = JSON.parse(decodeURIComponent(presentationEnvelope(clip))) as Record<string, unknown>;
  assert.equal(envelope.fadeInSamples, 2400);
  assert.equal(envelope.fadeOutSamples, 4800);
});
