/**
 * 草场 HTTP 基础设施 —— 共享请求封装、轮询常量与 presigned 上传。
 */
import type { GrasslandResponse, MediaUploadTicket } from '../types/grassland'

/** 轮询上限：Saga 经 Temporal + 跨服务 HTTP，本地通常 <2s；给 30 次 × 1s 容错。 */
export const POLL_MAX_ATTEMPTS = 30
export const POLL_INTERVAL_MS = 1000

/** 保留 HTTP 状态，供乐观锁冲突等需要按状态分支的交互使用。 */
export class GrasslandHttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'GrasslandHttpError'
  }
}

export async function readError(response: Response, fallback: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json() as { error?: string }
    return body.error || fallback
  }
  const text = await response.text()
  return text.trim() || fallback
}

/** 统一请求：注入 cookie、解信封、非 2xx 抛带后端消息的 Error。 */
export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: init.body
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers || {},
  })

  if (!response.ok) {
    throw new GrasslandHttpError(
      response.status,
      await readError(response, `请求失败（${response.status}）`),
    )
  }

  const body = await response.json() as GrasslandResponse<T>
  if (!body.success) {
    throw new Error(body.error || '请求失败')
  }
  return body.data as T
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 第二步：把文件直传到 presigned URL。
 *
 * ⚠️ **刻意不走 {@link request}**，三处都不能照抄本站请求的写法：
 * 1. 目标是 MinIO/S3（nginx CORS 反代 `:9002`）而非本站——presigned PUT 的鉴权是签名里的 SigV4，
 *    本就不需要 cookie，故刻意不带 `credentials`；nginx 的 CORS 策略（`ce53cfb` 后唯一来源，故意不回
 *    `Access-Control-Allow-Credentials`）也配合这一点——带了反而被浏览器拦。
 * 2. 只回放 ticket 给的 header。多加任何一个（如 `Authorization`）都不在 SigV4 的 SignedHeaders 里 → 403。
 * 3. 响应体是**空的 / XML 错误**，不是 `{success,data}` 信封——不能拿 `request` 的 json 解析路径去解。
 */
export async function putToPresignedUrl(ticket: MediaUploadTicket, file: File): Promise<void> {
  const response = await fetch(ticket.uploadUrl, {
    method: ticket.method || 'PUT',
    headers: ticket.headers || {},
    body: file,
  })
  if (!response.ok) {
    throw new Error(`附件上传失败（${response.status}）——凭据可能已过期，请重试`)
  }
}

/** `run()` 包装器的类型签名（域文件共享）。 */
export type RunFn = <T>(operation: () => Promise<T>) => Promise<T | null>
