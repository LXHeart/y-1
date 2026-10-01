// config.ts — C107-02 (task-107) backend configuration: env-driven, validated once.
//
// The backend is the trusted Hypit execution broker. It must never receive
// secrets through this config (credentials live in the credential stores behind
// the runtime profile); only paths, ports and internal auth material appear here.
//
// 107-fix-2 C03（D-03）：路径解析集中到本文件，全部走文档化 env（§6.8 配置唯一表），
// 不再从 generatedRoot 的父目录猜测 backendRoot，dataRoot 兄弟目录布局显式化：
//   生产：dataRoot=/data/hypit，兄弟 /data/{projects,resources,runner-state,runner-tmp}
//   持久伞 /data 由 compose 挂卷；dev 默认相对 repoRoot。
import { accessSync, mkdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** broker 源码根（src/..）：由 import.meta.url 推导，不依赖 CWD/generatedRoot。 */
const backendRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const repoRoot = resolve(backendRoot, "../..");

export type HypitBackendConfig = {
  readonly host: string;
  readonly port: number;
  /** broker 源码根（镜像 /app、dev 为 backend 包目录）。 */
  readonly backendRoot: string;
  /** G: the patched engine build this process may load (exactly one per process). */
  readonly generatedRoot: string;
  /** Host-side state root: bridge journal (C04), runner sockets, slots. */
  readonly dataRoot: string;
  /** Directory holding the per-slot Unix domain sockets. */
  readonly runnerSocketDir: string;
  /** Directory holding per-slot input/output/scratch volumes. */
  readonly runnerSlotRoot: string;
  /** Stable tmp root for runner tsx transform caches (reused across slots). */
  readonly runnerTmpRoot: string;
  /** 模板 catalog 根（catalog.json 及各模板目录；§6.8 HYPIT_TEMPLATES_ROOT）。 */
  readonly templatesRoot: string;
  /** fixtures 根（minimal-local 等受控模板源；§6.8 HYPIT_FIXTURES_ROOT，NEW）。 */
  readonly fixturesRoot: string;
  /** Bearer token for /internal/v1 endpoints (C03 hardens parity with Java). */
  readonly internalToken: string;
  /** C107F2-07：broker → Java /internal/hypit/executions 授权桥基址（容器内 intelligence:8086）。 */
  readonly javaInternalBaseUrl: string;
  /**
   * C107F2-19：Studio 会话代理监听端口（与内部 API 端口分离——代理面凭
   * session-access 断言准入，不持全局 internal bearer）。空串=不启用。
   */
  readonly studioProxyPort: number;
  /** C107F2-19：与 Java session-access 签名方共享的断言 HMAC 密钥（≥32 字符）。 */
  readonly sessionAssertionSecret: string;
  /** Runner IPC frame limit and timeouts. */
  readonly runnerFrameLimitBytes: number;
  readonly runnerRequestTimeoutMs: number;
  readonly runnerKillTimeoutMs: number;
  /**
   * C107F-03 (D-07): capture Chrome cache directory. Empty = the capture tool
   * surface is not deployed (capture.* kinds answer 409 hypit_capture_not_configured).
   */
  readonly captureBrowserCache: string;
};

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim().length === 0) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer (got ${raw})`);
  }
  return value;
}

function pathEnv(name: string, fallback: string): string {
  const raw = process.env[name];
  return resolve(raw === undefined || raw.trim().length === 0 ? fallback : raw);
}

export function loadConfig(): HypitBackendConfig {
  const dataRoot = pathEnv("HYPIT_DATA_ROOT", resolve(repoRoot, "data/hypit/host"));
  const config: HypitBackendConfig = {
    host: process.env.HYPIT_BACKEND_HOST ?? "127.0.0.1",
    port: intEnv("HYPIT_BACKEND_PORT", 9240),
    backendRoot,
    generatedRoot: pathEnv("HYPIT_GENERATED_ROOT", resolve(repoRoot, "platform-hypit/.generated/hypit")),
    dataRoot,
    // C03/TC-F2-03-02（BE-05 反例修复）：runner 目录必须真实读取文档化 env，
    // 显式设置优先于 dataRoot 内默认路径。
    runnerSocketDir: pathEnv("HYPIT_RUNNER_SOCKET_DIR", resolve(dataRoot, "runner-sockets")),
    runnerSlotRoot: pathEnv("HYPIT_RUNNER_SLOT_ROOT", resolve(dataRoot, "runner-slots")),
    runnerTmpRoot: pathEnv("HYPIT_RUNNER_TMP_ROOT", resolve(dataRoot, "../runner-tmp")),
    templatesRoot: pathEnv("HYPIT_TEMPLATES_ROOT", resolve(repoRoot, "platform-hypit/templates")),
    fixturesRoot: pathEnv("HYPIT_FIXTURES_ROOT", resolve(repoRoot, "platform-hypit/fixtures")),
    internalToken: process.env.HYPIT_INTERNAL_TOKEN ?? "",
    javaInternalBaseUrl: process.env.HYPIT_JAVA_INTERNAL_URL ?? "http://127.0.0.1:8086",
    // C107F2-19：studio 代理面端口默认 9464（compose HYPIT_STUDIO_UPSTREAM 同值）；
    // HYPIT_STUDIO_PORT=0 显式关闭该监听。
    studioProxyPort: process.env.HYPIT_STUDIO_PORT === "0"
      ? 0
      : intEnv("HYPIT_STUDIO_PORT", 9464),
    sessionAssertionSecret: process.env.HYPIT_SESSION_ASSERTION_SECRET ?? "",
    runnerFrameLimitBytes: intEnv("HYPIT_RUNNER_FRAME_LIMIT_BYTES", 1024 * 1024),
    runnerRequestTimeoutMs: intEnv("HYPIT_RUNNER_REQUEST_TIMEOUT_MS", 120_000),
    runnerKillTimeoutMs: intEnv("HYPIT_RUNNER_KILL_TIMEOUT_MS", 10_000),
    captureBrowserCache: process.env.HYPIT_CAPTURE_BROWSER_CACHE?.trim() ?? "",
  };
  return config;
}

/** Create the runtime directory layout; safe to call repeatedly. */
export function ensureRuntimeDirs(config: HypitBackendConfig): void {
  for (const dir of [config.dataRoot, config.runnerSocketDir, config.runnerSlotRoot, config.runnerTmpRoot]) {
    mkdirSync(dir, { recursive: true });
  }
  // Packages-only state root shared with the runner (never credentials).
  mkdirSync(resolve(config.dataRoot, "../runner-state"), { recursive: true });
}

export function assertConfigured(config: HypitBackendConfig): void {
  if (config.internalToken.length < 32) {
    throw new Error(
      "HYPIT_INTERNAL_TOKEN must be set to at least 32 characters before enabling /internal endpoints",
    );
  }
}

/**
 * C03（TC-F2-03-03）：启动预检——模板资源与 catalog 就位且受控。
 *
 * 失败抛错（broker 启动即非零退出），错误信息只含路径类别与原因，不含宿主
 * 绝对路径/秘密；不允许 healthz 假阳性（进程起不来即不healthy）。
 */
export function assertTemplateResources(config: HypitBackendConfig): void {
  const mustBeDir = (dir: string, category: string) => {
    try {
      if (!statSync(dir).isDirectory()) throw new Error("not a directory");
      accessSync(dir);
    } catch {
      throw new Error(`startup-precheck failed: ${category} 根不可读或缺目录（basename=${dir.split("/").pop()}）`);
    }
  };
  mustBeDir(config.fixturesRoot, "fixtures-root");
  mustBeDir(config.templatesRoot, "templates-root");
  // provision 模板文件（minimal-local）必须存在且可读。
  for (const file of ["package.json", "main.svml", "main.svrun"]) {
    try {
      accessSync(resolve(config.fixturesRoot, "minimal-local", file));
    } catch {
      throw new Error(`startup-precheck failed: provision 模板文件缺失（fixtures/minimal-local/${file}）`);
    }
  }
  // catalog：pinned sourceCommit + 源路径受控（相对、无 ..、落在 templates 根内）且目录存在。
  let catalog: { sourceCommit?: unknown; templates?: Array<{ templateId?: unknown; sourcePath?: unknown }> };
  try {
    catalog = JSON.parse(readFileSync(resolve(config.templatesRoot, "catalog.json"), "utf8"));
  } catch {
    throw new Error("startup-precheck failed: templates-root 缺 catalog.json 或不可解析");
  }
  if (typeof catalog.sourceCommit !== "string" || !/^[0-9a-f]{40}$/.test(catalog.sourceCommit)) {
    throw new Error("startup-precheck failed: catalog 未携带 pinned sourceCommit");
  }
  const templates = Array.isArray(catalog.templates) ? catalog.templates : [];
  if (templates.length === 0) {
    throw new Error("startup-precheck failed: catalog 模板列表为空");
  }
  for (const entry of templates) {
    const id = typeof entry.templateId === "string" ? entry.templateId : "<unnamed>";
    const sourcePath = entry.sourcePath;
    if (typeof sourcePath !== "string" || sourcePath.length === 0 || sourcePath.startsWith("/")) {
      throw new Error(`startup-precheck failed: 模板 ${id} 的 sourcePath 非法（须为仓库相对路径）`);
    }
    const resolved = resolve(config.templatesRoot, "../../", sourcePath);
    const contained =
      resolved === config.templatesRoot || resolved.startsWith(config.templatesRoot + "/");
    if (!contained || sourcePath.split("/").includes("..")) {
      throw new Error(`startup-precheck failed: 模板 ${id} 的 sourcePath 越界（不在 templates 根内）`);
    }
    try {
      if (!statSync(resolved).isDirectory()) throw new Error("not a directory");
      accessSync(resolved);
    } catch {
      throw new Error(`startup-precheck failed: 模板 ${id} 的源目录缺失（basename=${resolved.split("/").pop()}）`);
    }
  }
}
