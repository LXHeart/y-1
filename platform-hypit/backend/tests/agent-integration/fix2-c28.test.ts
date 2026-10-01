// fix2-c28.test.ts — 107-fix-2 C107F2-28：二进制保真的工程包与整包验证（F30/§6.13）。
//
// TC-F2-28-01 E01 含 PNG 魔数/MP4 ftyp/字体字节的包 export→import 逐文件 hash 一致，
//            无 UTF8 替换字符（ef bf bd）损坏；runner check 以 selectedRun 为入口。
// TC-F2-28-02 E02 manifest 合法但 svrun 内容非法 → compile_failed；新工程目录整体移除，
//            无 ready 工程/新 head；可清理 staging 之外零写入。
// TC-F2-28-03 E03 ../、绝对路径、symlink、hardlink、重复 entry 单因子导入各自被拒；
//            projectsRoot 内零新工程。
// TC-F2-28-04 E04 计数 20001/20000 边界、总量与单文件 4GiB 上限按 manifest 先行 413；
//            流式校验（size 不符即拒）；单流缓冲 ≤1MiB。
import { createHash } from "node:crypto";
import { existsSync, linkSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");
process.env.HYPIT_TEMPLATES_ROOT ??= join(import.meta.dirname, "../../../../platform-hypit/templates");

const { exportProjectPackage } = await import("../../src/project-package/export.ts");
const { importProjectPackage } = await import("../../src/project-package/import.ts");
const { STREAM_CHUNK_BYTES } = await import("../../src/project-package/binary-staging.ts");
const { DispatchError } = await import("../../src/commands/dispatcher.ts");
const { projectRootFor, provisionFromTemplate } = await import("../../src/workspace/provision.ts");
const { readHead } = await import("../../src/workspace/transactions.ts");

const repoRoot = join(import.meta.dirname, "../../../..");
const SOURCE_COMMIT = "2c320059c1260d0f6f8101472395f91f2f9c8243";
const templateDir = join(repoRoot, "platform-hypit/fixtures/minimal-local");
const templateFiles = ["main.svml", "main.svrun", "package.json"];

const sha256 = (bytes: Buffer): string => createHash("sha256").update(bytes).digest("hex");

/** PNG 魔数开头（AC 指定 89504e470d0a1a0a）+ 若干非文本字节。 */
const PNG_BYTES = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0x80, 0x00,
  0x01, 0x02, 0x03, 0xfe, 0xfd, 0x7f, 0x80,
]);
/** MP4 ftyp box 开头。 */
const MP4_BYTES = Buffer.from([
  0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d,
  0x00, 0x00, 0x02, 0x00, 0x69, 0x73, 0x6f, 0x6d, 0xff, 0x00, 0x80, 0x7f,
]);
/** 字体（OTF 魔数 OTTO + 非 UTF8 序列）。 */
const FONT_BYTES = Buffer.from([
  0x4f, 0x54, 0x54, 0x4f, 0x00, 0xff, 0xfe, 0x80, 0x7f, 0x00, 0x01, 0x02,
]);

const BINARY_ASSETS: Record<string, Buffer> = {
  "assets/cover.png": PNG_BYTES,
  "assets/clip.mp4": MP4_BYTES,
  "assets/title.otf": FONT_BYTES,
};

function newDir(prefix: string): string {
  return mkdtempSync(join(tmpdir(), `fix2-c28-${prefix}-`));
}

/** 源工程：模板 + 二进制资产直写 work/（导出侧按字节携带）。 */
async function seedSourceProject(projectsRoot: string, projectId: string): Promise<void> {
  await provisionFromTemplate(projectsRoot, projectId, templateDir, templateFiles);
  const work = join(projectRootFor(projectsRoot, projectId), "work");
  for (const [path, bytes] of Object.entries(BINARY_ASSETS)) {
    mkdirSync(join(work, path, ".."), { recursive: true });
    writeFileSync(join(work, path), bytes);
  }
}

function importCtx(projectsRoot: string, dir: string, executor?: (kind: string, payload: unknown) => Promise<unknown>) {
  return {
    projectsRoot,
    stagingRoot: dir,
    provisionTemplateDir: templateDir,
    provisionTemplateFiles: templateFiles,
    engineExecutor: executor,
  };
}

