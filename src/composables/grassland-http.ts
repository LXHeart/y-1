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
  // 错误体解析：优先后端 {error}（不依赖 content-type——部分网关/测试 stub 不带 headers），
  // JSON 无 error 或不可解析时回退文本，最后 fallback。全程容忍缺字段的 stub。
  if (typeof response.json === 'function') {
    const body = await response.json().catch(() => null) as { error?: string } | null
    if (body?.error) return body.error
  }
  const text = typeof response.text === 'function' ? await response.text().catch(() => '') : ''
  return text.trim() || fallback
}

/** FormData/Blob/URLSearchParams 等非字符串主体由浏览器自动带正确的 Content-Type，覆写会破坏上传。 */
function shouldDefaultJsonContentType(body: BodyInit | null | undefined): boolean {
  return typeof body === 'string'
}

/** 统一请求选项：fallbackError 允许调用方保留原有的领域化失败文案（如「登录失败」）。 */
export interface RequestOptions {
  fallbackError?: string
}

/**
 * 统一请求：注入 cookie、解 `{success,data,error}` 信封、非 2xx 抛 {@link GrasslandHttpError}（保留
 * 状态码供 401/409 等分支）、信封失败抛带后端消息的 Error。JSON 解析容错：非 JSON 响应体（网关错误页
 * 等）回退为状态码文案，不再抛 SyntaxError。
 */
export async function request<T>(
  url: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  const response = await fetchApi(url, init)

  if (!response.ok) {
    throw new GrasslandHttpError(
      response.status,
      await readError(response, `请求失败（${response.status}）`),
    )
  }

  // 刻意用 json()+catch 而非 text() 再 parse：与既有测试的 fetch mock（仅提供 json()）兼容，
  // 同时对非 JSON 成功体（网关错误页/空 202）容错为格式错误，不抛 SyntaxError。
  let body: GrasslandResponse<T> | null
  try {
    body = await response.json() as GrasslandResponse<T>
  } catch {
    body = null
  }
  if (!body || typeof body.success !== 'boolean') {
    throw new GrasslandHttpError(response.status, options.fallbackError || '响应格式错误')
  }
  if (!body.success) {
    throw new Error(body.error || options.fallbackError || '请求失败')
  }
  return body.data as T
}

/** 统一请求（文本响应）：验证码 SVG 等非 JSON 端点；cookie 与非 2xx 语义同 {@link request}。 */
export async function requestText(url: string, init: RequestInit = {}): Promise<string> {
  const response = await fetchApi(url, init)
  if (!response.ok) {
    throw new GrasslandHttpError(
      response.status,
      await readError(response, `请求失败（${response.status}）`),
    )
  }
  return response.text()
}

/**
 * 带统一默认项的裸 fetch：注入 cookie、字符串主体默认 JSON Content-Type。
 * 只做传输层统一——SSE/流式读取与上传等由调用方拥有响应体，不在此解信封。
 */
export async function fetchApi(url: string, init: RequestInit = {}): Promise<Response> {
  return fetch(url, {
    credentials: 'include',
    ...init,
    headers: shouldDefaultJsonContentType(init.body)
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers || {},
  })
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
