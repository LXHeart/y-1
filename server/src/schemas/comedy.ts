import { z } from 'zod'

export const comedyScriptRequestSchema = z.object({
  topic: z.string().trim().min(1, '请输入题材').max(200),
  duration: z.number().int().min(30).max(300).default(60),
})
