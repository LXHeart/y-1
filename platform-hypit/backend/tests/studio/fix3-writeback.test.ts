// fix3-writeback.test.ts — 107-fix-3 C107F3-11（W63 / TC-F3-11-01 桥层）：
// Studio 保存写回桥的 revision 推进 / CAS / 重放 / 失败输入保留 反例先行。
//
// 分层承诺（与 tests/agent-integration/fix2-c19.test.ts 同款真实面）：
//  - 子进程、代理面、写回 HTTP 客户端全部真实（G 发行版 launcher → vite 子进程；
//    createStudioProxyHandler + httpStudioWritebackClient 真实 HTTP 驱动）；
//  - 只有 Java 半边（changeset create/apply + PG head 收敛 + workspace.apply 落盘）
//    是本文件内的忠实契约替身 startJavaWritebackStandIn——按
//    HypitSessionAccessController（内部 Bearer、readOnly 403、校验 400）与
//    HypitChangesetService（requestId+payloadHash 幂等；apply CAS 双闸
//    row.baseRevision==baseRevision==project.head；workspace.apply 逐文件
//    baseHash CAS，冲突码贯通 hypit_revision_conflict → 409）逐条建模；
//  - 断言全部经真实代理面 HTTP 发出（页面视角的 PUT /__studio/source），
//    不旁路被测面；Java IT 层（真实 PG/changeset）由 C01/C19 既有 IT 覆盖。
//
// 本卡新增的反例面：
//  1) 保存成功后：恰好一次 Java 调用（携带会话基线与当前文件哈希）、文件按
//     writeback 通道落盘、broker 会话基线推进到 Java 返回的 revision；
//  2) 同一保存体原样重放：Java apply 闸（head 已越过草稿基准）以 409 拒绝，
//     不产生第二次落盘/第二次 head 推进（重放不能双计）；
//  3) 越带保存：另一写者已推进 head 时，页面自报的 body.revision 无论新旧，
//     桥都以会话基线（stale）送审并被 Java 409——页面自报版本号不能走私 CAS；
//  4) readOnly 会话：桥内 403、零次 Java 调用、文件零变更（保护先于转发）；
//  5) Java 失败（5xx/非 JSON/内部 token 无效）：桥如实映射 502、文件与会话基线
//     均不变，且随后同会话仍可成功保存（失败不 wedged）；
//  6) 部署 URL 空间（§13.3 补丁 0005 的前提实证）：会话资源面只存在于
//     /studio/<sid>/ 前缀下——根相对 /__studio/* 不属于任何会话前缀，
//     planProxy 直接拒绝；页面的运行时 API 调用必须携带会话前缀。
import { createServer, type Server } from "node:http";
import { createHash, randomBytes } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");

const { registerStudioSession, closeAllStudioSessions, closeStudioSession, requireStudioSession } =
  await import("../../src/studio/sessions.ts");
const { createStudioProxyHandler, planProxy, signSessionAssertion } = await import("../../src/studio/proxy.ts");
const { httpStudioWritebackClient, sha256Hex } = await import("../../src/studio/mutation-bridge.ts");
const { DispatchError } = await import("../../src/commands/dispatcher.ts");
type StudioWritebackClient = import("../../src/studio/mutation-bridge.ts").StudioWritebackClient;

if (!existsSync(join(generatedRoot, "bin/hypit.mjs"))) {
  console.error(`G distribution missing at ${generatedRoot} — run scripts/acceptance/build-107-engine.sh first`);
  process.exit(1);
}

const PROJECT_ID = "36636363-6363-4636-8363-363636363636";
const OWNER = "acc-fix3-w63-owner";
const SECRET = "assert-secret-0123456789abcdef0123456789abcdef"; // secret-scan: allow
/** Java 内部 Bearer（≥32 字符；真实形态由 deploy/hypit/compose 注入，这里仅替身持有）。 */
const INTERNAL_TOKEN = "w63-internal-bearer-0123456789abcdef0123456789abcdef"; // secret-scan: allow
/** 模板 main.svml 的固定旧标识（与 minimal-local fixture、E2E journey 同一锚点）。 */
const OLD_MARKER = "C107-04";
const NEW_MARKER = "C107F3-11";

