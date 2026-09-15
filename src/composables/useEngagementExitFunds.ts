import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import type { useGrassland } from './useGrassland'
import type { EngagementExitFunds } from '../types/grassland/task'

/**
 * 任务书 #103 C103-04（§6.2/BR-20）：退出资金态查询 composable。
 *
 * - 目标绑定：查询按 (taskId, applicationId) 捕获；响应合并前校验仍是当前对象——
 *   切合作/换账号后旧回包不回写（E15/E17）。
 * - 可见时轮询：state 为 pending/processing/retry_wait 时每 5s 刷新；
 *   succeeded/needs_review/对象失活即停止（读取消不冒充写取消）。
 * - 旧响应缺 exitFunds 字段 → 显示「资金状态待查询」，不推断成功（§6.1 兼容原则）。
 */
export function useEngagementExitFunds(client: ReturnType<typeof useGrassland>) {
  const funds = ref<EngagementExitFunds | null>(null) as Ref<EngagementExitFunds | null>
  const loading = ref(false)
  const error = ref('')
  const currentKey = ref('')
  let requestVersion = 0
  let pollTimer: ReturnType<typeof setInterval> | null = null

  function stopPolling(): void {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function schedulePollIfNeeded(): void {
    stopPolling()
    const state = funds.value?.state
    if (state === 'pending' || state === 'processing' || state === 'retry_wait') {
      pollTimer = setInterval(() => {
        void refresh()
      }, 5000)
    }
  }

  async function refresh(): Promise<EngagementExitFunds | null> {
    const key = currentKey.value
    if (!key) return null
    const [taskId, applicationId] = key.split('|')
    const version = ++requestVersion
    loading.value = true
    error.value = ''
    const result = await client.fetchEngagementExitFunds(taskId, applicationId)
    if (version !== requestVersion || currentKey.value !== key) return funds.value
    loading.value = false
    if (result === null || result === undefined) {
      error.value = client.error.value || '资金状态查询失败'
      return funds.value
    }
    funds.value = result?.operationId ? result : null
    schedulePollIfNeeded()
    return funds.value
  }

  /** 激活目标：切对象立即清旧数据并重查；空 key 停止轮询。 */
  function target(taskId: string | null | undefined, applicationId: string | null | undefined): void {
    const key = taskId && applicationId ? `${taskId}|${applicationId}` : ''
    if (key === currentKey.value) return
    currentKey.value = key
    requestVersion++
    stopPolling()
    funds.value = null
    error.value = ''
    if (key) void refresh()
  }

  if (getCurrentScope()) {
    onScopeDispose(stopPolling)
  }

  return { funds, loading, error, refresh, target, watch }
}
