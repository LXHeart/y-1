import nodemailer from 'nodemailer'
import { env } from './env.js'
import { logger } from './logger.js'

interface MailOptions {
  to: string
  subject: string
  text: string
  html: string
}

let cachedTransporter: nodemailer.Transporter | null = null

function getTransporter(): nodemailer.Transporter | null {
  if (!env.SMTP_HOST) {
    return null
  }

  if (cachedTransporter) {
    return cachedTransporter
  }

  cachedTransporter = nodemailer.createTransport({
    host: env.SMTP_HOST,
    port: env.SMTP_PORT,
    secure: env.SMTP_PORT === 465,
    auth: env.SMTP_USER
      ? { user: env.SMTP_USER, pass: env.SMTP_PASS }
      : undefined,
  })

  return cachedTransporter
}

export async function sendMail(options: MailOptions): Promise<void> {
  const transporter = getTransporter()

  if (!transporter) {
    logger.warn({ to: options.to }, 'SMTP not configured, skipping email send')
    return
  }

  const fromAddress = env.SMTP_FROM || env.SMTP_USER || 'noreply@example.com'

  await transporter.sendMail({
    from: fromAddress,
    to: options.to,
    subject: options.subject,
    text: options.text,
    html: options.html,
  })

  logger.info({ to: options.to, subject: options.subject }, 'Email sent successfully')
}

export function isEmailConfigured(): boolean {
  return !!env.SMTP_HOST
}

export async function sendVerificationEmail(email: string, code: string): Promise<void> {
  const subject = '邮箱验证码'
  const text = `你的验证码是：${code}，5 分钟内有效。`
  const html = `
<div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
  <h2 style="margin: 0 0 24px; font-size: 20px; color: #1a1a2e;">邮箱验证</h2>
  <p style="margin: 0 0 16px; font-size: 15px; color: #4a4a6a;">你的验证码是：</p>
  <div style="display: inline-block; padding: 12px 24px; background: #f0f0ff; border-radius: 8px; font-size: 28px; font-weight: 700; letter-spacing: 6px; color: #6c3ce0;">${code}</div>
  <p style="margin: 16px 0 0; font-size: 13px; color: #888;">验证码 5 分钟内有效，如非本人操作请忽略此邮件。</p>
</div>`

  await sendMail({ to: email, subject, text, html })
}
