import type { RequestHandler, Request } from 'express'
import { env } from './env.js'

/**
 * 状态变更请求的 Origin/Referer 校验（GL-P0-AUTH-001）。
 *
 * 会话是 cookie 承载的，`SameSite=Lax` 已挡掉跨站 POST 携带 cookie 的常见路径，
 * 但它不是完备的 CSRF 防护：`Lax` 只约束 cookie 发送，不校验请求来源，且
 * 同站不同子域、以及部分老旧/非标准客户端行为都可能绕过。这里补一层来源校验。
 *
 * 为什么不用 token（synchronizer / double-submit）：
 * 前端全部走 `fetch` + JSON/SSE，没有传统 form POST；Origin 校验对这类客户端是可靠的
 * （浏览器强制发 Origin 且不可由脚本伪造），且不需要在每个前端调用点分发 token。
 *
 * 允许无 Origin 且无 Referer 的请求（curl、服务端调用、健康检查）：
 * 这类请求不是浏览器发起的，不存在「被害者浏览器自动带上 cookie」的前提，
 * 拦它们只会打断非浏览器集成而不增加安全性。**有 Origin 就必须匹配**才是关键约束。
 */
const STATE_CHANGING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

function normalizeOrigin(value: string): string | undefined {
  try {
    return new URL(value).origin
  } catch {
    return undefined
  }
}

/**
 * 本次请求的自身 origin，用于放行同源请求。
 *
 * 不用 `req.secure`：它只在 `trust proxy` 生效时才看 X-Forwarded-Proto。若漏配
 * `TRUST_PROXY=1`，HTTPS 入口下自身 origin 会被算成 `http://host`，与浏览器发来的
 * `https://host` 不匹配 —— 后果是所有状态变更请求 403（把配置疏漏放大成功能故障）。
 * 这里直接读转发头：它只用于放宽同源判断，且 Host 仍是客户端实际请求的域名，
 * 伪造 proto 只能让攻击者把自己的 origin 声明成同源的另一个 scheme，拿不到跨站能力。
 */
function selfOrigin(req: Request): string | undefined {
  const host = req.headers.host
  if (!host) {
    return undefined
  }

  const forwarded = req.headers['x-forwarded-proto']
  const first = Array.isArray(forwarded) ? forwarded[0] : forwarded
  const forwardedProto = first?.split(',')[0]?.trim().toLowerCase()
  const proto = forwardedProto || (req.secure ? 'https' : 'http')
  return normalizeOrigin(`${proto}://${host}`)
}

export interface CsrfOriginCheckOptions {
  /** CORS 白名单（`CORS_ORIGIN` + 开发态 localhost），与 app.ts 复用同一份。 */
  allowedOrigins: string[]
}

export function createCsrfOriginCheck({ allowedOrigins }: CsrfOriginCheckOptions): RequestHandler {
  const enabled = (env.SECURITY_CSRF_ORIGIN_CHECK ?? '1') === '1'
  const configured = new Set(allowedOrigins.map(normalizeOrigin).filter((value): value is string => Boolean(value)))
  if (env.PUBLIC_BACKEND_ORIGIN) {
    const publicOrigin = normalizeOrigin(env.PUBLIC_BACKEND_ORIGIN)
    if (publicOrigin) {
      configured.add(publicOrigin)
    }
  }

  return (req, res, next) => {
    if (!enabled || !STATE_CHANGING_METHODS.has(req.method)) {
      next()
      return
    }

    const rawOrigin = req.headers.origin
    const origin = typeof rawOrigin === 'string' && rawOrigin !== 'null' ? normalizeOrigin(rawOrigin) : undefined
    // Origin 缺失时回退 Referer：少数浏览器/代理在同站导航型请求上只发 Referer。
    const rawReferer = req.headers.referer
    const referer = typeof rawReferer === 'string' ? normalizeOrigin(rawReferer) : undefined
    const candidate = origin ?? referer

    if (!candidate) {
      // 非浏览器客户端（无 Origin 也无 Referer）——见文件头说明。
      next()
      return
    }

    // 同源始终允许：前端与 API 同域部署时 Origin 就是自身，不必要求写进 CORS_ORIGIN。
    const self = selfOrigin(req)
    if (candidate === self || configured.has(candidate)) {
      next()
      return
    }

    res.status(403).json({
      success: false,
      error: '请求来源不被允许。',
    })
  }
}
