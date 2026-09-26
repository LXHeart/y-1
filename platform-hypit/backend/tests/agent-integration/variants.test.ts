// variants.test.ts — C107-19 (task-107) broker-side variant Run changeset
// support (TC107-19-02/04 broker layer). The Java variant service owns axes
// validation and batch state; this file proves the BROKER half of the contract:
// writing N variant Run files as ONE journaled workspace change advances the
// head exactly once, replays idempotently, and a second batch on a stale base
// revision is refused (no cross-batch grant inheritance by accident).
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { CommandStore } from "../../src/commands/store.ts";
import { dispatchCommand } from "../../src/commands/dispatcher.ts";
import { makeOptions } from "../runtime/helpers.ts";
import { WorkspaceConflictError } from "../../src/workspace/transactions.ts";
import { projectRootFor, provisionFromTemplate } from "../../src/workspace/provision.ts";
import { readHead } from "../../src/workspace/transactions.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const templateDir = join(repoRoot, "platform-hypit/fixtures/minimal-local");
const templateFiles = ["main.svml", "main.svrun", "package.json"];

type VariantAxis = { readonly key: string; readonly values: readonly string[] };

/** Deterministic variant Run content (mirrors the Java writer's shape). */
function variantRunContent(baseRunFile: string, parameters: Record<string, string>, ordinal: number): string {
  return `<?svml using="@hypit/run-markup@1"?>

<!-- C107-19 variant ${ordinal} params: ${JSON.stringify(parameters)} -->
<svrun version="1">
  <author source="./${baseRunFile.replace(/\.svrun$/u, "")}"/>
  <target output="final.video"/>
</svrun>
`;
}

async function seedProject(): Promise<{ options: Awaited<ReturnType<typeof makeOptions>>; projectId: string; baseRevision: number }> {
  const options = makeOptions({});
  const projectId = "bbbbbbbb-0000-4000-8000-00000000b019";
  await provisionFromTemplate(options.projectsRoot, projectId, templateDir, templateFiles);
  const head = await readHead(join(projectRootFor(options.projectsRoot, projectId)));
  return { options, projectId, baseRevision: head?.revision ?? 0 };
}

test("variants: N variant Run files land as one journaled change; replay is idempotent", async () => {
  const { options, projectId, baseRevision } = await seedProject();
  try {
    const axes: VariantAxis[] = [{ key: "topic", values: ["alpha", "beta", "gamma"] }];
    const combinations = axes.flatMap((axis) => axis.values.map((value) => ({ [axis.key]: value })));
    const changes = combinations.map((parameters, ordinal) => ({
      path: `runs/variants/variant-${ordinal}.svrun`,
      action: "put",
      content: variantRunContent("main.svrun", parameters as Record<string, string>, ordinal),
    }));
    const commandId = "variants-batch-1";
    const first = await dispatchCommand(options, commandId, "workspace.apply", {
      projectId, baseRevision, applyMode: "save", changes,
    });
    assert.equal(first.outcome, "completed");
    const result = JSON.parse(first.command.resultJson ?? '{}') as { revision: number };
    assert.equal(result.revision, baseRevision + 1, "one batch = one head advance");

    // Same commandId + same payload → replay, no second revision.
    const replay = await dispatchCommand(options, commandId, "workspace.apply", {
      projectId, baseRevision, applyMode: "save", changes,
    });
    assert.equal(replay.outcome, "replayed");
    const reread = await readHead(join(projectRootFor(options.projectsRoot, projectId)));
    assert.equal(reread?.revision, baseRevision + 1);

    // Files really exist with the parameter comment (per-variant independent runs).
    for (let ordinal = 0; ordinal < combinations.length; ordinal++) {
      const text = readFileSync(join(projectRootFor(options.projectsRoot, projectId), "work",
        `runs/variants/variant-${ordinal}.svrun`), "utf8");
      assert.ok(text.includes(`variant ${ordinal} params`), `variant-${ordinal} carries its parameters`);
      assert.ok(text.includes('<target output="final.video"/>'));
    }
  } finally {
    options.cleanup();
  }
});

test("variants: second batch on a stale base revision is refused (no silent overwrite)", async () => {
  const { options, projectId, baseRevision } = await seedProject();
  try {
    const firstChanges = [{
      path: "runs/variants/variant-0.svrun", action: "put",
      content: variantRunContent("main.svrun", { topic: "alpha" }, 0),
    }];
    await dispatchCommand(options, "variants-batch-A", "workspace.apply", {
      projectId, baseRevision, applyMode: "save", changes: firstChanges,
    });
    // Batch B arrives citing the OLD base revision → CAS conflict, nothing written.
    await assert.rejects(
      () => dispatchCommand(options, "variants-batch-B", "workspace.apply", {
        projectId, baseRevision, applyMode: "save",
        changes: [{ path: "runs/variants/variant-0.svrun", action: "put", content: variantRunContent("main.svrun", { topic: "beta" }, 0) }],
      }),
      (error: unknown) => error instanceof WorkspaceConflictError || /stale/iu.test(String((error as Error).message)),
    );
    const text = readFileSync(join(projectRootFor(options.projectsRoot, projectId), "work",
      "runs/variants/variant-0.svrun"), "utf8");
    assert.ok(text.includes("alpha"), "stale batch must not overwrite the committed variant file");
  } finally {
    options.cleanup();
  }
});
