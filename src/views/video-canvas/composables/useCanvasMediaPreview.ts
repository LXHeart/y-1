import { computed, getCurrentInstance, getCurrentScope, onActivated, onDeactivated, onScopeDispose, ref, watch } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { ShotMediaSource } from '../../../types/video-canvas'

/** On-demand signed media access. The source identity, range and audio policy travel together. */
export function useCanvasMediaPreview(options: {
  identity: () => string
  source: () => ShotMediaSource | null | undefined
}) {
  const url = ref<string | null>(null); const mimeType = ref(''); const loading = ref(false); const error = ref('')
  let generation = 0; let active = true; let controller: AbortController | null = null
  const source = computed(() => { const value = options.source(); return value?.kind === 'own-media' ? value : null })
  const range = computed(() => ({ trimStartMs: source.value?.trimStartMs ?? null, trimEndMs: source.value?.trimEndMs ?? null }))
  const muted = computed(() => source.value?.audioMode !== 'source')
  const isImage = computed(() => mimeType.value.startsWith('image/'))
  function clear(): void {
    generation++; controller?.abort(); controller = null
    url.value = null; mimeType.value = ''; loading.value = false; error.value = ''
  }
  watch(() => [options.identity(), source.value?.mediaId, source.value?.trimStartMs, source.value?.trimEndMs, source.value?.audioMode], clear, { flush: 'sync' })
  async function load(): Promise<void> {
    if (!source.value || !active || loading.value) return
    clear(); const ticket = generation; const mediaId = source.value.mediaId
    const requestController = new AbortController(); controller = requestController; loading.value = true
    const timer = setTimeout(() => requestController.abort(), 15_000)
    try {
      const result = await request<{ id: string; downloadUrl: string; mimeType: string }>(`/api/media/${encodeURIComponent(mediaId)}`, { signal: requestController.signal })
      if (ticket !== generation || !active) return
      if (result.id !== mediaId || !result.downloadUrl || !/^(image|video)\//.test(result.mimeType)) throw new Error('素材预览响应不完整，请重试')
      url.value = result.downloadUrl; mimeType.value = result.mimeType
    } catch (failure) {
      if (ticket === generation && active) error.value = requestController.signal.aborted ? '预览地址读取超时，请重试'
        : failure instanceof Error ? failure.message : '素材预览失败，请重试'
    } finally { clearTimeout(timer); if (ticket === generation) { loading.value = false; controller = null } }
  }
  function deactivate(): void { active = false; clear() }
  function activate(): void { active = true }
  if (getCurrentInstance()) { onDeactivated(deactivate); onActivated(activate) }
  if (getCurrentScope()) onScopeDispose(deactivate)
  return { url, mimeType, isImage, loading, error, range, muted, load, clear, activate, deactivate }
}
