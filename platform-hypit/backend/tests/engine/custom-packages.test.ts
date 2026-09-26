// custom-packages.test.ts — C107-17 (task-107): custom component/Provider/
// Companion packages end to end. The fixture at platform-hypit/fixtures/
// custom-package is a REAL author package: it compiles with the distribution's
// own tsc, renders through the real local HyperFrames stack (TC107-17-01),
// stays project-scoped per workspace (TC107-17-03: a palette edit in project B
// changes B's pixels while project A's Result stays byte-identical) and packs
// into a second project where the Provider facet still goes through the broker
// execution gate (TC107-17-04).
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";
import test from "node:test";

// External package resolution targets the shared packages-only runner state
// root (never the developer's home, never broker credentials).
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { loadHypit, installEngineResolution } from "../../src/engine/hypit-bootstrap.ts";
import { runCheck, runCompile } from "../../src/engine/compile-adapter.ts";
import {
  ensureRuntimeProfile,
  openEngineHost,
  openProjectResultsLocation,
  stopWorker,
  submitBuild,
} from "../../src/engine/runtime-adapter.ts";
import { ensureChatMachinePackages } from "./chat-workspace.ts";
import {
  isPackageTool,
  packageArtifactsRoot,
  runPackageTool,
  type PackageToolContext,
} from "../../src/tools/packages.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");
const fixtureRoot = join(repoRoot, "platform-hypit/fixtures/custom-package");
const stableRunnerStateRoot = join(repoRoot, "data/hypit/runner-state");
const builtDistCache = join(repoRoot, "data/hypit/test-locks/custom-badge/dist");

// Cross-process lock over the shared dist cache (test files run in parallel
// child processes).
function withLock<T>(lockDir: string, operation: () => T): T {
  mkdirSync(lockDir, { recursive: true });
  for (let attempt = 0; ; attempt += 1) {
    try {
      mkdirSync(join(lockDir, "lock"));
      break;
    } catch {
      if (attempt > 600) throw new Error(`lock not acquired at ${lockDir}`);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
    }
  }
  try {
    return operation();
  } finally {
    rmSync(join(lockDir, "lock"), { recursive: true, force: true });
  }
}

/** Build the fixture once into the repo-local cache with G's own tsc. */
function ensureFixtureBuilt(): string {
  const marker = join(builtDistCache, "index.js");
  if (existsSync(marker)) return builtDistCache;
  return withLock(join(repoRoot, "data/hypit/test-locks/custom-badge"), () => {
    if (existsSync(marker)) return builtDistCache;
    const tsc = join(generatedRoot, "node_modules/.bin/tsc");
    const staged = join(repoRoot, "data/hypit/test-locks/custom-badge/build");
    rmSync(staged, { recursive: true, force: true });
    mkdirSync(staged, { recursive: true });
    cpSync(fixtureRoot, staged, { recursive: true, filter: (entry) => !entry.includes("node_modules") });
    // Pinned resolution: per-package symlinks into G's own node_modules
    // (`@hypit/hypit` is the distribution ROOT package) — the same tree the
    // engine runs, never a fresh install.
    const linkDir = join(staged, "node_modules/@hypit");
    mkdirSync(linkDir, { recursive: true });
    for (const entry of readdirSync(join(generatedRoot, "node_modules/@hypit"), { withFileTypes: true })) {
      symlinkSync(join(generatedRoot, "node_modules/@hypit", entry.name), join(linkDir, entry.name), entry.isDirectory() ? "dir" : "file");
    }
    symlinkSync(generatedRoot + "/", join(linkDir, "hypit"), "dir");
    symlinkSync(join(generatedRoot, "node_modules/@types"), join(staged, "node_modules/@types"), "dir");
    writeFileSync(join(staged, "tsconfig.build.json"), `${JSON.stringify({
      compilerOptions: {
        strict: true, target: "es2022", module: "nodenext", moduleResolution: "nodenext",
        skipLibCheck: true, noEmitOnError: true, baseUrl: ".", outDir: "dist", rootDir: "src", declaration: true,
      },
      include: ["src/**/*"],
    }, null, 2)}\n`);
    const result = spawnSync(tsc, ["-p", "tsconfig.build.json"], { cwd: staged, encoding: "utf8", timeout: 180_000 });
    if (result.status !== 0 || !existsSync(join(staged, "dist", "index.js"))) {
      throw new Error(`custom-package build failed:\n${result.stdout}\n${result.stderr}`);
    }
    rmSync(join(staged, "tsconfig.build.json"), { force: true });
    mkdirSync(join(builtDistCache, ".."), { recursive: true });
    rmSync(builtDistCache, { recursive: true, force: true });
    cpSync(join(staged, "dist"), builtDistCache, { recursive: true });
    rmSync(staged, { recursive: true, force: true });
    return builtDistCache;
  });
}

