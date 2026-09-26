// planning.ts — C107-08 (task-107) Run planning: needs, providers, pricing, reuse.
//
// Two halves, one module (no author code crosses a process boundary, K10.4):
//
//  - planRunInRunner runs INSIDE the isolated runner (source package discovery
//    and deterministic producer evaluation execute author/package code). It
//    compiles the Run file against the slot workspace's real Results history
//    (native reuse), evaluates every planned request and emits the complete
//    serializable RuntimeHostProviderQuery set plus the request views.
//
//  - assemblePlanDocument / assemblePricingDocument run on the trusted broker
//    side: pure Host reads (providers/pricing/preflight touch no author code)
//    joined onto the runner output, ending in a content-addressed plan
//    document. planHash covers canonical content only — never wall-clock or
//    presentation text (card step 6); planId is derived from planHash so the
//    same content yields the same immutable plan record.
import { createHash } from "node:crypto";
import { resolve } from "node:path";

import { loadHypit } from "./hypit-bootstrap.ts";
import type { BuildDefinition } from "@hypit/protocol";

export type RunnerPlanOptions = {
  readonly distributionRoot: string;
};

export type RunnerPlanInput = {
  readonly workspaceRoot: string;
  readonly runFile: string;
};

export type RunnerProviderQuery = {
  readonly request: string;
  readonly capability: { readonly module: { readonly name: string; readonly version: string }; readonly name: string };
  readonly returns: unknown;
  readonly constraints: unknown;
  readonly pendingInputs?: readonly { readonly input: string; readonly role?: string }[];
};

export type RunnerPlanNeed = {
  readonly request: string;
  readonly step: string;
  readonly port: string;
  readonly capability: string;
  readonly summary?: {
    readonly fields: Readonly<Record<string, string | number | boolean>>;
    readonly references: Readonly<Record<string, number>>;
  };
  readonly pending: readonly {
    readonly input: string;
    readonly record: string;
    readonly sourceStep?: string;
    readonly kind?: "image" | "video" | "audio" | "other";
  }[];
  readonly issue?: string;
};

export type RunnerPlanOutput = {
  readonly runFile: string;
  readonly targets: readonly string[];
  readonly steps: number;
  readonly definition: BuildDefinition;
  readonly providerQueries: readonly RunnerProviderQuery[];
  readonly plannedRequests: readonly { readonly request: string; readonly step: string; readonly port: string; readonly capability: string }[];
  readonly needs: readonly RunnerPlanNeed[];
  /** Outputs satisfied from the project's Results history (native reuse). */
  readonly choices: readonly { readonly output: string; readonly candidate: string }[];
  readonly sourceClosureHash: string;
};

/** Capability name in the stable `module@version#name` form. */
function capabilityName(capability: { readonly module: { readonly name: string; readonly version: string }; readonly name: string }): string {
  return `${capability.module.name}@${capability.module.version}#${capability.name}`;
}

/**
 * Runner-side planning. Mirrors the native `hypit plan` flow: open the author
 * compiler, load the Run file against the workspace's REAL Results repository
 * (satisfied outputs become native reuse), evaluate deterministic producers
 * and emit every planned request as a Host query.
 */
