import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { applyManagedChromePath } from "../../src/engine/browser-profile.ts";

test("bundled Chromium is selected only when the project has not selected a browser", async () => {
  const dir = await mkdtemp(join(tmpdir(), "hypit-browser-profile-"));
  const file = join(dir, "profile.json");
  try {
    for (const config of [{}, { browserVersion: "123.0.0.1" }, { chromePath: "/custom/chrome" }]) {
      const before = { endpoints: { "hyperframes.local": { config } } };
      await writeFile(file, JSON.stringify(before));
      await applyManagedChromePath(file, "/usr/bin/chromium");
      const after = JSON.parse(await readFile(file, "utf8"));
      assert.deepEqual(after.endpoints["hyperframes.local"].config,
        Object.keys(config).length === 0 ? { chromePath: "/usr/bin/chromium" } : config);
      const once = await readFile(file, "utf8");
      await applyManagedChromePath(file, "/usr/bin/chromium");
      assert.equal(await readFile(file, "utf8"), once);
    }
    await writeFile(file, JSON.stringify({ endpoints: {} }));
    await applyManagedChromePath(file, "/usr/bin/chromium");
    assert.deepEqual(JSON.parse(await readFile(file, "utf8")), { endpoints: {} });
  } finally { await rm(dir, { recursive: true, force: true }); }
});