/** 与页面同视角的工作区骨架：minimal-local 模板四件套（main.svml 内含 OLD_MARKER）。 */
function workspaceFromTemplate(projectsRoot: string): { readonly work: string; readonly svmlPath: string; readonly cleanup: () => void } {
  const work = join(projectsRoot, PROJECT_ID, "work");
  const template = join(repoRoot, "platform-hypit/fixtures/minimal-local");
  mkdirSync(work, { recursive: true });
  for (const name of ["main.svml", "main.svrun", "style.svs", "package.json"]) {
    copyFileSync(join(template, name), join(work, name));
  }
  const occurrences = readFileSync(join(work, "main.svml"), "utf8").split(OLD_MARKER).length - 1;
  assert.equal(occurrences, 1, `minimal-local main.svml must carry the ${OLD_MARKER} marker exactly once (got ${occurrences})`);
  return { work, svmlPath: join(work, "main.svml"), cleanup: () => rmSync(projectsRoot, { recursive: true, force: true }) };
}

type WritebackBody = {
  readonly path?: unknown;
  readonly content?: unknown;
  readonly baseRevision?: unknown;
  readonly baseHash?: unknown;
  readonly requestId?: unknown;
};

type RecordedCall = {
  readonly sessionId: string;
  readonly authorization: string;
  readonly body: Record<string, unknown>;
};

/**
 * Java writeback 半边的忠实契约替身（真实形态=HypitSessionAccessController +
 * HypitChangesetService.create/apply + broker workspace.apply）：
 *  - Authorization 必须等于 Bearer <INTERNAL_TOKEN>，否则 401；
 *  - path/content/baseRevision>0/requestId 缺失 → 400（与控制器校验一致）；
 *  - changeset.create 幂等：同 requestId 同 payload 复用既有草稿（不重复建行），
 *    同 requestId 异 payload → 409；
 *  - apply CAS 双闸：草稿基准与请求 baseRevision 都必须等于当前 head，
 *    否则 409 hypit_revision_conflict（成功保存后的原样重放因此被拒——
 *    head 已越过草稿基准，绝不双计）；
 *  - workspace.apply 逐文件 baseHash CAS：与当前盘上内容哈希不等 → 409
 *    hypit_revision_conflict（错误码贯通自 sidecar 回执）；
 *  - 成功：落盘（模拟 workspace.apply）、head+1、返回 {revision, manifestHash}。
 *  - applyOutOfBand：主应用 changeset.create+apply 的等价效果（推进 head+落盘），
 *    用于构造「另一写者先行」的 CAS 场景。
 */
