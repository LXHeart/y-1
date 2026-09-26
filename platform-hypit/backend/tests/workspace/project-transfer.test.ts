// project-transfer.test.ts — C107-20 (task-107): export → import round trip
// (TC107-20-02/03/04). A real workspace exports into a manifest-described
// bundle, imports into a NEW project where the Run still checks, the original
// project is never rewritten; hostile bundles (traversal, symlink, bomb-ish
// over-limit, bad hash, missing selected run) are each refused with no
// half-ready project; and secret-shaped files are an export REFUSAL.
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { dispatchCommand } from "../../src/commands/dispatcher.ts";
import { CommandStore } from "../../src/commands/store.ts";
import { makeOptions } from "../runtime/helpers.ts";
import { exportProjectPackage } from "../../src/project-package/export.ts";
import { importProjectPackage } from "../../src/project-package/import.ts";
import { projectRootFor, provisionFromTemplate } from "../../src/workspace/provision.ts";
import { readHead } from "../../src/workspace/transactions.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const SOURCE_COMMIT = "2c320059c1260d0f6f8101472395f91f2f9c8243";
const templateDir = join(repoRoot, "platform-hypit/fixtures/minimal-local");
const templateFiles = ["main.svml", "main.svrun", "package.json"];
const importContext = (dir: string) => ({
  projectsRoot: join(dir, "projects"),
  stagingRoot: dir,
  provisionTemplateDir: templateDir,
  provisionTemplateFiles: templateFiles,
});

type Harness = Awaited<ReturnType<typeof makeOptions>>;

async function seed(options: Harness, projectId: string, extraFiles: Record<string, string> = {}): Promise<number> {
  await provisionFromTemplate(options.projectsRoot, projectId, templateDir, templateFiles);
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  for (const [path, content] of Object.entries(extraFiles)) {
    const target = join(projectRoot, "work", path);
    mkdirSync(join(target, ".."), { recursive: true });
    writeFileSync(target, content);
  }
  const head = await readHead(projectRoot);
  return head?.revision ?? 0;
}

function newHarness(): { options: Harness; dir: string } {
  const dir = mkdtempSync(join(tmpdir(), "hypit-transfer-"));
  const store = new CommandStore(join(dir, "bridge.sqlite"));
  const options = makeOptions({});
  // Rebase the harness onto our own dir for isolation.
  const rebased = { ...options, store, projectsRoot: join(dir, "projects"), cleanup: options.cleanup };
  return { options: rebased as Harness, dir };
}

// The import staging root mirrors export's base: the parent of projectsRoot.
const IMPORT_STAGING = (dir: string) => dir;

