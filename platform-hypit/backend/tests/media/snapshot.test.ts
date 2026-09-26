// snapshot.test.ts — C107-11 (task-107) snapshot scheduling + C107F-03 (fix-1)
// the snapshot TOOL channel: payload validation negatives, session_expired for
// unknown/closed sessions, and the real happy path (TC-F03-01) through the
// engine Studio pipeline with the render Headless Shell — at=[0,12,24] lands
// as resource handles with zero new Builds (workspace tree byte-identical).
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

// Before any engine bootstrap in this process: external package resolution
// then targets the shared packages-only runner state root (never the home).
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../data/hypit/runner-state");

import { runSnapshotTool, snapshotSchedule } from "../../src/tools/snapshot.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";
import { closePreviewSession, openPreviewSession, requireSession } from "../../src/preview/sessions.ts";
import {
  ensureChatMachinePackages, generatedRoot, prepareChatWorkspace, stableBrowserCache, stabilizeRenderProfile,
} from "../engine/chat-workspace.ts";

const document = { frameCount: 120 } as never;

test("exact frames are honored and grid pages paginate deterministically", () => {
  const schedule = snapshotSchedule({ document, frames: [0, 59, 119], framesPerPage: 2 });
  assert.deepEqual(schedule.frames, [0, 59, 119]);
  assert.equal(schedule.pages.length, 2);
  assert.deepEqual(schedule.pages[0]!.frameIndices, [0, 59]);
  assert.deepEqual(schedule.pages[1]!.frameIndices, [119]);
});

test("default schedule samples first/middle/last, preserving exact frame labels", () => {
  const schedule = snapshotSchedule({ document });
  assert.deepEqual(schedule.frames, [0, 59, 119]);
  // single page when no pagination is requested
  assert.equal(schedule.pages.length, 1);
});

test("out-of-range or fractional frames are refused", () => {
  assert.throws(() => snapshotSchedule({ document, frames: [120] }), DispatchError);
  assert.throws(() => snapshotSchedule({ document, frames: [-1] }), DispatchError);
  assert.throws(() => snapshotSchedule({ document, frames: [10.5] }), DispatchError);
});

// ---------------------------------------------------------------------------
// C107F-03: the snapshot kind channel (D-05).
// ---------------------------------------------------------------------------

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** Reject with an exact DispatchError code (a wrong code must stay red). */
function dispatchCode(code: string): (error: unknown) => boolean {
  return (error: unknown) => error instanceof DispatchError && error.code === code;
}

test("snapshot tool validates its payload before any session or render work (E-b)", async () => {
  const ctx = {
    distributionRoot: generatedRoot,
    framesRoot: mkdtempSync(join(tmpdir(), "hypit-snap-frames-")),
    registerResource: async () => { throw new Error("must not register on a rejected payload"); },
  };
  const cases: Array<Record<string, unknown>> = [
    {},
    { previewSessionId: "" },
    { previewSessionId: "pv-x", at: [0], ranges: [{ start: 0 }] },
    { previewSessionId: "pv-x", at: "0,12" },
    { previewSessionId: "pv-x", ranges: [] },
    { previewSessionId: "pv-x", ranges: ["0..12"] },
    { previewSessionId: "pv-x", ranges: [{ every: 0 }] },
    { previewSessionId: "pv-x", ranges: [{ start: 5, endExclusive: 5 }] },
    { previewSessionId: "pv-x", ranges: [{ start: "0" }] },
    { previewSessionId: "pv-x", pageSize: 0 },
    { previewSessionId: "pv-x", pageSize: 51 },
    { previewSessionId: "pv-x", pageSize: 1.5 },
  ];
  for (const payload of cases) {
    await assert.rejects(runSnapshotTool(ctx, payload), dispatchCode("invalid_input"), JSON.stringify(payload));
  }
  // over-quota request (50*64 ceiling) is refused before the session lookup
  await assert.rejects(
    runSnapshotTool(ctx, { previewSessionId: "pv-x", at: Array.from({ length: 50 * 64 + 1 }, (_unused, i) => i) }),
    dispatchCode("invalid_input"),
  );
});

test("unknown or closed preview sessions answer session_expired with zero render work (TC-F03-02)", async () => {
  const ctx = {
    distributionRoot: generatedRoot,
    framesRoot: mkdtempSync(join(tmpdir(), "hypit-snap-frames-")),
    registerResource: async () => { throw new Error("must not register for an expired session"); },
  };
  await assert.rejects(
    runSnapshotTool(ctx, { previewSessionId: "pv-does-not-exist" }),
    dispatchCode("session_expired"),
  );

  // a session that was live and then closed is equally unresumable
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-snap-ws-"));
  try {
    const projectId = "32222222-2222-4222-8222-222222222222";
    mkdirSync(join(projectsRoot, projectId, "work"), { recursive: true });
    writeFileSync(join(projectsRoot, projectId, "work", "main.svrun"), "<svrun version=\"1\"></svrun>\n");
    const opened = await openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot },
      { projectId, runFile: "main.svrun", revision: 0 },
    );
    closePreviewSession(opened.id);
    assert.throws(() => requireSession(opened.id), DispatchError);
    await assert.rejects(
      runSnapshotTool(ctx, { previewSessionId: opened.id }),
      dispatchCode("session_expired"),
    );
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

/** Full recursive file+size snapshot of a tree (zero-Build proof: identical before/after). */
function treeSnapshot(root: string): Map<string, number> {
  const files = new Map<string, number>();
  const walk = (dir: string, prefix: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const rel = prefix.length === 0 ? entry.name : `${prefix}/${entry.name}`;
      if (entry.isDirectory()) walk(join(dir, entry.name), rel);
      else files.set(rel, statSync(join(dir, entry.name)).size);
    }
  };
  walk(root, "");
  return files;
}

