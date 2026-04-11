import { AppError } from './errors.js'
import { logger } from './logger.js'

const allowedDouyinPageHosts = new Set([
  'douyin.com',
  'www.douyin.com',
  'v.douyin.com',
  'iesdouyin.com',
  'www.iesdouyin.com',
])

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

export async function fetchText(url: string, init: RequestInit, timeoutMs: number): Promise<{ finalUrl: string, body: string }> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)

  try {
    let nextUrl = url

    for (let redirectCount = 0; redirectCount < 5; redirectCount += 1) {
      const response = await fetch(nextUrl, {
        ...init,
        redirect: 'manual',
        signal: controller.signal,
      })

      if (response.status >= 300 && response.status < 400) {
        const location = response.headers.get('location')
        if (!location) {
          throw new AppError('抖音链接返回了无效的跳转地址', 502)
        }

        nextUrl = new URL(location, nextUrl).toString()
        assertAllowedHost(nextUrl)
        continue
      }

      const body = await response.text()
      return {
        finalUrl: nextUrl,
        body,
      }
    }

    throw new AppError('抖音链接跳转次数过多', 502)
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('请求抖音页面超时', 504)
    }

    logger.error({ err: error, targetUrl: url }, 'Failed to fetch douyin text')
    throw new AppError('请求抖音页面失败', 502)
  } finally {
    clearTimeout(timeout)
  }
}
