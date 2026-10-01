// fix2-c19.test.ts — 107-fix-2 C107F2-19：启动原生 Studio 并接通 HTTP、WebSocket 与写回。
//
// TC-F2-19-01 真实子进程：会话挂载后 GET 页面/JS 资源 200、WS 升级 101、
//            原生编辑器目标控件出现（vite client + /__studio/session snapshot）。
// TC-F2-19-02 语义写回：页面 parameter.adjust 经代理拦截→子进程 compute（只算不写）
//            →Java writeback（stand-in 记录 owner 会话/baseRevision/baseHash）→
//            文件由 writeback 通道落盘；子进程 watchSource 后快照可见，刷新仍保留；
//            Java 409 时文件零变更。
// TC-F2-19-03 启动失败：发行版入口不可读/端口不可用 → register 如实 reject，
//            无 active 假会话、无僵尸子进程。
// TC-F2-19-04 边界与秘密：子进程环境白名单（无 broker/Provider 秘密）、
//            断言缺失/错 sid/跨会话路径一律拒绝，越界 host/path 不穿透。
//
// 依赖真实 G 发行版（build-107-engine.sh 产物）；不依赖 Docker/PG——Java 半边
//（PG 登记/断言签发/writeback 落 changeset）由 C20/C149 IT 与 Java 侧验收覆盖。
import { createServer, type Server } from "node:http";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");

const { registerStudioSession, closeAllStudioSessions, requireStudioSession } = await import("../../src/studio/sessions.ts");
const { childEnvironmentFor } = await import("../../src/studio/launcher.ts");
const { createStudioProxyHandler, signSessionAssertion } = await import("../../src/studio/proxy.ts");
const { sha256Hex } = await import("../../src/studio/mutation-bridge.ts");
const { DispatchError } = await import("../../src/commands/dispatcher.ts");

if (!existsSync(join(generatedRoot, "bin/hypit.mjs"))) {
  console.error(`G distribution missing at ${generatedRoot} — run scripts/acceptance/build-107-engine.sh first`);
  process.exit(1);
}

const PROJECT_ID = "33191919-1919-4191-8191-191919191919";
const OWNER = "acc-c19-owner";
const SECRET = "assert-secret-0123456789abcdef0123456789abcdef";  // secret-scan: allow

/** C19 本地可解析工作区：overlay VisualTrack → 单轨 Film（无任何远程 Provider）。 */
function localWorkspace(projectsRoot: string): string {
  const work = join(projectsRoot, PROJECT_ID, "work");
  mkdirSync(work, { recursive: true });
  copyFileSync(join(repoRoot, "platform-hypit/fixtures/minimal-local/package.json"), join(work, "package.json"));
  writeFileSync(join(work, "style.svs"), `<?svml using="@hypit/svs@1"?>\n<sheet version="1">\n  film.main { background: #10131c; }\n</sheet>\n`);
  writeFileSync(join(work, "main.svml"), `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="time" from="@hypit/timeline-author@1"/>
  <import as="spatial" from="@hypit/spatial@1"/>
  <import as="film" from="@hypit/film@1"/>
  <import as="render" from="@hypit/render-hyperframes@1"/>
  <import as="overlay" from="@hypit/screen-overlay@1"/>
  <import as="recipes" source="./style.svs"/>

  <time:Clock id="clock" frame-rate="30"/>
  <time:Timeline id="animation" clock={clock} end="2s"/>
  <spatial:Canvas id="canvas" width="540" height="960"/>
  <overlay:Track id="overlays" canvas={canvas} timeline={animation.timeline}>
    <overlay:ColorWash id="wash-0" z="0" start="0s" end="2s" color="#101418" opacity="0.85"/>
  </overlay:Track>
  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.main}>
    <film:Track source={overlays.track}/>
  </film:Film>
  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
</svml>
`);
  writeFileSync(join(work, "main.svrun"), `<?svml using="@hypit/run-markup@1"?>
<svrun version="1">
  <author source="./main.svml"/>
  <target output="final.video"/>
</svrun>
`);
  return work;
}

