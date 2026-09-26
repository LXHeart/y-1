// companion-parity.test.ts — C107-13 (task-107): all TWELVE official
// Companion families load from the distribution, the fixture manifest matches
// them one-to-one with editable field declarations, and the y-1 theme tokens
// from patch 0003 are live in the Studio shell without external font CDNs.
// Field-level Inspector parity stays in the upstream *-studio package tests
// (native suites); this is the broker-side structural parity net.
import { readFileSync, existsSync } from "node:fs";
import { strict as assert } from "node:assert";
import test from "node:test";

import { THEME_CSS_VARIABLES, Y1_THEME_TOKENS, assertNoExternalFonts, themeStyleBlock } from "../../src/studio/theme-tokens.ts";

const generatedRoot = new URL("../../../.generated/hypit/", import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL("../../../fixtures/companions/manifest.json", import.meta.url).pathname, "utf8"),
) as {
  families: { id: string; package: string; entry: string; companion: string; editable: string[] }[];
};

test("all twelve official Companion families exist in the distribution with an entry module", () => {
  assert.equal(manifest.families.length, 12);
  for (const family of manifest.families) {
    const entry = new URL(`../../../.generated/hypit/${family.entry}`, import.meta.url).pathname;
    assert.ok(existsSync(entry), `${family.id}: ${family.entry} missing`);
    assert.ok(family.editable.length > 0, `${family.id}: no editable fields declared`);
  }
});

test("each family declares its companion entity and Inspector-editable surface", () => {
  const ids = manifest.families.map((family) => family.id).sort();
  assert.deepEqual(ids, [
    "audio-track", "caption-fine", "comment-sticker", "deck-track", "film", "media-track",
    "performance", "ranking", "screen-overlay", "script", "sound", "typography-track",
  ]);
  for (const family of manifest.families) {
    assert.match(family.package, /@hypit\/[a-z-]+-studio/u, `${family.id} package name`);
    assert.ok(family.companion.length > 0, `${family.id} companion entity`);
  }
});

test("patch 0003 injects the y-1 tokens into the Studio shell and keeps fonts self-hosted", () => {
  const html = readFileSync(new URL("../../../.generated/hypit/packages/studio/index.html", import.meta.url), "utf8");
  for (const variable of Object.values(THEME_CSS_VARIABLES)) {
    assert.ok(html.includes(variable), `shell missing ${variable}`);
  }
  assert.ok(html.includes(Y1_THEME_TOKENS.primary), "brand primary token value present");
  assert.ok(html.includes("4434d4"), "active primary token value present");
  assertNoExternalFonts(html);
  // the module and the injected block stay in lockstep
  assert.ok(html.includes(themeStyleBlock().trim().slice(0, 40)));
});

test("the theme style block itself carries no external font CDN", () => {
  assertNoExternalFonts(themeStyleBlock());
});
