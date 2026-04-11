import { isAllowedBilibiliVideoHost } from '../lib/bilibili-hosts.js'
import { AppError } from '../lib/errors.js'

const bilibiliUpstreamRedirectStatusCodes = new Set([301, 302, 303, 307, 308])

export function assertAllowedBilibiliMediaUrl(url: string): void {
  const parsed = new URL(url)
  if (parsed.protocol !== 'https:' || !isAllowedBilibiliVideoHost(parsed.hostname)) {
    throw new AppError('视频地址不受信任', 400)
  }
}

export function shouldFollowBilibiliMediaRedirect(statusCode: number): boolean {
  return bilibiliUpstreamRedirectStatusCodes.has(statusCode)
}

export function getSingleHeaderValue(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] || '' : value || ''
}

export function resolveBilibiliMediaRedirectUrl(currentUrl: string, locationHeader: string | string[] | undefined): string {
  const location = Array.isArray(locationHeader) ? locationHeader[0] : locationHeader
  if (!location) {
    throw new AppError('Bilibili upstream returned redirect without location', 502)
  }

  const redirectUrl = new URL(location, currentUrl).toString()
  assertAllowedBilibiliMediaUrl(redirectUrl)
  return redirectUrl
}