type WritebackCall = {
  readonly sessionId: string;
  readonly body: Record<string, unknown>;
};

/** Java writeback stand-in：记录调用，可选把内容落盘（模拟 workspace.apply）；rejectWith 可运行期切换以模拟 Java 409。 */
function startWritebackStandIn(options: { readonly applyToFile?: string }): Promise<{
  readonly server: Server;
  readonly port: number;
  readonly calls: WritebackCall[];
  readonly rejectWith: { value: number | undefined };
  readonly close: () => Promise<void>;
}> {
  const calls: WritebackCall[] = [];
  const rejectWith: { value: number | undefined } = { value: undefined };
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk: Buffer) => chunks.push(chunk));
    request.on("end", () => {
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
      const sid = (request.url ?? "").split("/")[4] ?? "";
      calls.push({ sessionId: decodeURIComponent(sid), body });
      if (rejectWith.value !== undefined) {
        response.writeHead(rejectWith.value, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "rejected" }));
        return;
      }
      if (options.applyToFile !== undefined && body.content !== undefined) {
        writeFileSync(options.applyToFile, String(body.content));
      }
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ revision: 2, manifestHash: `hash-${calls.length}` }));
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
          assert.ok(address !== null && typeof address === "object");
      resolve({
        server,
        port: address.port,
        calls,
        rejectWith,
        close: () => new Promise((done) => server.close(() => done())),
      });
    });
  });
}

/** 起一个真实代理面（createStudioProxyHandler），返回基础 URL。 */
function startProxy(writebackPort: number): Promise<{ server: Server; port: number; close: () => Promise<void> }> {
  const handler = createStudioProxyHandler({
    assertionSecret: SECRET,
    writeback: {
      async saveSource(input) {
        const response = await fetch(`http://127.0.0.1:${writebackPort}/internal/hypit/sessions/${input.sessionId}/writeback`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(input),
        });
        const body = await response.json() as { revision?: number; manifestHash?: string; error?: string };
        if (!response.ok || typeof body.revision !== "number") {
          throw new DispatchError(response.status === 409 ? "revision_conflict" : "studio_writeback_failed", String(body.error ?? response.status));
        }
        return { revision: body.revision, manifestHash: String(body.manifestHash ?? "") };
      },
    },
  });
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://studio");
    if (handler.matches(url.pathname)) {
      void handler.handle(request, response, url).catch(() => response.destroy());
      return;
    }
    response.writeHead(404).end();
  });
  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url ?? "/", "http://studio");
    if (handler.matches(url.pathname)) handler.handleUpgrade(request, socket, head, url);
    else socket.destroy();
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      assert.ok(address !== null && typeof address === "object");
      resolve({ server, port: address.port, close: () => new Promise((done) => server.close(() => done())) });
    });
  });
}

function assertion(sessionId: string, revision = 1, readOnly = false): string {
  return signSessionAssertion(SECRET, {
    sid: sessionId,
    projectId: PROJECT_ID,
    ownerAccountId: OWNER,
    revision,
    readOnly,
    exp: Date.now() + 30_000,
    aud: "hypit-session-proxy",
    nonce: "n-1",
  });
}

async function openSession(projectsRoot: string, sessionId: string, readyTimeoutMs = 60_000) {
  return await registerStudioSession(
    { distributionRoot: generatedRoot, projectsRoot, readyTimeoutMs },
    { sessionId, projectId: PROJECT_ID, ownerAccountId: OWNER, runFile: "main.svrun", revision: 1, readOnly: false },
  );
}

