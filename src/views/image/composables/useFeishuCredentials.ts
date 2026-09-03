import { ref, computed } from 'vue'
import { useAnalysisSettings } from '../../../composables/useAnalysisSettings'

export function useFeishuCredentials() {
  const analysisSettings = useAnalysisSettings()
  const showFeishuConfig = ref(false)
  const feishuAppId = ref('')
  const feishuAppSecret = ref('')
  const feishuFolderToken = ref('')
  const feishuSaveError = ref('')
  const savingFeishu = ref(false)

  /** 后端只回掩码，故「已保存」由 appSecret 字段是否有值判断，不看具体内容。 */
  const feishuSecretSaved = computed(() =>
    Boolean(analysisSettings.settings.value?.integrations?.feishu?.appSecret))
  const feishuConfigured = computed(() =>
    Boolean(analysisSettings.settings.value?.integrations?.feishu?.appId) && feishuSecretSaved.value)

  async function toggleFeishuConfig(): Promise<void> {
    if (showFeishuConfig.value) {
      showFeishuConfig.value = false
      return
    }
    feishuSaveError.value = ''
    if (!analysisSettings.loaded.value) {
      await analysisSettings.loadSettings()
    }
    const feishu = analysisSettings.settings.value?.integrations?.feishu
    feishuAppId.value = feishu?.appId ?? ''
    feishuFolderToken.value = feishu?.folderToken ?? ''
    feishuAppSecret.value = ''      // 密钥永不回显，留空即保持不变
    showFeishuConfig.value = true
  }

  async function submitFeishuCredentials(): Promise<void> {
    // 明文只在这一瞬间存在于内存，取出后立刻清空绑定
    const secret = feishuAppSecret.value
    feishuAppSecret.value = ''
    savingFeishu.value = true
    feishuSaveError.value = ''
    try {
      const ok = await analysisSettings.saveFeishuCredentials({
        appId: feishuAppId.value || undefined,
        // 不传 = 保持不变（沿用既有掩码语义）；空格 = 清空
        appSecret: secret === '' ? undefined : secret,
        folderToken: feishuFolderToken.value || undefined,
      })
      if (ok) {
        showFeishuConfig.value = false
      } else {
        feishuSaveError.value = analysisSettings.saveError.value || '保存飞书凭据失败'
      }
    } finally {
      savingFeishu.value = false
    }
  }

  async function handleExportToFeishu(exportToFeishu: () => Promise<void>): Promise<void> {
    await exportToFeishu()
  }

  return {
    analysisSettings,
    showFeishuConfig,
    feishuAppId,
    feishuAppSecret,
    feishuFolderToken,
    feishuSaveError,
    savingFeishu,
    feishuSecretSaved,
    feishuConfigured,
    toggleFeishuConfig,
    submitFeishuCredentials,
    handleExportToFeishu,
  }
}