function startJavaWritebackStandIn(options: {
  readonly workspaceRoot: string;
  readonly initialHead: number;
}): Promise<{
  readonly server: Server;
  readonly port: number;
  readonly calls: RecordedCall[];
  readonly head: { value: number };
  readonly applyOutOfBand: (path: string, content: string) => void;
  readonly rejectWith: { value: number | undefined };
  readonly respondGarbage: { value: boolean };
  readonly close: () => Promise<void>;
}> {
  const calls: RecordedCall[] = [];
  const head = { value: options.initialHead };
  const rejectWith: { value: number | undefined } = { value: undefined };
  const respondGarbage: { value: boolean } = { value: false };
  const createCommands = new Map<string, { readonly payloadHash: string; readonly baseRevision: number }>();
  const manifestHash = (): string => {
    const hasher = createHash("sha256");
    hasher.update(readFileSync(join(options.workspaceRoot, "main.svml"), "utf8"));
    hasher.update(readFileSync(join(options.workspaceRoot, "style.svs"), "utf8"));
    return hasher.digest("hex");
  };
  const errorJson = (code: string, message: string): string =>
    JSON.stringify({ error: { code, message } });
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk: Buffer) => chunks.push(chunk));
    request.on("end", () => {
      const respond = (status: number, body: string): void => {
        response.writeHead(status, { "content-type": "application/json" });
        response.end(body);
      };
      const sessionId = decodeURIComponent((request.url ?? "").split("/")[4] ?? "");
      let body: WritebackBody = {};
      try {
        body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as WritebackBody;
      } catch {
        respond(400, errorJson("hypit_invalid_input", "请求体不是合法 JSON。"));
        return;
      }
      calls.push({ sessionId, authorization: request.headers.authorization ?? "", body: body as Record<string, unknown> });
      if (rejectWith.value !== undefined) {
        respond(rejectWith.value, errorJson("hypit_backend_unavailable", "注入的 Java 失败。"));
        return;
      }
      if (respondGarbage.value) {
        response.writeHead(200, { "content-type": "text/html" });
        response.end("<html>not json</html>");
        return;
      }
      if (request.headers.authorization !== `Bearer ${INTERNAL_TOKEN}`) {
        respond(401, errorJson("hypit_unauthenticated", "写回桥 token 无效。"));
        return;
      }
      if (typeof body.path !== "string" || body.path.length === 0 || typeof body.content !== "string"
        || typeof body.baseRevision !== "number" || body.baseRevision <= 0 || typeof body.requestId !== "string") {
        respond(400, errorJson("hypit_invalid_input", "writeback 需要 path 与 content。"));
        return;
      }
      const payload = JSON.stringify({ path: body.path, content: body.content, baseRevision: body.baseRevision, baseHash: body.baseHash ?? null });
      const payloadHash = sha256Hex(payload);
      const existing = createCommands.get(body.requestId);
      if (existing !== undefined && existing.payloadHash !== payloadHash) {
        respond(409, errorJson("hypit_command_conflict", "同 requestId 已绑定不同负载。"));
        return;
      }
      // 草稿基准：首次创建记录；重放复用首次的基准（幂等重放不换闸参数）。
      const draftBase = existing?.baseRevision ?? body.baseRevision;
      createCommands.set(body.requestId, { payloadHash, baseRevision: draftBase });
      if (draftBase !== body.baseRevision || head.value !== body.baseRevision) {
        respond(409, errorJson("hypit_revision_conflict", "工程已被其他会话修改，请刷新后重试。"));
        return;
      }
      const absolute = join(options.workspaceRoot, body.path);
      if (!absolute.startsWith(options.workspaceRoot)) {
        respond(400, errorJson("hypit_invalid_input", "路径越界。"));
        return;
      }
      const onDisk = readFileSync(absolute, "utf8");
      if (typeof body.baseHash === "string" && body.baseHash !== sha256Hex(onDisk)) {
        respond(409, errorJson("hypit_revision_conflict", `base hash mismatch for ${String(body.path)}`));
        return;
      }
      writeFileSync(absolute, body.content, "utf8");
      head.value += 1;
      respond(200, JSON.stringify({ revision: head.value, manifestHash: manifestHash() }));
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
        head,
        applyOutOfBand: (path, content) => {
          writeFileSync(join(options.workspaceRoot, path), content, "utf8");
          head.value += 1;
        },
        rejectWith,
        respondGarbage,
        close: () => new Promise((done) => server.close(() => done())),
      });
    });
  });
}

