import { watch, type Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { useCreationDraftSessions } from '../../../lib/creation-draft-session'

/**
 * 任务书 #100 C100-07/C100-19：制作任务的草稿恢复链（视图体积门禁下沉）。
 *
 * - 任务 id 回写草稿：服务端绑定响应派生 productionTaskId 后写入
 *   inputs.video.productionTaskId——刷新/AI 入口恢复全靠它；幂等（草稿已带同值
 *   跳过，不空转版本）。
 * - SRT 下载：presign 短链新窗；失败经 onError 落会话 taskError。
 */
export function useCanvasTaskRestore(options: {
  sessions: ReturnType<typeof useCreationDraftSessions>
  draftId: Ref<string>
  taskId: Ref<string>
  currentTaskId: () => string | undefined
  onError: (message: string) => void
}) {
  const { sessions, draftId, taskId, currentTaskId, onError } = options

  watch(taskId, (id) => {
    const idOfDraft = draftId.value
    if (!id || !idOfDraft) return
    const session = sessions(idOfDraft)
    const current = session.draft.value
    if (!current) return
    const workspaceNow = (current.workspace ?? {}) as {
      inputs?: { video?: Record<string, unknown> }
    } & Record<string, unknown>
    if (workspaceNow.inputs?.video?.productionTaskId === id) return
    session.queueSave({
      workspace: {
        ...workspaceNow,
        inputs: {
          ...(workspaceNow.inputs ?? {}),
          video: { ...(workspaceNow.inputs?.video ?? {}), productionTaskId: id },
        },
      },
    })
    void session.flush()
  })

  async function downloadSubtitle(): Promise<void> {
    const id = currentTaskId()
    if (!id) return
    try {
      const body = await request<{ downloadUrl: string }>(
        `/api/video-production/tasks/${id}/subtitle`,
        {},
        { fallbackError: '字幕下载失败' },
      )
      if (body?.downloadUrl) {
        window.open(body.downloadUrl, '_blank', 'noopener')
      }
    } catch (err: unknown) {
      onError(err instanceof Error ? err.message : '字幕下载失败')
    }
  }

  return { downloadSubtitle }
}
