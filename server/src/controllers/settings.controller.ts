import type { Request, Response, NextFunction } from 'express'
import { logger } from '../lib/logger.js'
import { AppError } from '../lib/errors.js'
import { getAuthenticatedUser } from '../lib/auth.js'
import { loadSettings, loadSettingsForUser, saveSettingsForUser, maskSettingsSecrets, mergeAnalysisSettings, loadHomepageSettings, loadHomepageSettingsForUser, saveHomepageSettingsForUser, mergeHomepageSettings, maskHomepageSettingsSecrets } from '../services/analysis-settings.service.js'
import { resolveFeatureProviderConfig } from '../services/video-analysis.service.js'
import { getProvider } from '../services/providers/index.js'
import {
  updateAnalysisSettingsRequest,
  listModelsRequestSchema,
  verifyModelRequestSchema,
  updateHomepageSettingsRequest,
  type AnalysisFeature,
  type AnalysisProvider,
} from '../schemas/settings.js'

function isUnavailableSettingsError(error: unknown): error is Error {
  return error instanceof Error && (error.message === '分析设置文件不可用' || error.message === '首页设置文件不可用')
}

function resolveFeatureProvider(
  feature: AnalysisFeature,
  provider: AnalysisProvider | undefined,
  currentSettings: Awaited<ReturnType<typeof loadSettingsForUser>>,
): AnalysisProvider {
  if (feature === 'video') {
    return provider ?? currentSettings.features.video.provider
  }

  if (provider && provider !== 'qwen') {
    const featureLabel = feature === 'image'
      ? '图片分析'
      : feature === 'article'
        ? '文章创作'
        : '图片生成'
    throw new AppError(`${featureLabel}仅支持 Qwen 模型`, 400)
  }

  return 'qwen'
}

export async function getAnalysisSettingsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const settings = await loadSettingsForUser(user.id)
    const responseData = maskSettingsSecrets(settings)

    res.json({ success: true, data: responseData })
  } catch (error: unknown) {
    if (isUnavailableSettingsError(error)) {
      next(new AppError('加载设置失败，请检查服务端分析设置文件', 500))
      return
    }

    next(error)
  }
}

export async function updateAnalysisSettingsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = updateAnalysisSettingsRequest.safeParse(req.body)

    if (!parsed.success) {
      throw new AppError(parsed.error.issues.map((i) => i.message).join('; '), 400)
    }

    const currentSettings = await loadSettingsForUser(user.id)
    const mergedSettings = mergeAnalysisSettings(currentSettings, parsed.data)

    await saveSettingsForUser(user.id, mergedSettings)

    const masked = maskSettingsSecrets(mergedSettings)
    res.json({ success: true, data: masked })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    logger.error({ err: error }, 'Failed to save analysis settings')
    next(new AppError('保存设置失败', 500))
  }
}

export async function listModelsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = listModelsRequestSchema.safeParse(req.body)
    if (!parsed.success) {
      throw new AppError(parsed.error.issues.map((i) => i.message).join('; '), 400)
    }

    const currentSettings = await loadSettingsForUser(user.id)
    const providerId = resolveFeatureProvider(parsed.data.feature, parsed.data.provider, currentSettings)
    const provider = getProvider(providerId)

    if (!provider.supportsModelListing || !provider.listModels) {
      throw new AppError(`${provider.label} 不支持模型列表获取`, 400)
    }

    const config = await resolveFeatureProviderConfig(parsed.data.feature, providerId, user.id, {
      requireModel: false,
    })
    const models = await provider.listModels(config)

    res.json({ success: true, data: { models } })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    logger.error({ err: error }, 'Failed to list models')
    next(new AppError('获取模型列表失败', 502))
  }
}

export async function verifyModelHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = verifyModelRequestSchema.safeParse(req.body)
    if (!parsed.success) {
      throw new AppError(parsed.error.issues.map((i) => i.message).join('; '), 400)
    }

    const currentSettings = await loadSettingsForUser(user.id)
    const providerId = resolveFeatureProvider(parsed.data.feature, parsed.data.provider, currentSettings)
    const provider = getProvider(providerId)

    if (!provider.verifyModel) {
      throw new AppError(`${provider.label} 不支持模型验证`, 400)
    }

    const config = await resolveFeatureProviderConfig(parsed.data.feature, providerId, user.id)
    await provider.verifyModel(config, parsed.data.model, { feature: parsed.data.feature })

    res.json({ success: true, data: { verified: true, modelId: parsed.data.model } })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    logger.error({ err: error }, 'Failed to verify model')
    next(new AppError('模型验证失败', 502))
  }
}

export async function getHomepageSettingsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const settings = await loadHomepageSettingsForUser(user.id)
    const responseData = maskHomepageSettingsSecrets(settings)

    res.json({ success: true, data: responseData })
  } catch (error: unknown) {
    if (isUnavailableSettingsError(error)) {
      next(new AppError('加载首页设置失败', 500))
      return
    }

    next(error)
  }
}

export async function updateHomepageSettingsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = updateHomepageSettingsRequest.safeParse(req.body)

    if (!parsed.success) {
      throw new AppError(parsed.error.issues.map((i) => i.message).join('; '), 400)
    }

    const currentSettings = await loadHomepageSettingsForUser(user.id)
    const mergedSettings = mergeHomepageSettings(currentSettings, parsed.data)

    await saveHomepageSettingsForUser(user.id, mergedSettings)

    const masked = maskHomepageSettingsSecrets(mergedSettings)
    res.json({ success: true, data: masked })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    logger.error({ err: error }, 'Failed to save homepage settings')
    next(new AppError('保存首页设置失败', 500))
  }
}