/** 记录 check 调用的假执行器：svrun 含 BROKEN 标记 → check 失败（驱动 E02）。 */
function fakeExecutor() {
  const calls: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  const executor = async (kind: string, payload: unknown) => {
    const record = { kind, payload: payload as Record<string, unknown> };
    calls.push(record);
    const sourceDir = String(record.payload.sourceDir ?? "");
    const entryFile = String(record.payload.entryFile ?? "");
    let broken = false;
    try {
      broken = readFileSync(join(sourceDir, entryFile), "utf8").includes("BROKEN-RUN");
    } catch {
      broken = true;
    }
    return broken
      ? { ok: false, diagnostics: [{ severity: "error", message: `parse error in ${entryFile}` }] }
      : { ok: true, diagnostics: [] };
  };
  return { executor, calls };
}

test("TC-F2-28-01 E01 PNG/MP4/字体包 export→import 逐文件字节一致（无 UTF8 替换损坏）", async () => {
  const dir = newDir("roundtrip");
  const projectsRoot = join(dir, "projects");
  try {
    const sourceId = "cccccccc-0000-4000-8000-000000002801";
    await seedSourceProject(projectsRoot, sourceId);

    const exported = await exportProjectPackage(
      { projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId,
      { title: "Binary fidelity", selectedRun: "main.svrun" },
    );
    // manifest 如实记录二进制条目（sha256=原字节摘要）。
    for (const [path, bytes] of Object.entries(BINARY_ASSETS)) {
      const entry = exported.manifest.files.find((file) => file.path === path);
      assert.ok(entry, `manifest carries ${path}`);
      assert.equal(entry.sha256, sha256(bytes));
      assert.equal(entry.sizeBytes, bytes.byteLength);
      assert.equal(entry.role, "asset");
    }

    const { executor, calls } = fakeExecutor();
    const targetId = "cccccccc-0000-4000-8000-000000002802";
    const imported = await importProjectPackage(
      importCtx(projectsRoot, dir, executor),
      exported.artifactRoot,
      { newProjectId: targetId, requestId: "req-c28-e01" },
    );
    assert.equal(imported.revision, 2);
    assert.equal(imported.fileCount, exported.manifest.files.length);

    // runner parse/check 以 selectedRun 为入口对落地后的工作区执行。
    const check = calls.find((call) => call.kind === "check");
    assert.ok(check, "import runs the runner check");
    assert.equal(check.payload.entryFile, "main.svrun");
    assert.ok(String(check.payload.sourceDir).endsWith(join(targetId, "work")));

    // 逐文件 hash 一致 + 显式无 UTF8 替换字符。
    const targetWork = join(projectRootFor(projectsRoot, targetId), "work");
    const replacement = Buffer.from([0xef, 0xbf, 0xbd]);
    for (const [path, bytes] of Object.entries(BINARY_ASSETS)) {
      const landed = readFileSync(join(targetWork, path));
      assert.ok(landed.equals(bytes), `${path} must survive byte-identically`);
      assert.equal(sha256(landed), sha256(bytes));
      assert.ok(!landed.includes(replacement), `${path} carries no UTF8 replacement chars`);
    }
    // 文本同样逐字节一致。
    const svrun = readFileSync(join(targetWork, "main.svrun"));
    assert.ok(svrun.equals(readFileSync(join(projectRootFor(projectsRoot, sourceId), "work", "main.svrun"))));
    // 落盘二次核对后 head 才发布——revision2 的 manifest 覆盖二进制条目。
    const head = await readHead(projectRootFor(projectsRoot, targetId));
    assert.equal(head?.revision, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC-F2-28-02 E02 svrun 非法：compile_failed 且无 ready 工程/新 head", async () => {
  const dir = newDir("invalid");
  const projectsRoot = join(dir, "projects");
  try {
    const sourceId = "cccccccc-0000-4000-8000-000000002803";
    await seedSourceProject(projectsRoot, sourceId);
    // 直接改写 bundle 内 main.svrun（manifest 同步改哈希——只让 runner check 这道闸失败）。
    const exported = await exportProjectPackage(
      { projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId,
      { title: "Broken run", selectedRun: "main.svrun" },
    );
    const bundleRoot = join(dir, exported.artifactRoot);
    const broken = "BROKEN-RUN: not parseable\n";
    writeFileSync(join(bundleRoot, "main.svrun"), broken);
    const manifestPath = join(bundleRoot, "hypit-project.json");
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as {
      files: Array<{ path: string; sha256: string; sizeBytes: number }>;
    };
    for (const file of manifest.files) {
      if (file.path === "main.svrun") {
        file.sha256 = sha256(Buffer.from(broken));
        file.sizeBytes = Buffer.byteLength(broken);
      }
    }
    writeFileSync(manifestPath, `${JSON.stringify(manifest)}\n`);

    const { executor } = fakeExecutor();
    const targetId = "cccccccc-0000-4000-8000-000000002804";
    await assert.rejects(
      () => importProjectPackage(importCtx(projectsRoot, dir, executor), exported.artifactRoot,
        { newProjectId: targetId, requestId: "req-c28-e02" }),
      (error: unknown) => error instanceof DispatchError && error.code === "compile_failed",
    );
    // 无 ready 工程：新工程目录整体移除（staging 内的 bundle 可清理地保留）。
    assert.equal(existsSync(projectRootFor(projectsRoot, targetId)), false, "no provisioned shell survives");
    assert.ok(existsSync(bundleRoot), "staging bundle remains for controlled retry");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC-F2-28-03 E03 ../、绝对路径、symlink、hardlink、重复 entry 各自被拒；staging 外零写入", async () => {
  const dir = newDir("hostile");
  const projectsRoot = join(dir, "projects");
  try {
    const sourceId = "cccccccc-0000-4000-8000-000000002805";
    await seedSourceProject(projectsRoot, sourceId);
    const exported = await exportProjectPackage(
      { projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId, {},
    );
    const bundleRoot = join(dir, exported.artifactRoot);
    const manifestPath = join(bundleRoot, "hypit-project.json");
    const readManifest = () => JSON.parse(readFileSync(manifestPath, "utf8")) as {
      files: Array<{ path: string; sha256: string; sizeBytes: number; role: string }>;
    };
    const writeManifest = (files: unknown): void => {
      writeFileSync(manifestPath, `${JSON.stringify({ ...JSON.parse(readFileSync(manifestPath, "utf8")), files })}\n`);
    };
    const importOptions = importCtx(projectsRoot, dir);
    const importArgs = { newProjectId: "cccccccc-0000-4000-8000-000000002806", requestId: "req-c28-e03" };
    const refuses = async (needle: RegExp): Promise<void> => {
      await assert.rejects(
        () => importProjectPackage(importOptions, exported.artifactRoot, importArgs),
        (error: unknown) => error instanceof DispatchError && needle.test(error.message),
      );
    };

    const base = readManifest().files;

    // (a) ../ 相对路径逃逸。
    writeManifest([...base, { path: "../escape.svml", sha256: "0".repeat(64), sizeBytes: 1, role: "source" }]);
    await refuses(/forbidden path/u);
    writeManifest(base);

    // (b) 绝对路径。
    writeManifest([...base, { path: "/etc/passwd", sha256: "0".repeat(64), sizeBytes: 1, role: "source" }]);
    await refuses(/forbidden path/u);
    writeManifest(base);

    // (c) symlink entry。
    const outside = join(dir, "outside.txt");
    writeFileSync(outside, "secret");
    symlinkSync(outside, join(bundleRoot, "link.svml"));
    writeManifest([...base, {
      path: "link.svml", sha256: sha256(Buffer.from("secret")), sizeBytes: 6, role: "source",
    }]);
    await refuses(/not a regular file/u);
    rmSync(join(bundleRoot, "link.svml"), { force: true });
    writeManifest(base);

    // (d) hardlink entry（同 inode 两条路径）。
    const hardSource = join(bundleRoot, "hard-a.bin");
    writeFileSync(hardSource, PNG_BYTES);
    linkSync(hardSource, join(bundleRoot, "hard-b.bin"));
    writeManifest([...base,
      { path: "hard-a.bin", sha256: sha256(PNG_BYTES), sizeBytes: PNG_BYTES.byteLength, role: "asset" },
      { path: "hard-b.bin", sha256: sha256(PNG_BYTES), sizeBytes: PNG_BYTES.byteLength, role: "asset" },
    ]);
    await refuses(/hardlink/u);
    rmSync(hardSource, { force: true });
    rmSync(join(bundleRoot, "hard-b.bin"), { force: true });
    writeManifest(base);

    // (e) 重复 entry（manifest 层即拒）。
    const duplicated = JSON.parse(readFileSync(manifestPath, "utf8")) as { files: unknown[] };
    duplicated.files = [...duplicated.files, ...duplicated.files.slice(0, 1)];
    writeFileSync(manifestPath, `${JSON.stringify(duplicated)}\n`);
    await refuses(/duplicate manifest path/u);
    writeManifest(base);

    // staging 外零写入：全程没有一个新工程目录出现。
    const { readdirSync } = await import("node:fs");
    assert.deepEqual(readdirSync(projectsRoot), [sourceId], "no project dir created by refused imports");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("TC-F2-28-04 E04 计数/总量/单文件上限先行 413；流式校验拒 size 不符", async () => {
  const dir = newDir("caps");
  const projectsRoot = join(dir, "projects");
  try {
    const sourceId = "cccccccc-0000-4000-8000-000000002807";
    await seedSourceProject(projectsRoot, sourceId);
    const exported = await exportProjectPackage(
      { projectsRoot, distributionRoot: join(dir, "generated"), sourceCommit: SOURCE_COMMIT },
      sourceId, {},
    );
    const bundleRoot = join(dir, exported.artifactRoot);
    const manifestPath = join(bundleRoot, "hypit-project.json");
    const base = (JSON.parse(readFileSync(manifestPath, "utf8")) as { files: Array<Record<string, unknown>> }).files;
    const writeManifest = (files: unknown): void => {
      writeFileSync(manifestPath, `${JSON.stringify({ ...JSON.parse(readFileSync(manifestPath, "utf8")), files })}\n`);
    };
    const importOptions = importCtx(projectsRoot, dir);
    const importArgs = { newProjectId: "cccccccc-0000-4000-8000-000000002808", requestId: "req-c28-e04" };
    const rejectsCode = async (code: string): Promise<void> => {
      await assert.rejects(
        () => importProjectPackage(importOptions, exported.artifactRoot, importArgs),
        (error: unknown) => error instanceof DispatchError && error.code === code,
      );
    };
    const entry = (path: string, sizeBytes: number): Record<string, unknown> => ({
      path, sha256: "0".repeat(64), sizeBytes, role: "source",
    });

    // 计数 20001 → too_large（413，磁盘未动）。
    const over = [...base];
    for (let index = over.length; index <= 20000; index += 1) over.push(entry(`bulk/f${index}.bin`, 1));
    writeManifest(over);
    await rejectsCode("too_large");

    // 计数恰 20000 → 计数闸放行（失败点变成缺文件，而非 too_large）。
    const exact = [...base];
    for (let index = exact.length; index < 20000; index += 1) exact.push(entry(`bulk/f${index}.bin`, 1));
    writeManifest(exact);
    await assert.rejects(
      () => importProjectPackage(importOptions, exported.artifactRoot, importArgs),
      (error: unknown) => error instanceof DispatchError && /bundle missing file/u.test(error.message),
    );

    // 总量越界 → too_large（单文件恰在 4GiB 内，总量闸先于读取触发）。
    writeManifest([...base, entry("bulk/huge.bin", 4 * 1024 * 1024 * 1024)]);
    await rejectsCode("too_large");

    // 单文件 4GiB+1 → too_large（先于读取）。
    writeManifest([...base, entry("bulk/one.bin", 4 * 1024 * 1024 * 1024 + 1)]);
    await rejectsCode("too_large");

    // 流式校验：manifest 声明 size 与实际不符 → size mismatch（streamHash 而非整读）。
    const realEntry = join(bundleRoot, "sized.bin");
    writeFileSync(realEntry, PNG_BYTES);
    writeManifest([...base, {
      path: "sized.bin", sha256: sha256(PNG_BYTES), sizeBytes: PNG_BYTES.byteLength + 1, role: "asset",
    }]);
    await assert.rejects(
      () => importProjectPackage(importOptions, exported.artifactRoot, importArgs),
      (error: unknown) => error instanceof DispatchError && /size mismatch/u.test(error.message),
    );
    rmSync(realEntry, { force: true });

    // 单流缓冲 ≤1MiB（§6.13：内存缓冲不随包大小增长）。
    assert.ok(STREAM_CHUNK_BYTES <= 1024 * 1024);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
