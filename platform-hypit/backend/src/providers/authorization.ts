// authorization.ts — C107-07 (task-107) external execution authorization.
//
// The broker side of the Java bridge (§6.3): before any real provider submit,
// prepare() asks Java for a one-shot permit bound to the stable operationId,
// grant scope and request hash; Java answers with a short-lived permitId and
// the credentialRef the trusted side may resolve. complete/fail/cancel report
// receipts exactly once — polling never re-books. Unanswered prepares stay
// unknown: no refund is invented and no request is silently retried.
export type ExecutionPermit = {
  readonly permitId: string;
  readonly expiresAt: string;
  readonly credentialRef: { readonly store: string; readonly key: string };
};

export type ExecutionReceiptReport = {
  readonly state?: "succeeded" | "failed" | "cancelled" | "unknown";
  readonly receipt?: Record<string, unknown>;
  readonly actualCost?: number;
  readonly detail?: string;
};

export type PrepareRequest = {
  readonly operationId: string;
  readonly projectId?: string;
  readonly jobId?: string;
  readonly buildId?: string;
  readonly needId: string;
  readonly capability: string;
  readonly model: string;
  readonly endpointId: string;
  readonly requestHash: string;
  readonly grantId: string;
};

export class AuthorizationError extends Error {
  constructor(readonly code: string, message: string, readonly status?: number) {
    super(message);
    this.name = "AuthorizationError";
  }
}

export type ExecutionBridge = {
  prepare(request: PrepareRequest): Promise<ExecutionPermit>;
  complete(operationId: string, report: ExecutionReceiptReport): Promise<{ settled: boolean }>;
  fail(operationId: string, report: ExecutionReceiptReport): Promise<{ settled: boolean }>;
  cancel(operationId: string, detail?: string): Promise<{ settled: boolean }>;
};

/** HTTP bridge against Java's /internal/hypit/executions endpoints. */
export function httpExecutionBridge(options: {
  readonly baseUrl: string;
  readonly token: string;
  readonly fetch?: typeof globalThis.fetch;
  readonly timeoutMs?: number;
}): ExecutionBridge {
  const doFetch = options.fetch ?? globalThis.fetch;
  const timeoutMs = options.timeoutMs ?? 15_000;

  async function call<T>(path: string, method: string, body?: Record<string, unknown>): Promise<T> {
    let response: Response;
    try {
      response = await doFetch(`${options.baseUrl}${path}`, {
        method,
        headers: {
          authorization: `Bearer ${options.token}`,
          ...(body === undefined ? {} : { "content-type": "application/json" }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      // Transport failure to the bridge is unknown, never a denial: the caller
      // keeps the operation un-submitted and surfaces the outage (K12.8).
      throw new AuthorizationError("bridge_unreachable",
        `execution bridge call ${path} failed: ${error instanceof Error ? error.message : String(error)}`);
    }
    const text = await response.text();
    let payload: Record<string, unknown> = {};
    try {
      payload = text.length === 0 ? {} : (JSON.parse(text) as Record<string, unknown>);
    } catch {
      // non-JSON body; status below decides
    }
    if (!response.ok) {
      const code = typeof payload.code === "string" ? payload.code : `http_${String(response.status)}`;
      throw new AuthorizationError(code, typeof payload.message === "string" ? payload.message : text.slice(0, 500), response.status);
    }
    return payload as T;
  }

  return {
    async prepare(request) {
      return await call<ExecutionPermit>("/internal/hypit/executions/prepare", "POST", {
        operationId: request.operationId,
        ...(request.projectId === undefined ? {} : { projectId: request.projectId }),
        ...(request.jobId === undefined ? {} : { jobId: request.jobId }),
        ...(request.buildId === undefined ? {} : { buildId: request.buildId }),
        needId: request.needId,
        capability: request.capability,
        model: request.model,
        endpointId: request.endpointId,
        requestHash: request.requestHash,
        grantId: request.grantId,
      });
    },
    async complete(operationId, report) {
      return await call<{ settled: boolean }>("/internal/hypit/executions/complete", "POST", {
        operationId,
        state: report.state ?? "succeeded",
        ...(report.receipt === undefined ? {} : { receipt: report.receipt }),
        ...(report.actualCost === undefined ? {} : { actualCost: report.actualCost }),
        ...(report.detail === undefined ? {} : { detail: report.detail }),
      });
    },
    async fail(operationId, report) {
      return await call<{ settled: boolean }>("/internal/hypit/executions/fail", "POST", {
        operationId,
        ...(report.receipt === undefined ? {} : { receipt: report.receipt }),
        ...(report.detail === undefined ? {} : { detail: report.detail }),
      });
    },
    async cancel(operationId, detail) {
      return await call<{ settled: boolean }>("/internal/hypit/executions/cancel", "POST", {
        operationId,
        ...(detail === undefined ? {} : { detail }),
      });
    },
  };
}

/**
 * The guard provider execution flows through: one permit per operationId,
 * cached until it expires; a permit for a different request hash cannot be
 * reused (state carries the hash it was issued for). Without a live permit the
// trusted side must not perform any real network submit.
 */
export class ExecutionAuthorizer {
  readonly #bridge: ExecutionBridge;
  readonly #permits = new Map<string, { permit: ExecutionPermit; requestHash: string }>();

  constructor(bridge: ExecutionBridge) {
    this.#bridge = bridge;
  }

  async authorize(request: PrepareRequest): Promise<ExecutionPermit> {
    const cached = this.#permits.get(request.operationId);
    if (cached !== undefined && cached.requestHash === request.requestHash && new Date(cached.permit.expiresAt).getTime() > Date.now()) {
      return cached.permit;
    }
    if (cached !== undefined && cached.requestHash !== request.requestHash) {
      throw new AuthorizationError("request_hash_changed",
        `operation ${request.operationId} already permitted for a different request hash`);
    }
    const permit = await this.#bridge.prepare(request);
    this.#permits.set(request.operationId, { permit, requestHash: request.requestHash });
    return permit;
  }

  async settleSucceeded(operationId: string, receipt: Record<string, unknown>, actualCost?: number): Promise<void> {
    await this.#bridge.complete(operationId, { state: "succeeded", receipt, ...(actualCost === undefined ? {} : { actualCost }) });
    this.#permits.delete(operationId);
  }

  async settleFailed(operationId: string, receipt: Record<string, unknown>, detail: string): Promise<void> {
    await this.#bridge.fail(operationId, { receipt, detail });
    this.#permits.delete(operationId);
  }

  async settleCancelled(operationId: string, detail: string): Promise<void> {
    await this.#bridge.cancel(operationId, detail);
    this.#permits.delete(operationId);
  }

  hasPermit(operationId: string): boolean {
    const cached = this.#permits.get(operationId);
    return cached !== undefined && new Date(cached.permit.expiresAt).getTime() > Date.now();
  }
}
