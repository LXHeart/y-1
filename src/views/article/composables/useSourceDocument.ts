import { ref } from 'vue'
import type { SourceDocument } from '../../../types/creation-studio'
import { studioPost, studioErrorMessage, useStudioGuard } from '../../../lib/creation-studio-http'

/**
 * 任务书 #101 C101-03：原稿导入状态机（§4.3 原稿输入 UI）。
 *
 * empty/editing/saving/saved/error：saving 禁重复导入，error 保留原文；已创建文档不可变，
 * 重新导入创建新 ID。epoch 防迟到响应；requestId 只在网络结果未知（无响应）时沿用——
 * 同键同输入重放安全；确定性 4xx（如版本冲突）后换新键重试，不与旧键冲突。
 */
export type SourceInputState = 'empty' | 'editing' | 'saving' | 'saved' | 'error'

export interface SourceDocumentImportInput {
  draftId: string
  expectedDraftVersion: number
  kind: 'plain-text' | 'markdown'
  text: string
  title?: string
}

export function useSourceDocument(options: {
  draftId?: () => string | null
  onImported: (document: SourceDocument) => void | boolean | Promise<void | boolean>
}) {
  const state = ref<SourceInputState>('empty')
  const error = ref('')
  const importedId = ref<string | null>(null)
  const importedHash = ref<string | null>(null)
  /** 迟到响应丢弃：每次导入递增；卸载后全部丢弃（账号 epoch 由草稿会话层处理）。 */
  let epoch = 0
  let pendingRequestId: string | null = null
  let pendingPayload: SourceDocumentImportInput | null = null
  const guard = useStudioGuard(options.draftId)
  guard.onInvalidate(reset)

  function reset(): void {
    epoch += 1
    pendingRequestId = null
    pendingPayload = null
    state.value = 'empty'
    error.value = ''
    importedId.value = null
    importedHash.value = null
  }

  /** 编排层失败（如草稿尚未保存成功）——不发起网络请求，保留输入。 */
  function fail(message: string): void {
    epoch += 1
    pendingRequestId = null
    pendingPayload = null
    state.value = 'error'
    error.value = message
  }

  function markEditing(): void {
    if (state.value === 'saving') return
    if (state.value !== 'saved') state.value = 'editing'
  }

  async function importSource(input: SourceDocumentImportInput): Promise<boolean> {
    if (state.value === 'saving') return false
    const requestEpoch = ++epoch
    const isCurrent = guard.capture()
    state.value = 'saving'
    error.value = ''
    // 只有「无响应」的同一意图才沿用 requestId（安全重放）；确定性失败后重试换新键。
    const sameIntent = pendingPayload != null
      && pendingPayload.draftId === input.draftId
      && pendingPayload.expectedDraftVersion === input.expectedDraftVersion
      && pendingPayload.kind === input.kind
      && pendingPayload.text === input.text
      && pendingPayload.title === input.title
    if (!sameIntent) {
      pendingRequestId = crypto.randomUUID()
      pendingPayload = input
    }
    try {
      const document = await studioPost<SourceDocument>('/api/creation-studio/sources', {
          requestId: pendingRequestId,
          draftId: input.draftId,
          expectedDraftVersion: input.expectedDraftVersion,
          kind: input.kind,
          text: input.text,
          ...(input.title ? { title: input.title } : {}),
      })
      if (requestEpoch !== epoch || !isCurrent()) return false
      if (await options.onImported(document) === false) {
        if (requestEpoch === epoch && isCurrent()) {
          state.value = 'error'
          error.value = '来源已保存，但正文尚未保存成功；请处理草稿保存提示，原稿输入已保留'
        }
        return false
      }
      if (requestEpoch !== epoch || !isCurrent()) return false
      importedId.value = document.id
      importedHash.value = document.contentHash
      state.value = 'saved'
      return true
    } catch (failure) {
      if (requestEpoch !== epoch || !isCurrent()) return false
      state.value = 'error'
      error.value = studioErrorMessage(failure)
      return false
    }
  }

  return { state, error, importedId, importedHash, reset, fail, markEditing, importSource }
}
