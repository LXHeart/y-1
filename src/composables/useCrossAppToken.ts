/**
 * 跨应用一次性免登（任务书 #76 卡 A 前端侧）：草场 ⇄ AI 应用共用同一对端点、同一套动作。
 *
 * - 签发 `POST /api/auth/cross-app-tokens`（需登录会话）→ 不透明随机串，TTL 5 分钟；
 * - 跳转 URL 统一用 `xat` 参数（参数名定死）；
 * - 核销 `POST /api/auth/cross-app-tokens/exchange`（原子单次，GETDEL）→ 目标方 set-cookie 建会话；
 * - 核销后必须 `history.replaceState` 清参，防浏览器历史/分享泄漏。
 */
import { request } from './grassland-http'
import { aiAppHref, grasslandAppHref } from '../lib/app-config'
import { useAuth } from './useAuth'

/** 从当前 URL 移除指定 query 参数（replaceState，不留历史痕迹）。 */
export function stripUrlParams(names: string[]): void {
  const url = new URL(window.location.href)
  let changed = false
  for (const name of names) {
    if (url.searchParams.has(name)) {
      url.searchParams.delete(name)
      changed = true
    }
  }
  if (changed) window.history.replaceState(window.history.state, '', url)
}

type ConsumeOutcome = 'none' | 'exchanged' | 'failed'

/**
 * 核销 URL 中的 `xat`（存在时）。成功/失败都清参——失败落游客态由页面登录入口接住，
 * 参数残留只会造成刷新重放（必 401）与分享泄漏。
 */
export async function consumeCrossAppTokenFromUrl(): Promise<ConsumeOutcome> {
  const token = new URLSearchParams(window.location.search).get('xat')
  if (!token) return 'none'
  try {
    await request<{ token: string; expiresInSeconds: number }>(
      '/api/auth/cross-app-tokens/exchange',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) },
    )
    stripUrlParams(['xat'])
    return 'exchanged'
  } catch {
    stripUrlParams(['xat'])
    return 'failed'
  }
}

/**
 * 跨应用跳转：已登录先签发 token（拼 `xat`）实现免登；未登录/签发失败直接跳，
 * 目标方以游客态/登录页接住（AI 应用游客可试用，D1）。
 */
export function useCrossAppJump(): {
  jumpToAiApp: (path?: string, params?: Record<string, string>) => Promise<void>
  jumpToGrassland: (path?: string, params?: Record<string, string>) => Promise<void>
} {
  const { isAuthenticated } = useAuth()

  async function jump(build: (params: Record<string, string>) => string, params: Record<string, string> = {}): Promise<void> {
    let withToken = params
    if (isAuthenticated.value) {
      try {
        const issued = await request<{ token: string; expiresInSeconds: number }>(
          '/api/auth/cross-app-tokens', { method: 'POST' })
        withToken = { ...params, xat: issued.token }
      } catch {
        // 未登录边缘竞态或后端不可用：不带 token 跳，目标方登录页兜底，不阻断跳转
      }
    }
    window.location.href = build(withToken)
  }

  return {
    jumpToAiApp: (path = '/', params) => jump((next) => aiAppHref(path, next), params),
    jumpToGrassland: (path = '/', params) => jump((next) => grasslandAppHref(path, next), params),
  }
}
