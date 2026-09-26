// catalog.ts — C107-07 (task-107) provider catalog from real package metadata.
//
// K12.1: the catalog is built from what the packages actually declare — the
// provider factories' EndpointPackage (offers / credentials / pricing) plus the
// generation mapping arrays — never from README prose or a hardcoded matrix.
// Instantiating each factory with defaults is side-effect free: activation of
// browsers, venvs, or HTTP happens at execution, not construction. Any offer a
// mapping does not cover (or the reverse) is reported as an explicit gap instead
// of being silently dropped or faked.
import { realpathSync } from "node:fs";
import { pathToFileURL } from "node:url";

import { installEngineResolution } from "../engine/hypit-bootstrap.ts";
import type { CapabilityRef, TypeRef } from "@hypit/protocol";
import type { EndpointPackage } from "@hypit/endpoint-kit";

export type ProviderKind = "remote" | "local";

export type ProviderSupportEntry = {
  /** `${module.name}@${module.version}#${name}` — the exact CapabilityRef key. */
  readonly capability: string;
  /** Same key form for the capability's return TypeRef. */
  readonly returns: string;
  /** Generation result channel from the provider mapping, when one exists. */
  readonly result?: "audio" | "image" | "video";
  /** Route models from the mapping (upstream paths or model ids). */
  readonly models: readonly string[];
  readonly offered: boolean;
  readonly mapped: boolean;
  readonly transient: boolean;
};

export type ProviderCredentialSlot = {
  readonly slot: string;
  readonly label: string;
  readonly kind: "secret" | "json";
  readonly store: string;
  readonly key: string;
  /** Present only when the provider declares an OAuth acquisition for this slot. */
  readonly acquisition?: {
    readonly kind: string;
    readonly authorizationEndpoint: string;
    readonly redirectUri: string;
    readonly tokenEndpoint: string;
    readonly clientId: string;
    readonly scopes: readonly string[];
  };
};

export type ProviderDescriptor = {
  readonly packageId: string;
  readonly defaultEndpointId: string;
  readonly kind: ProviderKind;
  /** local providers ship a managed program (browser/venv/service lifecycle). */
  readonly managedProgram: boolean;
  readonly pricing:
    | { readonly type: "local" }
    | { readonly type: "page"; readonly url: string }
    | { readonly type: "reader" }
    | { readonly type: "none" };
  readonly credentials: readonly ProviderCredentialSlot[];
  readonly supports: readonly ProviderSupportEntry[];
  /** Capabilities where the offers/mapping relationship is not 1:1, honestly kept. */
  readonly gaps: readonly string[];
};

type ProviderRegistration = {
  readonly packageId: string;
  readonly defaultEndpointId: string;
  readonly kind: ProviderKind;
  readonly factory: string;
  readonly mappings?: string;
};

/** The upstream distribution default profile names three endpoint ids; the rest follow `<name>.default`. */
const REGISTRATIONS: readonly ProviderRegistration[] = [
  { packageId: "@hypit/provider-hypihub", defaultEndpointId: "hypihub.default", kind: "remote", factory: "createHypiHubProvider", mappings: "hypiHubMappings" },
  { packageId: "@hypit/provider-hiapi", defaultEndpointId: "hiapi.default", kind: "remote", factory: "createHiApiProvider", mappings: "hiApiMappings" },
  { packageId: "@hypit/provider-pollo", defaultEndpointId: "pollo.default", kind: "remote", factory: "createPolloProvider", mappings: "polloMappings" },
  { packageId: "@hypit/provider-monid", defaultEndpointId: "monid.default", kind: "remote", factory: "createMonidProvider", mappings: "monidMappings" },
  { packageId: "@hypit/provider-beatapi", defaultEndpointId: "beatapi.default", kind: "remote", factory: "createBeatApiProvider", mappings: "beatApiMappings" },
  { packageId: "@hypit/provider-tokendance", defaultEndpointId: "tokendance.default", kind: "remote", factory: "createTokenDanceProvider", mappings: "tokenDanceMappings" },
  { packageId: "@hypit/provider-hyperframes-local", defaultEndpointId: "hyperframes.local", kind: "local", factory: "createLocalHyperframesProvider" },
  { packageId: "@hypit/provider-media-local", defaultEndpointId: "media.local", kind: "local", factory: "createLocalMediaProvider" },
  { packageId: "@hypit/provider-image-opencv-local", defaultEndpointId: "image.opencv.local", kind: "local", factory: "createLocalOpenCvImageProvider" },
  { packageId: "@hypit/provider-whisperx-local", defaultEndpointId: "whisperx.local", kind: "local", factory: "createLocalWhisperXProvider" },
];

function capabilityKey(ref: CapabilityRef): string {
  return `${ref.module.name}@${ref.module.version}#${ref.name}`;
}

function typeKey(ref: TypeRef): string {
  return `${ref.module.name}@${ref.module.version}#${ref.name}`;
}

/**
 * Whether the provider package ships a managed program facet. Local providers
 * all return one from activation; the factory result itself does not carry it,
 * so kind is the honest proxy (the program contract is verified by the C06
 * programs tests against the real activations).
 */
function hasManagedProgram(registration: ProviderRegistration): boolean {
  return registration.kind === "local";
}

type MappingModule = {
  readonly [exported: string]: unknown;
};

type MappingEntry = { models: string[]; result: "audio" | "image" | "video" | undefined };

type SupportDraft = {
  capability: string;
  returns: string;
  offered: boolean;
  mapped: boolean;
  transient: boolean;
  models: string[];
  result: "audio" | "image" | "video" | undefined;
};

