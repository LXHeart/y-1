// compile-adapter.test.ts — C107-02 (task-107) author compilation + real local render
// (TC107-02-01). Uses the semantic-composition chat project from G: a real author
// package, real Run markup, real local HyperFrames render with a real browser.
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

// Must run before any engine bootstrap in this process: external package
// resolution then targets the shared packages-only runner state root (never
// the developer's home, never broker credentials).
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { loadHypit } from "../../src/engine/hypit-bootstrap.ts";
import { runCheck, runCompile } from "../../src/engine/compile-adapter.ts";
import {
  ensureRuntimeProfile,
  inspectBuild,
  openEngineHost,
  openProjectResultsLocation,
  stopWorker,
  submitBuild,
} from "../../src/engine/runtime-adapter.ts";
import {
  ensureChatMachinePackages,
  generatedRoot,
  prepareChatWorkspace,
  stabilizeRenderProfile,
} from "./chat-workspace.ts";

const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-attachments-"));

test("check accepts the chat author source and the run source", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    const author = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
      workspaceRoot: workspace,
      entryFile: "chat.svml",
    });
    assert.equal(author.ok, true, JSON.stringify(author.diagnostics, null, 2));
    assert.equal(author.sourceKind, "author");
    assert.equal(author.frontend, "@hypit/markup@1");
    assert.ok(author.sourceClosureHash.length === 64);

    const run = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
      workspaceRoot: workspace,
      entryFile: "chat.svrun",
    });
    assert.equal(run.ok, true, JSON.stringify(run.diagnostics, null, 2));
    assert.equal(run.sourceKind, "run");
    assert.deepEqual(run.targetNames, ["final.video"]);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("check reports broken sources without fabricating line numbers", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    writeFileSync(join(workspace, "broken.svml"), "<?svml using=\"@hypit/markup@1\"?>\n<svml>\n  <nonsense/>\n</svml>\n");
    const broken = await runCheck({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
      workspaceRoot: workspace,
      entryFile: "broken.svml",
    });
    assert.equal(broken.ok, false);
    assert.ok(broken.diagnostics.length >= 1);
    for (const diagnostic of broken.diagnostics) {
      assert.equal(typeof diagnostic.message, "string");
      // positions we cannot know stay null — never invented (K03)
      assert.ok(diagnostic.line === null || Number.isSafeInteger(diagnostic.line));
      assert.ok(diagnostic.column === null || Number.isSafeInteger(diagnostic.column));
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("compile produces a JSON round-trippable build request with materialized attachments", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    const compiled = await runCompile({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
      workspaceRoot: workspace,
      runFile: "chat.svrun",
    });
    assert.equal(compiled.definition.format, "hypit.build-definition@1");
    assert.ok(compiled.catalog.publishedOutputs.some((output) => output.name === "final.video"));
    assert.ok(compiled.componentPackages.some((name) => name.includes("chat-scene")));

    // K10.4 encode/decode round trip: the request is pure data
    const decoded = JSON.parse(JSON.stringify(compiled)) as typeof compiled;
    assert.deepEqual(decoded, compiled);

    for (const attachment of compiled.attachments) {
      const file = join(attachmentDir, attachment.fileName);
      assert.ok(existsSync(file), `attachment materialized: ${attachment.fileName}`);
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a real local render through the trusted runtime produces a decodable MP4", { timeout: 600_000 }, async (t) => {
  const engine = await loadHypit(generatedRoot);
  await ensureChatMachinePackages();
  // One workspace for compile AND execution: the runtime Worker re-resolves
  // component packages from the project at execution time, so the trusted
  // submit must bind the same real workspace the Run was compiled against.
  const workspace = prepareChatWorkspace();
  stabilizeRenderProfile(workspace);
  t.after(async () => {
    await stopWorker({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace)
      .catch(() => {});
    rmSync(workspace, { recursive: true, force: true });
  });

  await ensureRuntimeProfile(generatedRoot, workspace);
  const repositoryLocation = await openProjectResultsLocation(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    workspace,
  );
  const engineBuildId = engine.orderedBuildId(Date.now(), "C10702TEST");
  const compiled = await runCompile({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
    workspaceRoot: workspace,
    runFile: "chat.svrun",
  });

  // bring the trusted Worker up, then submit under the pre-allocated id (K06.2)
  const host = await openEngineHost({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace);
  const controller = await host.controller();
  // programs.up installs the managed render browser on first use (stable
  // cache under repo data/); worker.up then starts the execution process.
  await controller.programs.up({ maxWaitMs: 300_000 });
  await controller.worker.up({ maxWaitMs: 120_000 });

  const submission = await submitBuild(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    {
      engineBuildId,
      workspaceRoot: workspace,
      runFile: "chat.svrun",
      repositoryLocation,
      title: "C107-02 local render smoke",
    },
    compiled,
  );
  assert.equal(submission.engineBuildId, engineBuildId);

  // poll the result repository until the outcome is decided
  const deadline = Date.now() + 420_000;
  let outcome: string | undefined;
  while (Date.now() < deadline) {
    const opened = await engine.videoCliDistribution.openProjectResults!(workspace, {
      packageRoot: workspace,
    });
    const manifest = await opened.repository.read(engineBuildId);
    await opened.close?.();
    if (manifest?.outcome !== undefined) {
      outcome = manifest.outcome;
      break;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 2_000));
  }
  assert.equal(outcome, "complete", "render did not complete successfully");

  // export final.video through the native CLI get command, then verify with ffprobe
  const exportTarget = join(workspace, "chat.mp4");
  const { runVideoCli } = await import("@hypit/video-cli");
  await runVideoCli([
    "get", engineBuildId,
    "--workspace", workspace,
    "--output", "final.video",
    "--to", exportTarget,
  ], { write: () => {} });
  const probe = spawnSync("ffprobe", ["-v", "error", "-show_streams", "-of", "json", exportTarget], { encoding: "utf8" });
  assert.equal(probe.status, 0, probe.stderr);
  const streams = (JSON.parse(probe.stdout!) as { streams: { codec_type?: string; width?: number; height?: number }[] }).streams;
  const video = streams.find((stream) => stream.codec_type === "video");
  assert.ok(video, "exported file must contain a video stream");
  assert.equal(video!.width, 540);
  assert.equal(video!.height, 960);
  const decode = spawnSync("ffmpeg", ["-v", "error", "-xerror", "-i", exportTarget, "-f", "null", "-"], { encoding: "utf8" });
  assert.equal(decode.status, 0, `full decode failed: ${decode.stderr}`);
});
