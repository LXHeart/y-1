import { chmodSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { env } from '../lib/env.js'
import { logger } from '../lib/logger.js'
import { isAuthConfigured } from '../lib/auth.js'
import { loadUserSettingsRecord, saveUserSettingsRecord } from './user-settings.service.js'
import {
  analysisSettingsSchema,
  homepageSettingsSchema,
  type AnalysisSettings,
  type AnalysisProvider,
  type UpdateAnalysisSettingsRequest,
  type HomepageSettings,
  type UpdateHomepageSettingsRequest,
} from '../schemas/settings.js'

const DEFAULT_SETTINGS: AnalysisSettings = {
  features: {
    video: { provider: 'coze' },
    image: {},
    article: {},
    imageGeneration: {},
  },
  integrations: {
    feishu: {},
  },
}

const DEFAULT_HOMEPAGE_SETTINGS: HomepageSettings = {
  hotItems: {
    provider: '60s',
  },
}

function getSettingsPath(): string {
  if (env.ANALYSIS_SETTINGS_PATH) {
    return env.ANALYSIS_SETTINGS_PATH
  }
  return resolve(process.cwd(), 'server/.data/analysis-settings.json')
}

function normalizeLegacySettings(raw: unknown): AnalysisSettings {
  if (typeof raw !== 'object' || raw === null) {
    return DEFAULT_SETTINGS
  }

  const record = raw as Record<string, unknown>
  const legacyProvider = record.provider === 'qwen' ? 'qwen' : 'coze'
  const rawFeatures = typeof record.features === 'object' && record.features !== null
    ? record.features as Record<string, unknown>
    : undefined
  const rawProviders = typeof record.providers === 'object' && record.providers !== null
    ? record.providers as Record<string, unknown>
    : undefined
  const rawCoze = typeof rawProviders?.coze === 'object' && rawProviders.coze !== null
    ? rawProviders.coze as Record<string, unknown>
    : undefined
  const rawQwen = typeof rawProviders?.qwen === 'object' && rawProviders.qwen !== null
    ? rawProviders.qwen as Record<string, unknown>
    : undefined
  const legacyQwenModel = typeof rawQwen?.model === 'string' ? rawQwen.model : undefined

  const video = normalizeVideoFeature(rawFeatures?.video, legacyProvider, rawCoze, rawQwen, legacyQwenModel)
  const image = normalizeQwenFeature(rawFeatures?.image, rawQwen, legacyQwenModel)
  const article = normalizeQwenFeature(rawFeatures?.article, rawQwen, legacyQwenModel)
  const imageGeneration = normalizeQwenFeature(rawFeatures?.imageGeneration, undefined, undefined)

  const rawIntegrations = typeof record.integrations === 'object' && record.integrations !== null
    ? record.integrations as Record<string, unknown>
    : undefined
  const rawFeishu = typeof rawIntegrations?.feishu === 'object' && rawIntegrations.feishu !== null
    ? rawIntegrations.feishu as Record<string, unknown>
    : undefined

  const feishu = {
    appId: typeof rawFeishu?.appId === 'string' ? rawFeishu.appId : undefined,
    appSecret: typeof rawFeishu?.appSecret === 'string' ? rawFeishu.appSecret : undefined,
    folderToken: typeof rawFeishu?.folderToken === 'string' ? rawFeishu.folderToken : undefined,
  }

  return analysisSettingsSchema.parse({
    integrations: { feishu },
    features: {
      video,
      image,
      article,
      imageGeneration,
    },
  })
}

function normalizeVideoFeature(
  rawFeature: unknown,
  legacyProvider: AnalysisProvider,
  rawCoze: Record<string, unknown> | undefined,
  rawQwen: Record<string, unknown> | undefined,
  legacyQwenModel: string | undefined,
): AnalysisSettings['features']['video'] {
  if (typeof rawFeature !== 'object' || rawFeature === null) {
    return legacyProvider === 'qwen'
      ? {
          provider: 'qwen',
          baseUrl: typeof rawQwen?.baseUrl === 'string' ? rawQwen.baseUrl : undefined,
          apiKey: typeof rawQwen?.apiKey === 'string' ? rawQwen.apiKey : undefined,
          model: legacyQwenModel,
        }
      : {
          provider: 'coze',
          baseUrl: typeof rawCoze?.baseUrl === 'string' ? rawCoze.baseUrl : undefined,
          apiToken: typeof rawCoze?.apiToken === 'string' ? rawCoze.apiToken : undefined,
        }
  }

  const record = rawFeature as Record<string, unknown>
  const provider = record.provider === 'qwen' ? 'qwen' : record.provider === 'coze' ? 'coze' : legacyProvider

  if (provider === 'qwen') {
    return {
      provider,
      baseUrl: typeof record.baseUrl === 'string'
        ? record.baseUrl
        : typeof rawQwen?.baseUrl === 'string'
          ? rawQwen.baseUrl
          : undefined,
      apiKey: typeof record.apiKey === 'string'
        ? record.apiKey
        : typeof rawQwen?.apiKey === 'string'
          ? rawQwen.apiKey
          : undefined,
      model: typeof record.model === 'string' ? record.model : legacyQwenModel,
      apiToken: typeof record.apiToken === 'string' ? record.apiToken : undefined,
    }
  }

  return {
    provider,
    baseUrl: typeof record.baseUrl === 'string'
      ? record.baseUrl
      : typeof rawCoze?.baseUrl === 'string'
        ? rawCoze.baseUrl
        : undefined,
    apiToken: typeof record.apiToken === 'string'
      ? record.apiToken
      : typeof rawCoze?.apiToken === 'string'
        ? rawCoze.apiToken
        : undefined,
    apiKey: typeof record.apiKey === 'string' ? record.apiKey : undefined,
    model: typeof record.model === 'string' ? record.model : undefined,
  }
}

function normalizeQwenFeature(
  rawFeature: unknown,
  rawQwen: Record<string, unknown> | undefined,
  legacyQwenModel: string | undefined,
): AnalysisSettings['features']['image'] {
  if (typeof rawFeature !== 'object' || rawFeature === null) {
    return {
      baseUrl: typeof rawQwen?.baseUrl === 'string' ? rawQwen.baseUrl : undefined,
      apiKey: typeof rawQwen?.apiKey === 'string' ? rawQwen.apiKey : undefined,
      model: legacyQwenModel,
    }
  }

  const record = rawFeature as Record<string, unknown>
  return {
    baseUrl: typeof record.baseUrl === 'string'
      ? record.baseUrl
      : typeof rawQwen?.baseUrl === 'string'
        ? rawQwen.baseUrl
        : undefined,
    apiKey: typeof record.apiKey === 'string'
      ? record.apiKey
      : typeof rawQwen?.apiKey === 'string'
        ? rawQwen.apiKey
        : undefined,
    model: typeof record.model === 'string' ? record.model : legacyQwenModel,
  }
}

export function loadSettings(): AnalysisSettings {
  const settingsPath = getSettingsPath()

  try {
    const raw = readFileSync(settingsPath, 'utf-8')
    const parsed = JSON.parse(raw)
    return normalizeLegacySettings(parsed)
  } catch (error: unknown) {
    if (isEnoent(error)) {
      return DEFAULT_SETTINGS
    }

    logger.warn({ err: error, settingsPath }, 'Failed to load analysis settings')
    throw new Error('分析设置文件不可用')
  }
}

export async function loadSettingsForUser(userId?: string): Promise<AnalysisSettings> {
  if (!userId || !isAuthConfigured()) {
    return loadSettings()
  }

  const rawSettings = await loadUserSettingsRecord(userId, 'analysis')
  if (typeof rawSettings === 'undefined') {
    return DEFAULT_SETTINGS
  }

  return normalizeLegacySettings(rawSettings)
}

const MASKED_SECRET_PATTERN = /^\*{4}.*$/u

function hasOwnKey(object: object | undefined, key: string): boolean {
  return Boolean(object) && Object.prototype.hasOwnProperty.call(object, key)
}

function resolveUpdatedSecret(
  currentValue: string | undefined,
  nextSettings: Record<string, string | AnalysisProvider | undefined> | undefined,
  key: string,
): string | undefined {
  if (!hasOwnKey(nextSettings, key)) {
    return currentValue
  }

  const nextValue = nextSettings?.[key]
  if (typeof nextValue !== 'string' || MASKED_SECRET_PATTERN.test(nextValue)) {
    return currentValue
  }

  if (nextValue === '') {
    return undefined
  }

  return nextValue
}

function resolveUpdatedValue(
  currentValue: string | undefined,
  nextSettings: Record<string, string | AnalysisProvider | undefined> | undefined,
  key: string,
): string | undefined {
  if (!hasOwnKey(nextSettings, key)) {
    return currentValue
  }

  const nextValue = nextSettings?.[key]
  return typeof nextValue === 'string' ? nextValue : undefined
}

function resolveUpdatedProvider<T extends string>(
  currentValue: T,
  nextSettings: Record<string, string | AnalysisProvider | undefined> | undefined,
  key: string,
): T {
  if (!hasOwnKey(nextSettings, key)) {
    return currentValue
  }

  const nextValue = nextSettings?.[key]
  return (typeof nextValue === 'string' ? nextValue : undefined) as T | undefined ?? currentValue
}

function pruneVideoSettings(settings: AnalysisSettings['features']['video']): AnalysisSettings['features']['video'] {
  return settings.provider === 'qwen'
    ? {
        provider: settings.provider,
        ...(settings.baseUrl ? { baseUrl: settings.baseUrl } : {}),
        ...(settings.apiKey ? { apiKey: settings.apiKey } : {}),
        ...(settings.model ? { model: settings.model } : {}),
      }
    : {
        provider: settings.provider,
        ...(settings.baseUrl ? { baseUrl: settings.baseUrl } : {}),
        ...(settings.apiToken ? { apiToken: settings.apiToken } : {}),
      }
}

function pruneQwenFeatureSettings<T extends AnalysisSettings['features']['image']>(settings: T): T {
  return {
    ...(settings.baseUrl ? { baseUrl: settings.baseUrl } : {}),
    ...(settings.apiKey ? { apiKey: settings.apiKey } : {}),
    ...(settings.model ? { model: settings.model } : {}),
  } as T
}

export function mergeAnalysisSettings(
  currentSettings: AnalysisSettings,
  nextSettings: UpdateAnalysisSettingsRequest,
): AnalysisSettings {
  const nextFeatures = nextSettings.features ?? {}

  const mergedVideo = pruneVideoSettings({
    provider: resolveUpdatedProvider(
      currentSettings.features.video.provider,
      nextFeatures.video,
      'provider',
    ),
    baseUrl: resolveUpdatedValue(
      currentSettings.features.video.baseUrl,
      nextFeatures.video,
      'baseUrl',
    ),
    apiToken: resolveUpdatedSecret(
      currentSettings.features.video.apiToken,
      nextFeatures.video,
      'apiToken',
    ),
    apiKey: resolveUpdatedSecret(
      currentSettings.features.video.apiKey,
      nextFeatures.video,
      'apiKey',
    ),
    model: resolveUpdatedValue(
      currentSettings.features.video.model,
      nextFeatures.video,
      'model',
    ),
  })

  const mergedImage = pruneQwenFeatureSettings({
    baseUrl: resolveUpdatedValue(
      currentSettings.features.image.baseUrl,
      nextFeatures.image,
      'baseUrl',
    ),
    apiKey: resolveUpdatedSecret(
      currentSettings.features.image.apiKey,
      nextFeatures.image,
      'apiKey',
    ),
    model: resolveUpdatedValue(
      currentSettings.features.image.model,
      nextFeatures.image,
      'model',
    ),
  })

  const mergedArticle = pruneQwenFeatureSettings({
    baseUrl: resolveUpdatedValue(
      currentSettings.features.article.baseUrl,
      nextFeatures.article,
      'baseUrl',
    ),
    apiKey: resolveUpdatedSecret(
      currentSettings.features.article.apiKey,
      nextFeatures.article,
      'apiKey',
    ),
    model: resolveUpdatedValue(
      currentSettings.features.article.model,
      nextFeatures.article,
      'model',
    ),
  })

  const mergedImageGeneration = pruneQwenFeatureSettings({
    baseUrl: resolveUpdatedValue(
      currentSettings.features.imageGeneration.baseUrl,
      nextFeatures.imageGeneration,
      'baseUrl',
    ),
    apiKey: resolveUpdatedSecret(
      currentSettings.features.imageGeneration.apiKey,
      nextFeatures.imageGeneration,
      'apiKey',
    ),
    model: resolveUpdatedValue(
      currentSettings.features.imageGeneration.model,
      nextFeatures.imageGeneration,
      'model',
    ),
  })

  const nextIntegrations = nextSettings.integrations ?? {}
  const nextFeishu = nextIntegrations.feishu

  const currentFeishu = currentSettings.integrations?.feishu ?? {}

  const mergedFeishu = {
    appId: resolveUpdatedValue(currentFeishu.appId, nextFeishu as Record<string, string | undefined> | undefined, 'appId'),
    appSecret: resolveUpdatedSecret(currentFeishu.appSecret, nextFeishu as Record<string, string | undefined> | undefined, 'appSecret'),
    folderToken: resolveUpdatedValue(currentFeishu.folderToken, nextFeishu as Record<string, string | undefined> | undefined, 'folderToken'),
  }

  return analysisSettingsSchema.parse({
    integrations: { feishu: mergedFeishu },
    features: {
      video: mergedVideo,
      image: mergedImage,
      article: mergedArticle,
      imageGeneration: mergedImageGeneration,
    },
  })
}

export function saveSettings(settings: AnalysisSettings): void {
  const validated = analysisSettingsSchema.parse(settings)
  const settingsPath = getSettingsPath()
  const dir = dirname(settingsPath)

  mkdirSync(dir, { recursive: true })

  const tmpPath = settingsPath + '.tmp'
  writeFileSync(tmpPath, JSON.stringify(validated, null, 2), 'utf-8')
  applyOwnerOnlyPermissions(tmpPath)

  try {
    renameSync(tmpPath, settingsPath)
    applyOwnerOnlyPermissions(settingsPath)
  } catch (renameErr) {
    try { unlinkSync(tmpPath) } catch { /* best effort */ }
    throw renameErr
  }

  logger.info({
    settingsPath,
    features: maskSettingsSecrets(validated).features,
  }, 'Analysis settings saved')
}

export async function saveSettingsForUser(userId: string | undefined, settings: AnalysisSettings): Promise<void> {
  const validated = analysisSettingsSchema.parse(settings)

  if (!userId || !isAuthConfigured()) {
    saveSettings(validated)
    return
  }

  await saveUserSettingsRecord(userId, 'analysis', validated)
  logger.info({ userId, features: maskSettingsSecrets(validated).features }, 'Analysis settings saved for user')
}

function applyOwnerOnlyPermissions(filePath: string): void {
  try {
    chmodSync(filePath, 0o600)
  } catch (error: unknown) {
    logger.warn({ err: error, filePath }, 'Failed to set restrictive permissions on analysis settings file')
  }
}

function isEnoent(error: unknown): boolean {
  return error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === 'ENOENT'
}

function maskSecret(value: string | undefined): string | undefined {
  if (!value || value.length <= 4) {
    return value ? '****' : undefined
  }
  return '****' + value.slice(-4)
}

export function maskSettingsSecrets(settings: AnalysisSettings): AnalysisSettings {
  const feishu = settings.integrations?.feishu

  return {
    integrations: {
      feishu: {
        ...(feishu?.appId ? { appId: feishu.appId } : {}),
        ...(feishu?.appSecret ? { appSecret: maskSecret(feishu.appSecret) } : {}),
        ...(feishu?.folderToken ? { folderToken: feishu.folderToken } : {}),
      },
    },
    features: {
      video: {
        provider: settings.features.video.provider,
        ...(settings.features.video.baseUrl ? { baseUrl: settings.features.video.baseUrl } : {}),
        ...(settings.features.video.apiToken ? { apiToken: maskSecret(settings.features.video.apiToken) } : {}),
        ...(settings.features.video.apiKey ? { apiKey: maskSecret(settings.features.video.apiKey) } : {}),
        ...(settings.features.video.model ? { model: settings.features.video.model } : {}),
      },
      image: {
        ...(settings.features.image.baseUrl ? { baseUrl: settings.features.image.baseUrl } : {}),
        ...(settings.features.image.apiKey ? { apiKey: maskSecret(settings.features.image.apiKey) } : {}),
        ...(settings.features.image.model ? { model: settings.features.image.model } : {}),
      },
      article: {
        ...(settings.features.article.baseUrl ? { baseUrl: settings.features.article.baseUrl } : {}),
        ...(settings.features.article.apiKey ? { apiKey: maskSecret(settings.features.article.apiKey) } : {}),
        ...(settings.features.article.model ? { model: settings.features.article.model } : {}),
      },
      imageGeneration: {
        ...(settings.features.imageGeneration.baseUrl ? { baseUrl: settings.features.imageGeneration.baseUrl } : {}),
        ...(settings.features.imageGeneration.apiKey ? { apiKey: maskSecret(settings.features.imageGeneration.apiKey) } : {}),
        ...(settings.features.imageGeneration.model ? { model: settings.features.imageGeneration.model } : {}),
      },
    },
  }
}

function getHomepageSettingsPath(): string {
  if (env.ANALYSIS_SETTINGS_PATH) {
    const basePath = env.ANALYSIS_SETTINGS_PATH
    return basePath.replace(/\.json$/, '') + '-homepage.json'
  }
  return resolve(process.cwd(), 'server/.data/homepage-settings.json')
}

export function loadHomepageSettings(): HomepageSettings {
  const settingsPath = getHomepageSettingsPath()

  try {
    const raw = readFileSync(settingsPath, 'utf-8')
    const parsed = JSON.parse(raw)
    return homepageSettingsSchema.parse(parsed)
  } catch (error: unknown) {
    if (isEnoent(error)) {
      return DEFAULT_HOMEPAGE_SETTINGS
    }

    logger.warn({ err: error, settingsPath }, 'Failed to load homepage settings')
    throw new Error('首页设置文件不可用')
  }
}

export async function loadHomepageSettingsForUser(userId?: string): Promise<HomepageSettings> {
  if (!userId || !isAuthConfigured()) {
    return loadHomepageSettings()
  }

  const rawSettings = await loadUserSettingsRecord(userId, 'homepage')
  if (typeof rawSettings === 'undefined') {
    return DEFAULT_HOMEPAGE_SETTINGS
  }

  return homepageSettingsSchema.parse(rawSettings)
}

export function mergeHomepageSettings(
  currentSettings: HomepageSettings,
  nextSettings: UpdateHomepageSettingsRequest,
): HomepageSettings {
  const nextHotItems = nextSettings.hotItems

  if (!nextHotItems) {
    return currentSettings
  }

  const mergedProvider = resolveUpdatedProvider(
    currentSettings.hotItems.provider,
    nextHotItems as unknown as Record<string, string | undefined>,
    'provider',
  )

  const mergedAlapiToken = resolveUpdatedSecret(
    currentSettings.hotItems.alapiToken,
    nextHotItems as unknown as Record<string, string | undefined>,
    'alapiToken',
  )

  return homepageSettingsSchema.parse({
    hotItems: {
      provider: mergedProvider,
      ...(mergedAlapiToken ? { alapiToken: mergedAlapiToken } : {}),
    },
  })
}

export function saveHomepageSettings(settings: HomepageSettings): void {
  const validated = homepageSettingsSchema.parse(settings)
  const settingsPath = getHomepageSettingsPath()
  const dir = dirname(settingsPath)

  mkdirSync(dir, { recursive: true })

  const tmpPath = settingsPath + '.tmp'
  writeFileSync(tmpPath, JSON.stringify(validated, null, 2), 'utf-8')
  applyOwnerOnlyPermissions(tmpPath)

  try {
    renameSync(tmpPath, settingsPath)
    applyOwnerOnlyPermissions(settingsPath)
  } catch (renameErr) {
    try { unlinkSync(tmpPath) } catch { /* best effort */ }
    throw renameErr
  }

  logger.info({
    settingsPath,
    homepage: maskHomepageSettingsSecrets(validated),
  }, 'Homepage settings saved')
}

export async function saveHomepageSettingsForUser(userId: string | undefined, settings: HomepageSettings): Promise<void> {
  const validated = homepageSettingsSchema.parse(settings)

  if (!userId || !isAuthConfigured()) {
    saveHomepageSettings(validated)
    return
  }

  await saveUserSettingsRecord(userId, 'homepage', validated)
  logger.info({ userId, homepage: maskHomepageSettingsSecrets(validated) }, 'Homepage settings saved for user')
}

export function maskHomepageSettingsSecrets(settings: HomepageSettings): HomepageSettings {
  return {
    hotItems: {
      provider: settings.hotItems.provider,
      ...(settings.hotItems.alapiToken ? { alapiToken: maskSecret(settings.hotItems.alapiToken) } : {}),
    },
  }
}
