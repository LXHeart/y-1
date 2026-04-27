import { ref } from 'vue'
import type {
  HomepageSettings,
  HomepageSettingsApiResponse,
  HotItemsProvider,
} from '../types/settings'

function createDefaultSettings(): HomepageSettings {
  return {
    hotItems: { provider: '60s' },
  }
}

function normalizeSettings(data: unknown): HomepageSettings {
  if (typeof data !== 'object' || data === null) {
    return createDefaultSettings()
  }

  const record = data as Record<string, unknown>
  const rawHotItems = typeof record.hotItems === 'object' && record.hotItems !== null
    ? record.hotItems as Record<string, unknown>
    : undefined

  const provider: HotItemsProvider = rawHotItems?.provider === 'alapi' ? 'alapi' : '60s'
  const alapiToken = typeof rawHotItems?.alapiToken === 'string' ? rawHotItems.alapiToken : undefined

  return {
    hotItems: {
      provider,
      ...(alapiToken ? { alapiToken } : {}),
    },
  }
}

async function readApiError(response: Response, fallbackMessage: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json() as HomepageSettingsApiResponse
    return body.error || fallbackMessage
  }

  const text = await response.text()
  return text.trim() || fallbackMessage
}

export function useHomepageSettings() {
  const settings = ref<HomepageSettings>(createDefaultSettings())
  const loading = ref(false)
  const loaded = ref(false)
  const saving = ref(false)
  const error = ref('')
  const saveError = ref('')

  async function loadSettings(): Promise<void> {
    loading.value = true
    error.value = ''

    try {
      const response = await fetch('/api/settings/homepage')

      if (!response.ok) {
        throw new Error(await readApiError(response, `加载首页设置失败（${response.status}）`))
      }

      const body = await response.json() as HomepageSettingsApiResponse
      settings.value = normalizeSettings(body.data)
      loaded.value = true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '加载首页设置失败'
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(newSettings: HomepageSettings): Promise<boolean> {
    saving.value = true
    saveError.value = ''

    try {
      const response = await fetch('/api/settings/homepage', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newSettings),
      })

      if (!response.ok) {
        throw new Error(await readApiError(response, `保存首页设置失败（${response.status}）`))
      }

      const body = await response.json() as HomepageSettingsApiResponse
      settings.value = normalizeSettings(body.data)
      return true
    } catch (err: unknown) {
      saveError.value = err instanceof Error ? err.message : '保存首页设置失败'
      return false
    } finally {
      saving.value = false
    }
  }

  return {
    settings, loading, loaded, saving, error, saveError,
    loadSettings, saveSettings,
  }
}