/** The render Headless Shell is installed in the stable repo cache (RISK-F05
 *  probe). The installer layout nests the unzipped bundle one level deeper
 *  (…/mac_arm-<version>/chrome-headless-shell-mac-arm64/chrome-headless-shell),
 *  so search for the real binary, not just any cache entry. */
function renderBrowserReady(): boolean {
  const isRealBinary = (path: string): boolean => {
    try {
      return statSync(path).isFile() && statSync(path).size > 1_048_576;
    } catch {
      return false;
    }
  };
  const search = (dir: string, depth: number): boolean => {
    if (depth > 4) return false;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return false;
    }
    for (const entry of entries) {
      const path = join(dir, entry.name);
      if (entry.isFile() && entry.name.includes("chrome-headless-shell") && isRealBinary(path)) return true;
      if (entry.isDirectory() && !entry.name.startsWith(".") && search(path, depth + 1)) return true;
    }
    return false;
  };
  return search(stableBrowserCache, 0);
}

test("snapshot photographs at=[0,12,24] into resource handles with zero new Builds (TC-F03-01)", { timeout: 300_000 }, async (t) => {
  const engineMaterialized = existsSync(join(generatedRoot, "package.json"));
  if (!engineMaterialized || !renderBrowserReady()) {
    t.skip(`full engine/render browser unavailable (G=${engineMaterialized}): run scripts/acceptance/build-107-engine.sh and one render first (RISK-F05, EXTERNAL_BLOCKED)`);
    return;
  }
  await ensureChatMachinePackages();

  // project layout per provision.ts: <projectsRoot>/<id>/work holds the chat head
  const chat = prepareChatWorkspace();
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-snap-projects-"));
  const projectId = "33333333-3333-4333-8333-333333333333";
  const work = join(projectsRoot, projectId, "work");
  mkdirSync(work, { recursive: true });
  for (const entry of readdirSync(chat)) {
    cpSync(join(chat, entry), join(work, entry), { recursive: true });
  }
  rmSync(chat, { recursive: true, force: true });
  stabilizeRenderProfile(work);
  t.after(() => rmSync(projectsRoot, { recursive: true, force: true }));

  const session = await openPreviewSession(
    { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot },
    { projectId, runFile: "chat.svrun", revision: 0 },
  );

  const framesRoot = mkdtempSync(join(tmpdir(), "hypit-snap-frames-"));
  const registered: Array<{ absolutePath: string; role: string; mediaType: string; projectId: string | null }> = [];
  const ctx = {
    distributionRoot: generatedRoot,
    framesRoot,
    registerResource: async (input: { absolutePath: string; role: string; mediaType: string; projectId: string | null }) => {
      registered.push(input);
      return `res-snap-${registered.length}`;
    },
  };

  const before = treeSnapshot(work);
  const result = await runSnapshotTool(ctx, { previewSessionId: session.id, at: [0, 12, 24] });
  const after = treeSnapshot(work);

  // three exact frames, frameIndex fidelity, distinct handles, one page
  assert.equal(result.frames.length, 3);
  assert.deepEqual(result.frames.map((frame) => frame.frameIndex), [0, 12, 24]);
  assert.equal(new Set(result.frames.map((frame) => frame.assetId)).size, 3);
  assert.equal(result.pages, 1);
  assert.deepEqual(registered.map((entry) => entry.role), ["footage", "footage", "footage"]);
  assert.ok(registered.every((entry) => entry.mediaType === "image/png" && entry.projectId === projectId));

  // real rendered PNGs (signature + non-trivial bytes), not placeholder zeros
  for (const frame of result.frames) {
    const bytes = readFileSync(join(framesRoot, `${session.id.replace(/[^a-z0-9-]/giu, "")}-${frame.frameIndex}.png`));
    assert.ok(bytes.byteLength > 1_000, `frame ${frame.frameIndex} PNG is suspiciously small`);
    assert.ok(bytes.subarray(0, 8).equals(PNG_SIGNATURE), `frame ${frame.frameIndex} is not a PNG`);
  }

  // zero new Builds: the only tolerated tree delta is the engine's runtime
  // marker (.hypit/runtime, written by the transient preview execution); no
  // results, no state, no build directories may appear.
  const added = [...after.keys()].filter((path) => !before.has(path));
  const removed = [...before.keys()].filter((path) => !after.has(path));
  const changed = [...before.keys()].filter((path) => after.get(path) !== before.get(path));
  assert.deepEqual(added, [".hypit/runtime"], "snapshot must not create workspace artifacts beyond the runtime marker");
  assert.deepEqual(removed, []);
  assert.deepEqual(changed, []);
  assert.ok(![...after.keys()].some((path) => /results|builds/u.test(path)), "no Build/result artifacts in the workspace");

  closePreviewSession(session.id);
});
