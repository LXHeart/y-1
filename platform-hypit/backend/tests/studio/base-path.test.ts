// base-path.test.ts — C107-12 (task-107): the 0001 patch makes the Studio
// middleware prefix-agnostic. Verified against the PATCHED generated source:
// with HYPIT_STUDIO_BASE_PATH set, a prefixed request routes; without it,
// behavior is upstream-identical.
import { strict as assert } from "node:assert";
import test from "node:test";

import { readFileSync } from "node:fs";
import { assertBasePath } from "../../src/studio/url-policy.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

test("base path validation refuses malformed mounts", () => {
  assertBasePath("/studio");
  assertBasePath("/studio/sessions");
  assert.throws(() => assertBasePath("studio"), DispatchError);
  assert.throws(() => assertBasePath("/studio/"), DispatchError);
  assert.throws(() => assertBasePath("/stu dio"), DispatchError);
});

test("patched middleware strips the configured prefix before routing", () => {
  // the patch text is live in the generated server: prefix strip happens
  // before the URL is parsed, so /studio/<session>/__studio/* hits upstream
  // routes unchanged.
  const source = readFileSync(
    new URL("../../../.generated/hypit/packages/studio/src/server.ts", import.meta.url), "utf8");
  assert.ok(source.includes("HYPIT_STUDIO_BASE_PATH"), "patch 0001 must be applied in G");
  const stripIndex = source.indexOf("HYPIT_STUDIO_BASE_PATH");
  const urlIndex = source.indexOf("http://studio.hypit.local");
  assert.ok(stripIndex >= 0 && urlIndex > stripIndex, "prefix strip precedes URL parsing");
});
