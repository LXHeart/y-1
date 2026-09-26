// planning.test.ts — C107-08 (task-107) plan/pricing/vocabulary against the real
// distribution. Fixtures per card acceptance: a bad Header, a type error, a
// missing package, a local render plan, a remote Need, and a reused Output.
// Everything runs through the native compiler/runtime — no simplified planners.
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { loadHypit } from "../../src/engine/hypit-bootstrap.ts";
import {
  planRunInRunner,
  assemblePlanDocument,
  assemblePricingDocument,
} from "../../src/engine/planning.ts";
import { describeVocabulary } from "../../src/engine/vocabulary.ts";
import { runCompile } from "../../src/engine/compile-adapter.ts";
import {
  ensureRuntimeProfile,
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

const attachmentDir = mkdtempSync(join(tmpdir(), "hypit-plan-attachments-"));

/** Assemble a minimal remote-Need project: one gpt:Image generation target. */
function prepareRemoteNeedWorkspace(): string {
  const workspace = mkdtempSync(join(tmpdir(), "hypit-remote-ws-"));
  mkdirSync(workspace, { recursive: true });
  writeFileSync(join(workspace, "package.json"), `${JSON.stringify({
    name: "hypit-plan-remote",
    version: "0.0.0",
    private: true,
    type: "module",
  })}\n`);
  writeFileSync(join(workspace, "main.svml"), `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="text" from="@hypit/text@1"/>
  <import as="gpt" from="@hypit/gpt-image@1"/>
  <text:Value id="poster-prompt">A lighthouse at dusk, long exposure, cinematic.</text:Value>
  <gpt:Image id="poster" prompt={poster-prompt} aspect-ratio="16:9" resolution="1K"/>
</svml>
`);
  writeFileSync(join(workspace, "main.svrun"), `<?svml using="@hypit/run-markup@1"?>

<svrun version="1">
  <author source="./main.svml"/>
  <target output="poster.image"/>
</svrun>
`);
  return workspace;
}

async function planWorkspace(workspace: string, runFile: string) {
  const runner = await planRunInRunner({ distributionRoot: generatedRoot }, { workspaceRoot: workspace, runFile });
  const host = await openEngineHost(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    workspace,
  );
  const document = await assemblePlanDocument(host, runner);
  const pricing = await assemblePricingDocument(host, runner);
  return { runner, document, pricing };
}

test("plan resolves the chat run: local endpoints, honest needs, deterministic hash", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  stabilizeRenderProfile(workspace);
  try {
    await ensureRuntimeProfile(generatedRoot, workspace);
    const first = await planWorkspace(workspace, "chat.svrun");
    assert.deepEqual(first.runner.targets, ["final.video"]);
    assert.ok(first.runner.steps > 0, "run has a non-empty step plan");
    assert.ok(first.runner.providerQueries.length > 0, "planned requests are enumerated");
    assert.ok(first.document.ok, `plan should be fully resolvable: ${JSON.stringify(first.document.unresolvedRequests)} / ${JSON.stringify(first.document.unsupportedRequests)}`);
    assert.ok(first.document.providers.every((provider) => provider.status === "resolved"), JSON.stringify(first.document.providers));
    assert.ok(first.document.providers.every((provider) => provider.endpoint !== undefined && provider.endpoint.endsWith(".local")));
    assert.ok(first.document.preflight?.ok !== false, "preflight on local endpoints passes");
    // 纯本地：每个请求 pricing 都是 local，估价行不虚构数字（unknown/null 语义）
    assert.ok(first.document.providers.every((provider) => provider.pricing?.kind === "local"));
    assert.ok(first.pricing.rows.every((row) => row.status === "resolved" && row.pricing?.kind === "local"));
    assert.equal(first.pricing.unknownRequests.length, 0);

    // planHash/planId content-addressed: same inputs, same ids, no wall-clock.
    const second = await planWorkspace(workspace, "chat.svrun");
    assert.equal(second.document.planHash, first.document.planHash);
    assert.equal(second.document.planId, first.document.planId);
    assert.match(first.document.planId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u);
    // before any Build exists, nothing is reused
    assert.deepEqual(first.document.choices, []);
    assert.deepEqual(first.document.reuse, []);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a bad Header is rejected with the frontend named, no plan produced", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    const text = await import("node:fs").then((fs) => fs.readFileSync(join(workspace, "chat.svml"), "utf8"));
    writeFileSync(join(workspace, "chat.svml"), text.replace('@hypit/markup@1', "@hypit/not-a-real-frontend@9"));
    await assert.rejects(
      () => planRunInRunner({ distributionRoot: generatedRoot }, { workspaceRoot: workspace, runFile: "chat.svrun" }),
      /not-a-real-frontend/,
    );
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a type error surfaces as an accurate compile failure", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    const { readFileSync } = await import("node:fs");
    const text = readFileSync(join(workspace, "chat.svml"), "utf8");
    // clock frame-rate is a typed attribute: feed it a non-integer
    writeFileSync(join(workspace, "chat.svml"), text.replace('frame-rate="30"', 'frame-rate="thirty"'));
    await assert.rejects(
      () => planRunInRunner({ distributionRoot: generatedRoot }, { workspaceRoot: workspace, runFile: "chat.svrun" }),
      (error: unknown) => error instanceof Error && error.message.length > 0,
    );
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a missing package import fails resolution naming the package", { timeout: 240_000 }, async () => {
  const workspace = prepareChatWorkspace();
  try {
    const { readFileSync } = await import("node:fs");
    const text = readFileSync(join(workspace, "chat.svml"), "utf8");
    writeFileSync(join(workspace, "chat.svml"), text.replace(
      '<import as="film" from="@hypit/film@1"/>',
      '<import as="film" from="@hypit/does-not-exist@1"/>',
    ));
    await assert.rejects(
      () => planRunInRunner({ distributionRoot: generatedRoot }, { workspaceRoot: workspace, runFile: "chat.svrun" }),
      /does-not-exist/,
    );
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a remote Need without a configured endpoint plans as unresolved with null pricing", { timeout: 240_000 }, async () => {
  const workspace = prepareRemoteNeedWorkspace();
  try {
    await ensureRuntimeProfile(generatedRoot, workspace);
    // strip the remote endpoint the starter profile selects: nothing anywhere
    // configures a gpt capability endpoint now
    const profilePath = join(workspace, "hypit.runtime.json");
    const profile = JSON.parse(readFileSync(profilePath, "utf8")) as {
      endpoints: Record<string, unknown>;
    };
    delete profile.endpoints["hypihub.default"];
    writeFileSync(profilePath, `${JSON.stringify(profile, null, 2)}\n`);
    const planned = await planWorkspace(workspace, "main.svrun");
    assert.ok(planned.runner.providerQueries.length > 0, "the remote generation request is enumerated");
    assert.equal(planned.document.ok, false, "plan is honest about being un-submittable");
    assert.equal(planned.document.unresolvedRequests.length, 1);
    assert.ok(planned.document.missingCapabilities.some((capability) => capability.startsWith("@hypit/gpt-image@1#")));
    // 价格未知 null，不写 0：unresolved 行的估价与币种都是 null
    const row = planned.pricing.rows.find((entry) => entry.status !== "resolved");
    assert.ok(row, "unresolved request has a pricing row");
    assert.equal(row.estimatedCost, null);
    assert.equal(row.currency, null);
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("a reused Output changes the plan and removes the satisfied request", { timeout: 600_000 }, async (t) => {
  const engine = await loadHypit(generatedRoot);
  await ensureChatMachinePackages();
  const workspace = prepareChatWorkspace();
  stabilizeRenderProfile(workspace);
  t.after(async () => {
    await stopWorker({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace).catch(() => {});
    rmSync(workspace, { recursive: true, force: true });
  });
  await ensureRuntimeProfile(generatedRoot, workspace);
  const before = await planWorkspace(workspace, "chat.svrun");
  assert.deepEqual(before.document.choices, []);

  // produce one real Result via the trusted runtime (native local render)
  const repositoryLocation = await openProjectResultsLocation(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    workspace,
  );
  const engineBuildId = engine.orderedBuildId(Date.now(), "C10708TEST");
  const compiled = await runCompile({ distributionRoot: generatedRoot, attachmentOutputDir: attachmentDir }, {
    workspaceRoot: workspace,
    runFile: "chat.svrun",
  });
  const host = await openEngineHost({ distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir }, workspace);
  const controller = await host.controller();
  await controller.programs.up({ maxWaitMs: 300_000 });
  await controller.worker.up({ maxWaitMs: 120_000 });
  const submission = await submitBuild(
    { distributionRoot: generatedRoot, attachmentSourceDir: attachmentDir },
    { engineBuildId, workspaceRoot: workspace, runFile: "chat.svrun", repositoryLocation, title: "C107-08 reuse fixture" },
    compiled,
  );
  assert.equal(submission.engineBuildId, engineBuildId);
  const deadline = Date.now() + 420_000;
  let outcome: string | undefined;
  while (Date.now() < deadline) {
    const opened = await engine.videoCliDistribution.openProjectResults!(workspace, { packageRoot: workspace });
    const manifest = await opened.repository.read(engineBuildId);
    await opened.close?.();
    if (manifest?.outcome !== undefined) {
      outcome = manifest.outcome;
      break;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 2_000));
  }
  assert.equal(outcome, "complete", "render did not complete successfully");

  // 复用是 Run 文件里声明的原生语义：build-record candidate + satisfy。
  writeFileSync(join(workspace, "chat-reuse.svrun"), `<?svml using="@hypit/run-markup@1"?>

<svrun version="1">
  <author source="./chat.svml"/>
  <build-record id="prior" build="${engineBuildId}" output="final.video"/>
  <satisfy output="final.video" candidate="prior"/>
  <target output="final.video"/>
</svrun>
`);
  const after = await planWorkspace(workspace, "chat-reuse.svrun");
  assert.ok(after.document.choices.some((choice) => choice.output === "final.video"), JSON.stringify(after.document.choices));
  assert.equal(after.document.reuse.length, after.document.choices.length);
  // 复用改变计划：hash 变化且请求数下降（已复用 Output 不再生成）
  assert.notEqual(after.document.planHash, before.document.planHash);
  assert.ok(
    after.runner.providerQueries.length < before.runner.providerQueries.length,
    `reuse must reduce new requests: ${before.runner.providerQueries.length} -> ${after.runner.providerQueries.length}`,
  );
});

test("vocabulary reflects the real distribution: providers, gaps, programs", { timeout: 120_000 }, async () => {
  const vocabulary = await describeVocabulary(generatedRoot);
  assert.equal(vocabulary.providers.length, 10);
  const hypihub = vocabulary.providers.find((provider) => provider.defaultEndpointId === "hypihub.default");
  assert.ok(hypihub, "hypihub present");
  assert.equal(hypihub.kind, "remote");
  assert.ok(hypihub.capabilities.length > 0);
  assert.ok(hypihub.capabilities.every((capability) => capability.key.includes("#")));
  const oauthSlot = hypihub.credentials.find((slot) => slot.acquisition !== null && slot.acquisition !== undefined);
  assert.ok(oauthSlot, "hypihub declares an OAuth acquisition slot");
  assert.equal(oauthSlot.acquisition?.kind, "oauth2-pkce");
  const hyperframes = vocabulary.providers.find((provider) => provider.defaultEndpointId === "hyperframes.local");
  assert.equal(hyperframes?.managedProgram, true);
  const programs = new Set(vocabulary.programs.map((program) => program.program));
  assert.ok(programs.has("whisperx.local"));
  assert.ok(programs.has("image.opencv.local"));
  assert.ok(vocabulary.alignmentLanguages.length > 0);
});
