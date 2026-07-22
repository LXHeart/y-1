import { z } from 'zod'

export const INDUSTRY_TYPES = [
  '餐饮',
  '零售',
  '美业',
  '健身',
  '教育培训',
  '其他',
] as const

export const VIDEO_STYLES = [
  '烟火纪实',
  '治愈清新',
  '高级暗调',
  '数字人口播',
  '复古胶片',
] as const

export const generateScriptRequestSchema = z.object({
  images: z.array(z.string().min(1, '图片数据不能为空')).min(1, '请至少上传 1 张图片').max(9, '最多上传 9 张图片'),
  shopName: z.string().trim().min(1, '请输入店铺名称').max(100),
  industryType: z.enum(INDUSTRY_TYPES, { message: '请选择行业类型' }),
  shopAddress: z.string().trim().max(200).optional(),
  shopDescription: z.string().trim().max(500).optional(),
  videoStyle: z.enum(VIDEO_STYLES, { message: '请选择视频风格' }),
  customPrompt: z.string().trim().max(500).optional(),
})

export const generateVideoRequestSchema = z.object({
  script: z.string().trim().min(10, '脚本内容太短').max(5000),
  images: z.array(z.string().min(1)).min(1, '请至少上传 1 张图片').max(9, '最多上传 9 张图片'),
  videoStyle: z.enum(VIDEO_STYLES, { message: '请选择视频风格' }),
  shopName: z.string().trim().min(1),
  shopAddress: z.string().trim().max(200).optional(),
})

export type GenerateScriptRequest = z.infer<typeof generateScriptRequestSchema>
export type GenerateVideoRequest = z.infer<typeof generateVideoRequestSchema>
