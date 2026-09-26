// compile-adapter.ts — C107-02 (task-107) author-code compilation adapter.
//
// Runs INSIDE the isolated runner (never in the trusted HTTP host): source
// package discovery evaluates author package code, so it stays behind the runner
// boundary. Follows the native video distribution flow — discover packages,
// create the compiler, open the entry, check/compile — without re-implementing
// any compiler piece (D-03 native retention).
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

import { loadHypit } from "./hypit-bootstrap.ts";
import type {
  EngineCheckRequest,
  EngineCheckResult,
  EngineDiagnostic,
  EnginePlanRequest,
} from "./engine-port.ts";

export type CompileAdapterOptions = {
  readonly distributionRoot: string;
  /** Where materialized attachment bytes are written (slot output dir). */
  readonly attachmentOutputDir: string;
};

type CompilerBundle = Awaited<ReturnType<typeof loadHypit>>;

async function openCompilerForSource(
  engine: CompilerBundle,
  source: string,
  workspaceRoot: string,
): Promise<{
  readonly compiler: ReturnType<CompilerBundle["videoCliDistribution"]["createCompiler"]>;
  readonly workspace: Awaited<ReturnType<ReturnType<CompilerBundle["videoCliDistribution"]["createCompiler"]>["openFile"]>>;
  readonly runFrontends: readonly import("@hypit/run").RunFrontend[];
  readonly packageSet: readonly import("@hypit/package-loader-node").LoadedPackage[];
}> {
  const distribution = engine.videoCliDistribution;
  const packageRoot = workspaceRoot;
  const packageSet = await engine.loadDiscoveredSourcePackages(distribution, {
    source,
    workspaceRoot,
    packageRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  });
  const packageContributions = packageSet.map((item) => item.contribution);
  const runFrontends = engine.collectRunFrontends(packageContributions);
  const compiler = distribution.createCompiler({
    workspaceRoot,
    packageRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
    packageContributions,
  });
  const workspace = await compiler.openFile(source);
  return { compiler, workspace, runFrontends, packageSet };
}

function diagnosticFromError(error: unknown, fallbackFile: string): EngineDiagnostic {
  const message = error instanceof Error ? error.message : String(error);
  // Structured compile errors may carry location; anything absent stays null —
  // we never fabricate line numbers (K03).
  const located = error as { readonly file?: unknown; readonly line?: unknown; readonly column?: unknown } | null;
  const file = typeof located?.file === "string" ? located.file : fallbackFile;
  const line = typeof located?.line === "number" && Number.isSafeInteger(located.line) ? located.line : null;
  const column = typeof located?.column === "number" && Number.isSafeInteger(located.column) ? located.column : null;
  return { file, line, column, severity: "error", message };
}

function closureHash(value: unknown, blobIds: readonly string[]): string {
  const canonical = JSON.stringify(value);
  const hash = createHash("sha256");
  hash.update(canonical);
  for (const id of [...blobIds].sort()) hash.update(`\0${id}`);
  return hash.digest("hex");
}

/** Native `hypit check` semantics for one entry file. Never starts generation. */
export async function runCheck(
  options: CompileAdapterOptions,
  request: EngineCheckRequest,
): Promise<EngineCheckResult> {
  const engine = await loadHypit(options.distributionRoot);
  const workspaceRoot = resolve(request.workspaceRoot);
  const entry = resolve(workspaceRoot, request.entryFile);
  const opened = await openCompilerForSource(engine, entry, workspaceRoot);
  const header = engine.parseSourceHeader(opened.workspace.entry.name, opened.workspace.entry.text);
  const runMode = opened.runFrontends.some((frontend) => frontend.id === header.using);
  const authorMode = opened.compiler.supportsFrontend(header.using);
  if (runMode === authorMode) {
    throw new Error(
      runMode
        ? `Frontend ${header.using} is ambiguously registered as Author and Run`
        : `No trusted Author or Run compiler accepts Frontend ${header.using}`,
    );
  }
  if (runMode) {
    try {
      const loaded = await engine.checkRunFile({
        workspace: opened.workspace,
        authorCompiler: opened.compiler,
        frontends: opened.runFrontends,
        packageContributions: opened.packageSet.map((item) => item.contribution),
      });
      return {
        ok: true,
        sourceKind: "run",
        frontend: header.using,
        diagnostics: [],
        exports: [],
        modules: [],
        sourceClosureHash: closureHash(loaded.document.targets.map((item) => item.output), []),
        targetNames: loaded.document.targets.map((item) => item.output),
      };
    } catch (error) {
      return {
        ok: false,
        sourceKind: "run",
        frontend: header.using,
        diagnostics: [diagnosticFromError(error, request.entryFile)],
        exports: [],
        modules: [],
        sourceClosureHash: "",
        targetNames: [],
      };
    }
  }
  try {
    const result = await opened.compiler.compileSource(opened.workspace.entry, opened.workspace);
    const authorFacing = result.exports.filter((item) =>
      !item.name.includes(".__") && !/\.binding-\d+$/u.test(item.name) && !item.name.endsWith(".bindings"));
    return {
      ok: true,
      sourceKind: "author",
      frontend: header.using,
      diagnostics: [],
      exports: authorFacing.map((item) => ({
        name: item.name,
        kind: item.ref.kind === "logical-output" ? "logical-output" as const : "value" as const,
        typeRef: item.type === undefined ? null : String(item.type),
      })),
      modules: result.program.closure.modules.map((item) => `${item.manifest.name}@${item.manifest.version}`),
      sourceClosureHash: closureHash(
        result.program.closure.modules.map((item) => `${item.manifest.name}@${item.manifest.version}`),
        result.attachments.map((item) => JSON.stringify(item.artifact)),
      ),
      targetNames: [],
    };
  } catch (error) {
    return {
      ok: false,
      sourceKind: "author",
      frontend: header.using,
      diagnostics: [diagnosticFromError(error, request.entryFile)],
      exports: [],
      modules: [],
      sourceClosureHash: "",
      targetNames: [],
    };
  }
}

