import { readFile, writeFile } from "node:fs/promises";

/**
 * C107F2-08：部署侧选定的渲染浏览器（HYPIT_RENDER_CHROME_PATH，如 Debian arm64
 * chromium）。chrome-for-testing 没有 linux/arm64 构建，managed 浏览器会装回
 * x86_64 二进制，在 arm64 容器必炸；provider 契约内的 chromePath 是正解。
 * 幂等合并进 hyperframes.local 的 config；未设 env 或已配 chromePath 时不动。
 */
export async function applyManagedChromePath(profilePath: string, configuredPath = process.env.HYPIT_RENDER_CHROME_PATH): Promise<void> {
  const chromePath = configuredPath?.trim();
  if (!chromePath) return;
  let raw: unknown;
  try {
    raw = JSON.parse(await readFile(profilePath, "utf8"));
  } catch {
    return;
  }
  const profile = raw as { endpoints?: Record<string, { config?: Record<string, unknown> }> };
  const endpoint = profile.endpoints?.["hyperframes.local"];
  // 端点缺 config 是常态（初始 profile 只有 use 声明）——须创建后注入，跳过会让
  // hyperframes 回落 managed chrome-headless-shell 下载路径（arm64 无构建必炸，
  // C107F2-08 实录 ENOENT）。
  if (endpoint === undefined) return;
  const config = (endpoint.config ??= {});
  if (typeof config.chromePath === "string" && config.chromePath.length > 0) return;
  // An explicitly pinned browser is part of the project's rendering contract.
  if (config.browserVersion !== undefined) return;
  config.chromePath = chromePath;
  await writeFile(profilePath, `${JSON.stringify(profile, undefined, 2)}\n`, "utf8");
}

