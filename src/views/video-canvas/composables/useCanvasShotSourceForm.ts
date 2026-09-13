import { computed, getCurrentScope, onScopeDispose, ref, watch, type ComputedRef, type Ref } from 'vue'
import { fetchApi } from '../../../composables/grassland-http'
import type { ShotMediaSource, SaveShotSourcesRequest } from '../../../types/video-canvas'
import { fetchPersonalMediaAssets, type PersonalMediaAsset } from './usePersonalMediaLibrary'

export interface OwnMediaMeta { id: string; name: string; isImage: boolean; durationMs: number | null; hasAudio: boolean }
export interface ShotSourceFormHost {
  options: Ref<PersonalMediaAsset[]>
  optionsLoading: Ref<boolean>
  optionsError: Ref<string>
  selectedMediaId: Ref<string>
  selectedMedia: ComputedRef<OwnMediaMeta | null>
  probeLoading?: Ref<boolean>
  probeError?: Ref<string>
  saving: Ref<boolean>
  conflict: Ref<boolean>
  error: Ref<string>
  loadOptions: () => Promise<void>
  selectMedia: (mediaId: string) => void
  save: (shotId: string, source: ShotMediaSource) => Promise<boolean>
  dirty: Ref<boolean>
  stage: (shotId: string, source: ShotMediaSource | null, changed: boolean) => void
  flush: () => Promise<boolean>
  reset: () => void
}

