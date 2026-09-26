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
import { installEngineResolution } from "./hypit-bootstrap.ts";

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

/** C107F-05：surface/visual 扩展参数（API-F08）。surface=包名数组；visual 空串=形状清单。 */
export type VocabularyOptions = {
  readonly surface?: readonly string[];
  readonly visual?: string;
};

/** C107F-05 报告：既有三字段恒在，surfaces/visual 按参附加（上游字段原样不裁剪）。 */
export type VocabularyReport = Vocabulary & {
  readonly surfaces?: readonly import("@hypit/video-cli").SurfaceListing[];
  readonly visual?: ReturnType<(typeof import("@hypit/video-cli"))["visualSchema"]>;
};

/** 入参问题（未知包/未知形状）：server 层转 DispatchError("invalid_input") → 命令行 400 语义。 */
export class VocabularyInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "VocabularyInputError";
  }
}

/**
 * The vocabulary from the live distribution: one entry per provider package,
 * capabilities keyed `module@version#name` with offered/mapped flags (gaps stay
 * visible — no cartesian-product fake support), credential slots with their
 * acquisition kind, and the managed programs of this build.
 *
 * C107F-05（API-F08）：可选 options 附加 `surfaces`（上游 listSurfaces 原样）与 `visual`
 * （上游 visualSchema；空串=14 形状清单）。无参调用与既有三字段响应逐字节等价。
 */
export async function describeVocabulary(distributionRoot: string, options: VocabularyOptions = {}): Promise<VocabularyReport> {
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
  let surfaces: import("@hypit/video-cli").SurfaceListing[] | undefined;
  let visual: ReturnType<(typeof import("@hypit/video-cli"))["visualSchema"]> | undefined;
  if (options.surface !== undefined && options.surface.length > 0) {
    await installEngineResolution(distributionRoot);
    const videoCli = await import("@hypit/video-cli");
    try {
      surfaces = [...(await videoCli.listSurfaces(distributionRoot, options.surface))];
    } catch (error) {
      throw new VocabularyInputError(
        `surface 包不可用（未知包或加载失败）：${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }
  if (options.visual !== undefined) {
    await installEngineResolution(distributionRoot);
    const videoCli = await import("@hypit/video-cli");
    try {
      visual = videoCli.visualSchema(options.visual === "" ? undefined : options.visual);
    } catch (error) {
      throw new VocabularyInputError(
        `visual 形状不可用：${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }
  return {
    providers,
    programs,
    alignmentLanguages: alignmentLanguages(),
    ...(surfaces === undefined ? {} : { surfaces }),
    ...(visual === undefined ? {} : { visual }),
  };
}
