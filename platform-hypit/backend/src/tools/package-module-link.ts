// package-module-link.ts — C107F-06 (task-107-fix-1) shared staging helper.
//
// Stage the distribution's own node_modules as the compile-time module graph of
// an author-package build (`@hypit/hypit` is the distribution ROOT package,
// whose subpath exports re-export every domain kit): normal NodeNext
// resolution with the real exports maps, pinned to the exact tree the engine
// runs — never a fresh install, never the network.
//
// Extracted from tools/packages.ts so the isolated runner server can stage the
// same link layout inside its slot without importing the whole dispatcher
// chain (runner/server.ts must stay a leaf module graph).
import { existsSync, mkdirSync, readdirSync, symlinkSync } from "node:fs";
import { join } from "node:path";

export function stageModuleLink(stagedDir: string, distributionRoot: string): void {
  const linkDir = join(stagedDir, "node_modules", "@hypit");
  mkdirSync(linkDir, { recursive: true });
  const sourceDir = join(distributionRoot, "node_modules", "@hypit");
  for (const entry of readdirSync(sourceDir, { withFileTypes: true })) {
    const target = entry.isDirectory() && !entry.isSymbolicLink() ? join(sourceDir, entry.name) + "/" : join(sourceDir, entry.name);
    try {
      symlinkSync(target, join(linkDir, entry.name), entry.isDirectory() ? "dir" : "file");
    } catch (error) {
      if ((error as { code?: string }).code !== "EEXIST") throw error;
    }
  }
  try {
    symlinkSync(distributionRoot + "/", join(linkDir, "hypit"), "dir");
  } catch (error) {
    if ((error as { code?: string }).code !== "EEXIST") throw error;
  }
  // Node built-in types for dependency sources that import node:* modules.
  const typesDir = join(distributionRoot, "node_modules", "@types");
  if (existsSync(typesDir)) {
    try {
      symlinkSync(typesDir, join(stagedDir, "node_modules", "@types"), "dir");
    } catch (error) {
      if ((error as { code?: string }).code !== "EEXIST") throw error;
    }
  }
}
