import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchApi, readError } from '../composables/grassland-http'
import { normalizeAccountId, useAccountSessionStore, type AccountTicket } from './account-session'
import { useAuthStore } from './auth'
import type {
  AnalysisSettings,
  AnalysisSettingsApiResponse,
} from '../types/settings'

/**
 * 用户级分析设置 store（2026-08-26 收敛后仅剩飞书导出凭据）：
 * 视频分析等用户级模型配置已随设置弹窗页签下线——生成模型统一走
 * 「AI 内容创作中心 → 模型密钥」自定义/平台内置开关 + 管理后台平台模型配置。
 * 唯一消费方是图片评价视图的内联飞书凭据表单。
 *
 * 任务书 #79 C79-02：设置按账号隔离——owner 镜像 + 账号变化同步 reset，
 * 所有异步写入（数据/错误/finally）先验账号票据，旧账号的迟到结果一律静默丢弃。
 */
function createDefaultSettings(): AnalysisSettings {
  return {
    integrations: {
      feishu: {},
    },
  }
}

function normalizeSettings(data: unknown): AnalysisSettings {
  if (typeof data !== 'object' || data === null) {
    return createDefaultSettings()
  }

  const record = data as Record<string, unknown>
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
  const session = useAccountSessionStore()
  const auth = useAuthStore()
  const settings = ref<AnalysisSettings>(createDefaultSettings())
  const loading = ref(false)
  const loaded = ref(false)
  const saving = ref(false)
  const error = ref('')
  const saveError = ref('')
  /** 当前数据归属账号的镜像（公开可验证，任务书 #82 C82-02）：null = 匿名（未加载任何私有数据）。 */
  const ownerAccountId = ref<string | null>(null)
  let pendingLoad: Promise<void> | null = null

  /** 账号边界（D79-02）：换代先同步清私有状态（含错误与请求标记），再由消费方启动新加载。 */
  function resetForAccount(accountId: string | null): void {
    ownerAccountId.value = accountId
    pendingLoad = null
    settings.value = createDefaultSettings()
    loading.value = false
    loaded.value = false
    saving.value = false
    error.value = ''
    saveError.value = ''
  }

  watch(
    () => normalizeAccountId(auth.currentUser?.id),
    (accountId) => { resetForAccount(accountId) },
    { flush: 'sync', immediate: true },
  )

  /** 单次加载（票据守卫）：loading 释放只归当前票，迟到请求静默丢弃。 */
  async function loadSettingsOnce(ticket: AccountTicket): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const body = await loadSettingsEnvelope('/api/settings/analysis', { signal: ticket.signal }, '加载设置失败')
      if (!session.isCurrent(ticket)) return
      settings.value = normalizeSettings(body.data)
      loaded.value = true
    } catch (err: unknown) {
      if (!session.isCurrent(ticket)) return
      error.value = err instanceof Error ? err.message : '加载设置失败'
    } finally {
      if (session.isCurrent(ticket)) loading.value = false
    }
  }

  async function loadSettings(): Promise<void> {
    if (pendingLoad) return pendingLoad
    if (!ownerAccountId.value) return // 匿名不发私有初始化请求
    const ticket = session.capture()
    const attempt = loadSettingsOnce(ticket).finally(() => {
      if (pendingLoad === attempt) pendingLoad = null
    })
    pendingLoad = attempt
    return attempt
  }

  /**
   * 只保存飞书导出凭据（任务书 #47 S7a，2026-08-26 起为本 store 唯一写路径）。
   *
   * <p>刻意 PUT 局部对象：后端 {@code AnalysisSettingsService} 是掩码感知 merge
   * （字段缺失→保留当前值、掩码→保留、空串→清空），只传 {@code integrations.feishu}
   * 不会动到其余字段。
   *
   * <p>{@code appSecret} 语义沿用既有约定：不传 = 保持不变；空格 = 清空。
   *
   * <p>任务书 #79 C79-02：写请求不带 signal（取消不等于回滚，由服务端原事务完成）；
   * 迟到的旧账号保存结果静默丢弃（返回 false、不污染新账号的 saveError）。
   * 匿名提交不设前端闸——私有写的权威结果是原服务 401（§5.4）。
   */
  async function saveFeishuCredentials(input: {
    appId?: string
    appSecret?: string
    folderToken?: string
  }): Promise<boolean> {
    const ticket = session.capture()
    saving.value = true
    saveError.value = ''
    try {
      const body = await loadSettingsEnvelope('/api/settings/analysis', {
        method: 'PUT',
        body: JSON.stringify({ integrations: { feishu: input } }),
      }, '保存飞书凭据失败')
      if (!session.isCurrent(ticket)) return false
      settings.value = normalizeSettings(body.data)
      return true
    } catch (err: unknown) {
      if (!session.isCurrent(ticket)) return false
      saveError.value = err instanceof Error ? err.message : '保存飞书凭据失败'
      return false
    } finally {
      if (session.isCurrent(ticket)) saving.value = false
    }
  }

  return {
    ownerAccountId,
    settings, loading, loaded, saving, error, saveError,
    loadSettings, saveFeishuCredentials, resetForAccount,
  }
})
