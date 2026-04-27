import { ref } from 'vue'
import type {
  AnalysisFeature,
  AnalysisProvider,
  AnalysisSettings,
  AnalysisSettingsApiResponse,
  FeatureModelState,
  FeatureModelStateMap,
  ModelInfo,
} from '../types/settings'

function createDefaultSettings(): AnalysisSettings {
  return {
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
}

function createFeatureModelState(): FeatureModelState {
  return {
    loading: false,
    error: '',
    availableModels: [],
    verifying: false,
    verifyResult: 'idle',
    verifyError: '',
  }
}

function createDefaultFeatureStates(): FeatureModelStateMap {
  return {
    video: createFeatureModelState(),
    image: createFeatureModelState(),
    article: createFeatureModelState(),
    imageGeneration: createFeatureModelState(),
  }
}

function normalizeSettings(data: unknown): AnalysisSettings {
  if (typeof data !== 'object' || data === null) {
    return createDefaultSettings()
  }

  const record = data as Record<string, unknown>
  const rawFeatures = typeof record.features === 'object' && record.features !== null
    ? record.features as Record<string, unknown>
    : undefined

  const rawVideo = typeof rawFeatures?.video === 'object' && rawFeatures.video !== null
    ? rawFeatures.video as Record<string, unknown>
    : undefined
  const rawImage = typeof rawFeatures?.image === 'object' && rawFeatures.image !== null
    ? rawFeatures.image as Record<string, unknown>
    : undefined
  const rawArticle = typeof rawFeatures?.article === 'object' && rawFeatures.article !== null
    ? rawFeatures.article as Record<string, unknown>
    : undefined
  const rawImageGeneration = typeof rawFeatures?.imageGeneration === 'object' && rawFeatures.imageGeneration !== null
    ? rawFeatures.imageGeneration as Record<string, unknown>
    : undefined

  const rawIntegrations = typeof record.integrations === 'object' && record.integrations !== null
    ? record.integrations as Record<string, unknown>
    : undefined
  const rawFeishu = typeof rawIntegrations?.feishu === 'object' && rawIntegrations.feishu !== null
    ? rawIntegrations.feishu as Record<string, unknown>
    : undefined

  return {
    integrations: {
      feishu: {
        appId: typeof rawFeishu?.appId === 'string' ? rawFeishu.appId : undefined,
        appSecret: typeof rawFeishu?.appSecret === 'string' ? rawFeishu.appSecret : undefined,
        folderToken: typeof rawFeishu?.folderToken === 'string' ? rawFeishu.folderToken : undefined,
      },
    },
    features: {
      video: {
        provider: rawVideo?.provider === 'qwen' ? 'qwen' : 'coze',
        baseUrl: typeof rawVideo?.baseUrl === 'string' ? rawVideo.baseUrl : undefined,
        apiToken: typeof rawVideo?.apiToken === 'string' ? rawVideo.apiToken : undefined,
        apiKey: typeof rawVideo?.apiKey === 'string' ? rawVideo.apiKey : undefined,
        model: typeof rawVideo?.model === 'string' ? rawVideo.model : undefined,
      },
      image: {
        baseUrl: typeof rawImage?.baseUrl === 'string' ? rawImage.baseUrl : undefined,
        apiKey: typeof rawImage?.apiKey === 'string' ? rawImage.apiKey : undefined,
        model: typeof rawImage?.model === 'string' ? rawImage.model : undefined,
      },
      article: {
        baseUrl: typeof rawArticle?.baseUrl === 'string' ? rawArticle.baseUrl : undefined,
        apiKey: typeof rawArticle?.apiKey === 'string' ? rawArticle.apiKey : undefined,
        model: typeof rawArticle?.model === 'string' ? rawArticle.model : undefined,
      },
      imageGeneration: {
        baseUrl: typeof rawImageGeneration?.baseUrl === 'string' ? rawImageGeneration.baseUrl : undefined,
        apiKey: typeof rawImageGeneration?.apiKey === 'string' ? rawImageGeneration.apiKey : undefined,
        model: typeof rawImageGeneration?.model === 'string' ? rawImageGeneration.model : undefined,
      },
    },
  }
}

async function readApiError(response: Response, fallbackMessage: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''

  if (contentType.includes('application/json')) {
    const body = await response.json() as AnalysisSettingsApiResponse
    return body.error || fallbackMessage
  }

  const text = await response.text()
  return text.trim() || fallbackMessage
}

export function useAnalysisSettings() {
  const settings = ref<AnalysisSettings>(createDefaultSettings())
  const loading = ref(false)
  const loaded = ref(false)
  const saving = ref(false)
  const error = ref('')
  const saveError = ref('')
  const featureModelStates = ref<FeatureModelStateMap>(createDefaultFeatureStates())

  async function loadSettings(): Promise<void> {
    loading.value = true
    error.value = ''

    try {
      const response = await fetch('/api/settings/analysis')

      if (!response.ok) {
        throw new Error(await readApiError(response, `加载设置失败（${response.status}）`))
      }

      const body = await response.json() as AnalysisSettingsApiResponse
      settings.value = normalizeSettings(body.data)
      loaded.value = true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '加载设置失败'
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(newSettings: AnalysisSettings): Promise<boolean> {
    saving.value = true
    saveError.value = ''

    try {
      const response = await fetch('/api/settings/analysis', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newSettings),
      })

      if (!response.ok) {
        throw new Error(await readApiError(response, `保存设置失败（${response.status}）`))
      }

      const body = await response.json() as AnalysisSettingsApiResponse
      settings.value = normalizeSettings(body.data)
      return true
    } catch (err: unknown) {
      saveError.value = err instanceof Error ? err.message : '保存设置失败'
      return false
    } finally {
      saving.value = false
    }
  }

  async function persistBeforeFeatureAction(settingsToSave?: AnalysisSettings, fallbackMessage?: string): Promise<void> {
    if (!settingsToSave) {
      return
    }

    const saveResponse = await fetch('/api/settings/analysis', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settingsToSave),
    })

    if (!saveResponse.ok) {
      throw new Error(await readApiError(saveResponse, fallbackMessage ?? '保存设置失败'))
    }

    const saveBody = await saveResponse.json() as AnalysisSettingsApiResponse
    settings.value = normalizeSettings(saveBody.data)
  }

  async function fetchModels(
    feature: AnalysisFeature,
    provider: AnalysisProvider | undefined,
    settingsToSave?: AnalysisSettings,
  ): Promise<void> {
    featureModelStates.value = {
      ...featureModelStates.value,
      [feature]: {
        ...featureModelStates.value[feature],
        loading: true,
        error: '',
        availableModels: [],
      },
    }

    try {
      await persistBeforeFeatureAction(settingsToSave, '保存设置失败，无法获取模型列表')

      const response = await fetch('/api/settings/analysis/models', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ feature, provider }),
      })

      const body = await response.json() as { success: boolean; data?: { models: ModelInfo[] }; error?: string }

      if (!response.ok || !body.success) {
        throw new Error(body.error || `获取模型列表失败（${response.status}）`)
      }

      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          availableModels: body.data?.models ?? [],
        },
      }
    } catch (err: unknown) {
      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          error: err instanceof Error ? err.message : '获取模型列表失败',
        },
      }
    } finally {
      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          loading: false,
        },
      }
    }
  }

  async function verifyModel(
    feature: AnalysisFeature,
    provider: AnalysisProvider | undefined,
    model: string,
    settingsToSave?: AnalysisSettings,
  ): Promise<boolean> {
    featureModelStates.value = {
      ...featureModelStates.value,
      [feature]: {
        ...featureModelStates.value[feature],
        verifying: true,
        verifyResult: 'idle',
        verifyError: '',
      },
    }

    try {
      await persistBeforeFeatureAction(settingsToSave, '保存设置失败，无法验证模型')

      const response = await fetch('/api/settings/analysis/verify-model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ feature, provider, model }),
      })

      const body = await response.json() as { success: boolean; data?: { verified: boolean }; error?: string }

      if (!response.ok || !body.success) {
        throw new Error(body.error || `模型验证失败（${response.status}）`)
      }

      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          verifyResult: 'success',
        },
      }
      return true
    } catch (err: unknown) {
      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          verifyResult: 'error',
          verifyError: err instanceof Error ? err.message : '模型验证失败',
        },
      }
      return false
    } finally {
      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          verifying: false,
        },
      }
    }
  }

  function clearModelState(feature?: AnalysisFeature): void {
    if (!feature) {
      featureModelStates.value = createDefaultFeatureStates()
      return
    }

    featureModelStates.value = {
      ...featureModelStates.value,
      [feature]: createFeatureModelState(),
    }
  }

  return {
    settings, loading, loaded, saving, error, saveError,
    loadSettings, saveSettings,
    featureModelStates, fetchModels,
    verifyModel,
    clearModelState,
  }
}
