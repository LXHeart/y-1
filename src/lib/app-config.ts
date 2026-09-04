/**
 * 跨应用 origin 解析（任务书 #76）：草场（index.html）与 AI 创作中心（ai.html）是两个 origin。
 *
 * 生产：nginx 各 server 块经 envsubst 生成 `/app-config.js`（location = /app-config.js），
 * 注入 `window.__GRASSLAND_APP_CONFIG__`（AI_APP_ORIGIN / GRASSLAND_ORIGIN 部署变量）。
 * dev：单 vite 服务器同源服务 /ai.html，public/app-config.js 提供空缺省 → 同源推导，
 * cookie 天然共享（跨应用 token 流程在部署形态/compose 栈下才真正跨 origin）。
 */
export interface GrasslandAppConfig {
  /** AI 创作中心 origin（如 http://127.0.0.1:8084）；空 = 同源 dev 形态。 */
  aiAppOrigin: string
  /** 草场用户端 origin（如 http://127.0.0.1:8080）；空 = 同源。 */
  grasslandOrigin: string
}

declare global {
  interface Window {
    __GRASSLAND_APP_CONFIG__?: Partial<GrasslandAppConfig>
  }
}

function withParams(base: string, params?: Record<string, string>): string {
  if (!params) return base
  const url = new URL(base)
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') url.searchParams.set(key, value)
  }
  return url.toString()
}

/** AI 应用链接。dev 回落到同源 `/ai.html`（vite 多页入口）。 */
export function aiAppHref(path = '/', params?: Record<string, string>): string {
  const configured = window.__GRASSLAND_APP_CONFIG__?.aiAppOrigin?.trim()
  const base = configured
    ? `${configured.replace(/\/+$/, '')}${path}`
    : `${window.location.origin}/ai.html`
  return withParams(base, params)
}

/** 草场用户端链接。AI 应用内「打开草场」等回跳使用；dev 同源直连。 */
export function grasslandAppHref(path = '/', params?: Record<string, string>): string {
  const configured = window.__GRASSLAND_APP_CONFIG__?.grasslandOrigin?.trim()
  const base = configured
    ? `${configured.replace(/\/+$/, '')}${path}`
    : `${window.location.origin}${path}`
  return withParams(base, params)
}
