// chat-workspace.ts — C107-02 (task-107) shared fixture helper for engine tests.
//
// Builds the semantic-composition chat-scene package once inside G (its dist/
// is what the engine loads), then assembles a standalone copy of the chat
// project into a temp workspace. Also owns the stable, repo-local caches the
// render path needs (machine packages for the runner state root, render
// browser) so tests never write into the developer's home.
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadHypit } from "../../src/engine/hypit-bootstrap.ts";

// Cross-process lock (test files run in parallel child processes) over shared
// caches: G's chat-scene dist build and the machine-package install.
function withLock<T>(lockDir: string, operation: () => T): T {
  mkdirSync(lockDir, { recursive: true });
  for (let attempt = 0; ; attempt += 1) {
    try {
      mkdirSync(join(lockDir, "lock"));
      break;
    } catch {
      if (attempt > 600) throw new Error(`lock not acquired at ${lockDir}`);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
    }
  }
  try {
    return operation();
  } finally {
    rmSync(join(lockDir, "lock"), { recursive: true, force: true });
  }
}

export const repoRoot = join(import.meta.dirname, "../../../..");
export const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");
export const chatExampleRoot = join(generatedRoot, "examples/semantic-composition");
export const stableRunnerStateRoot = join(repoRoot, "data/hypit/runner-state");
export const stableBrowserCache = join(repoRoot, "data/hypit/render-browser");

const chatFiles = ["chat.svml", "chat.svrun", "chat.svs", "hypit.runtime.json"] as const;

/** Ensure G's chat-scene has a built dist/ (tsc through G's own node_modules). */
export function ensureChatSceneBuilt(): void {
  const dist = join(chatExampleRoot, "packages/chat-scene/dist");
  const marker = join(dist, "activation.js");
  if (existsSync(marker)) return;
  withLock(join(repoRoot, "data/hypit/test-locks/chat-scene"), () => {
    if (existsSync(marker)) return;
    const result = spawnSync("npm", ["run", "build"], {
      cwd: join(chatExampleRoot, "packages/chat-scene"),
      encoding: "utf8",
      timeout: 180_000,
    });
    if (result.status !== 0 || !existsSync(marker)) {
      throw new Error(`chat-scene build failed in G:\n${result.stdout}\n${result.stderr}`);
    }
  });
}

/** Assemble the chat project into a fresh temp workspace and return its root. */
export function prepareChatWorkspace(): string {
  ensureChatSceneBuilt();
  const workspace = mkdtempSync(join(tmpdir(), "hypit-chat-ws-"));
  for (const name of chatFiles) {
    cpSync(join(chatExampleRoot, name), join(workspace, name));
  }
  cpSync(join(chatExampleRoot, "packages/chat-scene"), join(workspace, "packages/chat-scene"), {
    recursive: true,
    filter: (source) => !source.includes("node_modules"),
  });
  mkdirSync(join(workspace, "packages"), { recursive: true });
  return workspace;
}

/** Point the workspace's render profile at stable software-render caches. */
export function stabilizeRenderProfile(workspace: string): void {
  const profilePath = join(workspace, "hypit.runtime.json");
  const profile = JSON.parse(readFileSync(profilePath, "utf8")) as {
    endpoints: Record<string, { config: Record<string, unknown> }>;
  };
  const hyperframes = profile.endpoints["hyperframes.local"]!;
  hyperframes.config.browserGpu = "software";
  hyperframes.config.browserCacheDirectory = stableBrowserCache;
  writeFileSync(profilePath, `${JSON.stringify(profile, null, 2)}\n`);
}

/** Install the chat project's machine package (font) into the runner state root. */
export async function ensureChatMachinePackages(): Promise<void> {
  const fontMarker = join(stableRunnerStateRoot, "packages/@fontsource-variable/inter");
  if (existsSync(fontMarker)) return;
  await withLockAsync(join(repoRoot, "data/hypit/test-locks/machine-packages"), async () => {
    if (existsSync(fontMarker)) return;
    // installEngineResolution must run before @hypit/video-cli can be imported;
    // runVideoCli then re-registers its own hooks with the same roots.
    await loadHypit(generatedRoot);
    const { runVideoCli } = await import("@hypit/video-cli");
    mkdirSync(stableRunnerStateRoot, { recursive: true });
    await runVideoCli(["packages", "install", "@fontsource-variable/inter@5.3.0"], {
      write: () => {},
      setExitCode: () => {},
    });
  });
}

async function withLockAsync<T>(lockDir: string, operation: () => Promise<T>): Promise<T> {
  mkdirSync(lockDir, { recursive: true });
  for (let attempt = 0; ; attempt += 1) {
    try {
      mkdirSync(join(lockDir, "lock"));
      break;
    } catch {
      if (attempt > 600) throw new Error(`lock not acquired at ${lockDir}`);
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
    }
  }
  try {
    return await operation();
  } finally {
    rmSync(join(lockDir, "lock"), { recursive: true, force: true });
  }
}
