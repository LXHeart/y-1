import { config as loadDotenv } from 'dotenv'
import { z } from 'zod'

loadDotenv()

function normalizeOrigin(value: string | undefined): string | undefined {
  if (!value) {
    return undefined
  }

  const trimmedValue = value.trim()
  if (!trimmedValue) {
    return undefined
  }

  const parsedUrl = new URL(trimmedValue)
  return parsedUrl.toString().replace(/\/$/, '')
}

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  CORS_ORIGIN: z.string().default('http://localhost:5173'),
  DOUYIN_FETCH_TIMEOUT_MS: z.coerce.number().int().positive().default(15000),
  DOUYIN_LOGIN_TIMEOUT_MS: z.coerce.number().int().positive().default(180000),
  DOUYIN_LOGIN_URL: z.string().url().default('https://www.douyin.com/'),
  DOUYIN_STORAGE_STATE_PATH: z.string().trim().default('server/.data/douyin-storage-state.json'),
  DOUYIN_MEDIA_PROCESS_TIMEOUT_MS: z.coerce.number().int().positive().default(45000),
  DOUYIN_MEDIA_TEMP_DIR: z.string().trim().default('server/.data/douyin-audio'),
  BILIBILI_MEDIA_TEMP_DIR: z.string().trim().default('server/.data/bilibili-media'),
  DOUYIN_USER_AGENT: z.string().default(
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
  ),
  DOUYIN_COOKIE_USER_AGENT: z.string().trim().optional().transform((value) => value || undefined),
  DOUYIN_PROXY_TOKEN_SECRET: z.string().min(32, 'DOUYIN_PROXY_TOKEN_SECRET must be at least 32 characters'),
  PUBLIC_BACKEND_ORIGIN: z.string().trim().optional().transform((value) => normalizeOrigin(value)),
  VIDEO_ANALYSIS_API_BASE_URL: z.string().trim().default('https://g3xqktww2r.coze.site/run'),
  VIDEO_ANALYSIS_API_PATH: z.string().trim().default(''),
  VIDEO_ANALYSIS_API_TOKEN: z.string().trim().optional().transform((value) => value || undefined),
  VIDEO_ANALYSIS_API_TIMEOUT_MS: z.coerce.number().int().positive().default(60000),
  BILIBILI_FETCH_TIMEOUT_MS: z.coerce.number().int().positive().default(15000),
  BILIBILI_USER_AGENT: z.string().default(
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
  ),
  BILIBILI_PROXY_TOKEN_SECRET: z.string().min(32, 'BILIBILI_PROXY_TOKEN_SECRET must be at least 32 characters'),
  FFMPEG_PATH: z.string().trim().default('ffmpeg'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  TRUST_PROXY: z.enum(['0', '1']).optional(),
})

export const env = envSchema.parse(process.env)
