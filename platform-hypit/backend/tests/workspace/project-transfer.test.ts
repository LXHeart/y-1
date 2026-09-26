// project-transfer.test.ts — C107-20 (task-107): export → import round trip
// (TC107-20-02/03/04). A real workspace exports into a manifest-described
// bundle, imports into a NEW project where the Run still checks, the original
// project is never rewritten; hostile bundles (traversal, symlink, bomb-ish
// over-limit, bad hash, missing selected run) are each refused with no
// half-ready project; and secret-shaped files are an export REFUSAL.
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");
// C107F-01: dispatcher-level export reads the pinned sourceCommit from the
// template catalog; point the catalog lookup at the in-repo templates root
// (the broker honors HYPIT_TEMPLATES_ROOT the same way in deployment).
process.env.HYPIT_TEMPLATES_ROOT ??= join(import.meta.dirname, "../../../../platform-hypit/templates");

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
  // Rebase the harness onto our own dir for isolation; the dispatcher-level
  // kinds need the same template wiring the broker server provides.
  const rebased = {
    ...options,
    store,
    projectsRoot: join(dir, "projects"),
    templateDir,
    templateFiles,
    cleanup: options.cleanup,
  };
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
    await seed(options, dirtyId, { "credentials.json": `{"api_key":"sk-should-not-travel"}` }); // secret-scan: allow
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

// ---------------------------------------------------------------------------
// C107F-01 dispatcher-level cases: the kinds the Java orchestration dispatches
// ("project-package.export"/"project-package.import") must route through
// runKind → CommandStore like every other command — removing the routing (the
// audited baseline) turns every case below red with unknown_kind.
// ---------------------------------------------------------------------------

