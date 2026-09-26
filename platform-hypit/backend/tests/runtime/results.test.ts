// results.test.ts — C107-10 (task-107) Results surface: indexing, export by
// kind (scalar JSON / resource bytes via handle / composite package / external
// dependency report), presentation write-back, cross-Build history, reuse
// changeset splicing, finish/discard honesty. Fast cases seed the NATIVE
// result format (decoded by the upstream repository on read — a malformed
// fixture fails loudly); one REAL local render proves the true-media path.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { loadHypit } from "../../src/engine/hypit-bootstrap.ts";
import { buildResultDirectory } from "@hypit/build-result";
import { CommandStore } from "../../src/commands/store.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";
import { runResultsRead, runResultsHistory } from "../../src/results/read.ts";
import { runResultsExport, resolveExportHandle } from "../../src/results/export.ts";
import { runResultsPresentation } from "../../src/results/presentation.ts";
import { runResultsFinish, runResultsDiscard } from "../../src/results/actions.ts";
import { runResultsReuse, spliceReuseDeclarations } from "../../src/results/reuse.ts";
import { openResultsRepository, ensureRuntimeProfile, openEngineHost, stopWorker } from "../../src/engine/runtime-adapter.ts";
import { resultsRootOf } from "../../src/results/repository.ts";
import { runCompile } from "../../src/engine/compile-adapter.ts";
import { openProjectResultsLocation, submitBuild } from "../../src/engine/runtime-adapter.ts";
import { ensureChatMachinePackages, generatedRoot, prepareChatWorkspace, stabilizeRenderProfile } from "../engine/chat-workspace.ts";

const adapter = { distributionRoot: generatedRoot, attachmentSourceDir: "" };