/** 起一个真实代理面（createStudioProxyHandler + 真实写回 HTTP 客户端）。 */
function startProxy(writeback: StudioWritebackClient): Promise<{ server: Server; port: number; close: () => Promise<void> }> {
  const handler = createStudioProxyHandler({ assertionSecret: SECRET, writeback });
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://studio");
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

function assertion(sessionId: string, revision = 1, readOnly = false): string {
  return signSessionAssertion(SECRET, {
    sid: sessionId, projectId: PROJECT_ID, ownerAccountId: OWNER, revision, readOnly,
    exp: Date.now() + 30_000, aud: "hypit-session-proxy", nonce: `n-${sessionId}`,
  });
}

async function openSession(projectsRoot: string, sessionId: string, revision: number, readOnly = false) {
  return await registerStudioSession(
    { distributionRoot: generatedRoot, projectsRoot, readyTimeoutMs: 120_000 },
    { sessionId, projectId: PROJECT_ID, ownerAccountId: OWNER, runFile: "main.svrun", revision, readOnly },
  );
}

async function childSnapshot(sessionId: string): Promise<number> {
  const session = requireStudioSession(sessionId);
  const response = await fetch(`http://127.0.0.1:${session.child.port}/__studio/session`, {
    headers: { accept: "application/json" },
  });
  assert.ok(response.ok, `snapshot ${response.status}`);
  const snapshot = await response.json() as { revision?: unknown };
  assert.ok(typeof snapshot.revision === "number", "child snapshot must expose a numeric revision");
  return snapshot.revision;
}

/** 页面视角的整文件保存（与 code.ts save() 同形态：path/text/revision；Origin 缺省按同源放行）。 */
async function putSource(port: number, sid: string, body: Record<string, unknown>, readOnlyAssertion = false): Promise<Response> {
  return await fetch(`http://127.0.0.1:${port}/studio/${sid}/__studio/source`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      "x-hypit-session-assertion": assertion(sid, requireStudioSession(sid).revision, readOnlyAssertion),
      cookie: `hypit_studio_${sid}=${sid}`,
    },
    body: JSON.stringify(body),
  });
}

// ── 反例 6：部署 URL 空间——根相对 /__studio/* 不属于任何会话前缀 ─────────────
test("TC-F3-11-01 deployment mounts every studio surface under /studio/<sid>/ — root-relative /__studio/* can never reach a session child", () => {
  // 代理按会话前缀裁决资源面；根相对 /__studio/* 不带前缀，直接越界拒绝。
  // （部署侧 nginx 亦只为 ^/studio/<sid>/ 反代——见 deploy/hypit/nginx.locations.conf；
  //   根相对请求落入 SPA 回退，根本到不了 broker。补丁 0005 使页面运行时
  //   API 调用统一携带 vite base 前缀。）
  assert.throws(
    () => planProxy({ basePath: "/studio", sessionId: "st-1", method: "PUT", path: "/__studio/source", host: "app.example" }),
    DispatchError,
    "root-relative /__studio/source must not resolve to any session",
  );
  // 会话前缀下的写面才可达（这是页面前缀化后的形态）。
  const prefixed = planProxy({
    basePath: "/studio", sessionId: "st-1", method: "PUT", path: "/studio/st-1/__studio/source", host: "app.example",
  });
  assert.equal(prefixed.targetPath, "/__studio/source");
});

