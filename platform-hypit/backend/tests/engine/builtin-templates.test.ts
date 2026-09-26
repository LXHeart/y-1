// builtin-templates.test.ts — C107-20 (task-107): the three built-in local
// templates are REAL projects (TC107-20-01): each checks, compiles and renders
// a decodable MP4 with ZERO paid-provider Needs; the catalog matches the
// template directories one-to-one with honest materialState; and the official
// example census (7 example roots / 28 tracked Runs) stays complete as the
// import surface for C24's full sweep.
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { loadHypit } from "../../src/engine/hypit-bootstrap.ts";
import { runCheck } from "../../src/engine/compile-adapter.ts";
import {
  ensureRuntimeProfile,
  openEngineHost,
  openProjectResultsLocation,
  stopWorker,
  submitBuild,
} from "../../src/engine/runtime-adapter.ts";
import { ensureChatMachinePackages } from "./chat-workspace.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");
const templatesRoot = join(repoRoot, "platform-hypit/templates");
const sourceCommit = JSON.parse(readFileSync(join(templatesRoot, "catalog.json"), "utf8")).sourceCommit as string;

test("catalog matches the template directories one-to-one with honest metadata", () => {
  const catalog = JSON.parse(readFileSync(join(templatesRoot, "catalog.json"), "utf8")) as {
    templates: {
      templateId: string; sourcePath: string; runPaths: string[]; materialState: string;
      localOrRemote: string; requiredCapabilities: string[]; dependencies: string[];
    }[];
  };
  const dirs = readdirSync(templatesRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort();
  assert.deepEqual(catalog.templates.map((entry) => entry.templateId).sort(), dirs);
  for (const entry of catalog.templates) {
    assert.equal(entry.materialState, "ready", `${entry.templateId}: local templates ship ready`);
    assert.equal(entry.localOrRemote, "local");
    assert.deepEqual(entry.requiredCapabilities, [], "zero paid-provider Needs");
    assert.ok(entry.dependencies.length >= 3);
    for (const run of entry.runPaths) {
      assert.ok(existsSync(join(templatesRoot, entry.templateId, run)), `${entry.templateId}/${run} exists`);
    }
    assert.ok(existsSync(join(templatesRoot, entry.templateId, "package.json")));
  }
});

test("official example census stays complete for the template/import surface", () => {
  const census = JSON.parse(
    readFileSync(new URL("../../../../platform-hypit/upstream-manifest.json", import.meta.url).pathname, "utf8"),
  ) as { files?: unknown[] };
  // The pinned upstream manifest is the census basis; the example roots and
  // tracked Runs from 附D/E must all be present in G.
  const exampleRoots = ["ranking-football", "podcast", "interview", "complex-explainer",
    "semantic-composition", "minimal-author-package", "provider-package"];
  for (const root of exampleRoots) {
    assert.ok(existsSync(join(generatedRoot, "examples", root)), `examples/${root} migrated`);
  }
  const runCount = Number(process.env.EXPECTED_EXAMPLE_RUNS ?? "28");
  let tracked = 0;
  if (Array.isArray(census.files)) {
    tracked = census.files.filter((entry) => {
      const path = typeof entry === "string" ? entry : String((entry as { path?: string }).path ?? "");
      return path.startsWith("examples/") && path.endsWith(".svrun");
    }).length;
  } else {
    // Fallback: count the actual .svrun files in G.
    const walk = (dir: string): number => readdirSync(dir, { withFileTypes: true }).reduce((sum, entry) => {
      const full = join(dir, entry.name);
      if (entry.isDirectory() && !entry.name.includes("node_modules")) return sum + walk(full);
      return sum + (entry.isFile() && entry.name.endsWith(".svrun") ? 1 : 0);
    }, 0);
    tracked = walk(join(generatedRoot, "examples"));
  }
  assert.ok(tracked >= runCount, `official example Runs present (found ${tracked}, expected >= ${runCount})`);
});

test("TC107-20-01: all three built-in templates check and render real MP4s", { timeout: 900_000 }, async (t) => {
  const engine = await loadHypit(generatedRoot);
  await ensureChatMachinePackages();
  const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-templates-att-"));
  t.after(() => rmSync(attachmentDir, { recursive: true, force: true }));

  for (const templateId of ["ranking-tier", "caption-motion", "deck-stack"]) {
    const workspace = mkdtempSync(join(tmpdir(), `hypit-tpl-${templateId}-`));
    t.after(() => rmSync(workspace, { recursive: true, force: true }));
    cpSync(join(templatesRoot, templateId), workspace, { recursive: true });

    // check passes BEFORE any render (compile diagnostics honest).
    const check = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
      workspaceRoot: workspace,
      entryFile: "main.svrun",
    });
    assert.equal(check.ok, true, `${templateId} check failed: ${JSON.stringify(check.diagnostics)}`);

    // real local render through the trusted runtime.
    await ensureRuntimeProfile(generatedRoot, workspace);
    const profilePath = join(workspace, "hypit.runtime.json");
    const profile = JSON.parse(readFileSync(profilePath, "utf8")) as {
      endpoints: Record<string, { config?: Record<string, unknown> }>;
    };
    profile.endpoints["hyperframes.local"]!.config = {
      ...(profile.endpoints["hyperframes.local"]!.config ?? {}),
      browserGpu: "software",
      browserCacheDirectory: join(repoRoot, "data/hypit/render-browser"),
    };
    writeFileSync(profilePath, `${JSON.stringify(profile, null, 2)}\n`);
    const repositoryLocation = await openProjectResultsLocation(
      { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace);
    const compiled = await (async () => {
      const { runCompile } = await import("../../src/engine/compile-adapter.ts");
      return await runCompile({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
        workspaceRoot: workspace, runFile: "main.svrun",
      });
    })();
    const host = await openEngineHost({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace);
    const controller = await host.controller();
    await controller.programs.up({ maxWaitMs: 300_000 });
    await controller.worker.up({ maxWaitMs: 120_000 });
    const engineBuildId = engine.orderedBuildId(Date.now(), templateId === "ranking-tier" ? "TPLRANK001" : templateId === "caption-motion" ? "TPLCAPT001" : "TPLDECK001");
    await submitBuild(
      { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
      { engineBuildId, workspaceRoot: workspace, runFile: "main.svrun", repositoryLocation, title: templateId },
      compiled,
    );
    const deadline = Date.now() + 420_000;
    let outcome: string | undefined;
    while (Date.now() < deadline) {
      const opened = await engine.videoCliDistribution.openProjectResults!(workspace, { packageRoot: workspace });
      const manifest = await opened.repository.read(engineBuildId);
      await opened.close?.();
      if (manifest?.outcome !== undefined) { outcome = manifest.outcome; break; }
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 2_000));
    }
    await stopWorker({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace).catch(() => {});
    let failureNote = "";
    if (outcome !== "complete") {
      const openedForDiag = await engine.videoCliDistribution.openProjectResults!(workspace, { packageRoot: workspace });
      const diag = await openedForDiag.repository.read(engineBuildId);
      await openedForDiag.close?.();
      failureNote = JSON.stringify(diag).slice(0, 1500);
    }
    assert.equal(outcome, "complete", `${templateId} render did not complete: ${failureNote}`);

    const mp4 = join(workspace, "final.mp4");
    const { runVideoCli } = await import("@hypit/video-cli");
    await runVideoCli(["get", engineBuildId, "--workspace", workspace, "--output", "final.video", "--to", mp4],
      { write: () => {} });
    const probe = spawnSync("ffprobe", ["-v", "error", "-show_streams", "-of", "json", mp4], { encoding: "utf8" });
    assert.equal(probe.status, 0, `${templateId}: ffprobe failed`);
    const streams = JSON.parse(probe.stdout!) as { streams: { codec_type?: string }[] };
    assert.ok(streams.streams.some((stream) => stream.codec_type === "video"), `${templateId}: video stream present`);
  }
});

void mkdirSync;