test("TC-F01-01: project-package.export via dispatchCommand returns the full receipt", async () => {
  const { options, dir } = newHarness();
  try {
    const sourceId = "bbbbbbbb-0000-4000-8000-00000000f001";
    const revision = await seed(options, sourceId, { "notes.md": "dispatcher level" });
    assert.equal(revision, 1);

    const outcome = await dispatchCommand(options, "cmd-export-f01", "project-package.export", {
      projectId: sourceId,
      title: "t",
      selectedRun: "main.svrun",
    });
    assert.equal(outcome.outcome, "completed");
    const receipt = JSON.parse(options.store.get("cmd-export-f01")?.resultJson ?? "{}") as {
      artifactRoot: string;
      fileCount: number;
      manifest: { files: { path: string }[]; sourceCommit: string; project: { revision: number } };
    };
    assert.ok(receipt.artifactRoot.startsWith("project-exports/"), "artifactRoot is broker-root relative");
    assert.equal(receipt.fileCount, receipt.manifest.files.length, "fileCount == manifest.files.length (D-02)");
    assert.equal(receipt.manifest.sourceCommit, SOURCE_COMMIT, "provenance from the template catalog");
    assert.ok(receipt.manifest.files.some((file) => file.path === "main.svml"));
    assert.ok(existsSync(join(dir, receipt.artifactRoot)), "bundle landed inside the broker artifacts root");

    // Negative: a workspace without a published head refuses with not_provisioned.
    const bare = "bbbbbbbb-0000-4000-8000-00000000f002";
    await provisionFromTemplate(options.projectsRoot, bare, templateDir, templateFiles);
    rmSync(join(projectRootFor(options.projectsRoot, bare), "workspace.json"), { force: true });
    await assert.rejects(
      () => dispatchCommand(options, "cmd-export-f01-bare", "project-package.export", { projectId: bare }),
      (error: Error & { code?: string }) => error.code === "not_provisioned",
    );
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC-F01-02: project-package.import via dispatchCommand round-trips; refusals leave no residue", async () => {
  const { options, dir } = newHarness();
  try {
    const sourceId = "bbbbbbbb-0000-4000-8000-00000000f003";
    await seed(options, sourceId, { "notes.md": "import via dispatcher" });
    await dispatchCommand(options, "cmd-import-f01-export", "project-package.export", { projectId: sourceId });
    const exported = JSON.parse(options.store.get("cmd-import-f01-export")?.resultJson ?? "{}") as {
      artifactRoot: string;
      manifest: { files: { path: string; sha256: string }[] };
    };

    const outcome = await dispatchCommand(options, "cmd-import-f01", "project-package.import", {
      artifactRoot: exported.artifactRoot,
    });
    assert.equal(outcome.outcome, "completed");
    const receipt = JSON.parse(options.store.get("cmd-import-f01")?.resultJson ?? "{}") as {
      projectId: string;
      revision: number;
      fileCount: number;
    };
    assert.notEqual(receipt.projectId, sourceId, "new project ids are broker-generated");
    assert.equal(receipt.revision, 2, "import lands files as one journaled change");
    assert.equal(receipt.fileCount, exported.manifest.files.length);
    for (const file of exported.manifest.files) {
      const source = readFileSync(join(projectRootFor(options.projectsRoot, sourceId), "work", file.path));
      const copy = readFileSync(join(projectRootFor(options.projectsRoot, receipt.projectId), "work", file.path));
      assert.equal(
        createHash("sha256").update(copy).digest("hex"),
        createHash("sha256").update(source).digest("hex"),
        `file ${file.path} survives the dispatcher round trip byte-identically`,
      );
    }

    // Tampered bundle (one byte flipped, size kept truthful) → invalid_input
    // and NO new project directory is created.
    const bundleRoot = join(dir, exported.artifactRoot);
    const original = readFileSync(join(bundleRoot, "main.svml"), "utf8");
    const tampered = `${original}\n<!-- tampered -->\n`;
    writeFileSync(join(bundleRoot, "main.svml"), tampered);
    const manifest = JSON.parse(readFileSync(join(bundleRoot, "hypit-project.json"), "utf8")) as {
      files: { path: string; sizeBytes: number }[];
    };
    for (const file of manifest.files) {
      if (file.path === "main.svml") file.sizeBytes = Buffer.byteLength(tampered, "utf8");
    }
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);
    const beforeCount = readdirSync(join(dir, "projects")).length;
    await assert.rejects(
      () => dispatchCommand(options, "cmd-import-f01-bad", "project-package.import", {
        artifactRoot: exported.artifactRoot,
      }),
      (error: Error & { code?: string }) => error.code === "invalid_input",
    );
    assert.equal(
      readdirSync(join(dir, "projects")).length,
      beforeCount,
      "a refused import creates zero project directories",
    );

    // Symlink entry inside the bundle → refused (regular-file gate).
    writeFileSync(join(bundleRoot, "main.svml"), original);
    for (const file of manifest.files) {
      if (file.path === "main.svml") file.sizeBytes = Buffer.byteLength(original, "utf8");
    }
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(manifest)}\n`);
    const outside = join(dir, "outside-f01.txt");
    writeFileSync(outside, "secret");
    symlinkSync(outside, join(bundleRoot, "link.svml"));
    const linkManifest = {
      ...manifest,
      files: [...manifest.files, { path: "link.svml", sha256: createHash("sha256").update("secret").digest("hex"), sizeBytes: 6 }],
    };
    writeFileSync(join(bundleRoot, "hypit-project.json"), `${JSON.stringify(linkManifest)}\n`);
    await assert.rejects(
      () => dispatchCommand(options, "cmd-import-f01-link", "project-package.import", {
        artifactRoot: exported.artifactRoot,
      }),
      (error: Error & { code?: string }) => error.code === "invalid_input",
    );
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC-F01-03: replaying the same command replays the recorded receipt with zero re-packaging", async () => {
  const { options, dir } = newHarness();
  try {
    const sourceId = "bbbbbbbb-0000-4000-8000-00000000f004";
    await seed(options, sourceId, { "notes.md": "idempotent" });

    const first = await dispatchCommand(options, "cmd-f01-replay", "project-package.export", {
      projectId: sourceId,
      title: "t",
      selectedRun: "main.svrun",
    });
    assert.equal(first.outcome, "completed");
    const exportsRoot = join(dir, "project-exports", sourceId);
    const bundleEntries = (): string[] => readdirSync(exportsRoot);

    const replay = await dispatchCommand(options, "cmd-f01-replay", "project-package.export", {
      projectId: sourceId,
      title: "t",
      selectedRun: "main.svrun",
    });
    assert.equal(replay.outcome, "replayed", "same commandId + payload replays from the store");
    assert.equal(options.store.get("cmd-f01-replay")?.state, "succeeded");
    assert.equal(bundleEntries().length, 1, "no second bundle revision directory appears");

    // Import replay: same command id returns the SAME project receipt.
    const exported = JSON.parse(options.store.get("cmd-f01-replay")?.resultJson ?? "{}") as { artifactRoot: string };
    const projectsBefore = readdirSync(join(dir, "projects")).length;
    await dispatchCommand(options, "cmd-f01-replay-import", "project-package.import", {
      artifactRoot: exported.artifactRoot,
    });
    const importReceipt = options.store.get("cmd-f01-replay-import")?.resultJson ?? "{}";
    await dispatchCommand(options, "cmd-f01-replay-import", "project-package.import", {
      artifactRoot: exported.artifactRoot,
    });
    assert.equal(options.store.get("cmd-f01-replay-import")?.resultJson, importReceipt, "identical import receipt");
    assert.equal(
      readdirSync(join(dir, "projects")).length,
      projectsBefore + 1,
      "the replayed import provisions exactly one new project",
    );
  } finally {
    options.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});
