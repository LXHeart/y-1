import { z } from 'zod'

export const adjustCreditsSchema = z.object({
  userId: z.string().uuid(),
  amount: z.number().int(),
  note: z.string().trim().min(1, '请输入备注').max(200),
})
