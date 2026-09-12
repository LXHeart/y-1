import { computed, ref, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { fetchApi } from '../../../composables/grassland-http'
import type { ShotMediaSource, SaveShotSourcesRequest } from '../../../types/video-canvas'
import { fetchPersonalMediaAssets, type PersonalMediaAsset } from './usePersonalMediaLibrary'

/**
 * 任务书 #100 C100-13/C100-20（来源编辑装配补缺）：每镜制作来源表单的取数与保存回路。
 *
 * 素材选项来自个人内容资产库（与素材轨同源）；保存走 API-10
 * PATCH /api/video-production/storyboards/{id}/sources（批量入口单镜用法，带
 * expectedEditVersion CAS）。时长探测是客户端尽力而为（video 元数据）——权威校验
 * 在服务端 ffprobe（§6.5「边界校验使用 ffprobe 结果而非浏览器声称的时长」），
 * 越界/无原音等拒绝以服务端错误就地展示。committed 分镜不渲染本表单（§8.2）。
 */
export interface OwnMediaMeta {
  id: string
  name: string
  isImage: boolean
  durationMs: number | null
  hasAudio: boolean
}

export interface ShotSourceFormHost {
  options: Ref<PersonalMediaAsset[]>
  optionsLoading: Ref<boolean>
  optionsError: Ref<string>
  selectedMediaId: Ref<string>
  selectedMedia: ComputedRef<OwnMediaMeta | null>
  saving: Ref<boolean>
  conflict: Ref<boolean>
  error: Ref<string>
  loadOptions: () => Promise<void>
  /** 选定素材（host 方法——模板直写 prop 的 ref 会被 vue/no-mutating-props 拦下）。 */
  selectMedia: (mediaId: string) => void
  save: (shotId: string, source: ShotMediaSource) => Promise<boolean>
}

export function useCanvasShotSourceForm(options: {
  authenticated: () => boolean
  storyboardId: () => string | null
  editVersion: () => number | null
  /** 保存成功后的权威刷新（重载分镜拿回 source 与新 editVersion；任务在途时刷新候选）。 */
  reload: () => Promise<void>
}): ShotSourceFormHost {
  const { authenticated, storyboardId, editVersion, reload } = options

  const assets = ref<PersonalMediaAsset[]>([])
  const optionsLoading = ref(false)
  const optionsError = ref('')
  const selectedMediaId = ref('')
  /** 选定素材的客户端探测（元数据级；null=未探测到，区间输入退化为默认 0 起截）。 */
  const probedDurationMs = ref<number | null>(null)
  const saving = ref(false)
  const conflict = ref(false)
  const error = ref('')

  async function loadOptions(): Promise<void> {
    if (!authenticated()) return
    optionsLoading.value = true
    optionsError.value = ''
    try {
      assets.value = await fetchPersonalMediaAssets()
    } catch (err: unknown) {
      optionsError.value = err instanceof Error ? err.message : '素材读取失败'
    } finally {
      optionsLoading.value = false
    }
  }

  const selectedAsset = computed(() =>
    assets.value.find(asset => asset.mediaId === selectedMediaId.value) ?? null)

  const selectedMedia = computed<OwnMediaMeta | null>(() => {
    const asset = selectedAsset.value
    if (!asset) return null
    return {
      id: asset.mediaId,
      name: asset.title || '未命名素材',
      isImage: (asset.mimeType ?? '').startsWith('image/'),
      durationMs: (asset.mimeType ?? '').startsWith('video/') ? probedDurationMs.value : null,
      // 浏览器元数据阶段测不出音轨：先放开选择，无原音由服务端 ffprobe 拒绝并就地报错
      hasAudio: true,
    }
  })

  /** 客户端时长探测：video 元素只预载 metadata（不下载整文件、不启动播放）。 */
  async function probeDuration(asset: PersonalMediaAsset): Promise<void> {
    probedDurationMs.value = null
    if (!(asset.mimeType ?? '').startsWith('video/')) return
    try {
      const ticket = await fetchApi(`/api/content-assets/${encodeURIComponent(asset.assetId)}/download-url`)
      if (!ticket.ok) return
      const ticketBody = await ticket.json() as { success: boolean; data?: { downloadUrl?: string } }
      const url = ticketBody.data?.downloadUrl
      if (!url) return
      const duration = await new Promise<number | null>(resolve => {
        const video = document.createElement('video')
        video.preload = 'metadata'
        const done = (value: number | null): void => {
          video.removeAttribute('src')
          resolve(value)
        }
        video.onloadedmetadata = () =>
          done(Number.isFinite(video.duration) ? Math.round(video.duration * 1000) : null)
        video.onerror = () => done(null)
        video.src = url
      })
      probedDurationMs.value = duration
    } catch {
      // 探测失败不阻塞表单：区间上界退化（默认 0 起截），权威校验在服务端
    }
  }

  watch(selectedMediaId, id => {
    const asset = assets.value.find(item => item.mediaId === id)
    if (asset) void probeDuration(asset)
  })

  function selectMedia(mediaId: string): void {
    selectedMediaId.value = mediaId
  }

  loadOptions()

  async function save(shotId: string, source: ShotMediaSource): Promise<boolean> {
    const id = storyboardId()
    if (!id) return false
    saving.value = true
    conflict.value = false
    error.value = ''
    try {
      const body: SaveShotSourcesRequest = {
        expectedEditVersion: editVersion() ?? 1,
        sources: [{ shotId, source }],
      }
      const response = await fetchApi(
        `/api/video-production/storyboards/${encodeURIComponent(id)}/sources`,
        { method: 'PATCH', body: JSON.stringify(body) })
      if (response.status === 409) {
        conflict.value = true
        error.value = '分镜版本已变化，请刷新载入最新后再试'
        return false
      }
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { error?: string } | null
        error.value = payload?.error || '来源保存失败'
        return false
      }
      await reload()
      return true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '来源保存失败'
      return false
    } finally {
      saving.value = false
    }
  }

  return {
    options: assets,
    optionsLoading,
    optionsError,
    selectedMediaId,
    selectedMedia,
    saving,
    conflict,
    error,
    loadOptions,
    selectMedia,
    save,
  }
}
