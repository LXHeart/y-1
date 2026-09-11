import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type {
  CreateVariantResult,
  VariantSummary,
} from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-15：独立方案列表/创建/切换会话（API-11/12）。
 *
 * - 创建：operationId 生成一次，丢响应原键重试（不能连点生成多个方案）；成功后由
 *   调用方导航到返回的新 draft/storyboard。
 * - 切换：先 flush 当前方案全部编辑（编辑器/布局/文档），失败保留当前方案不切走；
 *   切换即整页导航（新 draft+storyboard 深链），选择/历史/AI 上下文随会话自然重建。
 * - 比较只展示明确字段差异（父版本/来源/标题），不伪造视觉评分。
 */
export interface UseCanvasVariantsOptions {
  storyboardId: Ref<string>
  /** 切换前 flush（返回 false 阻止切换并提示）。 */
  flushBeforeSwitch: () => Promise<boolean>
  /** 导航到方案（视图提供：新 draft+storyboard 深链替换当前路由）。 */
  navigateToVariant: (storyboardId: string, draftId: string) => Promise<void>
}

export function useCanvasVariants(options: UseCanvasVariantsOptions) {
  const { storyboardId, flushBeforeSwitch, navigateToVariant } = options

  const variants = ref<VariantSummary[]>([])
  const loading = ref(false)
  const error = ref('')
  const creating = ref(false)
  /** 丢响应重试的原始键与参数（连点防护：creating 期间拒绝再次提交）。 */
  let pendingCreation: {
    operationId: string
    expectedEditVersion: number
    expectedDraftVersion: number
    title: string
    shotIds: string[]
  } | null = null

  async function load(): Promise<void> {
    if (!storyboardId.value) return
    loading.value = true
    error.value = ''
    try {
      const body = await request<{ items: VariantSummary[] }>(
        `/api/video-production/storyboards/${encodeURIComponent(storyboardId.value)}/variants`)
      variants.value = body.items ?? []
    } catch (err) {
      error.value = `方案列表读取失败：${err instanceof Error ? err.message : '网络异常'}`
    } finally {
      loading.value = false
    }
  }

  /**
   * 创建方案：同键重试（网络失败保留原 operationId 重发，幂等返回同一份）；
   * 版本冲突 409 提示刷新，不自动换键。
   */
  async function create(input: {
    expectedEditVersion: number
    expectedDraftVersion: number
    title: string
    shotIds: string[]
    operationId?: string
  }): Promise<CreateVariantResult | null> {
    if (creating.value) return null
    creating.value = true
    error.value = ''
    const pending = pendingCreation && input.operationId == null
      ? pendingCreation
      : {
        operationId: input.operationId ?? crypto.randomUUID(),
        expectedEditVersion: input.expectedEditVersion,
        expectedDraftVersion: input.expectedDraftVersion,
        title: input.title,
        shotIds: input.shotIds,
      }
    try {
      const result = await request<CreateVariantResult>(
        `/api/video-production/storyboards/${encodeURIComponent(storyboardId.value)}/variants`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            operationId: pending.operationId,
            expectedEditVersion: pending.expectedEditVersion,
            expectedDraftVersion: pending.expectedDraftVersion,
            title: pending.title,
            shotIds: pending.shotIds,
          }),
        })
      pendingCreation = null
      await load()
      return result
    } catch (err) {
      // 保留原始键供重试（不能换键盲重试——会生成第二份）
      pendingCreation = pending
      error.value = `方案创建失败（可原键重试）：${err instanceof Error ? err.message : '网络异常'}`
      return null
    } finally {
      creating.value = false
    }
  }

  /** 原键重试（仅存在丢响应的挂起创建时可用）。 */
  async function retryPending(): Promise<CreateVariantResult | null> {
    if (!pendingCreation) return null
    const pending = pendingCreation
    return create({
      expectedEditVersion: pending.expectedEditVersion,
      expectedDraftVersion: pending.expectedDraftVersion,
      title: pending.title,
      shotIds: pending.shotIds,
      operationId: pending.operationId,
    })
  }

  const hasPendingCreation = computed(() => pendingCreation !== null)

  /** 切换方案：flush 失败保留当前；成功导航（整页态切换，不共享选择/历史/AI 上下文）。 */
  async function switchTo(target: { storyboardId: string; draftId: string }): Promise<boolean> {
    const flushed = await flushBeforeSwitch()
    if (!flushed) {
      error.value = '有未保存的修改，已停留在当前方案'
      return false
    }
    await navigateToVariant(target.storyboardId, target.draftId)
    return true
  }

  /** 明确字段差异（父版本/标题/来源版本）——不伪造视觉评分或效果指标。 */
  function compareFields(current: VariantSummary, other: VariantSummary) {
    return [
      { field: '标题', current: current.title, other: other.title },
      {
        field: '来源版本',
        current: current.sourceEditVersion == null ? '根方案' : `v${current.sourceEditVersion}`,
        other: other.sourceEditVersion == null ? '根方案' : `v${other.sourceEditVersion}`,
      },
      {
        field: '父方案',
        current: current.parentStoryboardId ?? '—',
        other: other.parentStoryboardId ?? '—',
      },
    ]
  }

  return {
    variants,
    loading,
    error,
    creating,
    hasPendingCreation,
    load,
    create,
    retryPending,
    switchTo,
    compareFields,
  }
}
