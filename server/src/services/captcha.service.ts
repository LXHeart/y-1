import crypto from 'node:crypto'
import { logger } from '../lib/logger.js'

const CAPTCHA_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789'
const CAPTCHA_LENGTH = 4
const CAPTCHA_TTL_MS = 5 * 60 * 1000

declare module 'express-session' {
  interface SessionData {
    captcha?: {
      text: string
      expiresAt: number
    }
  }
}

function randomChars(length: number): string {
  const bytes = crypto.randomBytes(length)
  return Array.from(bytes, (byte) => CAPTCHA_CHARS[byte % CAPTCHA_CHARS.length]).join('')
}

function randomColor(): string {
  const r = Math.floor(80 + Math.random() * 175)
  const g = Math.floor(80 + Math.random() * 175)
  const b = Math.floor(80 + Math.random() * 175)
  return `rgb(${r},${g},${b})`
}

function randomRotation(): number {
  return (Math.random() - 0.5) * 30
}

function escapeXml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function generateCaptcha(): { text: string; svg: string } {
  const text = randomChars(CAPTCHA_LENGTH)
  const width = 160
  const height = 60

  const charElements = text.split('').map((char, i) => {
    const x = 30 + i * 32
    const y = 38 + (Math.random() - 0.5) * 10
    const rotate = randomRotation()
    const fontSize = 28 + Math.floor(Math.random() * 6)
    return `<text x="${x}" y="${y}" fill="${randomColor()}" font-family="Arial,Helvetica,sans-serif" font-size="${fontSize}" font-weight="bold" transform="rotate(${rotate} ${x} ${y})">${escapeXml(char)}</text>`
  }).join('\n')

  const noiseLines = Array.from({ length: 4 }, () => {
    const x1 = Math.random() * width
    const y1 = Math.random() * height
    const x2 = Math.random() * width
    const y2 = Math.random() * height
    return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${randomColor()}" stroke-width="1" opacity="0.5"/>`
  }).join('\n')

  const noiseDots = Array.from({ length: 30 }, () => {
    const cx = Math.random() * width
    const cy = Math.random() * height
    return `<circle cx="${cx}" cy="${cy}" r="1.5" fill="${randomColor()}" opacity="0.4"/>`
  }).join('\n')

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <rect width="100%" height="100%" fill="#1a1a2e" rx="8"/>
  ${noiseLines}
  ${noiseDots}
  ${charElements}
</svg>`

  return { text, svg }
}

export function storeCaptcha(session: Record<string, unknown>, text: string): void {
  session.captcha = {
    text: text.toLowerCase(),
    expiresAt: Date.now() + CAPTCHA_TTL_MS,
  }
}

export function validateCaptcha(session: Record<string, unknown>, input: string): boolean {
  const captcha = session.captcha as { text: string; expiresAt: number } | undefined
  if (!captcha) {
    logger.warn('Captcha validation failed: no captcha in session')
    return false
  }

  delete session.captcha

  if (Date.now() > captcha.expiresAt) {
    logger.warn('Captcha validation failed: expired')
    return false
  }

  return captcha.text === input.toLowerCase().trim()
}
