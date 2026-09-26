// capture.test.ts — C107-11 (task-107) capture restrictions (step 6/7) +
// C107F-03 (fix-1) the capture TOOL channel: not-configured 409 for all three
// kinds, url-policy denial with zero outbound bytes, capture.run negatives
// that fire before any browser launch, and the real screenshot pixels against
// the local-web fixture when the capture Chrome is installed.
import { createServer } from "node:http";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  assertRestrictedCaptureScript, assertWithinOutputRoot, CAPTURE_BROWSER_VERSION, captureTools, runCaptureTool,
} from "../../src/tools/capture.ts";
import { captureBrowserExecutablePath, installCaptureBrowser, withCapture } from "@hypit/browser-capture";
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

// ---------------------------------------------------------------------------
// C107F-03: the capture kind channel (D-06/D-07).
// ---------------------------------------------------------------------------

/** Reject with an exact DispatchError code (a wrong code must stay red). */
function dispatchCode(code: string): (error: unknown) => boolean {
  return (error: unknown) => error instanceof DispatchError && error.code === code;
}

const captureCacheProbe = join(import.meta.dirname, "../../../../data/hypit/capture-browser");

type Registered = { absolutePath: string; role: string; mediaType: string; projectId: string | null };

/** A context whose capture cache points at an empty dir: chrome absent, so any
 *  path that reaches withCapture fails with the installer's error instead of
 *  invalid_input — the negatives below prove they fire BEFORE browser launch. */
function makeContext(): {
  ctx: {
    captureBrowserCache: string;
    outputsRoot: string;
    registerResource: (input: Registered) => Promise<string>;
    workspaceRootFor: (projectId: string) => string;
  };
  registered: Registered[];
} {
  const registered: Registered[] = [];
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-cap-ws-"));
  const outputsRoot = mkdtempSync(join(tmpdir(), "hypit-cap-out-"));
  const ctx = {
    captureBrowserCache: join(projectsRoot, "browsers"), // configured, but empty
    outputsRoot,
    registerResource: async (input: Registered) => {
      registered.push(input);
      return `res-cap-${registered.length}`;
    },
    workspaceRootFor: (projectId: string) => join(projectsRoot, projectId),
  };
  return { ctx, registered };
}

test("every capture kind answers capture_not_configured when the surface is not deployed (TC-F03-03)", async () => {
  const { ctx } = makeContext();
  const bare = { ...ctx, captureBrowserCache: "" };
  for (const kind of captureTools) {
    await assert.rejects(
      runCaptureTool(bare, kind, kind === "capture.screenshot" ? { url: "https://example.com/" } : { projectId: "p1" }),
      dispatchCode("capture_not_configured"),
      kind,
    );
  }
  // blank-but-nonempty (whitespace) counts as not deployed too
  await assert.rejects(
    runCaptureTool({ ...bare, captureBrowserCache: "   " }, "capture.screenshot", { url: "https://example.com/" }),
    dispatchCode("capture_not_configured"),
  );
});