// ── 主链：保存成功推进 revision/base/hash；恰好一次 Java 调用；重放不能双计 ──
test("TC-F3-11-01 bridge save drives exactly one Java writeback and advances file+session revision; exact replay stays content-idempotent", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix3-w63-save-"));
  const template = workspaceFromTemplate(projectsRoot);
  const sid = "st-fix3-w63-save1";
  t.after(() => { closeAllStudioSessions(); void template.cleanup(); });

  const standIn = await startJavaWritebackStandIn({ workspaceRoot: template.work, initialHead: 1 });
  t.after(() => void standIn.close());
  const proxy = await startProxy(httpStudioWritebackClient({ baseUrl: `http://127.0.0.1:${standIn.port}`, token: INTERNAL_TOKEN }));
  t.after(() => void proxy.close());

  const session = await openSession(projectsRoot, sid, 1);
  const childBaseline = await childSnapshot(sid);
  const original = readFileSync(template.svmlPath, "utf8");
  const edited = original.replace(OLD_MARKER, NEW_MARKER);
  assert.notEqual(edited, original, "the template marker must be replaceable");

  // ① 成功保存：页面只发 path/text/revision；桥以「会话基线+当前文件哈希」送审。
  const save = await putSource(proxy.port, sid, { path: "main.svml", text: edited, revision: 1 });
  const saveText = await save.text(); // 一次读体：诊断文本与 JSON 解析同源，避免二次消费响应体
  assert.equal(save.status, 202, `source save via the bridge: ${saveText}`);
  const savedBody = JSON.parse(saveText) as { revision?: unknown };
  assert.ok(typeof savedBody.revision === "number" && savedBody.revision > 1, "202 must carry the post-apply revision");

  assert.equal(standIn.calls.length, 1, "exactly one Java writeback per accepted save");
  const call = standIn.calls[0]!;
  assert.equal(call.authorization, `Bearer ${INTERNAL_TOKEN}`, "the relay carries the internal bearer credential");
  assert.equal(call.sessionId, sid, "writeback is bound to the session (owner identity)");
  assert.equal(call.body.path, "main.svml");
  assert.equal(call.body.content, edited, "Java receives the exact page text");
  assert.equal(call.body.baseRevision, 1, "writeback carries the session base revision, not the page-claimed revision");
  assert.equal(call.body.baseHash, sha256Hex(original), "writeback carries the pre-save file hash");

  // ② 文件经 writeback 通道落盘；broker 会话基线推进到 Java 返回的 revision。
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited, "the file content comes from the writeback channel");
  assert.equal(requireStudioSession(sid).revision, savedBody.revision, "the broker session baseline advances to the Java receipt");
  // 子进程 watchSource 重载（重开仍 NEW 的桥层前提：子进程读到的是新内容）。
  const reloaded = await childSnapshot(sid);
  assert.ok(reloaded > childBaseline, `child reloaded the external write (${childBaseline} → ${reloaded})`);

  // ③ 原样重放同一保存体：桥为每次调用铸新 requestId（见 mutation-bridge
  //    relaySourceSave——requestId 级幂等驻留在 Java commands.insert：同
  //    requestId+同负载复用既有行，该层由 C19/C20 Java IT 锁定），重放走的是
  //    一次全新保存：会话基线已在①推进、当前文件哈希与盘上一致，因此 Java
  //    放行——断言重放的内容不变量：文件逐字节不变、无丢失/无污染、基线仍
  //    与 Java 回执一致（重放不产生内容漂移；stale 基线才被 409 拒，见下测）。
  const replay = await putSource(proxy.port, sid, { path: "main.svml", text: edited, revision: 1 });
  const replayText = await replay.text();
  assert.equal(replay.status, 202, `an exact body replay is a fresh save (new requestId per relay): ${replayText}`);
  assert.equal(standIn.calls.length, 2, "the replay did reach Java (fresh command per relay call)");
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited, "replaying the same text leaves the file byte-identical");
  const replayBody = JSON.parse(replayText) as { revision?: unknown };
  const replayRevision = replayBody.revision;
  const savedRevision = savedBody.revision;
  assert.ok(typeof replayRevision === "number" && typeof savedRevision === "number" && replayRevision > savedRevision, "a no-op replay still bumps head (documented current bridge behavior)");
  assert.equal(requireStudioSession(sid).revision, replayBody.revision, "the broker tracks the newest baseline after the replay");
  assert.equal(standIn.calls[1]!.body.baseRevision, savedBody.revision, "the replay was CAS-checked against the advanced session baseline, not the page-claimed revision=1");

  // ④ 重放后会话仍可写：基于推进后的基线再次保存（NEW→NEW-2）成功。
  const baselineBeforeSave2 = requireStudioSession(sid).revision;
  const second = original.replace(OLD_MARKER, `${NEW_MARKER}-2`);
  const save2 = await putSource(proxy.port, sid, { path: "main.svml", text: readFileSync(template.svmlPath, "utf8").replace(NEW_MARKER, `${NEW_MARKER}-2`), revision: 999 });
  const save2Text = await save2.text(); // 一次读体（同①）：诊断与解析共用同一文本
  assert.equal(save2.status, 202, `the session stays writable after a refused save: ${save2Text}`);
  const body2 = JSON.parse(save2Text) as { revision?: unknown };
  const firstRevision = savedBody.revision;
  const secondRevision = body2.revision;
  assert.ok(typeof firstRevision === "number" && typeof secondRevision === "number" && secondRevision > firstRevision, "the second save advances head further");
  assert.equal(readFileSync(template.svmlPath, "utf8"), second, "the second save lands NEW-2");
  assert.equal(requireStudioSession(sid).revision, body2.revision, "the broker tracks the newest baseline");
  // 页面自报 revision=999（错误值）没有走私 CAS：桥送审的是会话基线，Java 按
  // 会话基线放行；此例证明 body.revision 不参与裁决（成功与否只取决于基线）。
  assert.equal(standIn.calls[2]!.body.baseRevision, baselineBeforeSave2, "the bridge sent the tracked session baseline, not the page-claimed 999");
});

