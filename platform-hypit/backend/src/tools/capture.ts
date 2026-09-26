/**
 * C107-11 tools/capture.ts — web capture with its OWN capture Chrome (step 6)
 * and a restricted author-runner for capture.run (step 7).
 *
 * The capture browser (`installCaptureBrowser` / `captureBrowserExecutablePath`
 * from @hypit/browser-capture) is intentionally distinct from the render
 * Headless Shell: its own version manifest and cache, never shared.
 * capture.run executes the author's capture script inside the PAGE context
 * (never the host) with a network-deny default, an output-root allowlist and a
 * hard timeout — the raw request body is never eval()ed as host code.
 *
 * C107F-03 (D-06/D-07): the kind channel. capture.screenshot validates every
 * target through the outbound URL policy; capture.run reads the script from
 * the project workspace head only; capture.install-browser prepares the pinned
 * capture Chrome. All three refuse with 409 hypit_capture_not_configured when
 * HYPIT_CAPTURE_BROWSER_CACHE is unset (the surface is not deployed).
 */
import { captureBrowserExecutablePath, installCaptureBrowser, withCapture } from "@hypit/browser-capture";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { assertFetchableUrl, UrlPolicyError } from "../resources/url-policy.ts";

export type CaptureSetup = {
  readonly captureChromePath: string;
  readonly renderHeadlessShellMarker: "provider-hyperframes-local";
};

/** Step 6: prepare the capture Chrome; independent version field from render. */
export async function prepareCaptureChrome(options: {
  readonly version?: string;
  readonly cacheDirectory: string;
}): Promise<CaptureSetup> {
  if (options.cacheDirectory.trim().length === 0) {
    throw new DispatchError("invalid_input", "capture browser cache directory is required");
  }
  const version = options.version === undefined ? {} : { version: options.version };
  const captureChromePath = await installCaptureBrowser({ ...version, cacheDirectory: options.cacheDirectory });
  const resolved = await captureBrowserExecutablePath({ ...version, cacheDirectory: options.cacheDirectory });
  if (captureChromePath.length === 0 || resolved.length === 0) {
    throw new DispatchError("capture_unavailable", "capture Chrome is not installed");
  }
  return { captureChromePath, renderHeadlessShellMarker: "provider-hyperframes-local" };
}

export type RestrictedCaptureScript = {
  /** Author script text executed IN THE PAGE (not the host). */
  readonly script: string;
  /** Only files under this root may be written; anything else is refused. */
  readonly outputRoot: string;
  /** Network is denied for capture.run unless explicitly allowed (default: deny). */
  readonly allowNetwork?: boolean;
  readonly timeoutMs?: number;
};