export async function planRunInRunner(options: RunnerPlanOptions, request: RunnerPlanInput): Promise<RunnerPlanOutput> {
  const engine = await loadHypit(options.distributionRoot);
  // The planning toolkit ships in the cli/runtime packages; the resolution hooks
  // are live after loadHypit, so these dynamic imports resolve through G (K10.4:
  // author package code evaluated only inside the runner process).
  const cli = (await import("@hypit/cli")) as typeof import("@hypit/cli");
  const runtime = (await import("@hypit/runtime")) as typeof import("@hypit/runtime");
  const workspaceRoot = resolve(request.workspaceRoot);
  const runPath = resolve(workspaceRoot, request.runFile);
  const distribution = engine.videoCliDistribution;
  const packageSet = await engine.loadDiscoveredSourcePackages(distribution, {
    source: runPath,
    workspaceRoot,
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  });
  const packageContributions = packageSet.map((item) => item.contribution);
  const runFrontends = engine.collectRunFrontends(packageContributions);
  const compiler = distribution.createCompiler({
    workspaceRoot,
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
    packageContributions,
  });
  const workspace = await compiler.openFile(runPath);
  const header = engine.parseSourceHeader(workspace.entry.name, workspace.entry.text);
  if (!runFrontends.some((frontend) => frontend.id === header.using)) {
    throw new Error(`plan requires a self-described Run Source (got Frontend ${header.using})`);
  }
  const opened = await distribution.openProjectResults(workspaceRoot, {
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  });
  try {
    const loaded = await engine.loadRunFile({
      workspace,
      authorCompiler: compiler,
      frontends: runFrontends,
      packageContributions,
      results: opened.repository,
    });
    const planned = loaded.compiler.planCompilation(loaded);
    const evaluated = await engine.evaluatePlanNeeds(planned.definition, packageContributions);
    const outputNames = new Map(planned.compilation.author.exports
      .filter((item) => item.ref.kind === "logical-output")
      .map((item) => [item.ref.kind === "logical-output" ? item.ref.id : "", item.name]));
    const choices = planned.selections.flatMap((selection) => {
      const output = outputNames.get(selection.output);
      const candidate = loaded.run.satisfactionNames[selection.output];
      return output === undefined || candidate === undefined ? [] : [{ output, candidate }];
    });
    const needs = (cli.describePlanNeeds(evaluated.state, evaluated, []) as readonly {
      request: string; step: string; port: string; capability: string;
      summary?: RunnerPlanNeed["summary"];
      pending: RunnerPlanNeed["pending"];
      issue?: string;
    }[]).map((need) => ({
      request: need.request,
      step: need.step,
      port: need.port,
      capability: need.capability,
      ...(need.summary === undefined ? {} : { summary: need.summary }),
      pending: need.pending,
      ...(need.issue === undefined ? {} : { issue: need.issue }),
    }));
    // plannedNeeds(state) is a pure state read; the query set mirrors the
    // native plannedProviderQueries exactly (request/capability/returns/
    // constraints/pendingInputs) so the trusted side can ask the Host.
    const plannedNeedsList = runtime.plannedNeeds(evaluated.state) as readonly {
      need: string; step: string; port: string;
      capability: RunnerProviderQuery["capability"];
      returns: unknown;
    }[];
    const evaluatedNeeds = evaluated.needs as ReadonlyMap<string, {
      constraints?: unknown;
      pendingInputs: readonly { input: string; role?: string }[];
    }>;
    const plannedRequests = plannedNeedsList.map((item) => ({
      request: item.need,
      step: item.step,
      port: item.port,
      capability: capabilityName(item.capability),
    }));
    const providerQueries: RunnerProviderQuery[] = plannedNeedsList.flatMap((item) => {
      const plannedRequest = evaluatedNeeds.get(item.need);
      const constraints = plannedRequest?.constraints;
      if (plannedRequest === undefined || constraints === undefined) return [];
      return [{
        request: item.need,
        capability: item.capability,
        returns: item.returns,
        constraints,
        ...(plannedRequest.pendingInputs.length === 0
          ? {}
          : {
            pendingInputs: plannedRequest.pendingInputs.map((input) => ({
              input: input.input,
              ...(input.role === undefined ? {} : { role: input.role }),
            })),
          }),
      }];
    });
    return {
      runFile: request.runFile,
      targets: loaded.run.document.targets.map((item) => item.output),
      steps: planned.definition.plan.steps.length,
      definition: planned.definition,
      providerQueries,
      plannedRequests,
      needs,
      choices,
      sourceClosureHash: closureHash(planned.definition, planned.compilation.attachments.map((item) => JSON.stringify(item.artifact))),
    };
  } finally {
    await opened.close();
  }
}

/** Provider view for one planned request (trusted-side Host read only). */
export type PlanProviderRow = {
  readonly request: string;
  readonly capability: string;
  readonly status: "resolved" | "unresolved" | "unsupported" | "ambiguous";
  readonly endpoint?: string;
  readonly use?: string;
  readonly pricing?: { readonly kind: "page"; readonly url: string } | { readonly kind: "local" };
  readonly endpoints?: readonly string[];
  readonly rejections?: readonly { readonly endpoint: string; readonly message: string }[];
  readonly binding?: string;
};

/** Pricing row: a missing numeric quote stays null — never rewritten to 0 (K12.7). */
export type PlanPricingRow = PlanProviderRow & {
  readonly estimatedCost: number | null;
  readonly currency: string | null;
  readonly pricingDocuments?: readonly {
    readonly source: string;
    readonly data: unknown;
    readonly summary?: string;
  }[];
  readonly pricingError?: string;
};