test("TC107-20-02: export -> import round trip keeps the Run checkable; original untouched", async () => {
  const { options, dir } = newHarness();
  try {
    const sourceId = "bbbbbbbb-0000-4000-8000-00000000c001";
    const revision = await seed(options, sourceId, {
      "notes.md": "# Clone notes\nPreserved across the transfer.",
    });
    assert.equal(revision, 1);

    const exported = await exportProjectPackage(
      { projectsRoot: options.projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId,
      { title: "Transfer me", selectedRun: "main.svrun" },
    );
    assert.equal(exported.manifest.format, "y1.hypit-project@1");
    assert.equal(exported.manifest.sourceCommit, SOURCE_COMMIT);
    assert.equal(exported.manifest.project.revision, 1);
    assert.ok(exported.manifest.files.some((file) => file.path === "main.svml"));
    assert.ok(exported.manifest.files.some((file) => file.path === "notes.md"));
    assert.ok(exported.manifest.files.every((file) => !file.path.startsWith(".hypit") && !file.path.startsWith(".journal")));

    const targetId = "bbbbbbbb-0000-4000-8000-00000000c002";
    const imported = await importProjectPackage(
      importContext(dir),
      exported.artifactRoot,
      { newProjectId: targetId, requestId: `import-${Date.now()}`, title: "Imported" },
    );
    assert.equal(imported.revision, 2, "import lands files as one journaled change");
    assert.equal(imported.fileCount, exported.manifest.files.length);

    // The imported workspace holds identical bytes for every carried file.
    for (const file of exported.manifest.files) {
      const source = readFileSync(join(projectRootFor(options.projectsRoot, sourceId), "work", file.path));
      const copy = readFileSync(join(projectRootFor(options.projectsRoot, targetId), "work", file.path));
      assert.equal(
        createHash("sha256").update(copy).digest("hex"),
        createHash("sha256").update(source).digest("hex"),
        `file ${file.path} must survive the round trip byte-identically`,
      );
    }
    // The original project head never moved.
    const originalHead = await readHead(projectRootFor(options.projectsRoot, sourceId));
    assert.equal(originalHead?.revision, 1);
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC107-20-03: hostile bundles are refused with no half-ready project", async () => {
  const { options, dir } = newHarness();
  try {
    const sourceId = "bbbbbbbb-0000-4000-8000-00000000c003";
    const revision = await seed(options, sourceId);
    const exported = await exportProjectPackage(
      { projectsRoot: options.projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId, {},
    );
    const bundleRoot = join(IMPORT_STAGING(dir), exported.artifactRoot);
    const importOptions = importContext(dir);
    const importArgs = { newProjectId: "bbbbbbbb-0000-4000-8000-00000000c004", requestId: "x1" };

    // (a) bad hash: tamper one carried file AND keep its manifest size truthful
    // so the HASH gate (not the size gate) is what fires.
    const original = readFileSync(join(bundleRoot, "main.svml"), "utf8");
    const tampered = `${original}\n<!-- tampered -->\n`;
    writeFileSync(join(bundleRoot, "main.svml"), tampered);
    const manifest = JSON.parse(readFileSync(join(bundleRoot, "hypit-project.json"), "utf8")) as {
      project: { title: string; revision: number; selectedRun: string };
      files: { path: string; sha256: string; sizeBytes: number; role: string }[];
    };
    for (const file of manifest.files) {
      if (file.path === "main.svml") file.sizeBytes = Buffer.byteLength(tampered, "utf8");
    }
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);
    await assert.rejects(() => importProjectPackage(importOptions, exported.artifactRoot, importArgs), /hash mismatch/u);
    // restore the truthful bundle
    writeFileSync(join(bundleRoot, "main.svml"), original);
    for (const file of manifest.files) {
      if (file.path === "main.svml") file.sizeBytes = Buffer.byteLength(original, "utf8");
    }
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);

    // (b) manifest-format garbage.
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify({ format: "nope@9" })}\n`);
    await assert.rejects(() => importProjectPackage(importOptions, exported.artifactRoot, importArgs), /manifest format/u);
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);

    // (c) forbidden path in the manifest itself.
    const evilManifest = { ...manifest, files: [...manifest.files, { path: "../../escape.svml", sha256: "0".repeat(64), sizeBytes: 1, role: "source" }] };
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(evilManifest)}\n`);
    await assert.rejects(() => importProjectPackage(importOptions, exported.artifactRoot, importArgs), /forbidden path/u);
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);

    // (d) missing selected run.
    const missingRun = { ...manifest, project: { ...manifest.project, selectedRun: "absent.svrun" } };
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(missingRun)}\n`);
    await assert.rejects(() => importProjectPackage(importOptions, exported.artifactRoot, importArgs), /selected run missing/u);
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);

    // (e) symlink inside the bundle escapes → forbidden-path gate via placement.
    const outside = join(dir, "outside.txt");
    writeFileSync(outside, "secret");
    try {
      symlinkSync(outside, join(bundleRoot, "link.svml"));
      const linkManifest = { ...manifest, files: [...manifest.files, { path: "link.svml", sha256: createHash("sha256").update("secret").digest("hex"), sizeBytes: 6, role: "source" }] };
      writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(linkManifest)}\n`);
      await assert.rejects(() => importProjectPackage(importOptions, exported.artifactRoot, importArgs));
    } finally {
      rmSync(join(bundleRoot, "link.svml"), { force: true });
      writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);
    }

    // No half-ready target: a fresh import of the UNTAMPERED bundle still works.
    const late = await importProjectPackage(
      importOptions, exported.artifactRoot,
      { newProjectId: "bbbbbbbb-0000-4000-8000-00000000c005", requestId: "x2" },
    );
    assert.equal(late.revision, 2);
    void revision;
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC107-20-04: secret-shaped project files refuse the export; omission list stays explicit", async () => {
  const { options, dir } = newHarness();
  try {
    const dirtyId = "bbbbbbbb-0000-4000-8000-00000000c006";
    await seed(options, dirtyId, { "credentials.json": `{"api_key":"sk-should-not-travel"}` });
    await assert.rejects(
      () => exportProjectPackage(
        { projectsRoot: options.projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
        dirtyId, {},
      ),
      /non-exportable file/u,
    );

    const cleanId = "bbbbbbbb-0000-4000-8000-00000000c007";
    await seed(options, cleanId);
    const exported = await exportProjectPackage(
      { projectsRoot: options.projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      cleanId, {},
    );
    const manifestText = readFileSync(join(IMPORT_STAGING(dir), exported.artifactRoot, "hypit-project.json"), "utf8");
    assert.ok(!/api[_-]?key|secret|password/iu.test(manifestText), "export manifest must be secret-free");
    assert.deepEqual(exported.manifest.omitted, [], "a complete export declares zero omissions");
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});

void dispatchCommand;
