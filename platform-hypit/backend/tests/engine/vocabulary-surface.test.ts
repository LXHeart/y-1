// vocabulary-surface.test.ts — C107F-05 (task-107-fix-1) surface/visual vocabulary
// extension. The assertions are parity checks against the upstream functions
// with the same inputs (NOT hand-written snapshots): surfaces must equal
// `listSurfaces(distributionRoot, packages)` field for field, visual must equal
// `visualSchema(shape)` structurally, and the no-argument response must equal
// the three-field baseline byte for byte.
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { installEngineResolution } from "../../src/engine/hypit-bootstrap.ts";
import { describeVocabulary } from "../../src/engine/vocabulary.ts";
import { generatedRoot } from "./chat-workspace.ts";

await installEngineResolution(generatedRoot);
const videoCli = await import("@hypit/video-cli");

test("describeVocabulary with surface packages equals upstream listSurfaces field for field", async () => {
  const packages = ["@hypit/seedance", "@hypit/gpt-image"];
  const report = await describeVocabulary(generatedRoot, { surface: packages });
  const upstream = await videoCli.listSurfaces(generatedRoot, packages);
  assert.ok(report.surfaces !== undefined, "surfaces attached when surface given");
  assert.deepEqual(report.surfaces, upstream, "field-for-field parity with upstream listSurfaces");
  assert.ok(report.surfaces.length > 0, "seedance/gpt-image expose at least one surface");
  for (const listing of report.surfaces) {
    assert.equal(typeof listing.package, "string");
    assert.ok(listing.module !== undefined && listing.module !== null, "module present (upstream shape)");
    assert.equal(typeof listing.surface, "string");
    assert.equal(typeof listing.tag, "string");
    assert.ok(Array.isArray(listing.outputs));
  }
});

test("describeVocabulary with an unknown surface package rejects as invalid input", async () => {
  await assert.rejects(
    describeVocabulary(generatedRoot, { surface: ["@hypit/no-such-package"] }),
    (error: unknown) => error instanceof Error && error.name === "VocabularyInputError",
  );
});

test("describeVocabulary visual shape equals upstream visualSchema structurally", async () => {
  const report = await describeVocabulary(generatedRoot, { visual: "text-typography" });
  assert.ok(report.visual !== undefined, "visual attached when shape given");
  assert.deepEqual(report.visual, videoCli.visualSchema("text-typography"));
  assert.equal(report.visual.shapes[0]?.shape, "text-typography");
  assert.ok(report.visual.shapes[0]!.describes.length > 0);
});

test("describeVocabulary with empty visual lists all 14 shapes", async () => {
  const report = await describeVocabulary(generatedRoot, { visual: "" });
  assert.equal(report.visual?.shapes.length, 14);
  assert.deepEqual(
    report.visual!.shapes.map((shape) => shape.shape).sort(),
    videoCli.visualSchema().shapes.map((shape) => shape.shape).sort(),
  );
});

test("describeVocabulary with an unknown visual shape rejects with the shape list", async () => {
  await assert.rejects(
    describeVocabulary(generatedRoot, { visual: "not-a-shape" }),
    (error: unknown) =>
      error instanceof Error && error.name === "VocabularyInputError" && error.message.includes("shape must be one of"),
  );
});

test("describeVocabulary without options stays byte-for-byte the three-field baseline", async () => {
  const [plain, again, withEmptyOptions] = await Promise.all([
    describeVocabulary(generatedRoot),
    describeVocabulary(generatedRoot),
    describeVocabulary(generatedRoot, {}),
  ]);
  assert.deepEqual(Object.keys(plain).sort(), ["alignmentLanguages", "programs", "providers"]);
  assert.equal(JSON.stringify(plain), JSON.stringify(again), "stable serialization");
  assert.deepEqual(withEmptyOptions, plain);
});