/** Native Run compilation producing a fully serializable build request. */
export async function runCompile(
  options: CompileAdapterOptions,
  request: EnginePlanRequest,
): Promise<import("./engine-port.ts").CompiledRunRequest> {
  const engine = await loadHypit(options.distributionRoot);
  const workspaceRoot = resolve(request.workspaceRoot);
  const runPath = resolve(workspaceRoot, request.runFile);
  const opened = await openCompilerForSource(engine, runPath, workspaceRoot);
  const header = engine.parseSourceHeader(opened.workspace.entry.name, opened.workspace.entry.text);
  const runMode = opened.runFrontends.some((frontend) => frontend.id === header.using);
  if (!runMode) {
    throw new Error(`compile requires a self-described Run Source (got Frontend ${header.using})`);
  }
  // The Result repository location is decided by the trusted caller; during
  // compilation we only need a reader that resolves historical candidates.
  const loaded = await engine.loadRunFile({
    workspace: opened.workspace,
    authorCompiler: opened.compiler,
    frontends: opened.runFrontends,
    packageContributions: opened.packageSet.map((item) => item.contribution),
    results: emptyResultReader(),
  });
  const planned = await loaded.compiler.planCompilation(loaded);
  const catalog = engine.createCatalogDescriptor({
    source: loaded.authorSource,
    compilation: planned.compilation.author,
    run: { path: loaded.path },
  });
  await mkdir(options.attachmentOutputDir, { recursive: true });
  const attachments: { blob: import("@hypit/protocol").BlobRef; fileName: string }[] = [];
  for (const [index, attachment] of planned.compilation.attachments.entries()) {
    const artifact = JSON.stringify(attachment.artifact);
    const fileName = `attachment-${String(index).padStart(4, "0")}-${createHash("sha256").update(artifact).digest("hex").slice(0, 16)}.bin`;
    const target = resolve(options.attachmentOutputDir, fileName);
    await mkdir(dirname(target), { recursive: true });
    const chunks: Buffer[] = [];
    const stream = await Promise.resolve(attachment.open());
    for await (const chunk of stream) chunks.push(Buffer.from(chunk));
    await writeFile(target, Buffer.concat(chunks));
    attachments.push({ blob: attachment.artifact, fileName });
  }
  return {
    definition: planned.definition,
    catalog,
    componentPackages: opened.packageSet
      .filter((item) => (item.contribution.components?.length ?? 0) > 0)
      .map((item) => item.specifier),
    attachments,
    sourceClosureHash: closureHash(planned.definition, attachments.map((item) => JSON.stringify(item.blob))),
    compileWorkspaceRoot: workspaceRoot,
  };
}

/** A Results reader with no history: nothing is reused, nothing is missing. */
function emptyResultReader(): import("@hypit/build-result").BuildResultRepository {
  const nothing = async () => undefined;
  return {
    create: async () => { throw new Error("runner compilation must not write Results"); },
    openWriter: nothing,
    removeIncomplete: async () => {},
    read: nothing,
    updatePresentation: async () => { throw new Error("runner compilation must not edit Results"); },
    browse: async () => ({ results: [] }),
    describeOutput: nothing,
    resolve: nothing,
    describeFile: async (_build, file) => file,
    openFile: nothing,
  };
}
