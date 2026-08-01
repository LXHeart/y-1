import type { RequestHandler, Request } from 'express'
import { env } from './env.js'

/**
 * 公网安全响应头（GL-P0-AUTH-001）。
 *
 * 只放「与部署形态无关、不会因业务改动失效」的头。Nginx 侧对静态资源也发同一组
 * （见 `nginx.conf`）；两处都有是有意的：直连 backend:3000 的部署没有 nginx。
 *
 * 有意不做 CSP：当前前端是 Vite 打包的 Vue 单页，含内联样式与运行时注入，
 * 上一层没有 nonce 通道，贸然加 CSP 只会把页面打碎或退化成 `unsafe-inline`（等于没加）。
 * CSP 需要单独一项来做（收集违规 → report-only → 强制）。
 */
const NO_STORE_PATH_PREFIXES = ['/api/auth/', '/api/admin/', '/api/settings']

function isHttpsRequest(req: Request): boolean {
  // req.secure 在 `trust proxy` 生效时会读 X-Forwarded-Proto；否则只看本地连接。
  if (req.secure) {
    return true
  }

  const forwarded = req.headers['x-forwarded-proto']
  const first = Array.isArray(forwarded) ? forwarded[0] : forwarded
  if (!first) {
    return false
  }

  return first.split(',')[0]!.trim().toLowerCase() === 'https'
}

export function createSecurityHeaders(): RequestHandler {
  const hstsEnabled = env.SECURITY_HSTS_ENABLED === '1'
  const hstsValue = `max-age=${env.SECURITY_HSTS_MAX_AGE_SECONDS}; includeSubDomains`

  return (req, res, next) => {
    res.removeHeader('X-Powered-By')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('X-Frame-Options', 'DENY')
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
    // 关掉不需要的强权限特性，减少被嵌入/滥用面。
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()')
    res.setHeader('Cross-Origin-Opener-Policy', 'same-origin')

    // HSTS 双条件：显式开关 + 本次请求确实是 HTTPS。
    // 只看开关会在 HTTP 部署上发出无效（且危险）的 HSTS —— 浏览器仍会记住策略。
    if (hstsEnabled && isHttpsRequest(req)) {
      res.setHeader('Strict-Transport-Security', hstsValue)
    }

    // 认证/管理/设置响应不进任何缓存：这些 body 含用户身份与配置。
    if (NO_STORE_PATH_PREFIXES.some((prefix) => req.path.startsWith(prefix))) {
      res.setHeader('Cache-Control', 'no-store')
    }

    next()
  }
}