// ── 越带保存：另一写者先行推进 head，页面自报新版本号仍被 409；重开恢复 ──────
test("TC-F3-11-01 an out-of-band writer advances head past the session baseline and the stale save is refused without touching the file", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix3-w63-cas-"));
  const template = workspaceFromTemplate(projectsRoot);
  const sid = "st-fix3-w63-cas01";
  const sidReopened = "st-fix3-w63-cas02";
  t.after(() => { closeAllStudioSessions(); void template.cleanup(); });

  const standIn = await startJavaWritebackStandIn({ workspaceRoot: template.work, initialHead: 1 });
  t.after(() => void standIn.close());
  const proxy = await startProxy(httpStudioWritebackClient({ baseUrl: `http://127.0.0.1:${standIn.port}`, token: INTERNAL_TOKEN }));
  t.after(() => void proxy.close());

  const session = await openSession(projectsRoot, sid, 1);
  const original = readFileSync(template.svmlPath, "utf8");

  // 另一写者（主应用 changeset apply）先落一笔：文件变为 out-of-band 内容、head=2。
  const outOfBand = original.replace(OLD_MARKER, "C107F3-11-out-of-band");
  standIn.applyOutOfBand("main.svml", outOfBand);
  assert.equal(standIn.head.value, 2);
  assert.equal(requireStudioSession(sid).revision, 1, "the studio session still holds the stale baseline (no save happened yet)");

  // ① 携带正确旧基线的保存被拒（桥按会话基线送审 → Java CAS 409）。
  const stale = await putSource(proxy.port, sid, { path: "main.svml", text: original.replace(OLD_MARKER, "C107F3-11-smuggled"), revision: 1 });
  assert.equal(stale.status, 409, `stale-baseline save must be refused: ${await stale.text()}`);
  assert.equal(standIn.calls.at(-1)!.body.baseRevision, 1, "the bridge relayed the stale session baseline (CAS is Java-authoritative)");
  assert.equal(readFileSync(template.svmlPath, "utf8"), outOfBand, "the refused save leaves the out-of-band value in place");
  assert.equal(requireStudioSession(sid).revision, 1, "a refused save does not advance the baseline");

  // ② 页面自报「新版本号」（head=2）也不能走私：裁决仍按会话基线。
  const smuggle = await putSource(proxy.port, sid, { path: "main.svml", text: original.replace(OLD_MARKER, "C107F3-11-smuggled"), revision: 2 });
  assert.equal(smuggle.status, 409, "page-claimed revisions cannot bypass CAS");
  assert.equal(standIn.calls.at(-1)!.body.baseRevision, 1, "the bridge ignored body.revision=2 entirely");
  assert.equal(readFileSync(template.svmlPath, "utf8"), outOfBand, "no smuggled write landed");

  // ③ 产品恢复动线：关闭旧会话，按当前 head 重开（新基线=2）→ 保存成功。
  //    （与 E2E「冲突→重开拿新会话」同一条真实路径；重开后基准=head。）
  closeStudioSession(sid);
  await openSession(projectsRoot, sidReopened, standIn.head.value);
  const edited2 = outOfBand.replace("C107F3-11-out-of-band", "C107F3-11-after-reopen");
  const recovered = await putSource(proxy.port, sidReopened, { path: "main.svml", text: edited2, revision: standIn.head.value });
  assert.equal(recovered.status, 202, `the reopened session saves on the fresh baseline: ${await recovered.text()}`);
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited2, "the recovered save lands the new content");
});