export type PlanDocument = {
  readonly planId: string;
  readonly planHash: string;
  readonly ok: boolean;
  readonly targets: readonly string[];
  readonly steps: number;
  readonly needs: readonly RunnerPlanNeed[];
  readonly providers: readonly PlanProviderRow[];
  readonly choices: readonly { readonly output: string; readonly candidate: string }[];
  readonly missingCapabilities: readonly string[];
  readonly unresolvedRequests: readonly string[];
  readonly unsupportedRequests: readonly string[];
  readonly reuse: readonly { readonly output: string; readonly candidate: string }[];
  readonly preflight: {
    readonly ok: boolean;
    readonly capabilities: readonly string[];
    readonly diagnostics: readonly unknown[];
  } | null;
  readonly sourceClosureHash: string;
};

/**
 * Trusted-side assembly: resolve every planned request against the Host's
 * selected Endpoints (Profile reads only, no credential, no contact), run the
 * bounded preflight and seal the content-addressed plan.
 */
export async function assemblePlanDocument(
  host: {
    providers(requests: readonly RunnerProviderQuery[]): Promise<readonly {
      request: string;
      capability: { module: { name: string; version: string }; name: string };
      status: PlanProviderRow["status"];
      endpoint?: string;
      use?: string;
      pricing?: PlanProviderRow["pricing"];
      endpoints?: readonly string[];
      rejections?: readonly { readonly endpoint: string; readonly message: string }[];
      binding?: string;
    }[]>;
    preflight(options: { readonly endpoints: readonly string[] }): Promise<{
      readonly diagnostics: readonly { readonly severity: string }[];
    }>;
  },
  runner: RunnerPlanOutput,
): Promise<PlanDocument> {
  const described = await host.providers(runner.providerQueries as never);
  const providers: PlanProviderRow[] = runner.plannedRequests.map((planned) => {
    const found = described.find((item) => item.request === planned.request);
    if (found === undefined) {
      return { request: planned.request, capability: planned.capability, status: "unresolved" as const };
    }
    return {
      request: found.request,
      capability: capabilityName(found.capability),
      status: found.status,
      ...(found.endpoint === undefined ? {} : { endpoint: found.endpoint }),
      ...(found.use === undefined ? {} : { use: found.use }),
      ...(found.pricing === undefined ? {} : { pricing: found.pricing }),
      ...(found.endpoints === undefined ? {} : { endpoints: found.endpoints }),
      ...(found.rejections === undefined ? {} : { rejections: found.rejections }),
      ...(found.binding === undefined ? {} : { binding: found.binding }),
    };
  });
  const endpoints = [...new Set(providers.flatMap((item) =>
    item.status === "resolved" && item.endpoint !== undefined ? [item.endpoint] : []))];
  const preflightBase = endpoints.length === 0
    ? { diagnostics: [] as readonly { readonly severity: string }[] }
    : await host.preflight({ endpoints });
  const preflightOk = !preflightBase.diagnostics.some((item) => item.severity === "error");
  const byRequest = new Map(providers.map((item) => [item.request, item]));
  const requestIssues = runner.needs.filter((need) => need.issue !== undefined);
  const unresolved = providers
    .filter((item) => item.status === "unresolved" || item.status === "ambiguous")
    .map((item) => item.request);
  const unsupported = providers.filter((item) => item.status === "unsupported").map((item) => item.request);
  const ok = preflightOk && unresolved.length === 0 && unsupported.length === 0
    && requestIssues.length === 0;
  const missingCapabilities = [...new Set([
    ...unresolved
      .map((request) => byRequest.get(request)?.capability)
      .filter((item): item is string => item !== undefined),
    ...requestIssues.map((issue) => issue.capability),
  ])].sort();
  const planHash = sha256(normalizeVolatile(canonicalJson({
    definition: runner.definition,
    providers: providers.map((item) => ({
      request: item.request,
      capability: item.capability,
      status: item.status,
      ...(item.endpoint === undefined ? {} : { endpoint: item.endpoint }),
      ...(item.pricing === undefined ? {} : { pricing: item.pricing }),
      ...(item.rejections === undefined ? {} : { rejections: item.rejections }),
    })),
    choices: runner.choices,
    targets: runner.targets,
    sourceClosureHash: runner.sourceClosureHash,
  })));
  return {
    targets: runner.targets,
    steps: runner.steps,
    needs: runner.needs,
    providers,
    choices: runner.choices,
    missingCapabilities,
    unresolvedRequests: unresolved,
    unsupportedRequests: unsupported,
    reuse: runner.choices,
    ...(runner.providerQueries.length === 0
      ? { preflight: null }
      : {
        preflight: {
          ok: preflightOk,
          capabilities: [...new Set(runner.needs.map((need) => need.capability))].sort(),
          diagnostics: preflightBase.diagnostics,
        },
      }),
    sourceClosureHash: runner.sourceClosureHash,
    ok,
    planHash,
    planId: planHashToUuid(planHash),
  };
}