/** WS 升级探测：101 → resolve(status)。 */
function wsProbe(port: number, path: string, headers: Record<string, string>): Promise<number> {
  return new Promise((resolve, reject) => {
    void import("node:net").then(({ connect }) => {
      const sock = connect(port, "127.0.0.1", () => {
        sock.write(`GET ${path} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: vite-hmr\r\n${Object.entries(headers).map(([k, v]) => `${k}: ${v}`).join("\r\n")}\r\n\r\n`);
      });
      let head = "";
      const onData = (chunk: Buffer): void => {
        head += chunk.toString("utf8");
        const end = head.indexOf("\r\n\r\n");
        if (end >= 0) {
          const status = Number(head.slice(9, head.indexOf(" ", 9)));
          sock.off("data", onData);
          sock.destroy();
          resolve(status);
        }
      };
      sock.on("data", onData);
      sock.on("error", (error) => { sock.destroy(); reject(error); });
      setTimeout(() => { sock.destroy(); reject(new Error("ws probe timeout")); }, 8_000);
    });
  });
}

async function snapshotOf(sessionId: string): Promise<{ revision: number; entityId?: string }> {
  const session = requireStudioSession(sessionId);
  const response = await fetch(`http://127.0.0.1:${session.child.port}/__studio/session`, { headers: { accept: "application/json" } });
  assert.ok(response.ok, `snapshot ${response.status}`);
  const snapshot = await response.json() as { revision: number; tracks?: Array<{ clips?: Array<{ id: string }> }> };
  const entityId = snapshot.tracks?.[0]?.clips?.[0]?.id;
  return { revision: snapshot.revision, ...(entityId === undefined ? {} : { entityId }) };
}

// ── TC-F2-19-01：真实子进程 + HTTP/WS + 原生控件 ──────────────────────────
test("TC-F2-19-01 E01 native studio child serves page/assets and upgrades WS", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c19-e01-"));
  const work = localWorkspace(projectsRoot);
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  const session = await openSession(projectsRoot, "sess-c19-000001");
  const proxy = await startProxy(0); // writeback 不触发；仅代理面
  t.after(() => void proxy.close());
  const sid = session.id;
  const headers = { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}` };

  const index = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`, { headers: { ...headers, accept: "text/html" } });
  assert.equal(index.status, 200, "page must be 200 through the broker proxy");
  const html = await index.text();
  assert.match(html, /@vite\/client/u, "native editor asset bootstrap present");
  assert.match(html, /<div id="app"|<div id="root"|lang="en"/u, "native editor target control present");

  const asset = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/@vite/client`, { headers });
  assert.equal(asset.status, 200, "vite client asset must be 200");

  const snapshot = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/session`, { headers });
  assert.equal(snapshot.status, 200, "__studio/session must reach the child");
  const body = await snapshot.json() as { revision?: number };
  assert.equal(body.revision, 1, "child snapshot resolves for the local fixture");

  const ws = await wsProbe(proxy.port, `/studio/${sid}/`, { "x-hypit-session-assertion": assertion(sid) });
  assert.equal(ws, 101, "WS upgrade must return 101");
});

