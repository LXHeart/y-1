// fix2-c03.test.ts — 107-fix-2 C107F2-03：镜像目录、模板资源与运行期配置读取。
//
// TC-F2-03-01 新构建两镜像+只读 rootfs：容器内 fixtures/catalog/入口可读，
//            模板 provenance 与仓库一致，Node24 引擎（真实 docker build/run）。
// TC-F2-03-02 socket=/sockets、slot=/slots：loadConfig 返回指定值（BE-05 反例修复）。
// TC-F2-03-03 模板根缺失/catalog 越界：启动预检非零、无 ready 工程（真实进程退出）。
// TC-F2-03-04 隔离卷已有工程+journal：新镜像启动重读——原文件 hash 不变、无重置副作用。
//
// Docker 缺席时本文件如实失败（必需环境，不做 mock 替代；§9.3/§13）。
import { spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import assert from "node:assert/strict";
import test, { after } from "node:test";
import { guardedCompose, requireFreshSession } from "./guarded-compose.ts";

const repoRoot = resolve(import.meta.dirname, "../../../..");

const PROJECT = "fix2-c03-images";
requireFreshSession(PROJECT);
const token = randomBytes(24).toString("hex");
const dataDir = mkdtempSync(join(tmpdir(), "fix2-c03-restart-"));
after(() => rmSync(dataDir, { recursive: true, force: true }));
const COMPOSE_FILE = "tests/e2e/fixtures/hypit-image-isolation.compose.yml";
function dc(args: string[], extraEnv: NodeJS.ProcessEnv = {}, timeout = 600_000): string {
  return guardedCompose(PROJECT, COMPOSE_FILE, args, {
    ...process.env, HYPIT_INTERNAL_TOKEN: token, HYPIT_RESTART_DATA_DIR: dataDir, ...extraEnv,
  }, timeout);
}

process.env.HYPIT_STATE_HOME ??= join(repoRoot, "data/hypit/runner-state");

const { loadConfig, assertTemplateResources } = await import("../../src/config.ts");

// ── TC-F2-03-02：config 真实读取文档化 env（BE-05 反例） ──────────────────
test("TC-F2-03-02 loadConfig 读取 HYPIT_RUNNER_SOCKET_DIR/SLOT_ROOT/TMP_ROOT/TEMPLATES_ROOT/FIXTURES_ROOT", () => {
  const dir = mkdtempSync(join(tmpdir(), "fix2-c03-cfg-"));
  const sockets = join(dir, "sockets");
  const slots = join(dir, "slots");
  const tmp = join(dir, "tmp");
  const templates = join(dir, "templates");
  const fixtures = join(dir, "fixtures");
  const prior: Record<string, string | undefined> = {};
  for (const key of ["HYPIT_RUNNER_SOCKET_DIR", "HYPIT_RUNNER_SLOT_ROOT", "HYPIT_RUNNER_TMP_ROOT", "HYPIT_TEMPLATES_ROOT", "HYPIT_FIXTURES_ROOT", "HYPIT_DATA_ROOT"]) {
    prior[key] = process.env[key];
    process.env[key] = { HYPIT_RUNNER_SOCKET_DIR: sockets, HYPIT_RUNNER_SLOT_ROOT: slots, HYPIT_RUNNER_TMP_ROOT: tmp, HYPIT_TEMPLATES_ROOT: templates, HYPIT_FIXTURES_ROOT: fixtures, HYPIT_DATA_ROOT: join(dir, "hypit") }[key]!;
  }
  try {
    const config = loadConfig();
    assert.equal(config.runnerSocketDir, sockets, "runnerSocketDir 必须取 env（BE-05 反例：曾回退 dataRoot 内目录）");
    assert.equal(config.runnerSlotRoot, slots, "runnerSlotRoot 必须取 env");
    assert.equal(config.runnerTmpRoot, tmp, "runnerTmpRoot 必须取 env");
    assert.equal(config.templatesRoot, templates, "templatesRoot 必须取 env");
    assert.equal(config.fixturesRoot, fixtures, "fixturesRoot 必须取 env");
    // backendRoot 由 import.meta.url 推导（不依赖 CWD/generatedRoot 猜测）。
    assert.equal(config.backendRoot, resolve(import.meta.dirname, "../.."), "backendRoot 必须由源码位置推导");
    assert.ok(existsSync(join(config.backendRoot, "src/config.ts")), "backendRoot 必须指向 broker 源码根");
  } finally {
    for (const [key, value] of Object.entries(prior)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
  rmSync(dir, { recursive: true, force: true });
});

// ── TC-F2-03-03：启动预检（模板根缺失 / catalog 越界 / 缺 pinned commit） ──
function makeTemplatesRoot(dir: string, catalog: object): string {
  const templates = join(dir, "platform-hypit", "templates");
  const fixtures = join(dir, "platform-hypit", "fixtures");
  mkdirSync(join(templates, "demo"), { recursive: true });
  mkdirSync(join(fixtures, "minimal-local"), { recursive: true });
  for (const file of ["package.json", "main.svml", "main.svrun"]) {
    writeFileSync(join(fixtures, "minimal-local", file), "stub");
  }
  writeFileSync(join(templates, "catalog.json"), JSON.stringify(catalog));
  return templates;
}

test("TC-F2-03-03 预检：模板根缺失/catalog 越界/未 pinned → 抛错且不创建任何 ready 工程", () => {
  const validCatalog = {
    sourceCommit: "a".repeat(40),
    templates: [{ templateId: "demo", sourcePath: "platform-hypit/templates/demo" }],
  };
  // 正例：合法布局通过（防「拒绝一切」的假阳性验证器）。
  const okDir = mkdtempSync(join(tmpdir(), "fix2-c03-ok-"));
  const okTemplates = makeTemplatesRoot(okDir, validCatalog);
  assertTemplateResources({
    ...loadConfig(), fixturesRoot: join(okDir, "platform-hypit/fixtures"), templatesRoot: okTemplates,
  } as ReturnType<typeof loadConfig>);
  rmSync(okDir, { recursive: true, force: true });

  // 反例1：模板根不存在。
  const missing = mkdtempSync(join(tmpdir(), "fix2-c03-missing-"));
  assert.throws(
    () => assertTemplateResources({ ...loadConfig(), fixturesRoot: join(missing, "nope"), templatesRoot: join(missing, "nope2") } as ReturnType<typeof loadConfig>),
    /startup-precheck failed: fixtures-root/,
  );
  rmSync(missing, { recursive: true, force: true });

  // 反例2：catalog sourcePath 越界（.. 逃逸）。
  const escape = mkdtempSync(join(tmpdir(), "fix2-c03-escape-"));
  const escapeTemplates = makeTemplatesRoot(escape, {
    sourceCommit: "b".repeat(40),
    templates: [{ templateId: "evil", sourcePath: "platform-hypit/templates/../../../etc" }],
  });
  assert.throws(
    () => assertTemplateResources({ ...loadConfig(), fixturesRoot: join(escape, "platform-hypit/fixtures"), templatesRoot: escapeTemplates } as ReturnType<typeof loadConfig>),
    /越界/,
  );
  rmSync(escape, { recursive: true, force: true });

  // 反例3：未携带 pinned sourceCommit。
  const unpinned = mkdtempSync(join(tmpdir(), "fix2-c03-unpinned-"));
  const unpinnedTemplates = makeTemplatesRoot(unpinned, { templates: [] });
  assert.throws(
    () => assertTemplateResources({ ...loadConfig(), fixturesRoot: join(unpinned, "platform-hypit/fixtures"), templatesRoot: unpinnedTemplates } as ReturnType<typeof loadConfig>),
    /startup-precheck failed/,
  );
  rmSync(unpinned, { recursive: true, force: true });
});

test("TC-F2-03-03 预检失败=broker 进程启动非零（不监听、无 healthz 假阳性）", { timeout: 60_000 }, () => {
  // 真实进程：main.mjs 在监听前执行 assertTemplateResources。
  const dir = mkdtempSync(join(tmpdir(), "fix2-c03-proc-"));
  const run = spawnSync(
    process.execPath,
    ["--import", "tsx", "src/main.mjs"],
    {
      cwd: join(repoRoot, "platform-hypit/backend"),
      encoding: "utf8",
      timeout: 30_000,
      env: {
        ...process.env,
        HYPIT_DATA_ROOT: join(dir, "hypit"),
        HYPIT_TEMPLATES_ROOT: join(dir, "missing-templates"),
        HYPIT_FIXTURES_ROOT: join(dir, "missing-fixtures"),
        HYPIT_INTERNAL_TOKEN: "t".repeat(40),
        HYPIT_BACKEND_PORT: "19240",
      },
    },
  );
  rmSync(dir, { recursive: true, force: true });
  assert.notEqual(run.status, 0, "预检失败必须非零退出");
  const out = (run.stdout ?? "") + (run.stderr ?? "");
  assert.match(out, /startup-precheck failed/, `输出应含预检原因：${out.slice(0, 500)}`);
  // 输出脱敏：不泄漏完整宿主绝对路径（只允许类别+basename）。
  assert.ok(!out.includes(tmpdir()) || !out.includes("missing-templates/"), "错误信息不得携带宿主绝对路径");
});

// ── Docker 真实镜像层（TC-F2-03-01 / TC-F2-03-04） ────────────────────────
test("TC-F2-03-01 构建两镜像并只读 rootfs 容器内校验（需 Docker）", { timeout: 40 * 60_000 }, () => {
  for (const service of ["backend-image", "runner-image"]) {
    dc(["build", service], {}, 35 * 60_000);
  }
  const probe = (service: string, script: string): string =>
    dc(["run", "--rm", "-T", service], { HYPIT_IMAGE_PROBE: script }, 120_000);

  // Slim runtime retains the engine and its identity, not build-time input trees.
  for (const service of ["backend-image", "runner-image"]) {
    probe(service, `
      const fs = require("node:fs");
      for (const path of ["/app/platform-hypit/upstream", "/app/scripts/acceptance/build-107-engine.sh"])
        if (fs.existsSync(path)) throw new Error("build-only input leaked into runtime: " + path);
      fs.accessSync("/app/platform-hypit/patches/manifest.json");
      for (const path of ["package.json", "node_modules/tsx/package.json", "packages/studio/package.json", "packages/render-hyperframes/package.json"])
        fs.accessSync("/app/platform-hypit/.generated/hypit/" + path);
    `);
  }
  // broker 镜像：fixtures/catalog/入口全部可读；provenance 与仓库一致；Node 24.14.1。
  const catalog = JSON.parse(readFileSync(join(repoRoot, "platform-hypit/templates/catalog.json"), "utf8"));
  const out = probe("backend-image", `
    const { accessSync, readFileSync, statSync } = require("node:fs");
    for (const f of ["package.json", "main.svml", "main.svrun"])
      accessSync("/app/platform-hypit/fixtures/minimal-local/" + f);
    const catalog = JSON.parse(readFileSync("/app/platform-hypit/templates/catalog.json", "utf8"));
    for (const t of catalog.templates) {
      const dir = "/app/" + t.sourcePath;
      if (!statSync(dir).isDirectory()) throw new Error("template dir missing: " + t.templateId);
      for (const run of t.runPaths) accessSync(dir + "/" + run);
    }
    console.log(JSON.stringify({ sourceCommit: catalog.sourceCommit, node: process.version }));
  `);
  const parsed = JSON.parse(out) as { sourceCommit: string; node: string };
  assert.equal(parsed.sourceCommit, catalog.sourceCommit, "镜像内模板 provenance 与仓库 catalog 一致");
  assert.ok(parsed.node.startsWith("v24.14."), `Node 引擎须 24.14.x（实际 ${parsed.node}）`);
  probe("backend-image", `
    const { execFileSync } = require("node:child_process");
    if (!execFileSync("ffmpeg", ["-version"], { encoding: "utf8" }).includes("ffmpeg version")) throw new Error("ffmpeg unavailable");
    if (!execFileSync("chromium", ["--version"], { encoding: "utf8" }).includes("Chromium")) throw new Error("chromium unavailable");
    if (process.env.HYPIT_RENDER_CHROME_PATH !== "/usr/bin/chromium") throw new Error("bundled renderer not selected");
  `);
  // 入口文件在场。
  probe("backend-image", `require("node:fs").accessSync("/app/src/main.mjs")`);

  // runner 镜像：入口在场 + Node 24.14.1（只读 rootfs 下同样可读）。
  const runnerOut = probe("runner-image", `
    const { accessSync } = require("node:fs");
    accessSync("/app/src/runner/server.ts");
    console.log(process.version);
  `);
  assert.ok(runnerOut.trim().startsWith("v24.14."), `runner Node 引擎须 24.14.x（实际 ${runnerOut.trim()}）`);
});

test("TC-F2-03-04 新镜像启动重读既有工程：hash 不变、无重置/迁移副作用（需 Docker）", { timeout: 10 * 60_000 }, () => {
  // 隔离卷：预置工程 work 文件与 journal（broker sqlite 目录占位）。
  const projectId = "11111111-1111-4111-8111-111111111114";
  const workDir = join(dataDir, "projects", projectId, "work");
  mkdirSync(workDir, { recursive: true });
  const mainBytes = Buffer.from("<svml><!-- preserved --></svml>\n");
  writeFileSync(join(workDir, "main.svml"), mainBytes);
  mkdirSync(join(dataDir, "hypit"), { recursive: true });
  writeFileSync(join(dataDir, "hypit", "journal.marker"), "pre-existing journal");
  const beforeHash = createHash("sha256").update(mainBytes).digest("hex");

  try {
    dc(["up", "-d", "--no-build", "broker-restart"]);
    // 重读：原文件字节与 hash 不变；journal 仍在；无新增重置/迁移产物。
    const readBack = dc(["exec", "-T", "broker-restart", "node", "-e",
      `const b=require("node:fs").readFileSync("/data/projects/${projectId}/work/main.svml");console.log(require("node:crypto").createHash("sha256").update(b).digest("hex"))`]);
    assert.equal(readBack.trim(), beforeHash, "既有工程文件 hash 必须不变");
    const journal = dc(["exec", "-T", "broker-restart", "node", "-e",
      `console.log(require("node:fs").readFileSync("/data/hypit/journal.marker","utf8"))`]);
    assert.equal(journal.trim(), "pre-existing journal", "既有 journal 不被清除");
  } finally {
    dc(["stop", "broker-restart"]);
  }
});
