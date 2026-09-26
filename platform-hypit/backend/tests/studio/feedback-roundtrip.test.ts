// feedback-roundtrip.test.ts — C107-18 (task-107): the broker FEEDBACK bridge
// keeps the upstream FEEDBACK.json format EXACTLY (TC107-18-02). Comments ride
// the real upstream store (@hypit/studio feedback-store): add / replace
// (edit+resolve+reopen) / delete preserve stable ids, run binding and second
// times; the broker baseHash CAS rejects a stale view without losing input;
// there is no second editable comment store anywhere in y-1.
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { readFeedback, mutateFeedback } from "../../src/studio/feedback.ts";
import { projectRootFor, provisionFromTemplate } from "../../src/workspace/provision.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
// The bridge loads the upstream FEEDBACK modules verbatim from the pinned G.
const DISTRIBUTION_ROOT = join(repoRoot, "platform-hypit/.generated/hypit");
const templateDir = join(repoRoot, "platform-hypit/fixtures/minimal-local");
const templateFiles = ["main.svml", "main.svrun", "package.json"];

const RUN = "runs/main.svrun";

async function seedProject(): Promise<{ projectsRoot: string; projectId: string }> {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-feedback-"));
  const projectId = "bbbbbbbb-0000-4000-8000-00000000b018";
  await provisionFromTemplate(projectsRoot, projectId, templateDir, templateFiles);
  return { projectsRoot, projectId };
}

function feedbackFile(projectsRoot: string, projectId: string): string {
  return join(projectRootFor(projectsRoot, projectId), "work", "FEEDBACK.json");
}

test("feedback roundtrip: add/edit/resolve/reopen/delete keep the upstream document format", async () => {
  const { projectsRoot, projectId } = await seedProject();
  try {
    // add — the upstream comment shape: stable id, run, seconds, text.
    const added = await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "add",
      comment: { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大" },
    }], undefined) as { comments: { id: string; run: string; at: number; text: string }[]; hash: string };
    assert.equal(added.comments.length, 1);
    assert.deepEqual(
      { id: added.comments[0]!.id, run: added.comments[0]!.run, at: added.comments[0]!.at, text: added.comments[0]!.text },
      { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大" },
    );
    assert.ok(existsSync(feedbackFile(projectsRoot, projectId)), "FEEDBACK.json is the authoritative file");
    const onDisk = JSON.parse(readFileSync(feedbackFile(projectsRoot, projectId), "utf8"));
    assert.ok(Array.isArray(onDisk.comments), "document keeps the upstream comments array");

    // edit preserves id and run; resolve sets resolved=true; reopen clears it.
    const edited = await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [
      { type: "replace", before: added.comments[0], comment: { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大到 48px" } },
      { type: "replace", before: { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大到 48px" }, comment: { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大到 48px", resolved: true } },
    ], added.hash) as { comments: { text: string; resolved?: boolean }[]; hash: string };
    assert.equal(edited.comments[0]!.text, "字幕放大到 48px");
    assert.equal(edited.comments[0]!.resolved, true);

    const reopened = await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "replace", before: edited.comments[0],
      comment: { id: "fb-1", run: RUN, at: 1.5, text: "字幕放大到 48px" },
    }], edited.hash) as { comments: { resolved?: boolean }[]; hash: string };
    assert.equal(reopened.comments[0]!.resolved, undefined);

    const deleted = await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "delete", before: reopened.comments[0],
    }], reopened.hash) as { comments: unknown[]; hash: string };
    assert.equal(deleted.comments.length, 0);

    // read returns the upstream view fields (file, run filter, comments).
    const view = await readFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, RUN) as { file: string; run: string; comments: unknown[] };
    assert.equal(view.file, "FEEDBACK.json");
    assert.equal(view.run, RUN);
    assert.equal(view.comments.length, 0);
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

test("feedback CAS: a stale expectedHash rejects the batch and the file is untouched", async () => {
  const { projectsRoot, projectId } = await seedProject();
  try {
    const first = await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "add", comment: { id: "fb-a", run: RUN, at: 0, text: "提到产品时出图" },
    }], undefined) as { hash: string };

    // A concurrent writer lands first.
    await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "add", comment: { id: "fb-b", run: RUN, at: 2, text: "音效降低" },
    }], undefined);

    const before = readFileSync(feedbackFile(projectsRoot, projectId), "utf8");
    await assert.rejects(
      () => mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
        type: "delete", before: { id: "fb-a", run: RUN, at: 0, text: "提到产品时出图" },
      }], first.hash),
      (error: unknown) => error instanceof Error && /feedback_conflict|FEEDBACK changed/iu.test(String((error as { code?: string }).code ?? error.message)),
    );
    assert.equal(readFileSync(feedbackFile(projectsRoot, projectId), "utf8"), before,
      "rejected mutations must not touch the authoritative file");
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});

test("feedback refuses upstream-invalid mutations and duplicate ids (native semantics)", async () => {
  const { projectsRoot, projectId } = await seedProject();
  try {
    await assert.rejects(
      () => mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
        type: "add", comment: { id: "fb-x", run: RUN, at: -1, text: "negative time" },
      }], undefined),
      /non-negative time/iu,
    );
    await mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
      type: "add", comment: { id: "fb-dup", run: RUN, at: 0, text: "first" },
    }], undefined);
    await assert.rejects(
      () => mutateFeedback(DISTRIBUTION_ROOT, projectsRoot, projectId, [{
        type: "add", comment: { id: "fb-dup", run: RUN, at: 1, text: "second" },
      }], undefined),
      /already exists/iu,
    );
  } finally {
    rmSync(projectsRoot, { recursive: true, force: true });
  }
});