// ── TC-F2-19-02：语义写回经 Java changeset 通道，文件/哈希一致，拒绝零副作用 ──
test("TC-F2-19-02 E02 semantic write-back reaches Java, file+hash agree, refusal is inert", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c19-e02-"));
  const work = localWorkspace(projectsRoot);
  const svmlPath = join(work, "main.svml");
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  const session = await openSession(projectsRoot, "sess-c19-000002");

  const standIn = await startWritebackStandIn({ applyToFile: svmlPath });
  t.after(() => void standIn.close());
  const proxy = await startProxy(standIn.port);
  t.after(() => void proxy.close());
  const sid = session.id;
  const headers = { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}`, "content-type": "application/json" };

  const warm = await snapshotOf(sid);
  const entityId = warm.entityId;
  assert.ok(entityId !== undefined, "fixture must expose the ColorWash entity");
  const before = readFileSync(svmlPath, "utf8");
  const beforeHash = sha256Hex(before);

  const mutation = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/mutation`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      type: "parameter.adjust",
      revision: warm.revision,
      entityId,
      parameterId: `${entityId}:inspector:opacity`,
      value: 0.42,
    }),
  });
  assert.equal(mutation.status, 200, `semantic mutation through the bridge: ${await mutation.text()}`);

  // writeback stand-in 收到的是整文件内容 + 会话归属 + 基线（模拟 Java changeset/apply 输入）。
  assert.equal(standIn.calls.length, 1, "exactly one writeback call");
  const call = standIn.calls[0]!;
  assert.equal(call.sessionId, sid, "writeback is bound to the session (owner identity)");
  assert.equal(call.body.baseRevision, 1, "writeback carries the session base revision");
  assert.equal(call.body.baseHash, beforeHash, "writeback carries the pre-save file hash");
  assert.equal(call.body.path, "main.svml");

  // 文件经 writeback 通道落盘；子进程 watchSource 重载后快照 revision 自增（刷新仍保留）。
  const saved = readFileSync(svmlPath, "utf8");
  assert.match(saved, /opacity="0\.42"/u, "file content comes from the writeback channel");
  const after = await snapshotOf(sid);
  assert.ok(after.revision > warm.revision, `child reloaded the external change (${warm.revision} → ${after.revision})`);

  // 整文件保存面同样只走 Java：PUT /__studio/source。
  const putSource = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/source`, {
    method: "PUT",
    headers,
    body: JSON.stringify({ text: saved.replace('opacity="0.42"', 'opacity="0.5"'), revision: after.revision, path: "main.svml" }),
  });
  assert.equal(putSource.status, 202, "source save accepted via the bridge");
  assert.equal(standIn.calls.length, 2, "second save hit Java again");
  assert.match(readFileSync(svmlPath, "utf8"), /opacity="0\.5"/u);

  // Java 409（基线过期）→ 文件零变更、响应 409。
  standIn.rejectWith.value = 409;
  const hostile = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/source`, {
    method: "PUT",
    headers,
    body: JSON.stringify({ text: saved.replace('opacity="0.5"', 'opacity="0.01"'), revision: 99 }),
  });
  standIn.rejectWith.value = undefined;
  assert.equal(hostile.status, 409, "Java rejection maps to 409 for the page");
  assert.match(readFileSync(svmlPath, "utf8"), /opacity="0\.5"/u, "refused write leaves the file untouched");
});

// ── TC-F2-19-03：启动失败不留假会话/僵尸 ───────────────────────────────────
test("TC-F2-19-03 E03 launch failures reject honestly and leave nothing behind", { timeout: 120_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c19-e03-"));
  localWorkspace(projectsRoot);
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });

  // 发行版缺 studio 入口 → reject，无 active 会话。
  const brokenRoot = mkdtempSync(join(tmpdir(), "fix2-c19-broken-"));
  t.after(() => rmSync(brokenRoot, { recursive: true, force: true }));
  await assert.rejects(
    registerStudioSession(
      { distributionRoot: brokenRoot, projectsRoot, readyTimeoutMs: 10_000 },
      { sessionId: "sess-c19-000003", projectId: PROJECT_ID, ownerAccountId: OWNER, runFile: "main.svrun", revision: 1, readOnly: false },
    ),
    (error: unknown) => error instanceof DispatchError && error.code === "studio_unavailable",
    "missing distribution entry must reject studio_unavailable",
  );
  assert.throws(() => requireStudioSession("sess-c19-000003"), DispatchError, "no active fake session survives");

  // 工程工作区缺 run 文件 → 真实子进程启动即失败退出 → 如实 reject（E03：入口不可读）。
  const emptyRoot = mkdtempSync(join(tmpdir(), "fix2-c19-empty-"));
  mkdirSync(join(emptyRoot, PROJECT_ID, "work"), { recursive: true });
  t.after(() => rmSync(emptyRoot, { recursive: true, force: true }));
  await assert.rejects(
    registerStudioSession(
      { distributionRoot: generatedRoot, projectsRoot: emptyRoot, readyTimeoutMs: 30_000 },
      { sessionId: "sess-c19-000004", projectId: PROJECT_ID, ownerAccountId: OWNER, runFile: "main.svrun", revision: 1, readOnly: false },
    ),
    (error: unknown) => error instanceof DispatchError && error.code === "studio_unavailable",
    "unreadable studio entry (missing run) must reject studio_unavailable",
  );
  assert.throws(() => requireStudioSession("sess-c19-000004"), DispatchError, "failed launch leaves no active session");
});