const BADGE_SOURCE = `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="time" from="@hypit/timeline-author@1"/>
  <import as="spatial" from="@hypit/spatial@1"/>
  <import as="film" from="@hypit/film@1"/>
  <import as="render" from="@hypit/render-hyperframes@1"/>
  <import as="clone" from="@clone/custom-badge@1"/>
  <import as="recipes" source="./badge.svs"/>

  <time:Clock id="clock" frame-rate="30"/>
  <time:Timeline id="animation" clock={clock} end="1s"/>
  <spatial:Canvas id="canvas" width="540" height="960"/>
  <clone:Badge id="badge" timeline={animation.timeline}/>
  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.badge}>
    <film:Track source={badge.track}/>
  </film:Film>
  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
</svml>
`;

const BADGE_SVS = `<?svml using="@hypit/svs@1"?>
<sheet version="1">
  film.badge { background: #101418; }
</sheet>
`;

const BADGE_RUN = `<?svml using="@hypit/run-markup@1"?>

<svrun version="1">
  <author source="./badge.svml"/>
  <target output="final.video"/>
</svrun>
`;

/** One standalone project workspace with the custom package installed. */
function prepareBadgeWorkspace(options: { paletteColor?: string } = {}): string {
  const dist = ensureFixtureBuilt();
  const workspace = mkdtempSync(join(tmpdir(), "hypit-badge-ws-"));
  const packageDir = join(workspace, "packages/custom-badge");
  mkdirSync(join(workspace, "packages"), { recursive: true });
  cpSync(fixtureRoot, packageDir, { recursive: true, filter: (entry) => !entry.includes("node_modules") });
  cpSync(dist, join(packageDir, "dist"), { recursive: true });
  if (options.paletteColor !== undefined) {
    // The palette edit happens in BOTH the source and the built activation —
    // this is exactly what a later revision of the package looks like.
    const indexSource = join(packageDir, "src/index.ts");
    writeFileSync(indexSource, readFileSync(indexSource, "utf8").replace("#2456d8", options.paletteColor));
    const indexDist = join(packageDir, "dist/index.js");
    writeFileSync(indexDist, readFileSync(indexDist, "utf8").replace("#2456d8", options.paletteColor));
  }
  writeFileSync(join(workspace, "badge.svml"), BADGE_SOURCE);
  writeFileSync(join(workspace, "badge.svs"), BADGE_SVS);
  writeFileSync(join(workspace, "badge.svrun"), BADGE_RUN);
  return workspace;
}

