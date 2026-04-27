import { z } from 'zod'

export const imageAnalysisExportRequestSchema = z.object({
  review: z.string().trim().min(1, '评价内容不能为空'),
  title: z.string().trim().max(200).optional().transform((v) => v || undefined),
  tags: z.string().trim().optional().transform((v) => {
    if (!v) return undefined
    try { return JSON.parse(v) } catch { return undefined }
  }),
  runId: z.string().trim().optional().transform((v) => v || undefined),
  platform: z.enum(['taobao', 'dianping']).default('taobao'),
  reviewLength: z.coerce.number().int().min(15).max(300).optional(),
  feelings: z.string().trim().max(200).optional().transform((v) => v || undefined),
})

export type ImageAnalysisExportInput = z.infer<typeof imageAnalysisExportRequestSchema>
