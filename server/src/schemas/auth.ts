import { z } from 'zod'

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

const emailSchema = z.string().trim().email('请输入有效邮箱地址').transform(normalizeEmail)
const passwordSchema = z.string().min(8, '密码至少需要 8 个字符').max(200, '密码长度不能超过 200 个字符')
const displayNameSchema = z.string().trim().min(1, '请输入显示名称').max(100, '显示名称不能超过 100 个字符')

export const loginRequestSchema = z.object({
  email: emailSchema,
  password: passwordSchema,
})

export const registerRequestSchema = z.object({
  email: emailSchema,
  password: passwordSchema,
  confirmPassword: passwordSchema,
  displayName: displayNameSchema,
  verificationCode: z.string().trim().length(6, '验证码为 6 位数字'),
}).refine((value) => value.password === value.confirmPassword, {
  message: '两次输入的密码不一致',
  path: ['confirmPassword'],
})

export const sendCodeRequestSchema = z.object({
  email: emailSchema,
  captchaCode: z.string().trim().min(1, '请输入图形验证码'),
})

export const createAdminRequestSchema = z.object({
  email: emailSchema,
  password: passwordSchema,
  displayName: displayNameSchema,
})

export type LoginRequest = z.infer<typeof loginRequestSchema>
export type RegisterRequest = z.infer<typeof registerRequestSchema>
export type CreateAdminRequest = z.infer<typeof createAdminRequestSchema>
