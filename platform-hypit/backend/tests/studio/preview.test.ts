// preview.test.ts — C107-11 (task-107) 首建；107-fix-2 C107F2-22 重写为不可变快照契约：
// 会话绑定 revisions/<n>/ 快照（revision.json 的 manifestHash 必验），served 集合来自
// 同一快照 manifest（assets/ 内可服务媒体），跨会话/未授权资源拒绝；close 幂等；
// 控制桥协议不重写文档源；§6.10 消息 schema 三关校验（sessionId+nonce+类型）。
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  assertSessionMaterial, closePreviewSession, openPreviewSession, requireSession,
} from "../../src/preview/sessions.ts";
import { encodeControl, encodeControlMessage, parseBridgeMessage } from "../../src/preview/bridge.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

const generatedRoot = join(import.meta.dirname, "../../../.generated/hypit");
const OWNER = "acc-preview-owner";

function sha256(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex");
}

/** 新契约工作区：revisions/3/ 快照（revision.json+manifest.json+work 文件+assets 素材）。 */
function workspace(): { projectsRoot: string; projectId: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "hypit-preview-ws-"));
  const projectsRoot = dir;
  const projectId = "31111111-1111-4111-8111-111111111111";
  const snapshot = join(projectsRoot, projectId, "revisions", "3");
  mkdirSync(join(snapshot, "work"), { recursive: true });
  mkdirSync(join(snapshot, "assets"), { recursive: true });
  writeFileSync(join(snapshot, "work", "main.svrun"), "<svrun version=\"1\"></svrun>\n");
  writeFileSync(join(snapshot, "assets", "clip.png"), "png-bytes");
  const entries = [
    { path: "assets/clip.png", sha256: sha256("png-bytes"), sizeBytes: 9 },
    { path: "work/main.svrun", sha256: sha256("<svrun version=\"1\"></svrun>\n"), sizeBytes: 27 },
  ].sort((a, b) => (a.path < b.path ? -1 : 1));
  writeFileSync(join(snapshot, "manifest.json"), JSON.stringify({ format: "y1.hypit-workspace-manifest@1", entries }));
  writeFileSync(join(snapshot, "revision.json"),
    JSON.stringify({ revision: 3, manifestHash: sha256(JSON.stringify({ format: "y1.hypit-workspace-manifest@1", entries })) }));
  return { projectsRoot, projectId, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("sessions bind an immutable snapshot and refuse unauthorized or cross-session materials", async () => {
  const ws = workspace();
  try {
    const session = await openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, ownerAccountId: OWNER, runFile: "main.svrun", revision: 3 },
    );
    assert.equal(requireSession(session.id).revision, 3);
    // served 集合来自同一快照 manifest（assets/clip.png → image/png）
    assert.equal(assertSessionMaterial(session, "assets/clip.png"), "image/png");
    // 非素材/未授权路径一律拒绝（work/ 源码不进服务面，K10）
    assert.throws(() => assertSessionMaterial(session, "work/main.svrun"), DispatchError);
    assert.throws(() => assertSessionMaterial(session, "assets/absent.png"), DispatchError);

    assert.equal(closePreviewSession(session.id).closed, true);
    assert.throws(() => requireSession(session.id), DispatchError, "closed sessions are not resumable");
    // close 幂等（§6.9 DELETE 语义）：再关未知会话也 closed:true。
    assert.equal(closePreviewSession(session.id).closed, true);
  } finally {
    ws.cleanup();
  }
});

test("sessions refuse a missing snapshot, a revision mismatch, or an absent run file", async () => {
  const ws = workspace();
  try {
    // 快照不存在 → not_found（不回退 head，不生成空 src 成功）。
    await assert.rejects(openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, ownerAccountId: OWNER, revision: 9 },
    ), (error: unknown) => error instanceof DispatchError && error.code === "not_found");
    // run 文件不在该快照 → invalid_input。
    await assert.rejects(openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, ownerAccountId: OWNER, runFile: "absent.svrun", revision: 3 },
    ), DispatchError);
    // ownerAccountId 缺失 → invalid_input（access 链归属）。
    await assert.rejects(openPreviewSession(
      { distributionRoot: generatedRoot, attachmentSourceDir: "", projectsRoot: ws.projectsRoot },
      { projectId: ws.projectId, ownerAccountId: "", revision: 3 },
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

test("§6.10 message schema: three gates (sessionId, nonce, field types) before acceptance", () => {
  const sid = "pv-abc";
  const nonce = "n-1";
  // ready/frame/error 合法形态。
  assert.deepEqual(parseBridgeMessage({ type: "ready", sessionId: sid, messageNonce: nonce }, sid, nonce),
    { type: "ready", sessionId: sid, messageNonce: nonce });
  assert.deepEqual(
    parseBridgeMessage({ type: "frame", sessionId: sid, messageNonce: nonce, frame: 12, timeSeconds: 0.4 }, sid, nonce),
    { type: "frame", sessionId: sid, messageNonce: nonce, frame: 12, timeSeconds: 0.4 });
  // 错会话/错 nonce/未知 type/非有限数一律 null。
  assert.equal(parseBridgeMessage({ type: "ready", sessionId: "pv-other", messageNonce: nonce }, sid, nonce), null);
  assert.equal(parseBridgeMessage({ type: "ready", sessionId: sid, messageNonce: "n-2" }, sid, nonce), null);
  assert.equal(parseBridgeMessage({ type: "greeting", sessionId: sid, messageNonce: nonce }, sid, nonce), null);
  assert.equal(
    parseBridgeMessage({ type: "frame", sessionId: sid, messageNonce: nonce, frame: Number.NaN, timeSeconds: 0 }, sid, nonce),
    null);
  // 控制消息编码：显式 nonce，无秘密字段。
  assert.deepEqual(JSON.parse(encodeControlMessage({ kind: "seek", frame: 7 }, nonce)),
    { type: "control", messageNonce: nonce, kind: "seek", frame: 7 });
});
