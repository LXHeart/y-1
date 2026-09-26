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
 */
import { captureBrowserExecutablePath, installCaptureBrowser } from "@hypit/browser-capture";
import { DispatchError } from "../commands/dispatcher.ts";

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