function sha256File(file: string): string {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

/** Average luma/color stats of the first frame — debug evidence for palette diffs. */
function frameStats(mp4: string): string {
  const probe = spawnSync("ffmpeg", ["-v", "info", "-i", mp4, "-frames:v", "1", "-vf", "signalstats,metadata=print", "-f", "null", "-"], { encoding: "utf8", timeout: 60_000 });
  const lines = `${probe.stdout}${probe.stderr}`.split("\n").filter((line) => line.includes("YAVG") || line.includes("UAVG") || line.includes("VAVG"));
  return lines.slice(0, 3).join(" | ");
}

/** Extract the first frame to a PNG and hash it (ffmpeg PNG output is deterministic). */
function frameDigest(mp4: string): string {
  const out = join(tmpdir(), `badge-frame-${Date.now().toString(36)}.png`);
  try {
    const probe = spawnSync("ffmpeg", [
      "-v", "error", "-y", "-i", mp4, "-frames:v", "1", out,
    ], { encoding: "utf8", timeout: 60_000 });
    assert.equal(probe.status, 0, `frame extraction failed: ${probe.stdout ?? ""}${probe.stderr ?? ""}`);
    assert.ok(existsSync(out), "frame PNG must exist");
    return createHash("sha256").update(readFileSync(out)).digest("hex");
  } finally {
    rmSync(out, { force: true });
  }
}

/**
 * Point the workspace's render profile at stable software-render caches. The
 * default distribution profile has no endpoint config yet, so this creates it
 * (the chat helper assumes the example's own richer profile file).
 */
function stabilizeBadgeRenderProfile(workspace: string): void {
  const profilePath = join(workspace, "hypit.runtime.json");
  const profile = JSON.parse(readFileSync(profilePath, "utf8")) as {
    endpoints: Record<string, { config?: Record<string, unknown> }>;
  };
  const hyperframes = profile.endpoints["hyperframes.local"]!;
  hyperframes.config = {
    ...(hyperframes.config ?? {}),
    browserGpu: "software",
    browserCacheDirectory: join(repoRoot, "data/hypit/render-browser"),
  };
  writeFileSync(profilePath, `${JSON.stringify(profile, null, 2)}\n`);
}

async function renderBadge(workspace: string, nonce: string): Promise<string> {
  const engine = await loadHypit(generatedRoot);
  const engineBuildId = engine.orderedBuildId(Date.now(), nonce);
  await ensureRuntimeProfile(generatedRoot, workspace);
  stabilizeBadgeRenderProfile(workspace);
  const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-badge-attachments-"));
  const repositoryLocation = await openProjectResultsLocation(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    workspace,
  );
  const compiled = await runCompile({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
    workspaceRoot: workspace,
    runFile: "badge.svrun",
  });
  const host = await openEngineHost({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace);
  const controller = await host.controller();
  await controller.programs.up({ maxWaitMs: 300_000 });
  await controller.worker.up({ maxWaitMs: 120_000 });
  await submitBuild(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    { engineBuildId, workspaceRoot: workspace, runFile: "badge.svrun", repositoryLocation, title: "C107-17 badge render" },
    compiled,
  );
  // Terminal outcomes live in the Results repository (the runtime view only
  // carries active evidence) — poll the repository like the C02 render smoke.
  const deadline = Date.now() + 420_000;
  let outcome: string | undefined;
  let failureDetail = "";
  while (Date.now() < deadline) {
    const opened = await engine.videoCliDistribution.openProjectResults!(workspace, {
      packageRoot: workspace,
    });
    const manifest = await opened.repository.read(engineBuildId);
    await opened.close?.();
    if (manifest?.outcome !== undefined) {
      outcome = manifest.outcome;
      failureDetail = JSON.stringify(manifest).slice(0, 2000);
      break;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 2_000));
  }
  await stopWorker({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace).catch(() => {});
  assert.equal(outcome, "complete", `badge render did not complete: ${outcome ?? "no outcome"} ${failureDetail}`);
  const exportTarget = join(workspace, "badge.mp4");
  const { runVideoCli } = await import("@hypit/video-cli");
  await runVideoCli([
    "get", engineBuildId,
    "--workspace", workspace,
    "--output", "final.video",
    "--to", exportTarget,
  ], { write: () => {} });
  return exportTarget;
}

// -------------------------------------------------------------------------------------------------
// TC107-17-01: the custom package checks, plans and renders for real.

test("TC107-17-01: custom package compiles, checks and renders a real MP4", { timeout: 900_000 }, async (t) => {
  const workspace = prepareBadgeWorkspace();
  t.after(() => rmSync(workspace, { recursive: true, force: true }));
  const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-badge-check-"));
  t.after(() => rmSync(attachmentDir, { recursive: true, force: true }));

  const check = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
    workspaceRoot: workspace,
    entryFile: "badge.svrun",
  });
  assert.equal(check.ok, true, JSON.stringify(check.diagnostics, null, 2));
  assert.equal(check.sourceKind, "run");
  assert.deepEqual(check.targetNames, ["final.video"]);

  const authorCheck = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
    workspaceRoot: workspace,
    entryFile: "badge.svml",
  });
  assert.equal(authorCheck.ok, true, JSON.stringify(authorCheck.diagnostics, null, 2));
  assert.equal(authorCheck.sourceKind, "author");

  await ensureChatMachinePackages();
  const mp4 = await renderBadge(workspace, "C10717TC01");
  const probe = spawnSync("ffprobe", ["-v", "error", "-show_streams", "-of", "json", mp4], { encoding: "utf8" });
  assert.equal(probe.status, 0, probe.stderr);
  const streams = (JSON.parse(probe.stdout!) as { streams: { codec_type?: string; width?: number; height?: number }[] }).streams;
  const video = streams.find((stream) => stream.codec_type === "video");
  assert.ok(video, "exported badge render must contain a video stream");
  assert.equal(video!.width, 540);
  assert.equal(video!.height, 960);
});

