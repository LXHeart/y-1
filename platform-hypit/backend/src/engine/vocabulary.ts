// vocabulary.ts — C107-08 (task-107) knowledge vocabulary: the real capability/
// endpoint/program/model catalog for the knowledge surface and advanced UI.
//
// Data sources are the ACTUAL distribution and this build's own catalogs —
// provider packages via describeProviderCatalog (which instantiates each
// package factory exactly like native selection does) and the managed
// programs catalog from C107-06. Nothing is scanned beyond the selected
// distribution (card step 9); provider packages are trusted distribution code,
// author project packages are not touched here.
import { describeProviderCatalog } from "../providers/catalog.ts";
import { PROGRAM_CATALOG, alignmentLanguages } from "../programs/catalog.ts";

export type VocabularyCapability = {
  readonly key: string;
  readonly returns: string;
  readonly offered: boolean;
  readonly mapped: boolean;
  readonly models: readonly string[];
  readonly result?: "audio" | "image" | "video";
};

export type VocabularyCredentialSlot = {
  readonly slot: string;
  readonly kind: string;
  readonly acquisition?: { readonly kind: string } | null;
};

export type VocabularyProvider = {
  readonly packageId: string;
  readonly defaultEndpointId: string;
  readonly kind: "remote" | "local";
  readonly managedProgram: boolean;
  readonly capabilities: readonly VocabularyCapability[];
  readonly credentials: readonly VocabularyCredentialSlot[];
  readonly pricing: { readonly type: "page"; readonly url: string } | { readonly type: "local" } | { readonly type: "reader" } | { readonly type: "none" };
  readonly gaps: readonly string[];
};

export type VocabularyProgram = {
  readonly program: string;
  readonly kind: string;
  readonly identity: string;
  readonly loopbackPort: number;
};

export type Vocabulary = {
  readonly providers: readonly VocabularyProvider[];
  readonly programs: readonly VocabularyProgram[];
  readonly alignmentLanguages: readonly string[];
};

/**
 * The vocabulary from the live distribution: one entry per provider package,
 * capabilities keyed `module@version#name` with offered/mapped flags (gaps stay
 * visible — no cartesian-product fake support), credential slots with their
 * acquisition kind, and the managed programs of this build.
 */
export async function describeVocabulary(distributionRoot: string): Promise<Vocabulary> {
  const catalog = await describeProviderCatalog(distributionRoot);
  const providers: VocabularyProvider[] = catalog.map((provider) => ({
    packageId: provider.packageId,
    defaultEndpointId: provider.defaultEndpointId,
    kind: provider.kind,
    managedProgram: provider.managedProgram,
    capabilities: provider.supports.map((entry) => ({
      key: entry.capability,
      returns: entry.returns,
      offered: entry.offered,
      mapped: entry.mapped,
      models: entry.models,
      ...(entry.result === undefined ? {} : { result: entry.result }),
    })),
    credentials: provider.credentials.map((slot) => ({
      slot: slot.slot,
      kind: slot.kind,
      ...(slot.acquisition === undefined ? { acquisition: null } : { acquisition: { kind: slot.acquisition.kind } }),
    })),
    pricing: provider.pricing,
    gaps: provider.gaps,
  }));
  const programs: VocabularyProgram[] = Object.values(PROGRAM_CATALOG).map((spec) => ({
    program: spec.id,
    kind: spec.kind,
    identity: `${spec.expectedIdentity.protocol}${spec.expectedIdentity.model === undefined ? "" : `:${spec.expectedIdentity.model}`}`,
    loopbackPort: spec.loopback.port,
  }));
  return {
    providers,
    programs,
    alignmentLanguages: alignmentLanguages(),
  };
}
