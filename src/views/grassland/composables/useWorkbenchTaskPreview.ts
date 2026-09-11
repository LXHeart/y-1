import { onScopeDispose, ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { TaskPreview } from '../../../types/grassland'

/**
 * 错误文案清洗：后端/网关领域错误是中文（如「任务不存在」），但 Spring 默认错误体的
 * {@code error:"Not Found"} 等纯英文技术文案会被 http 层原样提出——不含中文的一律视为
 * 技术噪音，落回领域兜底文案，不给用户看裸 404 词汇。
 */
function humanReadable(message: string): string {
  return /[\u4e00-\u9fff]/.test(message) ? message : '合作条款暂时无法加载'
}

export function useWorkbenchTaskPreview(
  grassland: Pick<ReturnType<typeof useGrassland>, 'getTaskPreview'> & { error: Readonly<Ref<string>> },
  taskId: () => string | null,
  version: () => number = () => 0,
) {
  const session = useAccountSessionStore()
  const preview = ref<TaskPreview | null>(null)
  const loading = ref(false)
  const error = ref('')
  let sequence = 0

  async function load(): Promise<void> {
    const id = taskId()
    const request = ++sequence
    const ticket = session.capture()
    const current = () => request === sequence && session.isCurrent(ticket) && id === taskId()
    preview.value = null
    error.value = ''
    loading.value = Boolean(id)
    if (!id) return
    try {
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

  watch([taskId, version, () => session.epoch], () => { void load() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { sequence += 1 })
  return { preview, loading, error, load }
}
