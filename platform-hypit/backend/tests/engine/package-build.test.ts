// package-build.test.ts — C107F-06 (task-107-fix-1) author-package compile in
// the isolated runner slot (D-08).
//
// TC-F06-01 compile parity: the real custom-package fixture compiles through
// the runner slot (ok:true / tool:"tsc") and a broken source reports tsc
// diagnostics verbatim. TC-F06-02 broker has zero author-code tsc: the broker
// tool module no longer spawns tsc at all (static source assertion + the
// no-supervisor behavioral proof below — a silent fallback would compile fine
// where this suite demands an explicit failure). TC-F06-03 runner slot
// unavailable → DispatchError("runner_unavailable"), never a broker fallback.
import { mkdtempSync, readFileSync, rmSync, mkdirSync, cpSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { runPackageTool, type PackageToolContext } from "../../src/tools/packages.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";
import { startRunnerDaemon, type RunnerDaemonFixture } from "./runner-daemon.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");
const fixtureRoot = join(repoRoot, "platform-hypit/fixtures/custom-package");

/** Workspace with the fixture package at work/packages/custom-badge. */
function prepareProject(breakSource: boolean): { projectsRoot: string; projectId: string } {
  const projectsRoot = mkdtempSync(join(tmpdir(), "hypit-pkgbuild-projects-"));
  const projectId = "bbbbbbbb-0000-4000-8000-0000000000b1";
  const packageDir = join(projectsRoot, projectId, "work/packages/custom-badge");
  mkdirSync(packageDir, { recursive: true });
  cpSync(fixtureRoot, packageDir, { recursive: true, filter: (entry) => !entry.includes("node_modules") });
  if (breakSource) {
    writeFileSync(join(packageDir, "src/index.ts"), "export const broken = {{{;\n");
  }
  return { projectsRoot, projectId };
}

test("TC-F06-01: packages.build compiles the fixture through the runner slot and reports diagnostics verbatim", { timeout: 240_000 }, async (t) => {
  const fixture: RunnerDaemonFixture = await startRunnerDaemon("pkgbuild");
  t.after(() => fixture.stop());
  const supervisor = fixture.supervisor;
  const { projectsRoot, projectId } = prepareProject(false);
  t.after(() => rmSync(projectsRoot, { recursive: true, force: true }));
  const ctx: PackageToolContext = { projectsRoot, distributionRoot: generatedRoot, supervisor };

  const built = await runPackageTool(ctx, "packages.build", { projectId, packagePath: "packages/custom-badge" }) as {
    ok: boolean; diagnostics: string[]; tool: string; exitCode: number;
  };
  assert.equal(built.tool, "tsc");
  assert.equal(built.ok, true, `diagnostics: ${built.diagnostics.join("\n")}`);
  assert.equal(built.exitCode, 0);

  const broken = prepareProject(true);
  t.after(() => rmSync(broken.projectsRoot, { recursive: true, force: true }));
  const failed = await runPackageTool(
    { projectsRoot: broken.projectsRoot, distributionRoot: generatedRoot, supervisor },
    "packages.build",
    { projectId: broken.projectId, packagePath: "packages/custom-badge" },
  ) as { ok: boolean; diagnostics: string[]; exitCode: number };
  assert.equal(failed.ok, false);
  assert.notEqual(failed.exitCode, 0);
  assert.ok(failed.diagnostics.some((line) => line.includes("error TS")), `tsc diagnostics verbatim: ${failed.diagnostics.slice(0, 3).join(" | ")}`);
});

test("TC-F06-02: the broker tool module contains no author-code tsc spawn (compile lives in the runner slot)", () => {
  const source = readFileSync(join(repoRoot, "platform-hypit/backend/src/tools/packages.ts"), "utf8");
  // 存在性门禁（join(...,".bin","tsc")+existsSync）合法保留；这里断言的是零进程派发。
  assert.ok(!source.includes("spawnSync"), "tools/packages.ts must not spawn processes itself");
  // The isolated execution site: runner/server.ts compileAuthorPackage.
  const runnerSource = readFileSync(join(repoRoot, "platform-hypit/backend/src/runner/server.ts"), "utf8");
  assert.ok(runnerSource.includes("compileAuthorPackage"), "runner server hosts the package compile");
  assert.ok(runnerSource.includes('spawnSync(tsc'), "tsc argv executes in the runner process");
});

test("TC-F06-03: packages.build without a runner supervisor fails explicitly (no silent broker fallback)", { timeout: 60_000 }, async (t) => {
  const { projectsRoot, projectId } = prepareProject(false);
  t.after(() => rmSync(projectsRoot, { recursive: true, force: true }));
  const ctx: PackageToolContext = { projectsRoot, distributionRoot: generatedRoot };
  await assert.rejects(
    runPackageTool(ctx, "packages.build", { projectId, packagePath: "packages/custom-badge" }),
    (error: unknown) => error instanceof DispatchError && error.code === "runner_unavailable",
  );
});
