import { AppError } from './errors.js'
import { logger } from './logger.js'

const allowedDouyinPageHosts = new Set([
  'douyin.com',
  'www.douyin.com',
  'v.douyin.com',
  'iesdouyin.com',
  'www.iesdouyin.com',
])

const redirectStatusCodes = new Set([301, 302, 303, 307, 308])
const MAX_REDIRECT_HOPS = 5

function assertAllowedHost(url: string): void {
  const parsed = new URL(url)
  const hostname = parsed.hostname.toLowerCase()

  if (parsed.protocol !== 'https:') {
    throw new AppError('抖音链接跳转到了不安全的协议', 502)
  }

  if (!allowedDouyinPageHosts.has(hostname)) {
    logger.warn({ redirectHost: hostname, redirectPath: parsed.pathname }, 'Douyin request redirected to untrusted host')
    throw new AppError('抖音页面跳转到了不受信任的目标地址', 502)
  }
}

function getRedirectLocation(response: Response, currentUrl: string): string {
  const location = response.headers.get('location')
  if (!location) {
    throw new AppError('抖音链接返回了无效的跳转地址', 502)
  }

  return new URL(location, currentUrl).toString()
}

async function fetchWithValidatedRedirects(url: string, init: RequestInit): Promise<Response> {
  let currentUrl = url

  for (let hop = 0; hop <= MAX_REDIRECT_HOPS; hop += 1) {
    assertAllowedHost(currentUrl)

    const response = await fetch(currentUrl, {
      ...init,
      redirect: 'manual',
    })

    if (!redirectStatusCodes.has(response.status)) {
      return response
    }

    if (hop === MAX_REDIRECT_HOPS) {
      throw new AppError('抖音链接跳转次数过多', 502)
    }

    currentUrl = getRedirectLocation(response, currentUrl)
  }

  throw new AppError('抖音链接跳转次数过多', 502)
}

export async function fetchText(url: string, init: RequestInit, timeoutMs: number): Promise<{ finalUrl: string, body: string }> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  try {
    const response = await fetchWithValidatedRedirects(url, {
      ...init,
      signal: controller.signal,
    })

    if (!response.ok) {
      throw new AppError(`上游请求失败：${response.status}`, 502)
    }

    assertAllowedHost(response.url)

    return {
      finalUrl: response.url,
      body: await response.text(),
    }
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof Error && error.name === 'AbortError') {
      throw new AppError('请求抖音页面超时', 504)
    }

    throw new AppError('请求抖音页面失败', 502)
  } finally {
    clearTimeout(timer)
  }
}
