import { env } from '../lib/env.js'
import { z } from 'zod'

const allowedHosts = [
  'www.bilibili.com',
  'bilibili.com',
  'm.bilibili.com',
  'b23.tv',
]

function hasAllowedBilibiliUrl(input: string): boolean {
  const matches = input.match(/https:\/\/[^\s]+/g) || []

  return matches.some((value) => {
    try {
      const parsed = new URL(value.replace(/[),.;!?]+$/g, ''))
      return parsed.protocol === 'https:' && allowedHosts.includes(parsed.hostname.toLowerCase())
    } catch {
      return false
    }
  })
}

export const extractBilibiliVideoRequest = z.object({
  input: z.string().trim().min(1, '请输入包含 B 站链接的分享文本或链接').refine(hasAllowedBilibiliUrl, '请输入包含有效 B 站 HTTPS 链接的分享文本或链接'),
})

export const proxyBilibiliVideoRequestParams = z.object({
  token: z.string().trim().min(1, '缺少视频代理凭证'),
})

function isAllowedAnalyzeProxyUrl(value: string): boolean {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(value, 'http://localhost')
  } catch {
    return false
  }

  if (parsedUrl.origin !== 'http://localhost') {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      return false
    }

    try {
      if (parsedUrl.origin !== new URL(env.PUBLIC_BACKEND_ORIGIN).origin) {
        return false
      }
    } catch {
      return false
    }
  }

  return /^\/api\/bilibili\/proxy\/[^/]+$/u.test(parsedUrl.pathname)
}

export const analyzeBilibiliVideoRequest = z.object({
  proxyVideoUrl: z.string().trim().min(1, '缺少可分析的视频地址').refine(isAllowedAnalyzeProxyUrl, '视频代理地址无效'),
})

export const analysisBilibiliMediaRequestParams = z.object({
  id: z.string().trim().min(1, '缺少分析视频文件标识'),
})