/** Source drafts and metadata belong to a specific shot; no late request may retarget a different shot. */
export function useCanvasShotSourceForm(options: {
  authenticated: () => boolean
  storyboardId: () => string | null
  editVersion: () => number | null
  currentShot?: () => { id: string; source?: ShotMediaSource | null } | null
  reload: () => Promise<void>
  epoch?: () => number
}): ShotSourceFormHost {
  const { authenticated, storyboardId, editVersion, reload } = options
  const assets = ref<PersonalMediaAsset[]>([])
  const optionsLoading = ref(false); const optionsError = ref('')
  const selectedMediaId = ref(''); const probedDurationMs = ref<number | null>(null)
  const probedHasAudio = ref<boolean | null>(null); const probeLoading = ref(false); const probeError = ref('')
  const saving = ref(false); const conflict = ref(false); const error = ref(''); const dirty = ref(false)
  let generation = 0; let optionSequence = 0; let probeSequence = 0
  let staged: { shotId: string; source: ShotMediaSource | null } | null = null
  let pending: Promise<boolean> | null = null
  let cancelProbe: (() => void) | null = null
  const cloneSource = (source: ShotMediaSource | null) => source ? { ...source } : null
  const shotKey = () => options.currentShot?.()?.id ?? ''

  function reset(): void {
    generation++; optionSequence++; probeSequence++; cancelProbe?.(); cancelProbe = null
    staged = null; pending = null; dirty.value = false; saving.value = false; error.value = ''; conflict.value = false
    selectedMediaId.value = ''; probedDurationMs.value = null; probedHasAudio.value = null
    assets.value = []; optionsLoading.value = false; optionsError.value = ''; probeLoading.value = false; probeError.value = ''
  }
  function stage(shotId: string, source: ShotMediaSource | null, changed: boolean): void {
    if (options.currentShot && shotId !== shotKey()) return
    staged = changed ? { shotId, source: cloneSource(source) } : null
    dirty.value = changed
  }
  function flush(): Promise<boolean> {
    if (pending) return pending
    const run = drain(); pending = run
    void run.finally(() => { if (pending === run) pending = null })
    return run
  }
  async function drain(): Promise<boolean> {
    const ticket = generation
    while (staged && ticket === generation) {
      const next = staged
      if (!next.source) { error.value = '请完成素材与裁剪区间后保存'; return false }
      if (!(await saveSource(next.shotId, next.source))) return false
      if (ticket !== generation) return false
      if (staged === next || (staged?.shotId === next.shotId && JSON.stringify(staged.source) === JSON.stringify(next.source))) {
        staged = null; dirty.value = false
      }
    }
    return ticket === generation
  }
  async function loadOptions(): Promise<void> {
    if (!authenticated()) return
    const ticket = generation; const sequence = ++optionSequence
    optionsLoading.value = true; optionsError.value = ''
    const current = () => ticket === generation && sequence === optionSequence
    try { const result = await fetchPersonalMediaAssets(); if (current()) assets.value = result }
    catch (err) { if (current()) optionsError.value = err instanceof Error ? err.message : '素材读取失败' }
    finally { if (current()) optionsLoading.value = false }
  }
  const selectedAsset = computed(() => assets.value.find(asset => asset.mediaId === selectedMediaId.value) ?? null)
  const selectedMedia = computed<OwnMediaMeta | null>(() => {
    const asset = selectedAsset.value
    if (!asset) return null
    const isImage = (asset.mimeType ?? '').startsWith('image/')
    return { id: asset.mediaId, name: asset.title || '未命名素材', isImage,
      durationMs: isImage ? null : probedDurationMs.value,
      // Unknown browser audio metadata remains subject to the server's ffprobe validation.
      hasAudio: !isImage && probedHasAudio.value !== false }
  })

  async function probe(asset: PersonalMediaAsset | null): Promise<void> {
    const sequence = ++probeSequence; cancelProbe?.(); cancelProbe = null
    probedDurationMs.value = null; probedHasAudio.value = null; probeError.value = ''; probeLoading.value = false
    if (!asset || !(asset.mimeType ?? '').startsWith('video/')) return
    const ticket = generation; const selectedShot = shotKey(); const controller = new AbortController()
    let video: HTMLVideoElement | null = null; let finishVideo: (() => void) | null = null
    const current = () => ticket === generation && sequence === probeSequence && shotKey() === selectedShot && selectedMediaId.value === asset.mediaId
    const cleanup = () => {
      controller.abort()
      if (video) { video.onloadedmetadata = null; video.onerror = null; video.removeAttribute('src'); video.load() }
      finishVideo?.(); finishVideo = null
    }
    const timeout = setTimeout(() => {
      if (current()) { probeLoading.value = false; probeError.value = '素材时长读取超时，可重选素材重试'; probeSequence++ }
      cleanup()
    }, 10_000)
    cancelProbe = () => { clearTimeout(timeout); cleanup() }; probeLoading.value = true
    try {
      const response = await fetchApi(`/api/content-assets/${encodeURIComponent(asset.assetId)}/download-url`, { signal: controller.signal })
      if (!response.ok) throw new Error('素材预览地址读取失败，可重新选择素材')
      const body = await response.json() as { data?: { downloadUrl?: string } }
      if (!current()) return
      const url = body.data?.downloadUrl
      if (!url) throw new Error('素材预览暂不可用')
      await new Promise<void>(resolve => {
        finishVideo = resolve; video = document.createElement('video'); video.preload = 'metadata'
        video.onloadedmetadata = () => {
          if (current() && video) {
            probedDurationMs.value = Number.isFinite(video.duration) ? Math.round(video.duration * 1000) : null
            const tracks = (video as HTMLVideoElement & { audioTracks?: { length: number }; mozHasAudio?: boolean })
            // WebKit may expose an empty audioTracks list even for a real AAC track.
            // Absence from that list is unknown; ffprobe remains the save-time authority.
            probedHasAudio.value = tracks.audioTracks?.length ? true : tracks.mozHasAudio ?? null
          }
          resolve()
        }
        video.onerror = () => { if (current()) probeError.value = '素材时长暂不可读，保存时会校验区间'; resolve() }
        video.src = url
      })
    } catch (err) { if (current()) probeError.value = err instanceof Error ? err.message : '素材时长读取失败' }
    finally {
      clearTimeout(timeout)
      if (current()) { probeLoading.value = false; cancelProbe = null }
      cleanup()
    }
  }
  watch(() => [shotKey(), selectedMediaId.value, selectedAsset.value], () => { void probe(selectedAsset.value) })
  watch(() => [storyboardId(), options.epoch?.(), authenticated()], () => { reset(); void loadOptions() }, { immediate: true, flush: 'sync' })
  watch(() => [shotKey(), options.currentShot?.()?.source] as const, ([id, source], previous) => {
    if (id === previous?.[0] && dirty.value) return
    selectedMediaId.value = source?.kind === 'own-media' ? source.mediaId : ''
    if (id !== previous?.[0]) { staged = null; dirty.value = false; error.value = ''; conflict.value = false }
  }, { immediate: true, flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(reset)
  function selectMedia(mediaId: string): void {
    if (mediaId === selectedMediaId.value) { void probe(selectedAsset.value); return }
    selectedMediaId.value = mediaId
  }
  function save(shotId: string, source: ShotMediaSource): Promise<boolean> { stage(shotId, source, true); return flush() }
  async function saveSource(shotId: string, source: ShotMediaSource): Promise<boolean> {
    const id = storyboardId(); const version = editVersion()
    if (!id || !version) { error.value = '项目尚未载入，请稍后重试'; return false }
    const ticket = generation; saving.value = true; conflict.value = false; error.value = ''
    try {
      const body: SaveShotSourcesRequest = { expectedEditVersion: version, sources: [{ shotId, source }] }
      const response = await fetchApi(`/api/video-production/storyboards/${encodeURIComponent(id)}/sources`, { method: 'PATCH', body: JSON.stringify(body) })
      if (ticket !== generation) return false
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { error?: string; code?: string } | null
        if (ticket !== generation) return false
        conflict.value = response.status === 409 && (!payload?.code || payload.code.includes('VERSION_CONFLICT'))
        error.value = conflict.value ? '分镜版本已变化，请刷新载入最新后再试' : payload?.error || '来源保存失败'
        return false
      }
      await reload()
      return ticket === generation
    } catch (err) { if (ticket === generation) error.value = err instanceof Error ? err.message : '来源保存失败'; return false }
    finally { if (ticket === generation) saving.value = false }
  }
  return { options: assets, optionsLoading, optionsError, selectedMediaId, selectedMedia, probeLoading, probeError,
    saving, conflict, error, loadOptions, selectMedia, save, dirty, stage, flush, reset }
}
