import type { CreationDeliveryContract } from '../../../types/creation'
import type { CreationDraft } from '../../../types/creation-assistant'
import type { useCreationDraftSessions } from '../../../lib/creation-draft-session'

/**
 * 任务书 #100 C100-07/C100-18：交付字段写入（只写草稿 delivery，不触发媒体重生成；
 * 部分字段补全为完整契约——version/platform/contentForm 缺省补底，与快速模式 autosave 同构）。
 * 装配下沉以守住视图体积门禁。
 */
export function queueDeliverySave(
  getDraftSession: ReturnType<typeof useCreationDraftSessions>,
  draftId: () => string,
  currentDelivery: () => Partial<CreationDeliveryContract>,
  platform: () => string,
): (next: Partial<CreationDeliveryContract>) => void {
  return (next) => {
    const id = draftId()
    if (!id) return
    const session = getDraftSession(id)
    const current: CreationDraft | null = session.draft.value
    if (!current) return
    session.queueSave({
      workspace: {
        ...(current.workspace ?? {}),
        delivery: {
          version: 1,
          platform: platform(),
          contentForm: 'video',
          ...currentDelivery(),
          ...next,
        },
      },
    })
    void session.flush()
  }
}