// -------------------------------------------------------------------------------------------------
// TC107-17-03: same package name in two projects stays isolated; a palette edit
// changes the new project's pixels while the first project's Result is frozen.

test("TC107-17-03: same-name packages in two projects do not cross; edits only move new Builds", { timeout: 900_000 }, async (t) => {
  await ensureChatMachinePackages();
  const workspaceA = prepareBadgeWorkspace();
  const workspaceB = prepareBadgeWorkspace({ paletteColor: "#d82424" });
  t.after(() => {
    rmSync(workspaceA, { recursive: true, force: true });
    rmSync(workspaceB, { recursive: true, force: true });
  });

  // A shared discovery must see each workspace's own package copy — never a
  // global module cache crossing projects.
  const mp4A = await renderBadge(workspaceA, "C10717TC3A");
  const digestA = frameDigest(mp4A);
  const resultFileHashA = sha256File(mp4A);

  const mp4B = await renderBadge(workspaceB, "C10717TC3B");
  const digestB = frameDigest(mp4B);

  assert.notEqual(digestA, digestB,
    `the palette edit must be visible in project B's pixels; A=${digestA} [${frameStats(mp4A)}] B=${digestB} [${frameStats(mp4B)}]`);
  assert.equal(sha256File(mp4A), resultFileHashA, "project A's Result must stay byte-identical");
});

// -------------------------------------------------------------------------------------------------
// TC107-17-02: a broken changeset keeps its diagnostics; the fix loop is bounded.

