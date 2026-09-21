/**
 * 草场 HTTP 基础设施 —— 共享请求封装、轮询常量与 presigned 上传。
 */
import type { GrasslandResponse, MediaUploadTicket } from '../types/grassland'

/** 轮询上限：Saga 经 Temporal + 跨服务 HTTP，本地通常 <2s；给 30 次 × 1s 容错。 */
export const POLL_MAX_ATTEMPTS = 30
export const POLL_INTERVAL_MS = 1000

/** 保留 HTTP 状态，供乐观锁冲突等需要按状态分支的交互使用。 */
export class GrasslandHttpError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string,
    /** 任务书 #103 §6.1：服务端错误信封的机器可读 blockedReason（如 analytics_facts_incomplete）。 */
    public readonly blockedReason?: string) {
    super(message)
    this.name = 'GrasslandHttpError'
  }
}

/** 错误详情（#104 C104-08 / D07）：message 为有界展示文本；code/blockedReason 只收 string。 */
interface ErrorDetails {
  message: string
  code?: string
  blockedReason?: string
}

const MAX_MESSAGE_CODE_POINTS = 500

/** 展示文本清洗：去除换行/tab 外的控制字符；≤500 code point，超长截断加省略号（总长≤500）。 */
function sanitizeMessage(raw: string): string {
  const chars = Array.from(raw).filter((char) => char === '\n' || char === '\t' || char.charCodeAt(0) >= 0x20)
  if (chars.length <= MAX_MESSAGE_CODE_POINTS) return chars.join('')
  return chars.slice(0, MAX_MESSAGE_CODE_POINTS - 1).join('') + '…'
}

/** HTML/XML 猜测：Content-Type 声明或起始标签（网关错误页不回显为富文本）。 */
function looksLikeMarkup(contentType: string | null, text: string): boolean {
  if (contentType && /html|xml/i.test(contentType)) return true
  return /^\s*<(!doctype|html|head|body|svg|\?xml)/i.test(text)
}

/** 解析错误信封：仅接受以 {/[ 开头的合法 JSON；损坏/非对象返回 null。 */
function parseErrorEnvelope(text: string):
  | { error?: unknown; code?: unknown; blockedReason?: unknown }
  | null {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
  try {
    const parsed = JSON.parse(trimmed)
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

/**
 * 错误体只消费一次（D07）：真实 Response 优先 text() 一次再 JSON.parse；仅旧测试
 * stub 缺 text 且有 json 时允许单次 json 分支。不 clone、不先 json 后 text。
 * 空体/读取失败/HTML/损坏 JSON/合法非错误 JSON → fallback 文案；error 非 string 时
 * 仍保留合法机器字段（code/blockedReason 只收 string）；普通纯文本经清洗后保留。
 * 不记录原始错误 body，调用方不得以 v-html 呈现。
 */
async function readErrorDetails(response: Response, fallback: string): Promise<ErrorDetails> {
  const contentType = typeof response.headers?.get === 'function' ? response.headers.get('content-type') : null
  let text = ''
  if (typeof response.text === 'function') {
    text = await response.text().catch(() => '')
  } else if (typeof response.json === 'function') {
    // 旧测试 stub 兼容分支：单次 json，等价往返文本。
    const body = await response.json().catch(() => null)
    text = body === null || body === undefined ? '' : JSON.stringify(body)
  }
  const trimmed = text.trim()
  if (trimmed === '') return { message: fallback }
  if (looksLikeMarkup(contentType, text)) return { message: fallback }
  const envelope = parseErrorEnvelope(text)
  if (envelope) {
    const message = typeof envelope.error === 'string' && envelope.error.trim() !== ''
      ? sanitizeMessage(envelope.error)
      : fallback
    return {
      message,
      code: typeof envelope.code === 'string' ? envelope.code : undefined,
      blockedReason: typeof envelope.blockedReason === 'string' ? envelope.blockedReason : undefined,
    }
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    // 以 {/[ 开头却损坏的 JSON → fallback（不把残缺结构当纯文本回显）。
    return { message: fallback }
  }
  if (isPlainJsonValue(trimmed)) {
    // 合法非错误 JSON（标量/null）→ fallback，不当普通文本展示。
    return { message: fallback }
  }
  return { message: sanitizeMessage(trimmed) || fallback }
}

/** 整体是合法 JSON 标量（数字/布尔/null 等，无机器字段可提取）。 */
function isPlainJsonValue(trimmed: string): boolean {
  try {
    JSON.parse(trimmed)
    return true
  } catch {
    return false
  }
}

export async function readError(response: Response, fallback: string): Promise<string> {
  // 与 readErrorDetails 同一解析器（两入口共用）；仅返回展示文本。
  return (await readErrorDetails(response, fallback)).message
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
    const failure = await readErrorDetails(response, `请求失败（${response.status}）`)
    throw new GrasslandHttpError(
      response.status,
      failure.message,
      failure.code,
      failure.blockedReason,
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
    const failure = await readErrorDetails(response, `请求失败（${response.status}）`)
    throw new GrasslandHttpError(response.status, failure.message, failure.code, failure.blockedReason)
  }
  return response.text()
}

/**
 * 统一请求（裸 JSON）：站内**非信封** JSON 端点（如 `/api/guest-trial/quota`）的唯一读取口。
 *
 * ⚠️ 新代码 MUST NOT 手写 `fetchApi()` + `response.json()` 自行判断响应形态——裸 JSON 读取一律走本口
 * （任务书 #87 D-01：把「禁止调用方自行判断响应形态」从口头约定变成唯一出口的成文路径）。
 * SSE/流式读取与预签名上传不适用（各自拥有响应体/签名语义，见 {@link fetchApi}/{@link putToPresignedUrl}）。
 *
 * 非 2xx 抛 {@link GrasslandHttpError}（保留状态码，语义与 {@link request} 完全一致）；2xx 解 JSON
 * **原样返回不判 `success`**——信封形态对象、`null`、数组、负数一律不判不改，业务成败归调用方契约；
 * 2xx 坏 JSON（含 204 空体）抛 `GrasslandHttpError(status, fallbackError || '响应格式错误')`。
 */
export async function requestRaw<T>(
  url: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  const response = await fetchApi(url, init)
  if (!response.ok) {
    const failure = await readErrorDetails(response, options.fallbackError || `请求失败（${response.status}）`)
    throw new GrasslandHttpError(response.status, failure.message, failure.code, failure.blockedReason)
  }
  // 与 request 同款 json()+catch 容错；parsed 标志区分「解析得 null」（合法返回）与「解析失败」。
  let parsed = false
  let body: unknown
  try {
    body = await response.json()
    parsed = true
  } catch {
    // 解析失败保持 parsed=false（204 空体/网关错误页 → 格式错误分支）
  }
  if (!parsed) {
    throw new GrasslandHttpError(response.status, options.fallbackError || '响应格式错误')
  }
  return body as T
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
    // 按状态码分文案：413=文件过大（nginx/MinIO 拒收，与凭据无关）；403=预签名过期；
    // 其余通用。全站直传唯一出口，5 个 composable 调用面一处全覆盖。
    if (response.status === 413) {
      throw new Error('文件过大，超出大小上限（图片 ≤10MB / 视频 ≤20MB），请压缩后重试')
    }
    if (response.status === 403) {
      throw new Error(`附件上传失败（${response.status}）——凭据可能已过期，请重试`)
    }
    throw new Error(`附件上传失败（${response.status}），请重试`)
  }
}

/** `run()` 包装器的类型签名（域文件共享）。 */
export type RunFn = <T>(operation: () => Promise<T>) => Promise<T | null>
