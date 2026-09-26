// preview.test.ts — C107-11 (task-107) preview session surface: sessions bind
// exactly one (project, runFile, revision), only authorized materials are
// served (cross-session/unknown resources refused), and the control bridge
// drives the upstream shim protocol without rewriting the document source.
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  assertSessionMaterial, closePreviewSession, openPreviewSession, requireSession,
} from "../../src/preview/sessions.ts";
import { encodeControl } from "../../src/preview/bridge.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

const generatedRoot = join(import.meta.dirname, "../../../.generated/hypit");

function workspace(): { projectsRoot: string; projectId: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "hypit-preview-ws-"));
  const projectsRoot = dir;
  const projectId = "31111111-1111-4111-8111-111111111111";
  mkdirSync(join(projectsRoot, projectId, "work"), { recursive: true });
  writeFileSync(join(projectsRoot, projectId, "work", "main.svrun"), "<svrun version=\"1\"></svrun>\n");
  mkdirSync(join(projectsRoot, projectId, ".hypit", "revisions", "3"), { recursive: true });
  writeFileSync(join(projectsRoot, projectId, ".hypit", "revisions", "3", "manifest.json"),
    JSON.stringify({ resources: { "res-abc": { mediaType: "image/png" } } }));
  return { projectsRoot, projectId, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("sessions bind project/run/revision and refuse unknown or cross-session materials", async () => {
  const ws = workspace();
  try {
    const session = await openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, runFile: "main.svrun", revision: 3 },
    );
    assert.equal(requireSession(session.id).revision, 3);
    // authorized material resolves to its manifest media type
    assert.equal(assertSessionMaterial(session, "res-abc"), "image/png");
    // unknown material is refused instead of silently served (K10)
    assert.throws(() => assertSessionMaterial(session, "res-other"), DispatchError);

    assert.equal(closePreviewSession(session.id).closed, true);
    assert.throws(() => requireSession(session.id), DispatchError, "closed sessions are not resumable");
  } finally {
    ws.cleanup();
  }
});

test("sessions refuse a run file that does not exist in the bound revision", async () => {
  const ws = workspace();
  try {
    await assert.rejects(openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, runFile: "absent.svrun", revision: 3 },
    ), DispatchError);
  } finally {
    ws.cleanup();
  }
});

test("control bridge emits the native shim protocol without touching the source", () => {
  assert.equal(encodeControl({ kind: "seek", frame: 42 }), "window.__hypitSeekFrame(42);");
  assert.equal(encodeControl({ kind: "play", frame: 0 }), "window.__hypitPlayFrame(0);");
  assert.equal(encodeControl({ kind: "step", frames: 12 }), "window.__hypitPlayFrame(currentFrame() + 12);");
  assert.throws(() => encodeControl({ kind: "seek", frame: 1.5 }), DispatchError);
});