test("TC107-17-02: broken author changeset reports diagnostics; bounded fix round passes", { timeout: 240_000 }, async () => {
  const workspace = prepareBadgeWorkspace();
  try {
    const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-badge-fix-"));
    try {
      writeFileSync(join(workspace, "broken.svml"), BADGE_SOURCE.replace("<clone:Badge", "<clone:Nonsense"));
      const broken = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
        workspaceRoot: workspace,
        entryFile: "broken.svml",
      });
      assert.equal(broken.ok, false);
      assert.ok(broken.diagnostics.length >= 1, "broken source must carry diagnostics");
      // Round 1 fix: restore the real surface name (the author's deterministic repair).
      writeFileSync(join(workspace, "broken.svml"), BADGE_SOURCE);
      const fixed = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
        workspaceRoot: workspace,
        entryFile: "broken.svml",
      });
      assert.equal(fixed.ok, true, JSON.stringify(fixed.diagnostics, null, 2));
    } finally {
      rmSync(attachmentDir, { recursive: true, force: true });
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

// -------------------------------------------------------------------------------------------------
// TC107-17-04: pack → install into a second project; Provider goes through the
// broker gate; Companion facet is present; bundles carry no secrets.

type BadgeProviderModule = {
  createBadgeProvider(options: Record<string, unknown>): {
    readonly offers: readonly { readonly capability: { readonly name: string }; readonly endpoint: string }[];
    readonly credentials: readonly { readonly slot: string; readonly ref: { readonly store: string; readonly key: string } }[];
    install(registrar: {
      registerAsyncEndpoint(id: string, capability: unknown, returns: unknown, endpoint: Record<string, unknown>, options?: unknown): void;
      registerImmediateEndpoint(id: string, capability: unknown, returns: unknown, handler: unknown, options?: unknown): void;
    }): Promise<void>;
  };
};

test("TC107-17-04: pack installs into a second project; provider gated; companion present", { timeout: 240_000 }, async (t) => {
  await installEngineResolution(generatedRoot);
  const dist = ensureFixtureBuilt();
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-badge-projects-"));
  t.after(() => rmSync(projectsRoot, { recursive: true, force: true }));
  const ctx: PackageToolContext = { projectsRoot, distributionRoot: generatedRoot };

  // Provision two broker-visible workspaces (package tool operates on them).
  const PROJECT_A = "aaaaaaaa-0000-4000-8000-000000000001";
  const PROJECT_B = "aaaaaaaa-0000-4000-8000-000000000002";
  const { provisionFromTemplate } = await import("../../src/workspace/provision.ts");
  const templateDir = join(repoRoot, "platform-hypit/fixtures/minimal-local");
  const templateFiles = ["main.svml", "main.svrun", "package.json"];
  for (const id of [PROJECT_A, PROJECT_B]) {
    // Template provision publishes the initial head the install journal
    // requires; projectRootFor maps <projectsRoot>/<projectId> directly.
    await provisionFromTemplate(projectsRoot, id, templateDir, templateFiles);
  }
  const { readHead } = await import("../../src/workspace/transactions.ts");

  // Install the fixture as project A's package via the same journaled path the
  // packages.install command uses: stage files, then pack from the workspace.
  const stageA = prepareBadgeWorkspace();
  const packageSourceDir = join(stageA, "packages/custom-badge");
  cpSync(packageSourceDir, join(projectsRoot, PROJECT_A, "work/packages/custom-badge"), { recursive: true, filter: (entry) => !entry.includes("node_modules") });
  rmSync(stageA, { recursive: true, force: true });

  assert.equal(isPackageTool("packages.pack"), true);
  assert.equal(isPackageTool("workspace.files"), false);

  const packed = await runPackageTool(ctx, "packages.pack", { projectId: PROJECT_A, packagePath: "packages/custom-badge" }) as {
    name: string;
    version: string;
    artifactRoot: string;
    fileCount: number;
  };
  assert.equal(packed.name, "@clone/custom-badge");
  assert.ok(packed.fileCount >= 4, "bundle carries package.json + sources");
  const manifestFile = join(resolve(projectsRoot, ".."), packed.artifactRoot, "hypit-package-bundle.json");
  const manifestText = readFileSync(manifestFile, "utf8");
  assert.ok(!/secret|password|api[_-]?key\s*[:=]/iu.test(manifestText), "bundle manifest must not carry secrets");

  const statusA = await runPackageTool(ctx, "packages.status", { projectId: PROJECT_A }) as {
    packages: { name: string; facets: string[] }[];
  };
  assert.deepEqual(statusA.packages.map((entry) => entry.name), ["@clone/custom-badge"]);
  assert.deepEqual(statusA.packages[0]!.facets.sort(), ["activation", "companion", "provider"]);

  // Refusals: @hypit shadowing and artifact-root escapes are hard errors.
  const shadowDir = join(projectsRoot, PROJECT_A, "work/packages/shadow");
  mkdirSync(shadowDir, { recursive: true });
  writeFileSync(join(shadowDir, "package.json"), `${JSON.stringify({ name: "@hypit/evil", version: "1.0.0" })}\n`);
  await assert.rejects(
    () => runPackageTool(ctx, "packages.pack", { projectId: PROJECT_A, packagePath: "packages/shadow" }),
    /shadow the @hypit namespace/u,
  );
  await assert.rejects(
    () => runPackageTool(ctx, "packages.install", { projectId: PROJECT_B, artifactRoot: "../../secrets", baseRevision: 0 }),
    /artifacts root/u,
  );

  const baseRevisionB = (await readHead(join(projectsRoot, PROJECT_B)))?.revision ?? 0;
  const installed = await runPackageTool(ctx, "packages.install", {
    projectId: PROJECT_B,
    artifactRoot: packed.artifactRoot,
    baseRevision: baseRevisionB,
  }) as { installed: { name: string; version: string }; files: number; revision: number };
  assert.equal(installed.installed.name, "@clone/custom-badge");
  assert.equal(installed.revision, baseRevisionB + 1, "install is a journaled workspace change: head advances one revision");
  const statusB = await runPackageTool(ctx, "packages.status", { projectId: PROJECT_B }) as {
    packages: { name: string }[];
  };
  assert.deepEqual(statusB.packages.map((entry) => entry.name), ["@clone/custom-badge"]);

  // The installed copy is the same real package: its Companion facet loads.
  const companionModule = await import(pathToFileURL(join(
    projectsRoot, PROJECT_B, "work/packages/clone-custom-badge/dist/companion.js",
  )).href) as { badgeStudioCompanions: { id: string; inspector: unknown[]; bindings: unknown[] }[] };
  assert.equal(companionModule.badgeStudioCompanions.length, 1);
  assert.equal(companionModule.badgeStudioCompanions[0]!.id, "badge");
  assert.ok(companionModule.badgeStudioCompanions[0]!.inspector.length >= 2, "Companion declares Inspector fields");
  assert.ok(companionModule.badgeStudioCompanions[0]!.bindings.length >= 2, "Companion declares source bindings");

  // Provider facet: real fixture HTTP service, gated through the broker's
  // authorization decorator. No permit → no HTTP call; permit → full run.
  const providerModule = await import(pathToFileURL(join(dist, "provider.js")).href) as BadgeProviderModule;
  const servedPng = Buffer.from(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63fcffff3f030005fe02fea72d1e480000000049454e44ae426082",
    "hex",
  );
  const calls: { method: string; path: string; authorization: string | undefined }[] = [];
  const service = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      calls.push({ method: request.method ?? "", path: request.url ?? "", authorization: request.headers.authorization });
      if (request.url === "/tasks" && request.method === "POST") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ id: "task_1" }));
        return;
      }
      if (request.url === "/tasks/task_1") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ state: "succeeded", url: `http://127.0.0.1:${(service.address() as { port: number }).port}/assets/out.png` }));
        return;
      }
      if (request.url === "/assets/out.png") {
        response.writeHead(200, { "content-type": "image/png" });
        response.end(servedPng);
        return;
      }
      response.writeHead(404, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "not_found" }));
    });
  });
  await new Promise<void>((resolvePromise) => service.listen(0, "127.0.0.1", resolvePromise));
  t.after(() => {
    service.closeIdleConnections?.();
    service.close();
  });
  const baseUrl = `http://127.0.0.1:${(service.address() as { port: number }).port}`;

  const { ExecutionAuthorizer, httpExecutionBridge, AuthorizationError } = await import(
    "../../src/providers/authorization.ts"
  );
  const { withAuthorizationGuard } = await import("../../src/providers/activation-hooks.ts");

  const package_ = providerModule.createBadgeProvider({
    instance: "badge.fixture",
    pool: "badge.fixture",
    baseUrl,
    apiKey: { store: "env", key: "badge.fixture.apiKey" },
    pollIntervalMs: 20,
    fetch: globalThis.fetch,
  });
  assert.equal(package_.offers.length, 1);
  assert.equal(package_.offers[0]!.capability.name, "banana-pro");
  assert.equal(package_.credentials[0]!.slot, "apiKey");

  // A recording registrar wrapped by the broker guard — the same decorator the
  // trusted activation path uses for every endpoint.
  const registered = new Map<string, {
    start: (context: Record<string, unknown>) => Promise<unknown>;
    poll: (context: Record<string, unknown>) => Promise<unknown>;
    collect: (context: Record<string, unknown>) => Promise<unknown>;
  }>();
  const recording = {
    registerImmediateEndpoint(): void {},
    registerAsyncEndpoint(id: string, _capability: unknown, _returns: unknown, endpoint: Record<string, unknown>): void {
      registered.set(id, {
        start: endpoint.start as (context: Record<string, unknown>) => Promise<unknown>,
        poll: endpoint.poll as (context: Record<string, unknown>) => Promise<unknown>,
        collect: endpoint.collect as (context: Record<string, unknown>) => Promise<unknown>,
      });
    },
  };
  const authorizer = new ExecutionAuthorizer(httpExecutionBridge({
    baseUrl: "http://127.0.0.1:1", // unreachable: a permit must come from BEFORE any HTTP call
    token: "internal-token", // secret-scan: allow
  }));
  const operationId = "23456789-2345-4234-8234-234567890123";
  const prepareRequest = {
    operationId,
    projectId: "34567890-3456-4345-8345-345678901234",
    needId: "need-1",
    capability: "@hypit/nano-banana@1#banana-pro",
    model: "banana-pro",
    endpointId: "badge.fixture",
    requestHash: "b".repeat(64),
    grantId: "45678901-4567-4456-8456-456789012345",
  };
  await package_.install(withAuthorizationGuard(recording, authorizer, (operation) =>
    operation === operationId ? prepareRequest : undefined));
  const start = registered.get("badge.fixture")!.start;

  // Without a settled bridge the guard refuses BEFORE the fixture sees bytes.
  await assert.rejects(() => start({
    operation: operationId,
    need: { constraints: { ports: { aspectRatio: ["1:1"] }, prompt: "badge" }, pendingInputs: [] },
    credentials: { apiKey: { secret: "badge-secret" } }, // secret-scan: allow
    resources: { get: async () => undefined, put: async () => { throw new Error("not used"); } },
    reportProgress: async () => {},
    checkpoint: async () => {},
  }), (error: unknown) => error instanceof AuthorizationError || /authorize|permit|bridge/iu.test(String((error as Error).message)));
  assert.equal(calls.length, 0, "no fixture HTTP call may happen without a broker permit");

  // With a permit issued by a stub bridge the full start/poll/collect runs.
  const bridge = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      if ((request.url ?? "").endsWith("/prepare")) {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ permitId: "permit-badge", expiresAt: new Date(Date.now() + 60_000).toISOString() }));
        return;
      }
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ settled: true }));
    });
  });
  await new Promise<void>((resolvePromise) => bridge.listen(0, "127.0.0.1", resolvePromise));
  t.after(() => {
    bridge.closeIdleConnections?.();
    bridge.close();
  });
  const gatedAuthorizer = new ExecutionAuthorizer(httpExecutionBridge({
    baseUrl: `http://127.0.0.1:${(bridge.address() as { port: number }).port}`,
    token: "internal-token", // secret-scan: allow
  }));
  const gatedRecorder = {
    registerImmediateEndpoint(): void {},
    registerAsyncEndpoint(id: string, _capability: unknown, _returns: unknown, endpoint: Record<string, unknown>): void {
      registered.set(`gated-${id}`, {
        start: endpoint.start as (context: Record<string, unknown>) => Promise<unknown>,
        poll: endpoint.poll as (context: Record<string, unknown>) => Promise<unknown>,
        collect: endpoint.collect as (context: Record<string, unknown>) => Promise<unknown>,
      });
    },
  };
  await package_.install(withAuthorizationGuard(gatedRecorder, gatedAuthorizer, () => prepareRequest));
  const gated = registered.get("gated-badge.fixture")!;
  const context = {
    operation: operationId,
    need: { constraints: { ports: { aspectRatio: ["1:1"] }, prompt: "badge" }, pendingInputs: [] },
    credentials: { apiKey: { secret: "badge-secret" } }, // secret-scan: allow
    resources: {
      get: async () => undefined,
      // Shape mirrors the host's real resources.put return (a BlobRef).
      put: async (bytes: Uint8Array, mediaType: string) => ({
        kind: "blob", resource: "res_test_badge_image", size: bytes.byteLength, mediaType,
      }),
    },
    reportProgress: async () => {},
    checkpoint: async () => {},
  };
  // Async lifecycle: start submits (pending), poll observes ready, collect stores.
  const submission = await gated.start(context) as { status: string; handle: Record<string, unknown>; receipt: Record<string, unknown> };
  assert.equal(submission.status, "pending");
  assert.deepEqual(submission.receipt, { id: "task_1" });
  const polled = await gated.poll({ ...context, handle: submission.handle }) as { status: string; handle: Record<string, unknown> };
  assert.equal(polled.status, "ready");
  const collected = await gated.collect({ ...context, handle: polled.handle }) as { status: string };
  assert.equal(collected.status, "completed");
  const taskCalls = calls.filter((entry) => entry.path.startsWith("/tasks"));
  assert.ok(taskCalls.length >= 2, "fixture received the gated task calls");
  for (const call of taskCalls) {
    assert.equal(call.authorization, "Bearer badge-secret", "credentials ride only the service calls");
  }
});
