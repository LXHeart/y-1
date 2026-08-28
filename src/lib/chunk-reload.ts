/**
 * 发版后旧标签页的懒加载路由自救（用户端 / 治理台两个入口共用）。
 *
 * SPA 入口 HTML 是 no-cache，但浏览器里已加载的旧入口引用的是旧内容哈希 chunk；
 * 重建部署删除旧 chunk 后，旧标签页内第一次懒加载路由（如治理台点「运营处置」）
 * 会静默失败——点击看起来毫无反应。nginx 对 /assets/ 显式 404（见 nginx.conf），
 * 这里在 router.onError 识别 chunk 加载失败后带目标路由标记整页刷新一次
 * （新入口必然引用现存 chunk），启动时再把用户送回他想去的页面。
 */

const RETRY_PARAM = 'chunk_retry'

/**
 * 各引擎的 chunk 失败报错方言不同：
 * Chrome「Failed to fetch dynamically imported module」、Firefox「error loading
 * dynamically imported module」、Safari「Importing a module script failed」。
 * 若服务器把缺失 chunk 回退成 200+HTML（本仓库 nginx 已修为 404，此为防御），
 * 报错为 MIME 拒绝「Failed to load module script: Expected a JavaScript module script…」。
 */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  return /dynamically imported module|Importing a module script|module script/i.test(message)
}

/**
 * router.onError 处理器：chunk 失败 → 带目标路径刷新一次。
 * URL 里已有重试标记则放弃——刷新后目标路由仍失败说明是真实故障，不能无限循环；
 * 标记只在恢复导航成功后由 clearChunkRetryMarker 清除，下次发版可再次自救。
 */
export function reloadOnChunkError(error: unknown, toFullPath: string): void {
  if (!isChunkLoadError(error)) return
  const url = new URL(window.location.href)
  if (url.searchParams.has(RETRY_PARAM)) return
  url.searchParams.set(RETRY_PARAM, toFullPath)
  window.location.replace(url.toString())
}

/**
 * 启动时读取重试目标：返回用户原本要去的路由路径（非 / 开头视为无效），
 * 不清除标记——清除必须等恢复导航成功（见 clearChunkRetryMarker）。
 */
export function readChunkRetryTarget(): string | null {
  const target = new URL(window.location.href).searchParams.get(RETRY_PARAM)
  if (target === null) return null
  return target.startsWith('/') ? target : null
}

/** 恢复导航成功后调用：从地址栏抹掉重试标记。 */
export function clearChunkRetryMarker(): void {
  const url = new URL(window.location.href)
  if (!url.searchParams.has(RETRY_PARAM)) return
  url.searchParams.delete(RETRY_PARAM)
  window.history.replaceState(window.history.state, '', url.toString())
}
