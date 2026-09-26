// image.ts — C107-06 (task-107) OpenCV image tools (K08/K12).
//
// Real transforms/composites through the upstream provider's own bounded
// interpreter script (raster_execute.py) inside the managed image.opencv.local
// uv environment: one shell-free Python process per tool call, staged inputs,
// one output file, no uncontrolled pools. The request schema is the upstream
// provider contract verbatim — nothing here invents a parallel op vocabulary.
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import type { HandleRegistry } from "../resources/handles.ts";
import { registerResource } from "../resources/handles.ts";
import { PROGRAM_CATALOG } from "../programs/catalog.ts";
import { opencvProbe } from "../programs/health.ts";

export const imageTools = ["image.transform", "image.compose"] as const;
export type ImageTool = typeof imageTools[number];

export function isImageTool(value: string): value is ImageTool {
  return (imageTools as readonly string[]).includes(value);
}

export class ImageToolError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "ImageToolError";
  }
}

function assert(condition: unknown, code: string, message: string): asserts condition {
  if (!condition) throw new ImageToolError(code, message);
}

export type ImageToolContext = {
  readonly registry: HandleRegistry;
  readonly programsRoot: string;
  readonly distributionRoot: string;
  readonly resolveSource: (payload: Record<string, unknown>) => Promise<string>;
};

const PROCESS_TIMEOUT_MS = 5 * 60_000;
const MAX_STDERR_BYTES = 256 * 1024;

function rasterScript(distributionRoot: string): string {
  return join(distributionRoot, "packages/provider-image-opencv-local/runtime/raster_execute.py");
}

async function assertOpencvReady(context: ImageToolContext): Promise<string> {
  const spec = PROGRAM_CATALOG["image.opencv.local"];
  const python = spec.pythonExecutable(context.programsRoot);
  const info = await stat(python).then((item) => item.isFile(), () => false);
  assert(info, "program_not_ready", `image.opencv.local 未准备（缺解释器 ${python}）；先执行 programs prepare。`);
  const probe = await opencvProbe(spec, context.programsRoot);
  assert(probe.state === "ready", "program_not_ready",
    probe.state === "mismatch"
      ? `image.opencv.local 版本不匹配：${probe.detail ?? "cv2/numpy major differs"}`
      : `image.opencv.local 探测失败：${probe.detail ?? "probe failed"}`);
  return python;
}

function runBounded(python: string, script: string, request: string, output: string): Promise<void> {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(python, [script, request, output], { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill("SIGKILL");
      rejectPromise(new ImageToolError("transform_timeout", `OpenCV raster execution exceeded ${PROCESS_TIMEOUT_MS}ms`));
    }, PROCESS_TIMEOUT_MS);
    child.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString("utf8");
      if (stderr.length > MAX_STDERR_BYTES) {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          child.kill("SIGKILL");
          rejectPromise(new ImageToolError("transform_failed", "OpenCV raster stderr exceeded its limit"));
        }
      }
    });
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      rejectPromise(error);
    });
    child.on("exit", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0) resolvePromise();
      else rejectPromise(new ImageToolError("transform_failed",
        `raster_execute exited ${code ?? "by signal"}: ${stderr.trim().split("\n").at(-1) ?? ""}`));
    });
  });
}

/** The encode tail decides the artifact media type (upstream rasterOutputMediaType semantics). */
function outputMediaType(request: Record<string, unknown>): string {
  const operations = Array.isArray(request.operations) ? request.operations : [];
  for (let index = operations.length - 1; index >= 0; index -= 1) {
    const operation = operations[index] as { kind?: unknown; format?: unknown };
    if (operation.kind === "encode") {
      return operation.format === "jpeg" ? "image/jpeg" : "image/png";
    }
  }
  return "image/png";
}

async function executeRaster(
  context: ImageToolContext,
  request: Record<string, unknown>,
  stagedSources: readonly string[],
  projectId: string | null,
): Promise<Record<string, unknown>> {
  const python = await assertOpencvReady(context);
  const work = await mkdtemp(join(tmpdir(), "hypit-image-opencv-"));
  try {
    const requestPath = join(work, "request.json");
    const outputPath = join(work, "output.bin");
    await writeFile(requestPath, JSON.stringify(request), "utf8");
    await runBounded(python, rasterScript(context.distributionRoot), requestPath, outputPath);
    const info = await stat(outputPath);
    assert(info.isFile() && info.size > 0, "transform_failed", "OpenCV raster execution produced no output");
    // Artifacts must live inside a registry-allowed root (registerResource
    // enforces containment); the first allowed root is the resources root.
    const outputs = join(context.registry.allowedRoots[0] ?? join(context.programsRoot, "resources"), "image-outputs");
    await mkdir(outputs, { recursive: true });
    const bytes = await readFile(outputPath);
    const artifact = join(outputs, `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}.${outputMediaType(request) === "image/jpeg" ? "jpg" : "png"}`);
    await writeFile(artifact, bytes);
    const record = await registerResource(context.registry, {
      absolutePath: artifact,
      projectId,
      mediaType: outputMediaType(request),
      role: "image-tool",
    });
    return {
      handle: record.handle,
      sha256: record.sha256,
      sizeBytes: record.sizeBytes,
      mediaType: record.mediaType,
      sources: stagedSources.length,
    };
  } finally {
    await rm(work, { recursive: true, force: true }).catch(() => {});
  }
}

