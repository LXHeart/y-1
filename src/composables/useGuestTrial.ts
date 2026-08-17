/**
 * 游客有限体验（任务书 #36 / ADR-D14）：未登录可试用 3 项白名单 AI 能力。
 *
 * SSE 消费复用平台既有 fetch + getReader 模式（`data: ` 帧解析、`[DONE]` 终止）——trial 端点是
 * 一次性短流（progress → result/error），无断点续传需求。失败语义锁定（R5）：IP 限流 SSE 前判
 * （HTTP 429）；额度用尽/provider 失败在流内以 `{error, code}` 帧返回。
 */
import { ref } from 'vue'

export type GuestTrialCapability = 'article-titles' | 'content-score' | 'image-review'

export interface GuestTrialQuotaItem {
  used: number
  limit: number
  remaining: number
}

export interface GuestTrialQuota {
  capabilities: Record<string, GuestTrialQuotaItem>
  signupBonusCredits: number
}

export interface GuestTrialRunResult {
  /** provider 成功的 JSON 载荷（能力各自结构）；error 时为空。 */
  result: Record<string, unknown> | null
  /** error 帧的 code（quota_exhausted / provider_error）；成功为空。 */
  errorCode: string | null
  errorMessage: string | null
}

const quota = ref<GuestTrialQuota | null>(null)
const loading = ref(false)
const error = ref('')

async function refreshQuota(): Promise<void> {
  error.value = ''
  loading.value = true
  try {
    const response = await fetch('/api/guest-trial/quota', { credentials: 'include' })
    if (response.status === 404) {
      quota.value = null
      return
    }
    if (!response.ok) throw new Error(`额度加载失败（HTTP ${response.status}）`)
    const body = (await response.json()) as { data: GuestTrialQuota }
    quota.value = body.data
  } catch (e) {
    error.value = e instanceof Error ? e.message : '额度加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 跑一次试用。返回帧聚合结果；额度用尽时 errorCode=quota_exhausted（组件据此弹登录引导）。
 * IP 限流（HTTP 429）与网络失败都以 errorCode 返回，不在组件里区分 UI。
 */
async function runTrial(
  capability: GuestTrialCapability,
  body: Record<string, string>,
): Promise<GuestTrialRunResult> {
  const empty: GuestTrialRunResult = { result: null, errorCode: null, errorMessage: null }
  try {
    const response = await fetch(`/api/guest-trial/${capability}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (response.status === 429) {
      return { ...empty, errorCode: 'rate_limited', errorMessage: '尝试过于频繁，请稍后再试' }
    }
    if (!response.ok || !response.body) {
      return { ...empty, errorCode: 'provider_error', errorMessage: `请求失败（HTTP ${response.status}）` }
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    const outcome: GuestTrialRunResult = { result: null, errorCode: null, errorMessage: null }
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let index: number
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        if (!frame.startsWith('data: ')) continue
        const payload = frame.slice(6).trim()
        if (payload === '[DONE]') return outcome
        try {
          const parsed = JSON.parse(payload) as Record<string, unknown>
          if ('error' in parsed) {
            outcome.errorCode = String(parsed.code ?? 'provider_error')
            outcome.errorMessage = String(parsed.error ?? '生成失败')
            return outcome
          }
          if ('result' in parsed) {
            outcome.result = parsed.result as Record<string, unknown>
          }
        } catch {
          // 单帧解析失败跳过（防御脏帧）
        }
      }
    }
    return outcome
  } catch (e) {
    return { ...empty, errorCode: 'provider_error', errorMessage: e instanceof Error ? e.message : '网络错误' }
  }
}

export function useGuestTrial() {
  return { quota, loading, error, refreshQuota, runTrial }
}
