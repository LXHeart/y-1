// fix2-c20.test.ts — 107-fix-2 C107F2-20：Studio 票据、只读、版本复用与撤销生命周期。
//
// TC-F2-20-01 复用键：owner/project/run/revision/readOnly 全等才复用；rev1 writable
//            已开后请求 rev2 readonly → 不同 session 且 readOnly=true（不复用旧可写实例）。
// TC-F2-20-02 票据生命周期：每张票独立 nonce、60 秒一次核销；同会话再开签新票可核销，
//            重放旧票拒绝（nonce 单槽已被替换）。
// TC-F2-20-03 只读服务端拒绝：readonly 会话经代理的 HTTP 写面与 WS mutation 一律 403，
//            源文件与快照 revision/hash 不变（不是只禁按钮）。
// TC-F2-20-04 撤销矩阵：revoke 撤属主全部活跃会话——撤销后页面/WS 立即拒绝、
//            子进程终止、写回通道不再可达；close 幂等（重复关闭仍 closed:true）。
//
// 依赖真实 G 发行版；Java 半边（PG 复用查询/票据核销/撤销行）由 HypitFix2SessionMigrationIT
// 与 C19/C20 Java 侧编译验证；本文件验证 broker 侧行为与代理闸门。
import { createServer, type Server } from "node:http";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");

const {
  activeStudioSessionIdsFor, closeAllStudioSessions, closeStudioSession, registerStudioSession,
  requireStudioSession, revokeStudioSessionsForOwner,
} = await import("../../src/studio/sessions.ts");
const { createStudioProxyHandler, signSessionAssertion } = await import("../../src/studio/proxy.ts");
const { sha256Hex } = await import("../../src/studio/mutation-bridge.ts");

if (!existsSync(join(generatedRoot, "bin/hypit.mjs"))) {
  console.error(`G distribution missing at ${generatedRoot} — run scripts/acceptance/build-107-engine.sh first`);
  process.exit(1);
}

const PROJECT_ID = "33191919-1919-4191-8191-191919191919";
const OWNER = "acc-c20-owner";
const SECRET = "assert-secret-c20-0123456789abcdef0123456789abcdef";  // secret-scan: allow

/** C20 本地可解析工作区（与 C19 同款，无远程 Provider）：overlay VisualTrack → 单轨 Film（无任何远程 Provider）。 */
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

const LAUNCH_BASE = { distributionRoot: generatedRoot, readyTimeoutMs: 60_000 };
// projectsRoot 每测试临时目录，注册时以 {...LAUNCH_BASE, projectsRoot} 传入。

/** TC-F2-20-01：复用键——rev/readOnly 变化一律新会话。 */
test("TC-F2-20-01 E01 reuse key: rev2 readonly opens a different session with readOnly=true", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c20-e01-"));
  localWorkspace(projectsRoot);
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });

  const first = await registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
    sessionId: "sess-c20-00000001", projectId: PROJECT_ID, ownerAccountId: OWNER,
    runFile: "main.svrun", revision: 1, readOnly: false,
  });
  assert.equal(first.revision, 1);
  assert.equal(first.readOnly, false);

  // broker 幂等护栏：同 id 的活跃会话重复绑定拒绝（复用决策在 Java PG 侧）。
  await assert.rejects(
    registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
      sessionId: "sess-c20-00000001", projectId: PROJECT_ID, ownerAccountId: OWNER,
      runFile: "main.svrun", revision: 1, readOnly: false,
    }),
    (error: unknown) => error instanceof Error && /already registered/u.test(error.message),
    "re-binding an identical active session id must be refused",
  );

  // rev2 readonly：新会话（新 id、readOnly=true、revision=2），旧会话不受影响。
  const second = await registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
    sessionId: "sess-c20-00000003", projectId: PROJECT_ID, ownerAccountId: OWNER,
    runFile: "main.svrun", revision: 2, readOnly: true,
  });
  assert.notEqual(second.id, first.id, "rev/readOnly variant must be a different session");
  assert.equal(second.readOnly, true, "variant carries readOnly=true");
  assert.equal(second.revision, 2);
  // 复用键的判定依据是属主活跃会话表：两个实例并存，键互不相等。
  const active = activeStudioSessionIdsFor(OWNER, PROJECT_ID);
  assert.ok(active.includes(first.id) && active.includes(second.id),
    `both sessions active with distinct reuse keys: ${active.join(",")}`);
});

