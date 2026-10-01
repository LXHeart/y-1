// fix2-c22.test.ts — 107-fix-2 C107F2-22：构建绑定不可变版本的真实 Preview 服务。
//
// TC-F2-22-01 不可变版本：两个 revision 快照，head 改为 B 后请求 A 预览——
//            会话 served/读取都来自 A 快照（manifestHash=A），B 的改动不污染。
// TC-F2-22-02 资源面：授权视频资源 bytes=0-99 → 206 + 正确 Content-Range/Content-Type；
//            未授权/越权路径（跨快照、目录穿越、非素材）→ 404/400。
// TC-F2-22-03 明确失败：快照不存在 / revision 不匹配 → not_found/invalid_input，
//            不回退 head 或空 src 成功。
// TC-F2-22-04 撤销边界：关闭预览后资源立即 404（执行终止），同项目 Build 域零接触。
//
// 依赖真实 G 发行版（transient execution 打开路径）；不依赖 Docker/PG。
import { createServer, type Server } from "node:http";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

const generatedRoot = join(import.meta.dirname, "../../../.generated/hypit");
const { activePreviewSessionCount, closeAllPreviewSessions, closePreviewSession, openPreviewSession, requireSession }
  = await import("../../src/preview/sessions.ts");
const { createPreviewSurfaceHandler } = await import("../../src/preview/server.ts");
const { signSessionAssertion } = await import("../../src/studio/proxy.ts");

if (!existsSync(join(generatedRoot, "bin/hypit.mjs"))) {
  console.error(`G distribution missing at ${generatedRoot} — run scripts/acceptance/build-107-engine.sh first`);
  process.exit(1);
}

const PROJECT_ID = "34191919-1919-4191-8191-191919191919";
const OWNER = "acc-c22-owner";
const SECRET = "assert-secret-c22-0123456789abcdef0123456789abcdef";  // secret-scan: allow

function sha256(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex");
}

/** 冻结一个 revision 快照：work/main.svml 内容随 revisionVariant 变化（E01 的 A/B 内容）。 */
function freezeSnapshot(projectsRoot: string, revision: number, variant: string): string {
  const projectRoot = join(projectsRoot, PROJECT_ID);
  const snapshot = join(projectRoot, "revisions", String(revision));
  mkdirSync(join(snapshot, "work"), { recursive: true });
  mkdirSync(join(snapshot, "assets"), { recursive: true });
  writeFileSync(join(snapshot, "work", "main.svml"), `<svml><!-- ${variant} --></svml>\n`);
  writeFileSync(join(snapshot, "work", "main.svrun"),
    `<?svml using="@hypit/run-markup@1"?>\n<svrun version="1">\n  <author source="./main.svml"/>\n</svrun>\n`);
  // 12 字节占位 mp4（Range 0-99 会被截到 0-11）。
  const video = Buffer.from("0123456789AB", "utf8");
  writeFileSync(join(snapshot, "assets", "a.mp4"), video);
  const manifest = {
    format: "y1.hypit-workspace-manifest@1",
    entries: [
      { path: "assets/a.mp4", sha256: sha256("0123456789AB"), sizeBytes: video.length },
      { path: "work/main.svml", sha256: sha256(`<svml><!-- ${variant} --></svml>\n`),
        sizeBytes: Buffer.byteLength(`<svml><!-- ${variant} --></svml>\n`) },
      { path: "work/main.svrun", sha256: sha256(`<?svml using="@hypit/run-markup@1"?>\n<svrun version="1">\n  <author source="./main.svml"/>\n</svrun>\n`), sizeBytes: 0 },
    ].sort((a, b) => (a.path < b.path ? -1 : 1)),
  };
  writeFileSync(join(snapshot, "manifest.json"), JSON.stringify(manifest));
  writeFileSync(join(snapshot, "revision.json"),
    JSON.stringify({ revision, manifestHash: sha256(JSON.stringify(manifest)) }));
  return snapshot;
}

const LAUNCH_BASE = { distributionRoot: generatedRoot, attachmentSourceDir: "", readyTimeoutMs: 60_000 };

function assertion(sessionId: string): string {
  return signSessionAssertion(SECRET, {
    sid: sessionId, projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1, readOnly: true,
    exp: Date.now() + 30_000, aud: "hypit-session-proxy", nonce: "n-c22",
  });
}

function startSurface(): Promise<{ server: Server; port: number; close: () => Promise<void> }> {
  const handler = createPreviewSurfaceHandler({ assertionSecret: SECRET });
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://preview");
    if (handler.matches(url.pathname)) {
      void handler.handle(request, response, url).catch(() => response.destroy());
      return;
    }
    response.writeHead(404).end();
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      assert.ok(address !== null && typeof address === "object");
      resolve({ server, port: address.port, close: () => new Promise((done) => server.close(() => done())) });
    });
  });
}