function scenario(): {
  projectsRoot: string;
  store: CommandStore;
  projectId: string;
  options: { projectsRoot: string; distributionRoot: string; store: CommandStore };
  cleanup: () => void;
} {
  const dir = mkdtempSync(join(tmpdir(), "hypit-results-test-"));
  const projectsRoot = join(dir, "projects");
  mkdirSync(join(projectsRoot, "..", "resources"), { recursive: true });
  const store = new CommandStore(join(dir, "bridge.sqlite"));
  const projectId = "21111111-1111-4111-8111-111111111111";
  mkdirSync(join(projectsRoot, projectId, "work"), { recursive: true });
  return {
    projectsRoot,
    store,
    projectId,
    options: { projectsRoot, distributionRoot: generatedRoot, store },
    cleanup: () => {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

/** One real PNG (1×1 transparent) — a real decodable image, not a fake blob. */
const PNG_1PX = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "base64",
);

async function seedNativeResult(
  root: string,
  engineBuildId: string,
  manifest: Record<string, unknown>,
  files: Record<string, Buffer | string> = {},
): Promise<string> {
  const directory = buildResultDirectory(root, engineBuildId);
  mkdirSync(directory, { recursive: true });
  writeFileSync(join(directory, "result.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(join(directory, path, ".."), { recursive: true });
    writeFileSync(join(directory, path), content);
  }
  return directory;
}

test("indexing, export, presentation, history and reuse over a native multi-Output Result", { timeout: 240_000 }, async () => {
  const scenario1 = scenario();
  try {
    const engine = await loadHypit(generatedRoot);
    const opened = await openResultsRepository(adapter, join(scenario1.projectsRoot, scenario1.projectId));
    try {
      const buildId = engine.orderedBuildId(Date.now(), "C10710AAAA");
      const buildDir = buildResultDirectory(resultsRootOf(opened), buildId);
      const typeVideo = { module: { name: "@hypit/video", version: "1.0.0" }, name: "Video" };
      const typeImage = { module: { name: "@hypit/image", version: "1.0.0" }, name: "Image" };
      const typeData = { module: { name: "@hypit/data", version: "1.0.0" }, name: "Metadata" };
      const compositeDoc = {
        format: "hypit.result-value@1",
        value: { poster: null, caption: "hello" },
        resources: [{ at: ["poster"], file: { kind: "build-file", path: "assets/poster.png", size: PNG_1PX.length, mediaType: "image/png" } }],
      };
      await seedNativeResult(resultsRootOf(opened), buildId, {
        format: "hypit.build-result@1",
        id: buildId,
        title: "多输出构建",
        source: { path: "./main.svml" },
        run: { path: "./main.svrun" },
        targets: ["final.video"],
        outcome: "complete",
        finishedAt: Date.now(),
        highlightedOutputs: ["final.video"],
        outputs: {
          "final.video": { type: typeVideo, value: { kind: "build-file", path: "outputs/final.mp4", size: PNG_1PX.length, mediaType: "video/mp4" } },
          "poster.image": { type: typeImage, value: { kind: "value", path: "outputs/poster.value.json" } },
          "meta.json": { type: typeData, value: { kind: "inline", value: "v1" } },
        },
      }, {
        "outputs/final.mp4": PNG_1PX,
        "assets/poster.png": PNG_1PX,
        "outputs/poster.value.json": JSON.stringify(compositeDoc),
      });

      // read: three public Outputs indexed by kind, no folder scanning
      const view = await runResultsRead(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId });
      assert.equal(view.outcome, "complete");
      assert.equal(view.outputs.length, 3);
      const byName = new Map(view.outputs.map((output) => [output.name, output] as const));
      const videoIndexed = byName.get("final.video");
      assert.equal(videoIndexed?.kind, "resource");
      assert.equal(videoIndexed?.mediaType, "video/mp4");
      assert.equal(byName.get("poster.image")?.kind, "composite");
      assert.equal(byName.get("meta.json")?.kind, "scalar");
      assert.equal(videoIndexed?.highlighted, true);

      // export: resource → real bytes handle (content addressed), scalar → JSON
      const videoExport = await runResultsExport(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId, output: "final.video" });
      assert.equal(videoExport.kind, "resource");
      assert.equal(videoExport.mediaType, "video/mp4");
      assert.ok(typeof videoExport.handle === "string");
      const handleRecord = await resolveExportHandle(scenario1.options, videoExport.handle as string);
      assert.ok(existsSync(handleRecord.absolutePath));
      assert.equal(statSync(handleRecord.absolutePath).size, PNG_1PX.length);
      const sameHandle = await runResultsExport(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId, output: "final.video" });
      assert.equal(sameHandle.handle, videoExport.handle as string, "archive handle is content-addressed and idempotent");

      const scalarExport = await runResultsExport(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId, output: "meta.json" });
      assert.equal(scalarExport.kind, "scalar");
      assert.equal(scalarExport.value, "v1");

      // export: composite → value document + per-Resource handles
      const compositeExport = await runResultsExport(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId, output: "poster.image" });
      assert.equal(compositeExport.kind, "composite");
      const doc = JSON.parse(compositeExport.valueDocument as string);
      assert.equal(doc.format, "hypit.result-value@1");
      assert.equal(doc.value.caption, "hello");
      const compositeFiles = compositeExport.files ?? [];
      const posterFile = compositeFiles[0]!;
      assert.equal(compositeFiles.length, 1);
      assert.equal(posterFile.mediaType, "image/png");
      assert.ok(existsSync((await resolveExportHandle(scenario1.options, posterFile.handle as string)).absolutePath));

      // presentation: write-back updates the ORIGINAL repository, identity intact
      const presented = await runResultsPresentation(scenario1.options, {
        projectId: scenario1.projectId,
        engineBuildId: buildId,
        title: "新标题",
        note: "复核通过",
        outputDisplayNames: { "final.video": "成片" },
      });
      assert.equal(presented.title, "新标题");
      const reread = await runResultsRead(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId });
      assert.equal(reread.title, "新标题");
      assert.equal(reread.note, "复核通过");
      assert.equal(reread.outputs.length, 3, "presentation must not change Output identity");
      assert.equal(reread.outputs.find((output) => output.name === "final.video")?.displayName, "成片");

      // history: cross-Build filtering by output name
      const history = await runResultsHistory(scenario1.options, { projectId: scenario1.projectId, output: "final.video", limit: 10 });
      assert.equal(history.items.length, 1);
      assert.equal(history.items[0]!.engineBuildId, buildId);
      const historyMiss = await runResultsHistory(scenario1.options, { projectId: scenario1.projectId, output: "absent.output" });
      assert.equal(historyMiss.items.length, 0);

      // reuse: native build-record/satisfy spliced into the CURRENT run file
      writeFileSync(join(scenario1.projectsRoot, scenario1.projectId, "work", "main.svrun"),
        `<?svml using="@hypit/run-markup@1"?>\n\n<svrun version="1">\n  <author source="./main.svml"/>\n  <target output="final.video"/>\n</svrun>\n`);
      const reuse = await runResultsReuse(scenario1.options, {
        projectId: scenario1.projectId,
        runFile: "main.svrun",
        selections: [{ buildId, outputName: "final.video", target: "final.video" }],
      });
      assert.ok(reuse.changes[0]!.content.includes(`<build-record id="prior" build="${buildId}" output="final.video"/>`));
      assert.ok(reuse.changes[0]!.content.includes(`<satisfy output="final.video" candidate="prior"/>`));
      assert.ok(reuse.changes[0]!.content.includes(`<target output="final.video"/>`), "original targets preserved");
      // foreign/unknown build cannot be reused (private Results do not exist here)
      await assert.rejects(
        runResultsReuse(scenario1.options, {
          projectId: scenario1.projectId,
          selections: [{ buildId: engine.orderedBuildId(Date.now(), "NOPEAAAAAA"), outputName: "final.video", target: "final.video" }],
        }),
        (error: unknown) => error instanceof DispatchError && error.code === "not_found",
      );

      // finish/discard honesty: no runtime submission exists for a bare Result
      await assert.rejects(
        runResultsFinish(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId }),
        (error: unknown) => error instanceof DispatchError && error.code === "not_found",
      );
      await assert.rejects(
        runResultsDiscard(scenario1.options, { projectId: scenario1.projectId, engineBuildId: buildId }),
        (error: unknown) => error instanceof DispatchError && error.code === "submission_not_discardable",
      );
    } finally {
      await opened.close();
    }
  } finally {
    scenario1.cleanup();
  }
});