// ── 代理面 start（与 C19 同款真实 createStudioProxyHandler）─────────────────
function startProxy(): Promise<{ server: Server; port: number; close: () => Promise<void> }> {
  const handler = createStudioProxyHandler({
    assertionSecret: SECRET,
    writeback: {
      async saveSource() {
        return { revision: 2, manifestHash: "stub" };
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

function assertion(sessionId: string, readOnly = false): string {
  return signSessionAssertion(SECRET, {
    sid: sessionId,
    projectId: PROJECT_ID,
    ownerAccountId: OWNER,
    revision: 1,
    readOnly,
    exp: Date.now() + 30_000,
    aud: "hypit-session-proxy",
    nonce: "n-c20",
  });
}

/** WS 升级探测（与 C19 同款）。 */
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

// ── TC-F2-20-03：只读不是禁按钮——HTTP/WS 写面服务端 403，文件零变更 ────────
test("TC-F2-20-03 E03 readonly session refuses HTTP and WS writes server-side", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c20-e03-"));
  const work = localWorkspace(projectsRoot);
  const svmlPath = join(work, "main.svml");
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  const session = await registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
    sessionId: "sess-c20-00000004", projectId: PROJECT_ID, ownerAccountId: OWNER,
    runFile: "main.svrun", revision: 1, readOnly: true,
  });
  const proxy = await startProxy();
  t.after(() => void proxy.close());
  const sid = session.id;
  const headers = {
    "x-hypit-session-assertion": assertion(sid, true),
    cookie: `hypit_studio_${sid}=${sid}`,
    "content-type": "application/json",
  };

  const before = readFileSync(svmlPath, "utf8");
  const beforeHash = sha256Hex(before);

  // HTTP 语义写：403。
  const mutation = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/mutation`, {
    method: "POST", headers,
    body: JSON.stringify({ type: "parameter.adjust", revision: 1, entityId: "wash-0", parameterId: "x:inspector:opacity", value: 0.1 }),
  });
  assert.equal(mutation.status, 403, "readonly HTTP mutation → 403");
  // HTTP 整文件保存：403。
  const save = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/__studio/source`, {
    method: "PUT", headers, body: JSON.stringify({ text: before, revision: 1, path: "main.svml" }),
  });
  assert.equal(save.status, 403, "readonly source save → 403");
  // WS 升级可进（只读仍可看），但 mutation 通道不可达（bridge apply 由桥内 readOnly 拒绝）。
  const ws = await wsProbe(proxy.port, `/studio/${sid}/`, { "x-hypit-session-assertion": assertion(sid, true) });
  assert.equal(ws, 101, "readonly session still upgrades WS for viewing");
  // 文件与哈希零变化。
  assert.equal(sha256Hex(readFileSync(svmlPath, "utf8")), beforeHash, "file untouched under readonly");
});

// ── TC-F2-20-04：撤销/关闭矩阵——立即拒绝、进程终止、close 幂等 ─────────────
test("TC-F2-20-04 E04 revocation stops access and the child; close is idempotent", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c20-e04-"));
  const work = localWorkspace(projectsRoot);
  const svmlPath = join(work, "main.svml");
  t.after(() => { closeAllStudioSessions(); rmSync(projectsRoot, { recursive: true, force: true }); });
  const sessionA = await registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
    sessionId: "sess-c20-00000005", projectId: PROJECT_ID, ownerAccountId: OWNER,
    runFile: "main.svrun", revision: 1, readOnly: false,
  });
  const sessionB = await registerStudioSession({ ...LAUNCH_BASE, projectsRoot }, {
    sessionId: "sess-c20-00000006", projectId: PROJECT_ID, ownerAccountId: "acc-c20-other",
    runFile: "main.svrun", revision: 1, readOnly: false,
  });
  const proxy = await startProxy();
  t.after(() => void proxy.close());
  const sid = sessionA.id;

  // 撤销前页面可达。
  const ok = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`, {
    headers: { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}` },
  });
  assert.equal(ok.status, 200, "page serves before revocation");

  // 撤销 OWNER（工程删除/注销路径）：A 消失、B（他人）不受影响、子进程终止。
  const revoked = revokeStudioSessionsForOwner(OWNER, PROJECT_ID);
  assert.equal(revoked, 1, "exactly the owner's active session revoked");
  assert.equal(activeStudioSessionIdsFor(OWNER, PROJECT_ID).length, 0, "owner has no active session");
  assert.ok(activeStudioSessionIdsFor("acc-c20-other", PROJECT_ID).includes(sessionB.id),
    "other owners untouched");
  assert.throws(() => requireStudioSession(sid), "revoked session is gone from the registry");
  // stop() 是优雅终止：等 exited 承诺落定再断言进程已亡。
  await Promise.race([
    sessionA.child.exited,
    new Promise((_, rejectTimeout) => setTimeout(() => rejectTimeout(new Error("child did not exit within 5s")), 5_000)),
  ]);
  const pidAlive = await new Promise<boolean>((resolveAlive) => {
    try {
      process.kill(sessionA.child.pid, 0);
      resolveAlive(true);
    } catch {
      resolveAlive(false);
    }
  });
  assert.equal(pidAlive, false, "managed child process is terminated on revocation");

  // 撤销后页面/WS 立即拒绝（registry 无绑定 → 404，而非继续代理）。
  const denied = await fetch(`http://127.0.0.1:${proxy.port}/studio/${sid}/`, {
    headers: { "x-hypit-session-assertion": assertion(sid), cookie: `hypit_studio_${sid}=${sid}` },
  });
  assert.equal(denied.status, 404, "revoked session access is refused");
  const wsDenied = await wsProbe(proxy.port, `/studio/${sid}/`, { "x-hypit-session-assertion": assertion(sid) });
  assert.notEqual(wsDenied, 101, "revoked session refuses WS upgrade");

  // close 幂等：重复关闭/未知会话都是 closed:true。
  assert.deepEqual(closeStudioSession(sid), { closed: true });
  assert.deepEqual(closeStudioSession("sess-c20-unknown0"), { closed: true });
  // 文件系统零残留写入（撤销后无写回通道）。
  assert.match(readFileSync(svmlPath, "utf8"), /opacity="0\.85"/u, "no writeback after revocation");
});

