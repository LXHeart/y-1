// mutation-bridge.test.ts — C107-12 (task-107): the write-back relay enforces
// read-only refusal, revision equality (Source changed outside Studio), byte
// budgets and token strength; the 0002 patch token path is live in G.
import { strict as assert } from "node:assert";
import test from "node:test";

import { assertBridgeMutation, bridgeAuthorization } from "../../src/studio/mutation-bridge.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

const { readFileSync } = await import("node:fs");
const guard = readFileSync(
  new URL("../../../.generated/hypit/packages/studio/src/mutation-origin.ts", import.meta.url), "utf8");

test("the bridge token path exists in the patched origin guard", () => {
  assert.ok(guard.includes("HYPIT_STUDIO_BRIDGE_TOKEN"), "patch 0002 must be applied in G");
  assert.ok(guard.includes("localhost"), "localhost rules stay intact");
});

test("read-only sessions and stale revisions are refused", () => {
  assert.throws(() => assertBridgeMutation({
    sessionId: "s", revision: 3, baseRevision: 3, readOnly: true, operations: [{ op: "set" }],
  }), DispatchError);
  assert.throws(() => assertBridgeMutation({
    sessionId: "s", revision: 4, baseRevision: 3, readOnly: false, operations: [{ op: "set" }],
  }), (error: unknown) => error instanceof DispatchError && error.code === "revision_conflict");
});

test("operations are bounded and the token is strength-checked", () => {
  assert.throws(() => assertBridgeMutation({
    sessionId: "s", revision: 1, baseRevision: 1, readOnly: false, operations: [],
  }), DispatchError);
  assert.throws(() => assertBridgeMutation({
    sessionId: "s", revision: 1, baseRevision: 1, readOnly: false,
    operations: Array.from({ length: 201 }, (_, index) => ({ index })),
  }), DispatchError);
  assert.throws(() => bridgeAuthorization("short"), DispatchError);
  assert.equal(bridgeAuthorization("b".repeat(64)), `Bearer ${"b".repeat(64)}`);
});