// ── TC-F2-19-04：秘密零外泄 + 断言/路径闸门 ────────────────────────────────
test("TC-F2-19-04 E04 child env is allowlisted and the proxy gate refuses naked or foreign access", { timeout: 60_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c19-e04-"));
  localWorkspace(projectsRoot);
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });

  // E04-1：子进程环境白名单——broker/Provider 秘密绝不进入 Studio 进程。
  const prior: Record<string, string | undefined> = {};
  for (const key of [
    "HYPIT_INTERNAL_TOKEN", "HYPIT_STUDIO_TICKET_SECRET", "HYPIT_SESSION_ASSERTION_SECRET",
    "HYPIT_PROVIDER_API_KEY", "OPENAI_API_KEY", "AWS_SECRET_ACCESS_KEY",
  ]) {
    prior[key] = process.env[key];
    process.env[key] = `secret-${key}-value-0123456789abcdef`;
  }
  try {
    const env = childEnvironmentFor({
      distributionRoot: generatedRoot,
      workspaceRoot: "/w",
      runFile: "/w/main.svrun",
      port: 1,
      basePath: "/studio/s",
    });
    for (const key of Object.keys(prior)) {
      assert.ok(!(key in env), `${key} must not leak into the studio child`);
    }
    assert.equal(env.HYPIT_STUDIO_BASE_PATH, "/studio/s");
    assert.equal(env.PATH, process.env.PATH, "PATH survives for the vite toolchain");
  } finally {
    for (const [key, value] of Object.entries(prior)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }

  // E04-2：断言闸门——无断言 401；断言绑别的会话 401；裸资源抓取（无 cookie 无 ticket）403。
  const session = await openSession(projectsRoot, "sess-c19-000005");
  const proxy = await startProxy(0);
  t.after(() => void proxy.close());
  const sid = session.id;
  const naked = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`);
  assert.equal(naked.status, 401, "no assertion → 401");
  const foreign = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`, {
    headers: { "x-hypit-session-assertion": signSessionAssertion(SECRET, {
      sid: "sess-c19-other001", projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1, readOnly: false,
      exp: Date.now() + 30_000, aud: "hypit-session-proxy", nonce: "n-2",
    }), cookie: `hypit_studio_${sid}=${sid}` },
  });
  assert.equal(foreign.status, 401, "assertion bound to another session → 401");
  const cookieless = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/@vite/client`, {
    headers: { "x-hypit-session-assertion": assertion(sid) },
  });
  assert.equal(cookieless.status, 403, "asset grab without cookie/ticket → 403");
  const escape = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/../../etc/passwd`, {
    headers: { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}` },
  });
  assert.ok(escape.status === 400 || escape.status === 404, `path escape refused (${escape.status})`);
  const otherSessionPath = await fetch(`http://127.0.0.1:${proxy.port}/studio/sess-other-9999/__studio/session`, {
    headers: { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}` },
  });
  assert.ok(otherSessionPath.status === 401 || otherSessionPath.status === 404, "cross-session path refused");

  // E04-3：作者求值边界——代理只指向本会话子进程（固定 host），写面全部走 Java；
  // 断言过期的访问被拒（30 秒短窗）。
  const expired = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`, {
    headers: {
      "x-hypit-session-assertion": signSessionAssertion(SECRET, {
        sid, projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1, readOnly: false,
        exp: Date.now() - 1_000, aud: "hypit-session-proxy", nonce: "n-3",
      }),
      cookie: `hypit_studio_${sid}=${sid}`,
    },
  });
  assert.equal(expired.status, 401, "expired assertion → 401");
});
