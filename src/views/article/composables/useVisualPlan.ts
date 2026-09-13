import { computed, onScopeDispose, ref } from 'vue'
import type {
  RecipeRef, SourceBlock, SourceDocument, VisualPlan, VisualPlanDocument, VisualPlanItem, VisualStrategy,
} from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-06：视觉计划 API 客户端（API101-08～12）。
 *
 * 计划编辑有自己的 800ms 保存队列（每计划一条，与共享草稿队列独立）；只有 studio 引用
 * 变化（新建计划换 ID）才写草稿 workspace，PATCH 重修订不再触发 workspace 保存。
 * 迟到响应用 epoch 丢弃：切换计划／组件卸载后旧响应一律不落地（TC101-027/029）。
 */

const PLAN_SAVE_DEBOUNCE_MS = 800
const PREPARING_POLL_INTERVAL_MS = 2000
const PREPARING_POLL_LIMIT = 60

export interface PreparePlanInput {
  strategy?: VisualStrategy
  itemCount?: number
  style?: Partial<{ styleId: string; layoutId: string; paletteId: string }>
  targetAspect?: string
  selectedBlockIds?: string[]
}

export function useVisualPlan(options: {
  draftId: () => string | null
  draftVersion: () => number
  sourceDocumentId: () => string | null
  sourceContentHash: () => string | null
  recipe: () => RecipeRef | null
  /** 计划引用落 workspace（只在新建计划时调用一次，避免每次轮询写草稿）。 */
  onPlanCreated: (plan: VisualPlan) => void
}) {
  const current = ref<VisualPlan | null>(null)
  /** 本地可编辑文档副本；PATCH 快照保存在 savingDocument，成功不覆盖更新的人类编辑。 */
  const document = ref<VisualPlanDocument | null>(null)
  const preparing = ref(false)
  const saving = ref(false)
  const confirming = ref(false)
  const error = ref('')
  /** 来源文档（块表定位展示 + prepare 的 contentHash），由编排层导入/读取来源后绑定。 */
  const boundSource = ref<SourceDocument | null>(null)

  let epoch = 0
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let savingDocument: VisualPlanDocument | null = null
  let pendingPatch: { requestId: string; expectedRevision: number } | null = null
  let prepareRequestId: string | null = null
  let preparePayloadKey: string | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollCount = 0

  onScopeDispose(() => {
    epoch += 1
    clearSaveTimer()
    clearPollTimer()
  })

  function clearSaveTimer(): void {
    if (saveTimer != null) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
  }

  function clearPollTimer(): void {
    if (pollTimer != null) {
      clearTimeout(pollTimer)
      pollTimer = null
      pollCount = 0
    }
  }

  async function request<T>(url: string, init: RequestInit): Promise<T | null> {
    const response = await fetch(url, init)
    const body = await response?.json().catch(() => null) as { success?: boolean; data?: T; error?: string } | null
    if (!response?.ok || !body?.success) {
      error.value = body?.error || '请求失败，请重试'
      return null
    }
    return body.data ?? null
  }

  /** 计划文档是纯 JSON 数据（§6.2 封闭字段集）——JSON 深拷贝可规避 reactive Proxy。 */
function cloneDocument(source: VisualPlanDocument): VisualPlanDocument {
  return JSON.parse(JSON.stringify(source)) as VisualPlanDocument
}

function sameDocument(a: VisualPlanDocument | null, b: VisualPlanDocument | null): boolean {
    if (a === b) return true
    if (!a || !b) return false
    return JSON.stringify(a) === JSON.stringify(b)
  }

  /** 来源块表（块定位展示用）：boundSource 派生。 */
  const sourceBlocks = computed<Record<string, SourceBlock>>(() => {
    const map: Record<string, SourceBlock> = {}
    for (const block of boundSource.value?.blocks ?? []) map[block.id] = block
    return map
  })

  function bindSource(source: SourceDocument | null): void {
    boundSource.value = source
  }

  /** 落地服务端计划：document 只在本地无未保存编辑时对齐服务器副本。 */
  function applyPlan(plan: VisualPlan): void {
    current.value = plan
    if (!saving.value || sameDocument(document.value, savingDocument)) {
      document.value = plan.document ? cloneDocument(plan.document) : null
      savingDocument = null
    }
  }

  // ---- API101-08 prepare ----

  async function prepare(input: PreparePlanInput = {}): Promise<void> {
    const draftId = options.draftId()
    const sourceId = boundSource.value?.id ?? options.sourceDocumentId()
    const sourceHash = boundSource.value?.contentHash ?? options.sourceContentHash()
    const recipe = options.recipe()
    if (!draftId || !sourceId || !sourceHash || !recipe || preparing.value) return
    const requestEpoch = ++epoch
    clearPollTimer()
    clearSaveTimer()
    preparing.value = true
    error.value = ''
    const payloadKey = JSON.stringify([input, draftId, options.draftVersion(), sourceId, recipe])
    // 同一意图沿用 requestId（服务端 preparing 幂等，重复 preparing 不再请求模型）。
    if (preparePayloadKey !== payloadKey) {
      preparePayloadKey = payloadKey
      prepareRequestId = crypto.randomUUID()
    }
    try {
      const plan = await request<VisualPlan>('/api/creation-studio/visual-plans', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: prepareRequestId,
          draftId,
          expectedDraftVersion: options.draftVersion(),
          recipe: { id: recipe.id, version: recipe.version },
          source: { id: sourceId, contentHash: sourceHash },
          selectedBlockIds: input.selectedBlockIds ?? [],
          ...(input.strategy ? { strategy: input.strategy } : {}),
          ...(input.itemCount != null ? { itemCount: input.itemCount } : {}),
          ...(input.style ? { style: input.style } : {}),
          ...(input.targetAspect ? { targetAspect: input.targetAspect } : {}),
        }),
      })
      if (requestEpoch !== epoch) return
      if (!plan) return
      applyPlan(plan)
      options.onPlanCreated(plan)
      // 202 同键 preparing：轮询读取直到终态（有界，超限交给用户手动刷新）。
      if (plan.status === 'preparing') schedulePoll(plan.id, requestEpoch)
    } finally {
      if (requestEpoch === epoch) preparing.value = false
    }
  }

  function schedulePoll(planId: string, requestEpoch: number): void {
    if (requestEpoch !== epoch) return
    if (pollCount >= PREPARING_POLL_LIMIT) return
    pollCount += 1
    pollTimer = setTimeout(async () => {
      if (requestEpoch !== epoch) return
      const plan = await request<VisualPlan>(`/api/creation-studio/visual-plans/${planId}`, { method: 'GET' })
      if (requestEpoch !== epoch || !plan) return
      applyPlan(plan)
      if (plan.status === 'preparing') schedulePoll(planId, requestEpoch)
      else clearPollTimer()
    }, PREPARING_POLL_INTERVAL_MS)
  }

  // ---- API101-09 load ----

  async function refresh(planId?: string): Promise<void> {
    const id = planId ?? current.value?.id
    if (!id) return
    const requestEpoch = ++epoch
    clearSaveTimer()
    const plan = await request<VisualPlan>(`/api/creation-studio/visual-plans/${id}`, { method: 'GET' })
    if (requestEpoch !== epoch || !plan) return
    applyPlan(plan)
  }

  /** 恢复 studio.visualPlan 引用（刷新后按 ID 读回当前计划）。 */
  async function restore(planId: string): Promise<void> {
    await refresh(planId)
  }

  // ---- 编辑（本地副本 + 800ms 队列） ----

  const dirty = computed(() => !sameDocument(document.value, current.value?.document ?? null))

  function touch(): void {
    if (!current.value || !document.value) return
    queueSave()
  }

  function queueSave(): void {
    if (!dirty.value) return
    clearSaveTimer()
    saveTimer = setTimeout(() => { void flush() }, PLAN_SAVE_DEBOUNCE_MS)
  }

  // ---- API101-10 PATCH ----

  async function flush(): Promise<boolean> {
    clearSaveTimer()
    if (!current.value || !document.value || !dirty.value || saving.value) return !dirty.value
    saving.value = true
    error.value = ''
    const expectedRevision = current.value.revision
    savingDocument = cloneDocument(document.value)
    // 无响应重试沿用同键（安全重放）；编辑变更后换新键。
    if (pendingPatch && pendingPatch.expectedRevision !== expectedRevision) pendingPatch = null
    if (!pendingPatch) pendingPatch = { requestId: crypto.randomUUID(), expectedRevision }
    try {
      const plan = await request<VisualPlan>(`/api/creation-studio/visual-plans/${current.value.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: pendingPatch.requestId,
          expectedRevision,
          document: savingDocument,
        }),
      })
      if (!plan) {
        pendingPatch = null
        return false
      }
      pendingPatch = null
      applyPlan(plan)
      return true
    } finally {
      saving.value = false
      savingDocument = null
    }
  }

  // ---- API101-11 confirm ----

  async function confirm(): Promise<boolean> {
    if (!current.value || confirming.value) return false
    // 保存成功后才能确认：先 flush 计划编辑。
    if (!await flush()) return false
    confirming.value = true
    error.value = ''
    try {
      const plan = current.value
      const confirmed = await request<VisualPlan>(`/api/creation-studio/visual-plans/${plan.id}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: crypto.randomUUID(),
          draftId: options.draftId(),
          expectedDraftVersion: options.draftVersion(),
          expectedRevision: plan.revision,
          sourceContentHash: plan.source.contentHash,
        }),
      })
      if (!confirmed) return false
      applyPlan(confirmed)
      return true
    } finally {
      confirming.value = false
    }
  }

  // ---- 条目编辑操作（顺序变动不变身份；position 连续重排） ----

  function renumber(): void {
    const items = document.value?.items ?? []
    items.forEach((item, index) => { item.position = index + 1 })
  }

  function moveItem(index: number, direction: -1 | 1): void {
    const items = document.value?.items
    if (!items) return
    const target = index + direction
    if (target < 0 || target >= items.length) return
    const [moved] = items.splice(index, 1)
    items.splice(target, 0, moved)
    renumber()
    touch()
  }

  /** 删除封面须先指定新封面（§6.5）；低于模板下限同样拒绝。 */
  function removeItem(index: number): string | null {
    const items = document.value?.items
    if (!items) return null
    const item = items[index]
    if (!item) return null
    if (items.length <= 1) return '至少保留 1 项，不能删除'
    if (item.role === 'cover') return '删除封面前，请先把其他页设为封面'
    items.splice(index, 1)
    renumber()
    touch()
    return null
  }

  /** 指定新合法封面：目标项移到首位并升为 cover，原封面降为 content（身份不变）。 */
  function promoteToCover(index: number): void {
    const items = document.value?.items
    if (!items) return
    const target = items[index]
    if (!target || target.role === 'cover') return
    const previous = items.find((item) => item.role === 'cover')
    if (previous) previous.role = target.role === 'summary' ? 'summary' : 'content'
    target.role = 'cover'
    items.splice(index, 1)
    items.unshift(target)
    renumber()
    touch()
  }

  function dismiss(): void {
    epoch += 1
    clearSaveTimer()
    clearPollTimer()
    error.value = ''
  }

  return {
    current, document, sourceBlocks, boundSource, preparing, saving, confirming, error, dirty,
    prepare, refresh, restore, flush, confirm, touch, moveItem, removeItem, promoteToCover,
    bindSource, dismiss,
  }
}