test("spliceReuseDeclarations appends before </svrun> when no explicit targets exist", () => {
  const content = `<svrun version="1">\n  <author source="./main.svml"/>\n</svrun>`;
  const updated = spliceReuseDeclarations(content, ["  <build-record id=\"prior\" build=\"b\" output=\"o\"/>"]);
  assert.ok(updated.indexOf("build-record") < updated.indexOf("</svrun>"));
});

test("a REAL local render exports real media bytes through the handle path", { timeout: 600_000 }, async (t) => {
  const scenario2 = scenario();
  const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-results-att-"));
  const renderAdapter = { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir };
  const engine = await loadHypit(generatedRoot);
  await ensureChatMachinePackages();
  const workspace = prepareChatWorkspace();
  stabilizeRenderProfile(workspace);
  const dir = mkdtempSync(join(tmpdir(), "hypit-results-render-"));
  // Move the workspace under one parent so projectsRoot/<projectId> == workspace.
  const projectsRoot = dir;
  const projectId = "22111111-1111-4111-8111-111111111111";
  const workspaceRoot = join(projectsRoot, projectId);
  rmSync(workspaceRoot, { recursive: true, force: true });
  const { renameSync } = await import("node:fs");
  renameSync(workspace, workspaceRoot);
  mkdirSync(join(projectsRoot, "..", "resources"), { recursive: true });
  const renderOptions = { projectsRoot, distributionRoot: generatedRoot, store: scenario2.options.store };
  t.after(async () => {
    await stopWorker(renderAdapter, workspaceRoot).catch(() => {});
    rmSync(dir, { recursive: true, force: true });
    rmSync(attachmentDir, { recursive: true, force: true });
    scenario2.cleanup();
  });
  await ensureRuntimeProfile(generatedRoot, workspaceRoot);
  const repositoryLocation = await openProjectResultsLocation(renderAdapter, workspaceRoot);
  const engineBuildId = engine.orderedBuildId(Date.now(), "C10710BBBB");
  const compiled = await runCompile(
    { distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir },
    { workspaceRoot: workspaceRoot, runFile: "chat.svrun" },
  );
  const host = await openEngineHost(renderAdapter, workspaceRoot);
  const controller = await host.controller();
  await controller.programs.up({ maxWaitMs: 300_000 });
  await controller.worker.up({ maxWaitMs: 120_000 });
  await submitBuild(renderAdapter, { engineBuildId, workspaceRoot: workspaceRoot, runFile: "chat.svrun", repositoryLocation }, compiled);
  const deadline = Date.now() + 420_000;
  let outcome: string | undefined;
  while (Date.now() < deadline) {
    const opened = await openResultsRepository(adapter, workspaceRoot);
    const manifest = await opened.repository.read(engineBuildId);
    await opened.close?.();
    if (manifest?.outcome !== undefined) {
      outcome = manifest.outcome;
      break;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 2_000));
  }
  assert.equal(outcome, "complete", "chat render did not finish successfully");

  // The real finished Result surfaces through the same read/export commands.
  const view = await runResultsRead(renderOptions, { projectId, engineBuildId });
  assert.equal(view.outcome, "complete");
  const videoOutput = view.outputs.find((output) => output.kind === "resource");
  assert.ok(videoOutput, `expected a media Output: ${JSON.stringify(view.outputs)}`);
  const mediaExport = await runResultsExport(renderOptions, { projectId, engineBuildId, output: videoOutput.name });
  assert.equal(mediaExport.kind, "resource");
  assert.ok(typeof mediaExport.handle === "string");
  const record = await resolveExportHandle(renderOptions, mediaExport.handle as string);
  assert.ok(statSync(record.absolutePath).size > 0, "real media bytes are downloadable");
  assert.equal(record.mediaType, videoOutput.mediaType);
});
