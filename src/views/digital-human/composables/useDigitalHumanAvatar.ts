/**
 * 自有形象上传域 composable（任务书 #105F C105F-04 / 共享契约 K03 API30-31、K09、K14.3）。
 *
 * - upload：本地预检（JPEG/PNG、≤10MiB）→ 既有媒体三步（upload-ticket → presigned PUT →
 *   confirm）→ mediaId；进度只反映真实阶段（uploading/processing），无虚百分比。
 * - process：API30（rightsAccepted=true 由组件确认后传入）→ 轮询 API31 至 ready/failed/revoked
 *   （同录制轮询口径：请求完成后 1 秒、至多 90 次、换号/隐藏终止）。
 * - 失败保留配置：不重置调用方的文件/授权选择，由组件层保留重试。
 */
import { ref, shallowRef } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { request, putToPresignedUrl, GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { AvatarItem } from '../../../types/digital-human'
import type { MediaUploadTicket } from '../../../types/grassland'

export const AVATAR_MAX_BYTES = 10 * 1024 * 1024 // K09：原图上界
export const AVATAR_MIME_WHITELIST = ['image/jpeg', 'image/png']
export const AVATAR_RIGHTS_VERSION = 'dh-avatar-v1'
export const AVATAR_POLL_INTERVAL_MS = 1000
export const AVATAR_POLL_MAX_ATTEMPTS = 90

function messageOf(failure: unknown): string {
  if (failure instanceof GrasslandHttpError) return failure.message
  if (failure instanceof Error && failure.message) return failure.message
  return '网络异常，请稍后重试。'
}

export type AvatarStage = 'idle' | 'uploading' | 'processing' | 'ready' | 'failed'

export function useDigitalHumanAvatar(api: DigitalHumanApi, account: AccountSessionPort) {
  const stage = ref<AvatarStage>('idle')
  const error = ref<string | null>(null)
  const avatar = shallowRef<AvatarItem | null>(null)
  const mediaId = ref<string | null>(null)
  let polling = false
  let pollGeneration = 0

  function delay(ms: number): Promise<void> {
    return new Promise((resolve) => { setTimeout(resolve, ms) })
  }

  /** 三步上传 + 受理处理；成功进入 processing 轮询。 */
  async function upload(file: File): Promise<boolean> {
    if (stage.value === 'uploading' || stage.value === 'processing') return false
    error.value = null
    if (!AVATAR_MIME_WHITELIST.includes(file.type)) {
      error.value = '仅支持 JPG / PNG 图片。'
      return false
    }
    if (file.size > AVATAR_MAX_BYTES) {
      error.value = '图片不能超过 10MiB。'
      return false
    }
    const ticket = account.capture()
    stage.value = 'uploading'
    try {
      const uploadTicket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
        method: 'POST',
        body: JSON.stringify({ contentType: file.type, purpose: 'user_upload', sizeBytes: file.size }),
      })
      await putToPresignedUrl(uploadTicket, file)
      const confirmed = await request<{ id: string }>(`/api/media/${uploadTicket.id}/confirm`, {
        method: 'POST',
      })
      if (!account.isCurrent(ticket)) return false
      mediaId.value = confirmed.id
      return await process(confirmed.id)
    } catch (failure) {
      if (account.isCurrent(ticket)) {
        error.value = messageOf(failure)
        stage.value = 'failed' // 保留配置（文件/授权由组件持有），可重试
      }
      return false
    }
  }

  /** API30 受理 → API31 轮询到终态（processing/failed/revoked 之外）。 */
  async function process(targetMediaId: string): Promise<boolean> {
    stage.value = 'processing'
    error.value = null
    const ticket = account.capture()
    let avatarId: string | null
    try {
      // API30 回 202 AvatarItem（K03 契约行）；预置客户端的 Operation 类型偏保守，按字段事实取 id。
      const created = await api.createAvatar({
        requestId: crypto.randomUUID(),
        mediaId: targetMediaId,
        rightsAccepted: true,
        rightsVersion: AVATAR_RIGHTS_VERSION,
      }) as unknown as Partial<AvatarItem> & { id?: string; state?: string }
      if (!account.isCurrent(ticket)) return false
      avatarId = typeof created.id === 'string' ? created.id : null
    } catch (failure) {
      if (account.isCurrent(ticket)) {
        error.value = messageOf(failure)
        stage.value = 'failed'
      }
      return false
    }
    if (avatarId == null) {
      error.value = '受理回执缺少形象 id。'
      stage.value = 'failed'
      return false
    }
    return await pollUntilSettled(avatarId)
  }

  async function pollUntilSettled(avatarId: string): Promise<boolean> {
    if (polling) return true
    polling = true
    const generation = pollGeneration
    try {
      for (let attempt = 0; attempt < AVATAR_POLL_MAX_ATTEMPTS; attempt += 1) {
        if (generation !== pollGeneration) return false
        const ticket = account.capture()
        let next: AvatarItem | null = null
        try {
          next = await api.getAvatar(avatarId, ticket.signal) as unknown as AvatarItem | null
        } catch {
          next = null
        }
        if (generation !== pollGeneration || !account.isCurrent(ticket)) return false
        if (next != null) {
          avatar.value = next
          if (next.state !== 'processing') {
            if (next.state === 'ready') {
              stage.value = 'ready'
              return true
            }
            stage.value = 'failed'
            error.value = next.reasonCode ?? `形象处理未通过（${next.state}）。`
            return false
          }
        }
        await delay(AVATAR_POLL_INTERVAL_MS)
      }
      stage.value = 'failed'
      error.value = '处理超时，请稍后在角色列表查看结果。'
      return false
    } finally {
      polling = false
    }
  }

  /** 隐藏/卸载：终止当前轮询。 */
  function dispose(): void {
    pollGeneration += 1
  }

  function reset(): void {
    dispose()
    stage.value = 'idle'
    error.value = null
    avatar.value = null
    mediaId.value = null
  }

  return { stage, error, avatar, mediaId, upload, dispose, reset }
}
