// capture.test.ts — C107-11 (task-107) capture restrictions (step 6/7):
// the capture Chrome carries its own version/cache (never the render Headless
// Shell), capture.run scripts stay inside the page sandbox with network denied
// by default and bounded timeouts, and outputs cannot escape the allowed root.
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  assertRestrictedCaptureScript, assertWithinOutputRoot,
} from "../../src/tools/capture.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

test("capture.run refuses host-escape constructs and unbounded timeouts", () => {
  assertRestrictedCaptureScript({
    script: "document.querySelector('h1').textContent",
    outputRoot: "/tmp/capture-out",
  });
  for (const hostile of [
    "process.env.SECRET",
    "require('node:fs')",
    "import('node:child_process')",
    "globalThis.fetch('/admin')",
  ]) {
    assert.throws(() => assertRestrictedCaptureScript({ script: hostile, outputRoot: "/tmp/out" }), DispatchError,
      `must refuse ${hostile}`);
  }
  assert.throws(() => assertRestrictedCaptureScript({ script: "", outputRoot: "/tmp/out" }), DispatchError);
  assert.throws(() => assertRestrictedCaptureScript({
    script: "1", outputRoot: "/tmp/out", timeoutMs: 500_000,
  }), DispatchError);
});

test("capture.run denies host network unless explicitly allowed", () => {
  assert.throws(() => assertRestrictedCaptureScript({
    script: "window.fetch('/api')", outputRoot: "/tmp/out",
  }), DispatchError);
  // page-scope network APIs are not the host fetch and stay allowed
  assertRestrictedCaptureScript({
    script: "const r = await fetch('/page-data')", outputRoot: "/tmp/out",
  });
});

test("capture outputs cannot escape the allowed root", () => {
  assertWithinOutputRoot("/tmp/capture-out", "/tmp/capture-out/shot.png");
  assertWithinOutputRoot("/tmp/capture-out/", "/tmp/capture-out/nested/shot.png");
  assert.throws(() => assertWithinOutputRoot("/tmp/capture-out", "/etc/passwd"), DispatchError);
  assert.throws(() => assertWithinOutputRoot("/tmp/capture-out", "/tmp/capture-out-evil/x.png"), DispatchError);
});
