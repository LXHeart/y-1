import { access, chmod, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'
import type { Browser, BrowserContext, Page } from 'playwright'
import { launchDouyinLoginPage, readDouyinLoginState, saveBrowserStorageState } from '../lib/browser.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'

export type DouyinSessionStatus = 'missing' | 'launching' | 'qr_ready' | 'waiting_for_confirm' | 'authenticated' | 'expired' | 'error'

export interface DouyinSessionSnapshot {
  status: DouyinSessionStatus
  hasPersistedSession: boolean
  qrImageUrl?: string
  detailCode?: 'missing' | 'launching' | 'qr_ready' | 'waiting_for_confirm' | 'authenticated' | 'timeout' | 'session_expired' | 'login_failed'
  message?: string
  lastAuthenticatedAt?: string
  lastUsedAt?: string
}

interface DouyinLoginJob {
  browser: Browser
  context: BrowserContext
  page: Page
  status: Exclude<DouyinSessionStatus, 'missing'>
  startedAt: number
  qrImageUrl?: string
  message?: string
}

const storageStatePath = path.resolve(process.cwd(), env.DOUYIN_STORAGE_STATE_PATH)

let currentJob: DouyinLoginJob | null = null
let lastAuthenticatedAt: string | undefined
let lastUsedAt: string | undefined
let currentStatus: DouyinSessionStatus = 'missing'
let currentMessage: string | undefined

async function hasPersistedSession(): Promise<boolean> {
  try {
    await access(storageStatePath)
    return true
  } catch {
    return false
  }
}

async function ensureStorageDirectory(): Promise<void> {
  const directoryPath = path.dirname(storageStatePath)
  await mkdir(directoryPath, { recursive: true, mode: 0o700 })
  await chmod(directoryPath, 0o700).catch(() => undefined)
}

async function clearCurrentJob(): Promise<void> {
  if (!currentJob) {
    return
  }

  const job = currentJob
  currentJob = null

  await job.page.close().catch(() => undefined)
  await job.context.close().catch(() => undefined)
  await job.browser.close().catch(() => undefined)
}

function getDefaultDetailCode(status: DouyinSessionStatus): DouyinSessionSnapshot['detailCode'] {
  switch (status) {
    case 'expired':
      return 'session_expired'
    case 'error':
      return 'login_failed'
    default:
      return status
  }
}

function buildSnapshot(overrides: Partial<DouyinSessionSnapshot> = {}): DouyinSessionSnapshot {
  const status = overrides.status ?? currentStatus
  const message = overrides.message ?? currentMessage

  return {
    status,
    hasPersistedSession: false,
    detailCode: overrides.detailCode ?? getDefaultDetailCode(status),
    message,
    lastAuthenticatedAt,
    lastUsedAt,
    ...overrides,
  }
}

async function persistAuthenticatedJob(job: DouyinLoginJob): Promise<DouyinSessionSnapshot> {
  await ensureStorageDirectory()
  await saveBrowserStorageState(job.context, storageStatePath)
  await chmod(storageStatePath, 0o600).catch(() => undefined)
  lastAuthenticatedAt = new Date().toISOString()
  currentStatus = 'authenticated'
  currentMessage = '抖音登录成功，后端已保存可复用会话。'

  await clearCurrentJob()

  return buildSnapshot({
    status: 'authenticated',
    detailCode: 'authenticated',
    hasPersistedSession: true,
    message: currentMessage,
    lastAuthenticatedAt,
  })
}

async function refreshCurrentJob(): Promise<DouyinSessionSnapshot | null> {
  if (!currentJob) {
    return null
  }

  if (Date.now() - currentJob.startedAt > env.DOUYIN_LOGIN_TIMEOUT_MS) {
    currentStatus = 'expired'
    currentMessage = '扫码登录已超时，请重新生成二维码。'
    await clearCurrentJob()

    return buildSnapshot({
      status: 'expired',
      detailCode: 'timeout',
      hasPersistedSession: await hasPersistedSession(),
      message: currentMessage,
    })
  }

  try {
    const state = await readDouyinLoginState(currentJob.context, currentJob.page)
    currentJob.status = state.status
    currentJob.qrImageUrl = state.qrImageUrl
    currentJob.message = state.message
    currentStatus = state.status
    currentMessage = state.message

    if (state.status === 'authenticated') {
      return persistAuthenticatedJob(currentJob)
    }

    return buildSnapshot({
      status: state.status,
      detailCode: getDefaultDetailCode(state.status),
      hasPersistedSession: await hasPersistedSession(),
      qrImageUrl: state.qrImageUrl,
      message: state.message,
    })
  } catch (error: unknown) {
    logger.warn({
      err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown error' },
    }, 'Douyin login session refresh failed')
    currentStatus = 'error'
    currentMessage = error instanceof Error ? error.message : '扫码登录流程失败，请重试。'
    await clearCurrentJob()

    return buildSnapshot({
      status: 'error',
      detailCode: 'login_failed',
      hasPersistedSession: await hasPersistedSession(),
      message: currentMessage,
    })
  }
}

export async function getDouyinSessionSnapshot(): Promise<DouyinSessionSnapshot> {
  const activeSnapshot = await refreshCurrentJob()
  if (activeSnapshot) {
    return activeSnapshot
  }

  const persisted = await hasPersistedSession()
  if (persisted) {
    currentStatus = currentStatus === 'expired' ? 'expired' : 'authenticated'
    currentMessage = currentStatus === 'expired'
      ? currentMessage || '登录态已失效，请重新扫码。'
      : '后端已保存可复用的抖音登录态。'

    return buildSnapshot({
      status: currentStatus,
      detailCode: currentStatus === 'expired' ? 'session_expired' : 'authenticated',
      hasPersistedSession: true,
      message: currentMessage,
    })
  }

  if (currentStatus !== 'expired' && currentStatus !== 'error') {
    currentStatus = 'missing'
    currentMessage = '当前没有可用的抖音登录态。'
  }

  return buildSnapshot({
    status: currentStatus,
    hasPersistedSession: false,
    message: currentMessage,
  })
}

export async function startDouyinSession(): Promise<DouyinSessionSnapshot> {
  await clearCurrentJob()

  try {
    const { browser, context, page } = await launchDouyinLoginPage({
      loginUrl: env.DOUYIN_LOGIN_URL,
      desktopUserAgent: env.DOUYIN_COOKIE_USER_AGENT || env.DOUYIN_USER_AGENT,
    })

    currentJob = {
      browser,
      context,
      page,
      status: 'launching',
      startedAt: Date.now(),
      message: '正在打开抖音登录页。',
    }
    currentStatus = 'launching'
    currentMessage = '正在打开抖音登录页。'

    const snapshot = await refreshCurrentJob()
    if (!snapshot) {
      throw new AppError('抖音登录页启动失败', 502)
    }

    return snapshot
  } catch (error: unknown) {
    currentStatus = 'error'
    currentMessage = error instanceof Error ? error.message : '启动抖音扫码登录失败。'
    await clearCurrentJob()

    return buildSnapshot({
      status: 'error',
      hasPersistedSession: await hasPersistedSession(),
      message: currentMessage,
    })
  }
}

export async function pollDouyinSession(): Promise<DouyinSessionSnapshot> {
  return getDouyinSessionSnapshot()
}

export async function logoutDouyinSession(): Promise<DouyinSessionSnapshot> {
  await clearCurrentJob()
  await rm(storageStatePath, { force: true }).catch(() => undefined)
  currentStatus = 'missing'
  currentMessage = '已断开抖音登录态。'
  lastAuthenticatedAt = undefined
  lastUsedAt = undefined

  return buildSnapshot({
    status: 'missing',
    detailCode: 'missing',
    hasPersistedSession: false,
    message: currentMessage,
    lastAuthenticatedAt: undefined,
    lastUsedAt: undefined,
  })
}

export async function markDouyinSessionUsed(): Promise<void> {
  if (!(await hasPersistedSession())) {
    return
  }

  lastUsedAt = new Date().toISOString()
  if (currentStatus !== 'expired') {
    currentStatus = 'authenticated'
    currentMessage = '后端已保存可复用的抖音登录态。'
  }
}

export async function markDouyinSessionExpired(message = '当前登录态疑似失效，请重新扫码登录。'): Promise<void> {
  await rm(storageStatePath, { force: true }).catch(() => undefined)
  currentStatus = 'expired'
  currentMessage = message
}

export async function getPersistedDouyinStorageStatePath(): Promise<string | undefined> {
  return (await hasPersistedSession()) ? storageStatePath : undefined
}
