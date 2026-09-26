// main.mjs — C107-02 (task-107) backend entry.
//
// Mirrors bin/hypit.mjs bootstrap ordering exactly: tsx register, then the
// Distribution package resolution hook (scoped to G), then the external host
// package hook — and only afterwards dynamic import of the TypeScript service.
// The service import is dynamic on purpose: its static @hypit/* imports must be
// evaluated with the hooks already installed.
import { realpathSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const backendRoot = resolve(import.meta.dirname, "..");
const repoRoot = resolve(backendRoot, "../..");
const distributionRoot = process.env.HYPIT_GENERATED_ROOT
  ?? resolve(repoRoot, "platform-hypit/.generated/hypit");

const root = realpathSync(distributionRoot);
const url = pathToFileURL(root + "/");
const { register } = await import("tsx/esm/api");
register();
const resolution = await import(
  new URL("packages/package-loader-node/src/distribution-resolution.ts", url).href
);
resolution.installDistributionPackageResolution([root]);
const runtimeHost = await import(new URL("packages/runtime-host-node/src/index.ts", url).href);
resolution.installExternalPackageResolution([runtimeHost.hypitHostPackageRoot()]);

const { runServer } = await import("./server.ts");
await runServer();