const INTERPOLATIONS = new Set(["nearest", "linear", "cubic", "area", "lanczos"]);
const OPERATION_KINDS = new Set(["crop", "resize", "rotate", "flip", "denoise", "color", "sharpen", "blur", "alpha", "encode"]);

function validateOperations(value: unknown): void {
  assert(Array.isArray(value) && value.length > 0, "invalid_input", "operations must be a non-empty array");
  for (const operation of value) {
    assert(typeof operation === "object" && operation !== null, "invalid_input", "each operation must be an object");
    const kind = (operation as { kind?: unknown }).kind;
    assert(typeof kind === "string" && OPERATION_KINDS.has(kind), "invalid_input",
      `unknown image operation ${String(kind)}; supported: ${[...OPERATION_KINDS].join(", ")}`);
    const interpolation = (operation as { interpolation?: unknown }).interpolation;
    assert(interpolation === undefined || (typeof interpolation === "string" && INTERPOLATIONS.has(interpolation)),
      "invalid_input", `unknown interpolation ${String(interpolation)}`);
  }
}

export async function runImageTool(
  tool: ImageTool,
  payload: Record<string, unknown>,
  context: ImageToolContext,
): Promise<Record<string, unknown>> {
  const projectId = typeof payload.projectId === "string" ? payload.projectId : null;
  if (tool === "image.transform") {
    const source = await context.resolveSource(payload);
    validateOperations(payload.operations);
    // Source staged under a per-call temp name: the upstream script decodes from
    // bytes; only the request paths are server-controlled.
    const work = await mkdtemp(join(tmpdir(), "hypit-image-source-"));
    try {
      const staged = join(work, "source.bin");
      await writeFile(staged, await readFile(source));
      const request = { kind: "transform", source: staged, operations: payload.operations };
      return await executeRaster(context, request, [staged], projectId);
    } finally {
      await rm(work, { recursive: true, force: true }).catch(() => {});
    }
  }
  // image.compose
  const layers = payload.layers;
  assert(Array.isArray(layers) && layers.length > 0, "invalid_input", "layers must be a non-empty array");
  const canvas = payload.canvas;
  assert(typeof canvas === "object" && canvas !== null
    && Number.isFinite((canvas as { widthPx?: unknown }).widthPx)
    && Number.isFinite((canvas as { heightPx?: unknown }).heightPx),
    "invalid_input", "canvas needs numeric widthPx and heightPx");
  const background = payload.background;
  assert(typeof background === "string" && /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/u.test(background),
    "invalid_input", "background must be #rrggbb or #rrggbbaa");
  const work = await mkdtemp(join(tmpdir(), "hypit-image-compose-"));
  try {
    const stagedLayers: Array<Record<string, unknown>> = [];
    for (const [index, layer] of layers.entries()) {
      assert(typeof layer === "object" && layer !== null, "invalid_input", "each layer must be an object");
      const layerPayload = layer as Record<string, unknown>;
      const source = layerPayload.source;
      assert(typeof source === "object" && source !== null, "invalid_input",
        `layer ${index} needs source {handle} or {projectId, path}`);
      // The upstream script accesses frame/fit/interpolation/opacity directly:
      // all four are mandatory on the wire, validated here instead of failing mid-Python.
      assert(typeof layerPayload.frame === "object" && layerPayload.frame !== null,
        "invalid_input", `layer ${index} needs frame {xPx, yPx, widthPx, heightPx}`);
      assert(typeof layerPayload.fit === "string", "invalid_input", `layer ${index} needs fit (stretch|contain|cover)`);
      assert(typeof layerPayload.interpolation === "string" && INTERPOLATIONS.has(layerPayload.interpolation),
        "invalid_input", `layer ${index} needs a known interpolation`);
      assert(typeof layerPayload.opacity === "number" && Number.isFinite(layerPayload.opacity),
        "invalid_input", `layer ${index} needs numeric opacity`);
      const resolved = await context.resolveSource(source as Record<string, unknown>);
      const stagedPath = join(work, `layer-${String(index).padStart(4, "0")}.bin`);
      await writeFile(stagedPath, await readFile(resolved));
      stagedLayers.push({
        source: stagedPath,
        frame: layerPayload.frame,
        fit: layerPayload.fit,
        interpolation: layerPayload.interpolation,
        opacity: layerPayload.opacity,
      });
    }
    const request = {
      kind: "compose",
      canvas,
      background,
      layers: stagedLayers,
    };
    return await executeRaster(context, request, stagedLayers.map((layer) => String(layer.source)), projectId);
  } finally {
    await rm(work, { recursive: true, force: true }).catch(() => {});
  }
}
