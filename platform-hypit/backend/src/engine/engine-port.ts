// engine-port.ts — C107-02 (task-107) the stable engine surface the broker exposes.
//
// These DTOs are the ONLY shapes crossing the Java sidecar boundary (B client) and
// the runner IPC. They are plain JSON: functions and live compiler objects never
// cross a process boundary (K10.4). Capabilities not yet implemented by the owning
// card throw EngineNotReadyError — they never return empty fake results.
import type { ArtifactAttachment } from "@hypit/workspace";
import type { BuildDefinition } from "@hypit/protocol";
import type { BuildResultRepositoryLocation } from "@hypit/build-result-kit";

export const ENGINE_PORT_VERSION = "y1.hypit-engine-port@1";

export type EngineDiagnostic = {
  readonly file: string | null;
  readonly line: number | null;
  readonly column: number | null;
  readonly severity: "error" | "warning";
  readonly message: string;
};

export type EngineCheckRequest = {
  readonly workspaceRoot: string;
  readonly entryFile: string;
  /** Logical revision label for logs only; the caller freezes the input bytes. */
  readonly revision?: number | null;
};

export type EngineCheckResult = {
  readonly ok: boolean;
  readonly sourceKind: "author" | "run";
  readonly frontend: string;
  readonly diagnostics: readonly EngineDiagnostic[];
  readonly exports: readonly {
    readonly name: string;
    readonly kind: "logical-output" | "value";
    readonly typeRef: string | null;
  }[];
  readonly modules: readonly string[];
  readonly sourceClosureHash: string;
  readonly targetNames: readonly string[];
};

export type EnginePlanRequest = {
  readonly workspaceRoot: string;
  readonly runFile: string;
  readonly revision?: number | null;
};

/** Implemented by C107-08 (planning.ts); declared here so the port is frozen early. */
export type EnginePlanResult = {
  readonly planId: string;
  readonly planHash: string;
  readonly targets: readonly string[];
  readonly needs: readonly unknown[];
  readonly reuse: readonly unknown[];
  readonly missingCapabilities: readonly string[];
};

export type EngineSubmitRequest = {
  /** Stable native build id allocated by the caller before the first attempt. */
  readonly engineBuildId: string;
  readonly workspaceRoot: string;
  readonly runFile: string;
  readonly repositoryLocation: BuildResultRepositoryLocation;
  readonly title?: string;
};

export type EngineSubmission = {
  readonly engineBuildId: string;
  readonly state: string;
  readonly activity: string;
  readonly outcome?: string;
};

export type EngineBuildStatus = {
  readonly engineBuildId: string;
  readonly found: boolean;
  readonly activity?: string;
  readonly outcome?: string;
  readonly targets?: readonly string[];
  readonly cancellationRequested?: boolean;
};

export class EngineNotReadyError extends Error {
  readonly capability: string;
  constructor(capability: string, owner: string) {
    super(`engine capability "${capability}" is not implemented in this build (planned in ${owner})`);
    this.name = "EngineNotReadyError";
    this.capability = capability;
  }
}

/** One compiled run ready for a trusted-side runtime submission. */
export type CompiledRunRequest = {
  readonly definition: BuildDefinition;
  readonly catalog: import("@hypit/runtime").BuildCatalogDescriptor;
  readonly componentPackages: readonly string[];
  /** Attachments materialized by the runner as slot-local files. */
  readonly attachments: readonly {
    readonly blob: import("@hypit/protocol").BlobRef;
    readonly fileName: string;
  }[];
  readonly sourceClosureHash: string;
  /** Workspace root the runner compiled against (slot input on the broker side). */
  readonly compileWorkspaceRoot: string;
};

export type EnginePort = {
  readonly version: string;
  /** Compile-time check of an Author or Run source inside the isolated runner. */
  check(request: EngineCheckRequest): Promise<EngineCheckResult>;
  /** Compile a Run file to a serializable build request inside the runner. */
  compileRun(request: EnginePlanRequest): Promise<CompiledRunRequest>;
  /** Submit a previously compiled request through the trusted Runtime Host. */
  submit(request: EngineSubmitRequest, compiled: CompiledRunRequest): Promise<EngineSubmission>;
  inspect(engineBuildId: string): Promise<EngineBuildStatus>;
  cancel(engineBuildId: string, reason?: string): Promise<EngineBuildStatus>;
};

/** Convert slot file names back into runtime attachments (trusted side). */
export type AttachmentSource = (fileName: string) => ArtifactAttachment;

export type { BuildDefinition, ArtifactAttachment };