/** Pricing read for the plan's exact requests; a read failure keeps the row with nulls. */
export async function assemblePricingDocument(
  host: {
    pricing(requests: readonly RunnerProviderQuery[]): Promise<readonly (Omit<PlanProviderRow, "capability"> & {
      readonly capability: { module: { name: string; version: string }; name: string };
      readonly pricingDocuments?: readonly { readonly source: string; readonly data: unknown; readonly summary?: string }[];
      readonly pricingError?: string;
    })[]>;
  },
  runner: RunnerPlanOutput,
): Promise<{
  readonly pricingHash: string;
  readonly rows: readonly PlanPricingRow[];
  readonly knownCosts: number;
  readonly unknownRequests: readonly string[];
}> {
  const described = await host.pricing(runner.providerQueries as never);
  const rows: PlanPricingRow[] = runner.plannedRequests.map((planned) => {
    const found = described.find((item) => item.request === planned.request);
    if (found === undefined) {
      return {
        request: planned.request,
        capability: planned.capability,
        status: "unresolved" as const,
        estimatedCost: null,
        currency: null,
      };
    }
    return {
      request: found.request,
      capability: capabilityName(found.capability),
      status: found.status,
      ...(found.endpoint === undefined ? {} : { endpoint: found.endpoint }),
      ...(found.use === undefined ? {} : { use: found.use }),
      ...(found.pricing === undefined ? {} : { pricing: found.pricing }),
      ...(found.endpoints === undefined ? {} : { endpoints: found.endpoints }),
      ...(found.rejections === undefined ? {} : { rejections: found.rejections }),
      ...(found.binding === undefined ? {} : { binding: found.binding }),
      // A numeric quote is surfaced only when the Provider's own material
      // carries one; anything else stays null (价格未知 null，不写 0).
      estimatedCost: numericQuote(found.pricingDocuments?.map((item) => item.data) ?? []),
      currency: stringQuote(found.pricingDocuments?.map((item) => item.data) ?? []),
      ...(found.pricingDocuments === undefined ? {} : { pricingDocuments: found.pricingDocuments }),
      ...(found.pricingError === undefined ? {} : { pricingError: found.pricingError }),
    };
  });
  const pricingHash = sha256(canonicalJson(rows.map((row) => ({
    request: row.request,
    capability: row.capability,
    status: row.status,
    ...(row.endpoint === undefined ? {} : { endpoint: row.endpoint }),
    estimatedCost: row.estimatedCost,
    currency: row.currency,
    ...(row.pricingError === undefined ? {} : { pricingError: row.pricingError }),
  }))));
  return {
    pricingHash,
    rows,
    knownCosts: rows.filter((row) => row.estimatedCost !== null).length,
    unknownRequests: rows
      .filter((row) => row.estimatedCost === null && row.status !== "resolved")
      .map((row) => row.request),
  };
}

/** First numeric cost field in Provider pricing material, or null. */
function numericQuote(documents: readonly unknown[]): number | null {
  for (const document of documents) {
    const record = document as { readonly estimatedCost?: unknown; readonly cost?: unknown; readonly amount?: unknown };
    for (const key of ["estimatedCost", "cost", "amount"] as const) {
      const value = record[key];
      if (typeof value === "number" && Number.isFinite(value)) return value;
    }
  }
  return null;
}

function stringQuote(documents: readonly unknown[]): string | null {
  for (const document of documents) {
    const record = document as { readonly currency?: unknown };
    if (typeof record.currency === "string") return record.currency;
  }
  return null;
}

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

/**
 * Volatile handles are not plan content: every compile materializes workspace
 * attachments under a fresh res_<uuid>, so the raw definition would never
 * hash equal for identical plans. Size + mediaType stay in the hash (they are
 * content); byte identity is enforced by the runtime at Build time, not here.
 */
function normalizeVolatile(value: string): string {
  return value.replace(/res_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gu, "res_<handle>");
}

/** Deterministic planId: the planHash rendered as a UUID-shaped id. */
function planHashToUuid(planHash: string): string {
  const hex = planHash.slice(0, 32);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

/** Stable JSON: sorted keys, no whitespace — same content, same hash, always. */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record).filter((key) => record[key] !== undefined).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`).join(",")}}`;
}

function closureHash(definition: unknown, attachments: readonly string[]): string {
  const hash = createHash("sha256");
  hash.update(normalizeVolatile(canonicalJson(definition)));
  for (const id of [...attachments].map(normalizeVolatile).sort()) hash.update(`\0${id}`);
  return hash.digest("hex");
}