export type VisualPlanController = ReturnType<typeof useVisualPlan>
export type { VisualPlanItem }

const MAX_AI_BLOCKS = 200
const MAX_AI_CODE_POINTS = 8000

/** §5.1 AI 输入选择缺省：按顺序取块，至多 200 块 / 合计 8,000 code point。 */
export function defaultSelectedBlockIds(blocks: SourceBlock[]): string[] {
  const ids: string[] = []
  let total = 0
  for (const block of blocks) {
    if (ids.length >= MAX_AI_BLOCKS) break
    const length = block.endCodePoint - block.startCodePoint
    if (total + length > MAX_AI_CODE_POINTS && ids.length > 0) break
    ids.push(block.id)
    total += length
  }
  return ids
}

/**
 * C101-06 编排：冻结当前正文为 draft-content 来源（无既有来源时）→ 绑定块表 → 发起一次策划。
 * 既有 studio 来源（用户导入原稿）直接复用——图卡计划绑定最初导入的原文（§6.5 来源选择）。
 */
export async function launchVisualPlan(plan: VisualPlanController, options: {
  draftId: () => string | null
  draftVersion: () => number
  ensureDraftSaved: () => Promise<boolean>
  setStudioSource: (documentId: string, recipe?: RecipeRef) => void
  sourceDocumentId: () => string | null
}): Promise<void> {
  if (plan.preparing.value) return
  const existing = options.sourceDocumentId()
  if (existing) {
    if (!plan.boundSource.value || plan.boundSource.value.id !== existing) {
      const response = await fetch(`/api/creation-studio/sources/${existing}`, { method: 'GET' })
      const body = await response.json().catch(() => null) as
        { success?: boolean; data?: SourceDocument } | null
      if (!response.ok || !body?.success || !body.data) return
      plan.bindSource(body.data)
    }
  } else {
    const draftId = options.draftId()
    if (!draftId || !await options.ensureDraftSaved()) return
    const response = await fetch('/api/creation-studio/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        requestId: crypto.randomUUID(),
        draftId,
        expectedDraftVersion: options.draftVersion(),
        kind: 'draft-content',
      }),
    })
    const body = await response.json().catch(() => null) as
      { success?: boolean; data?: SourceDocument } | null
    if (!response.ok || !body?.success || !body.data) return
    plan.bindSource(body.data)
    options.setStudioSource(body.data.id)
  }
  const blocks = plan.boundSource.value?.blocks ?? []
  await plan.prepare({ selectedBlockIds: defaultSelectedBlockIds(blocks) })
}