async function mappingModels(
  registration: ProviderRegistration,
  distributionRoot: string,
): Promise<Map<string, MappingEntry>> {
  const out = new Map<string, MappingEntry>();
  if (registration.mappings === undefined) return out;
  // Mapping modules are not part of the packages' public exports; load the
  // source file directly under tsx the same way hypit-bootstrap loads engine
  // internals (file URL, engine resolution already installed).
  const mappingUrl = new URL(`${registration.packageId.split("/").slice(1).join("/")}/src/mapping.ts`, pathToFileURL(realpathSync(`${distributionRoot}/packages/`) + "/"));
  const loaded = (await import(mappingUrl.href)) as MappingModule;
  const mappings = loaded[registration.mappings];
  if (!Array.isArray(mappings)) {
    throw new Error(`${registration.packageId} did not export ${registration.mappings}`);
  }
  for (const mapping of mappings as ReadonlyArray<Record<string, unknown>>) {
    const capability = mapping.capability as CapabilityRef | undefined;
    if (capability === undefined) continue;
    const key = capabilityKey(capability);
    const routes = Array.isArray(mapping.routes) ? (mapping.routes as ReadonlyArray<{ model?: unknown }>) : [];
    const models = routes.map((route) => String(route.model)).filter((model) => model.length > 0);
    const result = mapping.result as "audio" | "image" | "video" | undefined;
    out.set(key, { models, result });
  }
  return out;
}

function descriptorFor(
  registration: ProviderRegistration,
  pkg: EndpointPackage,
  mappings: Map<string, MappingEntry>,
): ProviderDescriptor {
  const hasMappingDomain = registration.mappings !== undefined;
  const supports = new Map<string, SupportDraft>();
  for (const offer of pkg.offers) {
    const key = capabilityKey(offer.capability);
    const mapped = mappings.get(key);
    supports.set(key, {
      capability: key,
      returns: typeKey(offer.returns),
      offered: true,
      // Local providers own no generation mapping table; mapped refers to the
      // generation wire mapping domain, which does not apply to them.
      mapped: hasMappingDomain ? mapped !== undefined : true,
      transient: offer.transient === true,
      models: [...(mapped?.models ?? [])],
      result: mapped?.result,
    });
  }
  for (const [key, entry] of mappings) {
    if (!supports.has(key)) {
      // A mapping with no offer executes nowhere on this endpoint; keep it with
      // models so the catalog still shows the full provider surface, flagged.
      supports.set(key, {
        capability: key,
        returns: "unknown",
        offered: false,
        mapped: true,
        transient: false,
        models: [...entry.models],
        result: entry.result,
      });
    }
  }
  const gaps = hasMappingDomain
    ? [...supports.values()]
      .filter((entry) => entry.offered !== entry.mapped)
      .map((entry) => (entry.offered ? `offered-without-mapping:${entry.capability}` : `mapped-without-offer:${entry.capability}`))
    : [];
  const credentials: ProviderCredentialSlot[] = pkg.credentials.map((slot) => ({
    slot: slot.slot,
    label: slot.label,
    kind: slot.kind,
    store: slot.ref.store,
    key: slot.ref.key,
    ...(slot.acquisition === undefined
      ? {}
      : {
          acquisition: {
            kind: slot.acquisition.kind,
            authorizationEndpoint: slot.acquisition.authorizationEndpoint,
            redirectUri: slot.acquisition.redirectUri,
            tokenEndpoint: slot.acquisition.tokenEndpoint,
            clientId: slot.acquisition.clientId,
            scopes: [...slot.acquisition.scopes],
          },
        }),
  }));
  const pricing: ProviderDescriptor["pricing"] = pkg.pricing === undefined
    ? { type: "none" }
    : pkg.pricing.kind === "local"
      ? { type: "local" }
      : { type: "page", url: pkg.pricing.url };
  return {
    packageId: registration.packageId,
    defaultEndpointId: registration.defaultEndpointId,
    kind: registration.kind,
    managedProgram: hasManagedProgram(registration),
    pricing: pkg.readPricing === undefined ? pricing : { type: "reader" },
    credentials,
    supports: [...supports.values()].map((entry) => ({
      capability: entry.capability,
      returns: entry.returns,
      offered: entry.offered,
      mapped: entry.mapped,
      transient: entry.transient,
      models: [...entry.models],
      ...(entry.result === undefined ? {} : { result: entry.result }),
    })),
    gaps,
  };
}

const catalogCache = new Map<string, Promise<ProviderDescriptor[]>>();

/**
 * Enumerate all ten providers (6 remote / 4 local) from one distribution.
 * Repeat calls with the same root share the loaded modules; the result is a
 * frozen snapshot of what the packages declare right now.
 */
export function describeProviderCatalog(distributionRoot: string): Promise<ProviderDescriptor[]> {
  const cached = catalogCache.get(distributionRoot);
  if (cached !== undefined) return cached;
  const promise = (async () => {
    await installEngineResolution(distributionRoot);
    const descriptors: ProviderDescriptor[] = [];
    for (const registration of REGISTRATIONS) {
      const pkgModule = (await import(registration.packageId)) as Record<string, unknown>;
      const factory = pkgModule[registration.factory] as (options?: Record<string, unknown>) => EndpointPackage;
      if (typeof factory !== "function") {
        throw new Error(`${registration.packageId} did not export ${registration.factory}`);
      }
      // Factories take no required config; defaults mirror the upstream
      // distribution profile. Nothing is started by construction.
      const pkg = factory({});
      const mappings = await mappingModels(registration, distributionRoot);
      descriptors.push(descriptorFor(registration, pkg, mappings));
    }
    return descriptors;
  })();
  catalogCache.set(distributionRoot, promise);
  return promise;
}

export function providerRegistrations(): readonly ProviderRegistration[] {
  return REGISTRATIONS;
}
