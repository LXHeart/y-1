// activation-hooks.ts — C107-07 (task-107) trusted provider activation.
//
// K12.2/07.6: the broker activates providers through their native factories
// with trusted-only options (publicAssetUrl, credentialRef, fetch) and keeps
// every secret on this side of the runner IPC: credentials are resolved from
// the declared slots at invocation time, in this process, and never appear in
// payloads crossing into the sandboxed author runner. The authorization
// decorator wraps an EndpointRegistrar so async submits cannot run without a
// Java-issued permit for that exact operation.
import { installEngineResolution } from "../engine/hypit-bootstrap.ts";
import type { EndpointPackage, EndpointRegistrar } from "@hypit/endpoint-kit";
import type { CredentialRef, CredentialValue } from "@hypit/runtime";
import type { ProviderDescriptor } from "./catalog.ts";
import type { CredentialStores } from "./credentials.ts";
import type { ExecutionAuthorizer, PrepareRequest } from "./authorization.ts";

export type TrustedEndpointConfig = {
  readonly endpointId: string;
  readonly baseUrl?: string;
  /** CredentialRef for the endpoint's apiKey slot, exactly as a Runtime Profile would carry it. */
  readonly credential?: CredentialRef;
};

export type TrustedActivation = {
  readonly endpointId: string;
  readonly descriptor: ProviderDescriptor;
  readonly pkg: EndpointPackage;
  /**
   * Resolve the endpoint's declared credential slots for one invocation.
   * Trusted-side only: the returned record never crosses the runner IPC, and
   * writable stores additionally expose the narrow `replace` authority the
   * upstream OAuth refresh path uses.
   */
  invocationCredentials(): Promise<Record<string, CredentialValue & {
    replace?: (value: CredentialValue) => Promise<void>;
  }>>;
};

type RemoteFactoryOptions = {
  readonly instance?: string;
  readonly pool?: string;
  readonly baseUrl?: string;
  readonly apiKey?: CredentialRef;
  readonly publicAssetUrl?: unknown;
  readonly fetch?: typeof globalThis.fetch;
};

/** Name → factory per provider package (single source: the packages' own index). */
const FACTORY_BY_PACKAGE: Readonly<Record<string, string>> = {
  "@hypit/provider-hypihub": "createHypiHubProvider",
  "@hypit/provider-hiapi": "createHiApiProvider",
  "@hypit/provider-pollo": "createPolloProvider",
  "@hypit/provider-monid": "createMonidProvider",
  "@hypit/provider-beatapi": "createBeatApiProvider",
  "@hypit/provider-tokendance": "createTokenDanceProvider",
  "@hypit/provider-hyperframes-local": "createLocalHyperframesProvider",
  "@hypit/provider-media-local": "createLocalMediaProvider",
  "@hypit/provider-image-opencv-local": "createLocalOpenCvImageProvider",
  "@hypit/provider-whisperx-local": "createLocalWhisperXProvider",
};

/**
 * Activate one provider endpoint with trusted factory options. Remote
 * providers receive baseUrl/credentialRef/publicAssetUrl/fetch; local ones
 * only identity — their programs are managed by the C06 programs domain.
 */
export async function activateTrustedEndpoint(options: {
  readonly distributionRoot: string;
  readonly descriptor: ProviderDescriptor;
  readonly config: TrustedEndpointConfig;
  readonly credentials: CredentialStores;
  readonly publicAssetUrl?: (artifact: unknown, artifacts: unknown, fields?: unknown) => Promise<string>;
  readonly fetch?: typeof globalThis.fetch;
}): Promise<TrustedActivation> {
  const factoryName = FACTORY_BY_PACKAGE[options.descriptor.packageId];
  if (factoryName === undefined) {
    throw new Error(`no activation hook for ${options.descriptor.packageId}`);
  }
  await installEngineResolution(options.distributionRoot);
  const pkgModule = (await import(options.descriptor.packageId)) as Record<string, unknown>;
  const factory = pkgModule[factoryName] as (factoryOptions?: RemoteFactoryOptions) => EndpointPackage;
  if (typeof factory !== "function") {
    throw new Error(`${options.descriptor.packageId} did not export ${factoryName}`);
  }
  const factoryOptions: RemoteFactoryOptions = {
    instance: options.config.endpointId,
    pool: options.config.endpointId,
    ...(options.config.baseUrl === undefined ? {} : { baseUrl: options.config.baseUrl }),
    ...(options.config.credential === undefined ? {} : { apiKey: options.config.credential }),
    ...(options.publicAssetUrl === undefined ? {} : { publicAssetUrl: options.publicAssetUrl }),
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  };
  const pkg = factory(options.descriptor.kind === "remote" ? factoryOptions : {
    instance: options.config.endpointId,
    pool: options.config.endpointId,
    ...(options.config.baseUrl === undefined ? {} : { baseUrl: options.config.baseUrl }),
  });

  return {
    endpointId: options.config.endpointId,
    descriptor: options.descriptor,
    pkg,
    async invocationCredentials() {
      const out: Record<string, CredentialValue & { replace?: (value: CredentialValue) => Promise<void> }> = {};
      for (const slot of pkg.credentials) {
        const value = await options.credentials.composite.resolve(slot.ref);
        if (value === undefined) continue;
        const entry: CredentialValue & { replace?: (value: CredentialValue) => Promise<void> } = { ...value };
        if (options.credentials.writable(slot.ref)) {
          entry.replace = async (next) => {
            await options.credentials.put(slot.ref, next.secret);
          };
        }
        out[slot.slot] = entry;
      }
      return out;
    },
  };
}

/**
 * Wrap an EndpointRegistrar with the execution authorization decorator: async
 * `start` must hold a live Java permit for that exact operation (resolved by
 * the caller's mapping — the decorator never invents grant/scope facts), and
 * immediate handlers require the same. `poll`/`collect` pass through —
 * polling never books a second generation (07.8).
 */
export function withAuthorizationGuard(
  inner: EndpointRegistrar,
  authorizer: ExecutionAuthorizer,
  resolveRequest: (operation: string) => PrepareRequest | undefined,
): EndpointRegistrar {
  const guard = (label: string): ((operation: string) => Promise<void>) => {
    return async (operation: string) => {
      const request = resolveRequest(operation);
      if (request === undefined) {
        throw new Error(`no execution authorization mapping for ${label} ${operation}; refusing to submit`);
      }
      await authorizer.authorize(request);
    };
  };
  const startGuard = guard("operation");
  return {
    registerImmediateEndpoint(id, capability, returns, handler, options) {
      inner.registerImmediateEndpoint(id, capability, returns, async (context) => {
        // Immediate handlers carry no operation id in their context; the
        // registration id scopes the guard to this endpoint instance.
        await startGuard(id);
        return await handler(context);
      }, options);
    },
    registerAsyncEndpoint(id, capability, returns, endpoint, options) {
      inner.registerAsyncEndpoint(id, capability, returns, {
        start: async (context) => {
          await startGuard(String(context.operation));
          return await endpoint.start(context);
        },
        poll: endpoint.poll.bind(endpoint),
        ...(endpoint.collect === undefined ? {} : { collect: endpoint.collect.bind(endpoint) }),
        ...(endpoint.cancel === undefined ? {} : { cancel: endpoint.cancel.bind(endpoint) }),
      }, options);
    },
  };
}