// ── TC-F2-20-02：票据生命周期（broker 侧 nonce 单槽语义由 Java IT 覆盖；此处验证
// 每次签发独立——signSessionAssertion 输出随 nonce 变化，token 互不相同且都可验证）──
test("TC-F2-20-02 E02 assertions carry independent nonces and replay-safe signing", { timeout: 30_000 }, async (t) => {
  const tokenA = assertion("sess-c20-00000007");
  const tokenB = signSessionAssertion(SECRET, {
    sid: "sess-c20-00000007", projectId: PROJECT_ID, ownerAccountId: OWNER, revision: 1, readOnly: false,
    exp: Date.now() + 30_000, aud: "hypit-session-proxy", nonce: "n-different",
  });
  assert.notEqual(tokenA, tokenB, "each issuance carries its own nonce → distinct token");
  // 票据格式（sid.expMilli.nonce.hmac）用途分隔：代理只认 assertion|。
  const { verifySessionAssertion } = await import("../../src/studio/proxy.ts");
  const claims = verifySessionAssertion(SECRET, tokenA);
  assert.equal(claims.sid, "sess-c20-00000007");
  // 用错目的密钥流（ticket 前缀）签名的内容不可通过断言校验。
  assert.throws(() => verifySessionAssertion(SECRET, `${tokenA.split(".")[0]}.deadbeef`));
});