test("capture.screenshot refuses policy-denied targets with zero outbound bytes (TC-F03-03)", async () => {
  const fixture = readFileSync(new URL("../../../fixtures/local-web/index.html", import.meta.url), "utf8");
  let requests = 0;
  let bytesServed = 0;
  const server = createServer((_request, response) => {
    requests += 1;
    bytesServed += Buffer.byteLength(fixture);
    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(fixture);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const port = (server.address() as { port: number }).port;
    const { ctx } = makeContext();
    // loopback targets never reach the browser: url_denied before any launch
    await assert.rejects(
      runCaptureTool(ctx, "capture.screenshot", { url: `http://127.0.0.1:${port}/` }),
      dispatchCode("url_denied"),
    );
    // non-http schemes are refused identically
    await assert.rejects(
      runCaptureTool(ctx, "capture.screenshot", { url: "file:///etc/passwd" }),
      dispatchCode("url_denied"),
    );
    assert.equal(requests, 0, "policy must deny before any outbound byte");
    assert.equal(bytesServed, 0);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("capture.run negatives are 400 before any browser launch, without leaking script bodies (TC-F03-04)", async () => {
  const { ctx } = makeContext();
  const work = join(ctx.workspaceRootFor("p1"), "work");
  mkdirSync(work, { recursive: true });
  const MARKER = "SECRETMARKER-DO-NOT-LEAK";
  writeFileSync(join(work, "fetchy.js"), `const token = "${MARKER}";\nwindow.fetch("https://example.com/");\n`);
  writeFileSync(join(work, "benign.js"), "return document.title;\n");
  writeFileSync(join(work, "slow.js"), "return 1;\n");

  const cases: Array<[string, Record<string, unknown>]> = [
    ["traversal", { projectId: "p1", scriptPath: "../outside.js" }],
    ["absolute", { projectId: "p1", scriptPath: "/etc/passwd" }],
    ["missing file", { projectId: "p1", scriptPath: "absent.js" }],
    ["fetch denied", { projectId: "p1", scriptPath: "fetchy.js" }],
    ["timeout over cap", { projectId: "p1", scriptPath: "slow.js", timeoutMs: 200_000 }],
    ["no projectId", { scriptPath: "benign.js" }],
  ];
  for (const [label, payload] of cases) {
    await assert.rejects(
      runCaptureTool(ctx, "capture.run", payload),
      (error: unknown) => {
        // exact code AND the browser never launched: this context's cache is
        // configured-but-empty, so a regression past the guard would surface
        // the installer's "not installed" error here instead of invalid_input.
        assert.ok(error instanceof DispatchError, `${label}: ${String(error)}`);
        assert.equal(error.code, "invalid_input", label);
        assert.ok(!error.message.includes(MARKER), `${label}: script body leaked into the error path`);
        return true;
      },
      label,
    );
  }
});

test("capture chrome screenshots the local-web fixture with real pixels when installed (TC-F03-03)", { timeout: 600_000 }, async (t) => {
  let executable = "";
  try {
    const resolved = await captureBrowserExecutablePath({ version: CAPTURE_BROWSER_VERSION, cacheDirectory: captureCacheProbe });
    if (existsSync(resolved)) executable = resolved;
  } catch {
    // probe failed: not installed
  }
  if (executable === "" && process.env.HYPIT_TEST_CAPTURE_INSTALL === "1") {
    try {
      executable = await installCaptureBrowser({ version: CAPTURE_BROWSER_VERSION, cacheDirectory: captureCacheProbe });
    } catch (error) {
      t.skip(`capture chrome download failed: ${(error as Error).message.slice(0, 160)} (EXTERNAL_BLOCKED — operator install)`);
      return;
    }
  }
  if (executable === "") {
    t.skip(`capture chrome ${CAPTURE_BROWSER_VERSION} not installed at ${captureCacheProbe}: run with HYPIT_TEST_CAPTURE_INSTALL=1 once, or operator-install via capture.install-browser (EXTERNAL_BLOCKED: large download)`);
    return;
  }

  const fixture = readFileSync(new URL("../../../fixtures/local-web/index.html", import.meta.url), "utf8");
  let requests = 0;
  const server = createServer((_request, response) => {
    requests += 1;
    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(fixture);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const outputsRoot = mkdtempSync(join(tmpdir(), "hypit-cap-pixels-"));
  try {
    const port = (server.address() as { port: number }).port;
    const target = join(outputsRoot, "fixture.png");
    // withCapture itself carries no URL policy (that is the broker's layer,
    // proven above) so the loopback fixture is a legitimate mechanism probe.
    await withCapture({ browser: { cacheDirectory: captureCacheProbe } }, async (session) => {
      await session.page.goto(`http://127.0.0.1:${port}/`, { waitUntil: "load", timeout: 60_000 });
      await session.screenshot({ path: target });
    });
    assert.ok(requests >= 1, "the fixture page was actually loaded");
    const bytes = readFileSync(target);
    assert.ok(bytes.byteLength > 1_000, "screenshot is suspiciously small");
    assert.ok(bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), "not a PNG");
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(outputsRoot, { recursive: true, force: true });
  }
});
