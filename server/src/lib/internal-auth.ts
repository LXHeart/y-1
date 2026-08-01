import type { NextFunction, Request, Response } from 'express'
import { AppError } from './errors.js'
import { env } from './env.js'

/**
 * 内部接口共享密钥鉴权（草场 intelligence → legacy credits 的过渡通道）。
 *
 * legacy 不识别草场 `X-Grassland-Identity` 断言（那是 Java 域内部契约），故内部服务到 legacy
 * 走共享密钥 `INTERNAL_API_KEY`。密钥未配置时 fail-closed（503）——杜绝未配置即裸奔。
 * 仅容器网络/localhost 可达该端点（不暴露给公网）。退役：草场 usage-account 落地后随 user_credits 退出。
 */
/**
 * 拒绝经反向代理到达的内部请求（GL-P0-CRED-001 纵深防御）。
 *
 * 内部端点只应由容器网络内的服务直连。`X-Forwarded-*` 的出现说明请求穿过了
 * 面向公网的代理跳数——即便 nginx 的 deny 被误改，这里仍 fail-closed。
 * 不用 IP 白名单：容器网段不固定，误判会静默切断 bridge。
 */
export function rejectForwardedRequest(req: Request, _res: Response, next: NextFunction): void {
  const forwardedHeaders = ['x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'forwarded']
  const hop = forwardedHeaders.find((header) => req.header(header))

  if (hop) {
    throw new AppError('内部接口不可经代理访问', 404)
  }
  next()
}

export function requireInternalKey(req: Request, _res: Response, next: NextFunction): void {
  if (!env.INTERNAL_API_KEY) {
    throw new AppError('内部接口未配置密钥', 503)
  }
  const provided = req.header('X-Internal-Key')
  if (!provided || provided !== env.INTERNAL_API_KEY) {
    throw new AppError('内部接口鉴权失败', 401)
  }
  next()
}