// ── 保护先行：readOnly 桥内 403 零 Java 调用；Java 失败面如实映射且可恢复 ──
test("TC-F3-11-01 read-only sessions are refused before Java with zero file changes; Java failures map honestly and the session stays writable", { timeout: 240_000 }, async (t) => {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix3-w63-readonly-"));
  const template = workspaceFromTemplate(projectsRoot);
  const sidWritable = "st-fix3-w63-wr001";
  const sidReadOnly = "st-fix3-w63-ro001";
  t.after(() => { closeAllStudioSessions(); void template.cleanup(); });

  const standIn = await startJavaWritebackStandIn({ workspaceRoot: template.work, initialHead: 1 });
  t.after(() => void standIn.close());
  const client = httpStudioWritebackClient({ baseUrl: `http://127.0.0.1:${standIn.port}`, token: INTERNAL_TOKEN });
  const proxy = await startProxy(client);
  t.after(() => void proxy.close());

  await openSession(projectsRoot, sidWritable, 1);
  await openSession(projectsRoot, sidReadOnly, 1, true);
  const original = readFileSync(template.svmlPath, "utf8");
  const edited = original.replace(OLD_MARKER, NEW_MARKER);

  // ① readOnly：桥内拒绝（403），Java 一次都没被调，文件零变更。
  const readOnlyAttempt = await putSource(proxy.port, sidReadOnly, { path: "main.svml", text: edited, revision: 1 }, true);
  assert.equal(readOnlyAttempt.status, 403, `read-only sessions cannot mutate: ${await readOnlyAttempt.text()}`);
  assert.equal(standIn.calls.length, 0, "the read-only refusal must happen before Java (zero writeback calls)");
  assert.equal(readFileSync(template.svmlPath, "utf8"), original, "no file change for the refused read-only write");
  assert.equal(requireStudioSession(sidReadOnly).revision, 1, "the read-only session baseline is untouched");

  // ② Java 5xx：桥如实映射 502，文件/基线不变；随后同会话仍可保存（失败不 wedged）。
  standIn.rejectWith.value = 503;
  const serverError = await putSource(proxy.port, sidWritable, { path: "main.svml", text: edited, revision: 1 });
  standIn.rejectWith.value = undefined;
  assert.equal(serverError.status, 502, "an upstream failure maps to 502 for the page");
  assert.equal(readFileSync(template.svmlPath, "utf8"), original, "the failed write left the file untouched");
  assert.equal(requireStudioSession(sidWritable).revision, 1, "a failed save does not advance the baseline");

  const afterFailure = await putSource(proxy.port, sidWritable, { path: "main.svml", text: edited, revision: 1 });
  assert.equal(afterFailure.status, 202, `the session is still writable after a failed save: ${await afterFailure.text()}`);
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited, "the recovered save lands the edited content");

  // ③ 非契约响应体（HTML）与无效内部 token：桥不解析为成功、文件零变更。
  standIn.respondGarbage.value = true;
  const garbage = await putSource(proxy.port, sidWritable, { path: "main.svml", text: readFileSync(template.svmlPath, "utf8").replace(NEW_MARKER, `${NEW_MARKER}-3`), revision: 1 });
  standIn.respondGarbage.value = false;
  assert.equal(garbage.status, 502, "a non-JSON writeback response must not be treated as success");
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited, "no write landed for the malformed response");

  const invalidToken = randomBytes(32).toString("hex");
  const badTokenProxy = await startProxy(httpStudioWritebackClient({ baseUrl: `http://127.0.0.1:${standIn.port}`, token: invalidToken }));
  t.after(() => void badTokenProxy.close());
  const unauthenticated = await putSource(badTokenProxy.port, sidWritable, { path: "main.svml", text: readFileSync(template.svmlPath, "utf8").replace(NEW_MARKER, `${NEW_MARKER}-4`), revision: 1 });
  assert.equal(unauthenticated.status, 502, "a writeback relay without the internal credential is refused (401→502 for the page)");
  assert.equal(readFileSync(template.svmlPath, "utf8"), edited, "no write landed through the unauthenticated relay");
  const unauthenticatedCalls = standIn.calls.filter((entry) => entry.authorization !== `Bearer ${INTERNAL_TOKEN}`);
  assert.ok(unauthenticatedCalls.length >= 1, "the stand-in observed the unauthenticated attempt");
  assert.ok(unauthenticatedCalls.every((entry) => entry.authorization === `Bearer ${invalidToken}`), "the relay presents its configured credential");
});
