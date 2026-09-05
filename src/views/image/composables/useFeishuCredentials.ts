import { computed, onScopeDispose, ref, watch } from 'vue'
import { useAnalysisSettings } from '../../../composables/useAnalysisSettings'
import { useAccountSessionStore } from '../../../stores/account-session'

/**
 * 图片评价视图的内联飞书凭据表单（任务书 #79 C79-02 按账号隔离）：
 * - 本地 appId/secret/folder/错误/弹窗归当前账号；换账号 sync 清空并关闭弹窗，卸载同样清理；
 * - 提交闭包先取票据再取字段（PUT 只含当前表单值，不复制旧账号字段到新账号）；
 * - await 后的关闭/错误提示/finally 都验票——旧提交不污染新会话。
 */
export function useFeishuCredentials() {
  const analysisSettings = useAnalysisSettings()
  const session = useAccountSessionStore()
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

  /** 换账号/卸载共用：同步清空本地表单并关闭弹窗，明文密钥一并清除。 */
  function clearLocalForm(): void {
    showFeishuConfig.value = false
    feishuAppId.value = ''
    feishuAppSecret.value = ''
    feishuFolderToken.value = ''
    feishuSaveError.value = ''
    savingFeishu.value = false
  }

  watch(
    () => session.ownerAccountId,
    () => { clearLocalForm() },
    { flush: 'sync' },
  )
  onScopeDispose(() => { clearLocalForm() })

  async function toggleFeishuConfig(): Promise<void> {
    if (showFeishuConfig.value) {
      showFeishuConfig.value = false
      return
    }
    const ticket = session.capture()
    feishuSaveError.value = ''
    // 匿名不开私有加载（E01），表单以空值打开；写动作交原服务裁决（§5.4 原 401）
    if (ticket.accountId && !analysisSettings.loaded.value) {
      await analysisSettings.loadSettings()
      // 加载期间换账号：表单已被 sync 清空，这里不得再把旧账号值填回去
      if (!session.isCurrent(ticket)) return
      const feishu = analysisSettings.settings.value?.integrations?.feishu
      feishuAppId.value = feishu?.appId ?? ''
      feishuFolderToken.value = feishu?.folderToken ?? ''
      feishuAppSecret.value = ''      // 密钥永不回显，留空即保持不变
    }
    showFeishuConfig.value = true
  }

  async function submitFeishuCredentials(): Promise<void> {
    // 先取票据再取字段：提交体 = 发起时刻的表单快照
    const ticket = session.capture()
    const appId = feishuAppId.value
    const secret = feishuAppSecret.value
    const folderToken = feishuFolderToken.value
    // 明文只在这一瞬间存在于内存，取出后立刻清空绑定
    feishuAppSecret.value = ''
    savingFeishu.value = true
    feishuSaveError.value = ''
    try {
      const ok = await analysisSettings.saveFeishuCredentials({
        appId: appId || undefined,
        // 不传 = 保持不变（沿用既有掩码语义）；空格 = 清空
        appSecret: secret === '' ? undefined : secret,
        folderToken: folderToken || undefined,
      })
      // 旧提交的关闭/错误/finally 都不落进新会话
      if (!session.isCurrent(ticket)) return
      if (ok) {
        showFeishuConfig.value = false
      } else {
        feishuSaveError.value = analysisSettings.saveError.value || '保存飞书凭据失败'
      }
    } finally {
      if (session.isCurrent(ticket)) savingFeishu.value = false
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
