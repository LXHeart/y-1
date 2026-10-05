import { onScopeDispose, ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { TaskPreview } from '../../../types/grassland'
import type { ContractSnapshot } from '../../../lib/task-contract'

/**
 * 错误文案清洗：后端/网关领域错误是中文（如「任务不存在」），但 Spring 默认错误体的
 * {@code error:"Not Found"} 等纯英文技术文案会被 http 层原样提出——不含中文的一律视为
 * 技术噪音，落回领域兜底文案，不给用户看裸 404 词汇。
 */
function humanReadable(message: string): string {
  return /[\u4e00-\u9fff]/.test(message) ? message : '合作条款暂时无法加载'
}

export function useWorkbenchTaskPreview(
  grassland: Pick<ReturnType<typeof useGrassland>, 'getTaskPreview'>
    & Partial<Pick<ReturnType<typeof useGrassland>, 'getTaskContext'>> & { error: Readonly<Ref<string>> },
  taskId: () => string | null,
  version: () => number = () => 0,
  acceptedApplicationId: () => string | null = () => null,
) {
  const session = useAccountSessionStore()
  const preview = ref<TaskPreview | null>(null)
  const acceptedTerms = ref<ContractSnapshot | null>(null)
  const loading = ref(false)
  const error = ref('')
  let sequence = 0

  async function load(): Promise<void> {
    const id = taskId()
    const appId = acceptedApplicationId()
    const request = ++sequence
    const ticket = session.capture()
    const current = () => request === sequence && session.isCurrent(ticket) && id === taskId()
      && appId === acceptedApplicationId()
    preview.value = null
    acceptedTerms.value = null
    error.value = ''
    loading.value = Boolean(id)
    if (!id) return
    try {
      if (appId) {
        const snapshot = await grassland.getTaskContext?.(id, appId)
        if (!current()) return
        if (snapshot?.taskId === id && snapshot.applicationId === appId) acceptedTerms.value = { ...snapshot }
        else error.value = humanReadable(grassland.error.value || '已接受的合同暂时无法加载')
        return
      }
      const result = await grassland.getTaskPreview(id)
      if (!current()) return
      if (result?.taskId === id) preview.value = result
      else error.value = humanReadable(grassland.error.value || '合作条款暂时无法加载')
    } catch (cause) {
      if (current()) {
        error.value = humanReadable(cause instanceof Error && cause.message
          ? cause.message
          : '合作条款暂时无法加载')
      }
    } finally {
      if (current()) loading.value = false
    }
  }

  watch([taskId, version, acceptedApplicationId, () => session.epoch], () => { void load() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { sequence += 1 })
  return { preview, acceptedTerms, loading, error, load }
}
