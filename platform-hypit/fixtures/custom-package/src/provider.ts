// provider.ts — C107-17 fixture Provider facet: a custom image provider.
//
// Same defineEndpointPackage contract as the official provider-package
// example, but scoped to the fixture's own wire protocol: one capability
// backed by an async task service (POST /tasks, poll, signed URL collect).
// The BROKER keeps control: baseUrl/apiKey/publicAssetUrl/fetch are injected
// trusted-only options, and async starts are gated by the execution
// authorization decorator (TC107-17-04: no permit, no HTTP call).
import { canonicalize, defineEndpointPackage, wakeAfter } from "@hypit/hypit/endpoint-kit";
import type { AsyncEndpoint, CredentialRef, EndpointRequest } from "@hypit/hypit/endpoint-kit";
import { compileWireRequest, generationTypes, sealGeneratedImageSet, selectWireModelForRequest } from "@hypit/hypit/generation";
import type { GenerationRequest, GenerationWireMapping } from "@hypit/hypit/generation";

export const providerModule = { name: "@clone/custom-badge", version: "1" } as const;
export const capability = { module: { name: "@hypit/nano-banana", version: "1" }, name: "banana-pro" } as const;

// The fixture service implements this subset only: prompt in, one image out.
const mapping: GenerationWireMapping = {
  capability,
  result: "image",
  routes: [{ model: "banana-pro" }],
  fields: {
    prompt: { as: "value", field: "prompt" },
    aspectRatio: { as: "value", field: "ratio" },
  },
};

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("Expected service object");
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) throw new Error("Expected nonempty service text");
  return value;
}

function address(value: string): string {
  const url = new URL(value);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname))) {
    throw new Error("Service URLs require HTTPS or loopback HTTP");
  }
  return url.href;
}

function support(request: EndpointRequest) {
  const ports = (request.constraints as unknown as GenerationRequest).ports;
  const unsupported = !["1:1", "9:16", "16:9"].includes(String(ports.aspectRatio?.[0]))
    || Object.keys(ports).some((port) => !(port in mapping.fields))
    || request.pendingInputs?.some((slot) => !(slot.input in mapping.fields));
  return unsupported
    ? { status: "unsupported" as const, reason: "This fixture offers aspect ratio 1:1, 9:16 or 16:9 with prompt only" }
    : { status: "supported" as const };
}

export function createBadgeProvider(options: {
  instance: string;
  pool: string;
  baseUrl: string;
  apiKey: CredentialRef;
  concurrency?: number;
  pollIntervalMs?: number;
  fetch?: typeof globalThis.fetch;
}) {
  const base = address(options.baseUrl).replace(/\/$/u, "");
  const fetcher = options.fetch ?? globalThis.fetch;
  const interval = options.pollIntervalMs ?? 50;
  const key = (credentials: Readonly<Record<string, { secret: string }>>) => text(credentials.apiKey?.secret);
  async function json(path: string, secret: string, init: RequestInit = {}) {
    const response = await fetcher(`${base}${path}`, {
      ...init,
      headers: { ...init.headers, authorization: `Bearer ${secret}` },
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw Object.assign(new Error(`Badge service ${init.method ?? "GET"} ${path} returned HTTP ${response.status}`), { code: "BADGE_SERVICE_FAILED" });
    return object(await response.json());
  }
  const endpoint: AsyncEndpoint = {
    async start(context) {
      const supported = support(context.need);
      if (supported.status === "unsupported") throw new Error(supported.reason);
      const secret = key(context.credentials);
      const authored = context.need.constraints as unknown as GenerationRequest;
      const model = selectWireModelForRequest(mapping, authored);
      await context.reportProgress?.({ phase: `Preparing badge request: ${model}` });
      const request = await compileWireRequest(mapping, authored, async () => {
        throw new Error("Reference transport is not part of this fixture");
      });
      const task = await json("/tasks", secret, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(request),
      });
      const id = text(task.id);
      const handle = { id };
      const receipt = { id };
      await context.checkpoint?.({ handle, receipt });
      return { ...wakeAfter(handle, interval), receipt };
    },
    async poll(context) {
      const id = text(object(context.handle).id);
      const task = await json(`/tasks/${encodeURIComponent(id)}`, key(context.credentials));
      if (task.state === "queued" || task.state === "running") {
        return wakeAfter({ id }, interval, Date.now(), { phase: String(task.state) });
      }
      if (task.state === "failed") {
        return {
          status: "failed",
          receipt: { id },
          failure: { code: "BADGE_SERVICE_FAILED", message: `Badge service task ${id} failed` },
        };
      }
      if (task.state !== "succeeded") throw new Error("Badge service returned an unknown task state");
      return { status: "ready", handle: { id, url: address(text(task.url)) } };
    },
    async collect(context) {
      const url = address(text(object(context.handle).url));
      await context.reportProgress?.({ phase: "Receiving generated image" });
      const response = await fetcher(url, { signal: AbortSignal.timeout(10_000) });
      if (!response.ok) throw new Error(`Badge image download returned HTTP ${response.status}`);
      const mediaType = response.headers.get("content-type")?.split(";")[0]?.trim();
      if (mediaType === undefined || !mediaType.startsWith("image/")) throw new Error("Badge service returned a non-image result");
      const artifact = await context.resources.put(new Uint8Array(await response.arrayBuffer()), mediaType);
      return {
        status: "completed",
        result: { value: { kind: "inline" as const, value: canonicalize(sealGeneratedImageSet({ images: [artifact] })) } },
      };
    },
  };
  return defineEndpointPackage({
    module: providerModule,
    facet: "badge-images",
    instance: options.instance,
    pool: options.pool,
    credentials: { apiKey: options.apiKey },
    credentialInputs: { apiKey: { label: "Badge service API key" } },
    defaultConcurrency: options.concurrency ?? 1,
    actionLimits: { submit: { concurrency: 1 }, poll: { concurrency: 4 }, collect: { concurrency: 1 } },
    capabilities: [{ capability, returns: generationTypes.imageSet, lifecycle: "asynchronous", supports: support, endpoint }],
  });
}