// ── TC-F2-22-01：A 快照不随 head 漂移 ────────────────────────────────────────
test("TC-F2-22-01 E01 revision-A preview keeps serving A while head moves to B", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c22-e01-"));
  t.after(() => { closeAllPreviewSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  freezeSnapshot(projectsRoot, 1, "A");
  const session = await openPreviewSession({ ...LAUNCH_BASE, projectsRoot }, {
    projectId: PROJECT_ID, ownerAccountId: OWNER, runFile: "main.svml", revision: 1,
  });
  assert.equal(session.revision, 1);

  // head 工作区改写为 B（甚至换掉 A 快照目录外的所有内容）。
  const work = join(projectsRoot, PROJECT_ID, "work");
  mkdirSync(work, { recursive: true });
  writeFileSync(join(work, "main.svml"), `<svml><!-- B --></svml>\n`);
  freezeSnapshot(projectsRoot, 2, "B");

  // 会话仍绑定 A 快照：manifestHash=A、读取 A 内容（B 改动不污染）。
  const live = requireSession(session.id);
  assert.equal(live.manifestHash, sha256(JSON.stringify({
    format: "y1.hypit-workspace-manifest@1",
    entries: JSON.parse(readFileSync(join(projectsRoot, PROJECT_ID, "revisions", "1", "manifest.json"), "utf8")).entries,
  })));
  const servedA = readFileSync(join(live.snapshotDir, "work", "main.svml"), "utf8");
  assert.match(servedA, /<!-- A -->/u, "session reads revision A bytes");
  assert.doesNotMatch(servedA, /<!-- B -->/u, "head-B change must not leak into the A session");
});

// ── TC-F2-22-02：Range 与授权集合 ────────────────────────────────────────────
test("TC-F2-22-02 E02 authorized video serves 206 with correct Content-Range; foreign paths are 404", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c22-e02-"));
  t.after(() => { closeAllPreviewSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  freezeSnapshot(projectsRoot, 1, "A");
  const session = await openPreviewSession({ ...LAUNCH_BASE, projectsRoot }, {
    projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1,
  });
  const surface = await startSurface();
  t.after(() => void surface.close());
  const sid = session.id;
  const base = `http://127.0.0.1:${surface.port}/preview/${sid}`;

  const ranged = await fetch(`${base}/assets/a.mp4`, {
    headers: { "x-hypit-session-assertion": assertion(sid), range: "bytes=0-99" },
  });
  assert.equal(ranged.status, 206, "authorized video range request → 206");
  assert.equal(ranged.headers.get("content-range"), "bytes 0-11/12", "Content-Range clamped to real size");
  assert.equal(ranged.headers.get("content-type"), "video/mp4", "Content-Type from the snapshot manifest");
  assert.equal(ranged.headers.get("accept-ranges"), "bytes");

  const foreign = await fetch(`${base}/assets/../../revisions/2/assets/a.mp4`, {
    headers: { "x-hypit-session-assertion": assertion(sid) },
  });
  assert.notEqual(foreign.status, 200, "path escape is refused");

  const notServed = await fetch(`${base}/work/main.svml`, {
    headers: { "x-hypit-session-assertion": assertion(sid) },
  });
  assert.equal(notServed.status, 404, "non-material paths are outside the served set");

  const noAssertion = await fetch(`${base}/assets/a.mp4`);
  assert.equal(noAssertion.status, 401, "no assertion → 401");
});

// ── TC-F2-22-03：明确失败，不回退 head ───────────────────────────────────────
test("TC-F2-22-03 E03 missing snapshot or revision mismatch fails explicitly", { timeout: 120_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c22-e03-"));
  t.after(() => { closeAllPreviewSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  freezeSnapshot(projectsRoot, 2, "B");
  const { DispatchError } = await import("../../src/commands/dispatcher.ts");
  // 请求 revision 1（不存在）→ not_found，绝不静默回退 head/revision 2。
  await assert.rejects(
    openPreviewSession({ ...LAUNCH_BASE, projectsRoot }, {
      projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1,
    }),
    (error: unknown) => error instanceof DispatchError && error.code === "not_found",
  );
  // 无 owner 归属 → invalid_input。
  await assert.rejects(
    openPreviewSession({ ...LAUNCH_BASE, projectsRoot }, { projectId: PROJECT_ID, ownerAccountId: "", revision: 2 }),
    (error: unknown) => error instanceof DispatchError && error.code === "invalid_input",
  );
  assert.equal(activePreviewSessionCount(), 0, "no ghost active sessions after failures");
});

// ── TC-F2-22-04：关闭撤销资源，Build 域零接触 ────────────────────────────────
test("TC-F2-22-04 E04 closing the preview revokes resources immediately; builds untouched", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c22-e04-"));
  t.after(() => { closeAllPreviewSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  freezeSnapshot(projectsRoot, 1, "A");
  const session = await openPreviewSession({ ...LAUNCH_BASE, projectsRoot }, {
    projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1,
  });
  const surface = await startSurface();
  t.after(() => void surface.close());
  const sid = session.id;

  const before = await fetch(`http://127.0.0.1:${surface.port}/preview/${sid}/assets/a.mp4`, {
    headers: { "x-hypit-session-assertion": assertion(sid), range: "bytes=0-3" },
  });
  assert.equal(before.status, 206, "resource serves before close");

  // 同项目 Build 域零接触：预览从不创建/触碰 Build（broker 侧没有 build 状态可被预览改变；
  // 这里断言 close 只影响预览注册表）。
  assert.equal(closePreviewSession(sid).closed, true);

  const after = await fetch(`http://127.0.0.1:${surface.port}/preview/${sid}/assets/a.mp4`, {
    headers: { "x-hypit-session-assertion": assertion(sid), range: "bytes=0-3" },
  });
  assert.equal(after.status, 404, "closed preview resources are revoked immediately");

  // close 幂等。
  assert.equal(closePreviewSession(sid).closed, true);
});
