// hypit-bootstrap.ts — C107-02 (task-107) engine bootstrap facade.
//
// Loads the Grassland-built engine copy (G = platform-hypit/.generated/hypit) the
// way `bin/hypit.mjs` loads a distribution: tsx register, then the Distribution
// package resolution hook, then the external (host package) resolution hook, and
// only then dynamic imports of `@hypit/*` sources. All @hypit imports in this
// codebase are dynamic on purpose: the resolution hooks must be installed before
// any @hypit module is evaluated, and static imports would evaluate first.
import { realpathSync } from "node:fs";
import { pathToFileURL } from "node:url";

export type LoadedHypit = {
  readonly distributionRoot: string;
  /** Official video authoring distribution assembled from the G checkout. */
  readonly videoCliDistribution: import("@hypit/cli").CliDistribution;
  readonly loadDiscoveredSourcePackages: typeof import("@hypit/cli").loadDiscoveredSourcePackages;
  readonly collectRunFrontends: typeof import("@hypit/cli").collectRunFrontends;
  readonly loadRunFile: typeof import("@hypit/cli").loadRunFile;
  readonly checkRunFile: typeof import("@hypit/cli").checkRunFile;
  readonly createCatalogDescriptor: typeof import("@hypit/cli").createCatalogDescriptor;
  readonly evaluatePlanNeeds: typeof import("@hypit/cli").evaluatePlanNeeds;
  readonly orderedBuildId: typeof import("@hypit/protocol").orderedBuildId;
  readonly parseSourceHeader: typeof import("@hypit/source").parseSourceHeader;
};

let installedRoot: string | undefined;
let loading: Promise<LoadedHypit> | undefined;

/** Install the engine module-resolution chain for one distribution root (idempotent). */
export async function installEngineResolution(distributionRoot: string): Promise<void> {
  const root = realpathSync(distributionRoot);
  if (installedRoot !== undefined) {
    if (installedRoot !== root) {
      throw new Error(
        `engine resolution already installed for ${installedRoot}; refusing to switch to ${root}`,
      );
    }
    return;
  }
  const url = pathToFileURL(root + "/");
  const { register } = await import("tsx/esm/api");
  register();
  const resolution = await import(
    new URL("packages/package-loader-node/src/distribution-resolution.ts", url).href
  );
  resolution.installDistributionPackageResolution([root]);
  const runtimeHost = await import(new URL("packages/runtime-host-node/src/index.ts", url).href);
  resolution.installExternalPackageResolution([runtimeHost.hypitHostPackageRoot()]);
  installedRoot = root;
}

/**
 * One engine initialization per process: repeat calls with the same root return
 * the cached facade; a second, different root is rejected — no runtime version
 * switching inside a live host.
 */
export async function loadHypit(distributionRoot: string): Promise<LoadedHypit> {
  const root = realpathSync(distributionRoot);
  if (loading !== undefined) {
    const loaded = await loading;
    if (loaded.distributionRoot !== root) {
      throw new Error(
        `loadHypit already bound to ${loaded.distributionRoot}; refusing ${root}`,
      );
    }
    return loaded;
  }
  loading = (async () => {
    await installEngineResolution(root);
    const videoCli = await import("@hypit/video-cli");
    const cli = await import("@hypit/cli");
    const protocol = await import("@hypit/protocol");
    const source = await import("@hypit/source");
    return {
      distributionRoot: root,
      videoCliDistribution: videoCli.videoCliDistribution,
      loadDiscoveredSourcePackages: cli.loadDiscoveredSourcePackages,
      collectRunFrontends: cli.collectRunFrontends,
      loadRunFile: cli.loadRunFile,
      checkRunFile: cli.checkRunFile,
      createCatalogDescriptor: cli.createCatalogDescriptor,
      evaluatePlanNeeds: cli.evaluatePlanNeeds,
      orderedBuildId: protocol.orderedBuildId,
      parseSourceHeader: source.parseSourceHeader,
    } satisfies LoadedHypit;
  })();
  return await loading;
}
