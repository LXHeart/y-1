import { z } from 'zod'

export type ArticlePlatform = 'wechat' | 'zhihu' | 'xiaohongshu'

const platformSchema = z.enum(['wechat', 'zhihu', 'xiaohongshu']).default('wechat')

export const generateTitlesRequestSchema = z.object({
  topic: z.string().trim().min(1, '请输入主题或关键词').max(200),
  platform: platformSchema,
})

export const generateOutlineRequestSchema = z.object({
  topic: z.string().trim().min(1).max(200),
  title: z.string().trim().min(1, '请选择或输入标题').max(100),
  platform: platformSchema,
})

export const generateContentRequestSchema = z.object({
  topic: z.string().trim().min(1).max(200),
  title: z.string().trim().min(1, '请选择或输入标题').max(100),
  outline: z.string().trim().min(10, '大纲内容过短'),
  platform: platformSchema,
})

export const imageRecommendationRequestSchema = z.object({
  content: z.string().trim().min(10, '正文内容过短').max(20_000),
  outline: z.string().trim().min(1).max(10_000).optional(),
  platform: platformSchema,
})

export const imageSearchRequestSchema = z.object({
  keywords: z.string().trim().min(1, '请输入搜图关键词').max(200),
  count: z.number().int().min(1).max(10).default(3),
})

export const imageGenerateRequestSchema = z.object({
  prompt: z.string().trim().min(1, '请输入生图提示词').max(4_000),
  size: z.enum(['1024x1024', '1024x1792', '1792x1024']).default('1024x1024'),
})
