import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchApi, readError, request } from '../composables/grassland-http'
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
      videoProduction: {},
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
    videoProduction: createFeatureModelState(),
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
  const rawVideoProduction = typeof rawFeatures?.videoProduction === 'object' && rawFeatures.videoProduction !== null
    ? rawFeatures.videoProduction as Record<string, unknown>
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
      videoProduction: {
        baseUrl: typeof rawVideoProduction?.baseUrl === 'string' ? rawVideoProduction.baseUrl : undefined,
        apiKey: typeof rawVideoProduction?.apiKey === 'string' ? rawVideoProduction.apiKey : undefined,
        model: typeof rawVideoProduction?.model === 'string' ? rawVideoProduction.model : undefined,
      },
    },
  }
}

/** 设置读写不校验信封 success（静默回退默认值），只统一传输层与非 2xx 文案。 */
async function loadSettingsEnvelope(url: string, init: RequestInit | undefined, fallbackPrefix: string): Promise<AnalysisSettingsApiResponse> {
  const response = await fetchApi(url, init)
  if (!response.ok) {
    throw new Error(await readError(response, `${fallbackPrefix}（${response.status}）`))
  }
  return await response.json() as AnalysisSettingsApiResponse
}

export const useAnalysisSettingsStore = defineStore('analysis-settings', () => {
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
      const body = await loadSettingsEnvelope('/api/settings/analysis', undefined, '加载设置失败')
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
      const body = await loadSettingsEnvelope('/api/settings/analysis', {
        method: 'PUT',
        body: JSON.stringify(newSettings),
      }, '保存设置失败')
      settings.value = normalizeSettings(body.data)
      return true
    } catch (err: unknown) {
      saveError.value = err instanceof Error ? err.message : '保存设置失败'
      return false
    } finally {
      saving.value = false
    }
  }

  /**
   * 只保存飞书导出凭据（任务书 #47 S7a）。
   *
   * <p>顶部「分析设置」modal 即将下线，飞书凭据改为在图片评价视图内联维护——它是唯一用到这组
   * 凭据的地方。这里刻意 PUT 局部对象而不是整份 `AnalysisSettings`：后端
   * `AnalysisSettingsService` 是掩码感知 merge（字段缺失→保留当前值、掩码→保留、空串→清空），
   * 所以只传 `integrations.feishu` 不会动到其余字段。
   *
   * <p>`appSecret` 语义沿用既有约定：不传 = 保持不变；空格 = 清空。
   */
  async function saveFeishuCredentials(input: {
    appId?: string
    appSecret?: string
    folderToken?: string
  }): Promise<boolean> {
    saving.value = true
    saveError.value = ''
    try {
      const body = await loadSettingsEnvelope('/api/settings/analysis', {
        method: 'PUT',
        body: JSON.stringify({ integrations: { feishu: input } }),
      }, '保存飞书凭据失败')
      settings.value = normalizeSettings(body.data)
      return true
    } catch (err: unknown) {
      saveError.value = err instanceof Error ? err.message : '保存飞书凭据失败'
      return false
    } finally {
      saving.value = false
    }
  }

  async function persistBeforeFeatureAction(settingsToSave?: AnalysisSettings, fallbackMessage?: string): Promise<void> {
    if (!settingsToSave) return
    const saveBody = await loadSettingsEnvelope('/api/settings/analysis', {
      method: 'PUT',
      body: JSON.stringify(settingsToSave),
    }, fallbackMessage ?? '保存设置失败')
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
      const data = await request<{ models?: ModelInfo[] }>('/api/settings/analysis/models', {
        method: 'POST',
        body: JSON.stringify({ feature, provider }),
      }, { fallbackError: '获取模型列表失败' })
      featureModelStates.value = {
        ...featureModelStates.value,
        [feature]: {
          ...featureModelStates.value[feature],
          availableModels: data?.models ?? [],
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
      await request<{ verified: boolean }>('/api/settings/analysis/verify-model', {
        method: 'POST',
        body: JSON.stringify({ feature, provider, model }),
      }, { fallbackError: '模型验证失败' })
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
    loadSettings, saveSettings, saveFeishuCredentials,
    featureModelStates, fetchModels,
    verifyModel,
    clearModelState,
  }
})