const FORBIDDEN = [/\bprocess\b/, /\brequire\s*\(/, /\bimport\s*\(/, /\bglobalThis\b/] as const;

/** Static gate before the script ever reaches the browser context. */
export function assertRestrictedCaptureScript(script: RestrictedCaptureScript): void {
  if (script.script.trim().length === 0) {
    throw new DispatchError("invalid_input", "capture.run needs a script");
  }
  if (script.allowNetwork !== true && /\bwindow\.fetch\s*\(/.test(script.script)) {
    throw new DispatchError("invalid_input", "capture.run denies host network access");
  }
  for (const pattern of FORBIDDEN) {
    if (pattern.test(script.script)) {
      throw new DispatchError("invalid_input", "capture.run script must stay inside the page sandbox");
    }
  }
  const timeout = script.timeoutMs ?? 30_000;
  if (!Number.isSafeInteger(timeout) || timeout <= 0 || timeout > 120_000) {
    throw new DispatchError("invalid_input", "capture.run timeout must be 1..120000 ms");
  }
}

/** Output paths must stay inside the session's output root. */
export function assertWithinOutputRoot(outputRoot: string, target: string): void {
  const root = outputRoot.replace(/\/+$/u, "");
  if (!target.startsWith(`${root}/`)) {
    throw new DispatchError("invalid_input", `capture output ${target} escapes the allowed root`);
  }
}

// ---------------------------------------------------------------------------
// C107F-03: the capture TOOL channel (D-06/D-07).
// ---------------------------------------------------------------------------

export const captureTools = ["capture.screenshot", "capture.run", "capture.install-browser"] as const;
export type CaptureTool = typeof captureTools[number];

export function isCaptureTool(value: string): value is CaptureTool {
  return (captureTools as readonly string[]).includes(value);
}

/** The pinned capture Chrome; never "latest" (contract testCase). */
export const CAPTURE_BROWSER_VERSION = "153.0.8010.12";

export type CaptureToolContext = {
  /** Empty = capture surface not deployed (D-07): every kind answers 409. */
  readonly captureBrowserCache: string;
  /** Screenhots/outputs land here before registration (inside the resource roots). */
  readonly outputsRoot: string;
  /** Registers a materialized file as a resource handle (media assets area). */
  readonly registerResource: (input: {
    readonly absolutePath: string;
    readonly projectId: string | null;
    readonly mediaType: string;
    readonly role: string;
  }) => Promise<string>;
  /** Resolves the workspace root for capture.run script lookups (project head). */
  readonly workspaceRootFor: (projectId: string) => string;
};

function requireCaptureConfigured(ctx: CaptureToolContext): string {
  if (ctx.captureBrowserCache.trim().length === 0) {
    throw new DispatchError("capture_not_configured", "capture tools are not deployed on this broker");
  }
  return ctx.captureBrowserCache;
}

/** capture.screenshot: policy-checked URL → restricted capture Chrome → handle. */
async function runScreenshot(
  ctx: CaptureToolContext,
  cacheDirectory: string,
  payload: Record<string, unknown>,
  projectId: string | null,
): Promise<Record<string, unknown>> {
  const raw = payload.url;
  if (typeof raw !== "string" || raw.length === 0) {
    throw new DispatchError("invalid_input", "capture.screenshot needs url");
  }
  let target: URL;
  try {
    target = assertFetchableUrl(raw);
  } catch (error) {
    if (error instanceof UrlPolicyError) {
      throw new DispatchError("url_denied", `capture target refused: ${error.message}`);
    }
    throw error;
  }
  const viewport = payload.viewport;
  const selector = typeof payload.selector === "string" ? payload.selector : undefined;
  const fullPage = payload.fullPage === true;
  const waitUntil = payload.waitFor === "networkidle" || payload.waitFor === "load" || payload.waitFor === "domcontentloaded"
    ? payload.waitFor
    : "load";
  const outputs: string[] = [];
  await withCapture(
    {
      browser: { cacheDirectory },
      ...(viewport !== undefined && typeof viewport === "object" && viewport !== null
        ? { launch: { defaultViewport: viewport as never } }
        : {}),
    },
    async (session) => {
      await session.page.goto(target.toString(), { waitUntil: waitUntil as never, timeout: 60_000 });
      const absolutePath = join(ctx.outputsRoot, `capture-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}.png`);
      await session.screenshot({ path: absolutePath, ...(selector === undefined ? {} : { selector }), fullPage } as never);
      outputs.push(absolutePath);
    },
  );
  const handle = await registerOutput(ctx, outputs[0] ?? "", projectId, "image/png", "footage");
  return { outputs: [{ assetId: handle, role: "footage", mediaType: "image/png" }] };
}

/** capture.run: the script comes from the project workspace head; page-sandbox execution. */
async function runCaptureScript(
  ctx: CaptureToolContext,
  cacheDirectory: string,
  payload: Record<string, unknown>,
  projectId: string,
): Promise<Record<string, unknown>> {
  const scriptPath = payload.scriptPath;
  if (typeof scriptPath !== "string" || scriptPath.length === 0) {
    throw new DispatchError("invalid_input", "capture.run needs scriptPath (workspace-relative)");
  }
  if (scriptPath.includes("..") || scriptPath.startsWith("/") || scriptPath.includes("\\")) {
    throw new DispatchError("invalid_input", "scriptPath must be workspace-relative without traversal");
  }
  const args = Array.isArray(payload.args)
    ? (payload.args as unknown[]).filter((item): item is string => typeof item === "string").slice(0, 16)
    : [];
  const workspaceRoot = ctx.workspaceRootFor(projectId);
  const runRoot = join(workspaceRoot, "work");
  const absoluteScript = join(runRoot, scriptPath);
  if (!absoluteScript.startsWith(`${runRoot}/`)) {
    throw new DispatchError("invalid_input", "scriptPath escapes the project workspace");
  }
  let scriptText: string;
  try {
    scriptText = await readFile(absoluteScript, "utf8");
  } catch {
    throw new DispatchError("invalid_input", `script ${scriptPath} does not exist in the project head`);
  }
  const timeoutMs = typeof payload.timeoutMs === "number" ? payload.timeoutMs : 30_000;
  assertRestrictedCaptureScript({ script: scriptText, outputRoot: ctx.outputsRoot, timeoutMs });
  const produced: string[] = [];
  await withCapture(
    { browser: { cacheDirectory }, timeoutMs },
    async (session) => {
      // The author script executes IN THE PAGE (never host eval); outputs the
      // page hands back are names inside the session output root only.
      const page = session.page;
      const announced = await page.evaluate(async (body: { script: string; args: readonly string[] }) => {
        const fn = new Function("args", `"use strict";\n${body.script}`) as (args: readonly string[]) => unknown;
        return await Promise.resolve(fn(body.args));
      }, { script: scriptText, args } as never) as { readonly outputs?: readonly string[] } | undefined;
      for (const output of announced?.outputs ?? []) {
        if (typeof output !== "string") continue;
        assertWithinOutputRoot(ctx.outputsRoot, output);
        produced.push(output);
      }
    },
    (output) => {
      assertWithinOutputRoot(ctx.outputsRoot, output.path);
      produced.push(output.path);
    },
  );
  const handles: Array<{ assetId: string; role: string; mediaType: string }> = [];
  for (const path of [...new Set(produced)]) {
    const handle = await registerOutput(ctx, path, projectId, "image/png", "footage");
    handles.push({ assetId: handle, role: "footage", mediaType: "image/png" });
  }
  return { outputs: handles };
}

/** capture.install-browser: prepare the pinned capture Chrome (operator-grade, D-07). */
async function runInstallBrowser(cacheDirectory: string): Promise<Record<string, unknown>> {
  const setup = await prepareCaptureChrome({ version: CAPTURE_BROWSER_VERSION, cacheDirectory });
  return { version: CAPTURE_BROWSER_VERSION, executable: captureBrowserNameOf(setup.captureChromePath) };
}

function captureBrowserNameOf(path: string): string {
  const segments = path.split("/");
  return segments[segments.length - 1] ?? "";
}

async function registerOutput(
  ctx: CaptureToolContext,
  absolutePath: string,
  projectId: string | null,
  mediaType: string,
  role: string,
): Promise<string> {
  if (absolutePath.length === 0) {
    throw new DispatchError("engine_error", "capture produced no output file");
  }
  return await ctx.registerResource({ absolutePath, projectId, mediaType, role });
}

/** The capture kind body shared by the dispatcher. */
export async function runCaptureTool(
  ctx: CaptureToolContext,
  kind: CaptureTool,
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const cacheDirectory = requireCaptureConfigured(ctx);
  const projectId = typeof payload.projectId === "string" && payload.projectId.length > 0
    ? payload.projectId
    : null;
  if (kind === "capture.screenshot") return await runScreenshot(ctx, cacheDirectory, payload, projectId);
  if (kind === "capture.install-browser") return await runInstallBrowser(cacheDirectory);
  if (projectId === null) {
    throw new DispatchError("invalid_input", "capture.run needs projectId");
  }
  return await runCaptureScript(ctx, cacheDirectory, payload, projectId);
}
