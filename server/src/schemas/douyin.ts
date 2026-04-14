import { z } from 'zod'

const allowedHosts = [
  'douyin.com',
  'www.douyin.com',
  'v.douyin.com',
  'iesdouyin.com',
  'www.iesdouyin.com',
]

function hasAllowedDouyinUrl(input: string): boolean {
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

export const extractDouyinVideoRequest = z.object({
  input: z.string().trim().min(1, '请输入抖音分享文本或链接').refine(hasAllowedDouyinUrl, '请输入包含有效抖音 HTTPS 链接的分享文本或链接'),
})

export const analyzeDouyinVideoRequest = z.object({
  proxyVideoUrl: z.string().trim().min(1, '缺少视频代理地址'),
})

export const analysisDouyinMediaRequestParams = z.object({
  id: z.string().trim().min(1, '缺少分析视频文件标识'),
})

export const proxyDouyinVideoRequestParams = z.object({
  token: z.string().trim().min(1, '缺少视频代理 token'),
})

export const douyinSessionResponseSchema = z.object({
  status: z.enum(['missing', 'launching', 'qr_ready', 'waiting_for_confirm', 'authenticated', 'expired', 'error']),
  hasPersistedSession: z.boolean(),
  qrImageUrl: z.string().optional(),
  detailCode: z.enum(['missing', 'launching', 'qr_ready', 'waiting_for_confirm', 'authenticated', 'timeout', 'session_expired', 'login_failed']).optional(),
  message: z.string().optional(),
  lastAuthenticatedAt: z.string().optional(),
  lastUsedAt: z.string().optional(),
})
