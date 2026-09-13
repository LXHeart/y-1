import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useCanvasMediaPreview } from './useCanvasMediaPreview'
import type { ShotMediaSource } from '../../../types/video-canvas'

const source = (mediaId = 'media-1'): ShotMediaSource => ({ kind: 'own-media', mediaId, trimStartMs: 1250, trimEndMs: 6250, audioMode: 'source' })
const json = (id: string, url = '/signed-video', mimeType = 'video/mp4') => new Response(JSON.stringify({ success: true, data: { id, downloadUrl: url, mimeType } }))
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
describe('C102 source preview authorization and lifetime', () => {
  test('loads only on demand, refreshes the signature, and keeps range/audio identity', async () => {
    const scope = effectScope(); const current = ref(source()); const fetchMock = vi.fn().mockResolvedValueOnce(json('media-1')).mockResolvedValueOnce(json('media-1', '/renewed'))
    vi.stubGlobal('fetch', fetchMock)
    const preview = scope.run(() => useCanvasMediaPreview({ source: () => current.value, identity: () => 'shot-1' }))!
    expect(fetchMock).not.toHaveBeenCalled(); await preview.load()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/media/media-1')
    expect(preview.url.value).toBe('/signed-video'); expect(preview.muted.value).toBe(false)
    expect(preview.range.value).toEqual({ trimStartMs: 1250, trimEndMs: 6250 })
    await preview.load(); expect(preview.url.value).toBe('/renewed')
    current.value = { ...source(), audioMode: 'mute' } as ShotMediaSource
    expect(preview.url.value).toBeNull(); expect(preview.muted.value).toBe(true)
    scope.stop()
  })
  test('late requests from a different shot or deactivated page cannot set URL or clear current loading', async () => {
    const scope = effectScope(); const id = ref('shot-1'); const current = ref(source())
    const replies: Array<(response: Response) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => replies.push(resolve))))
    const preview = scope.run(() => useCanvasMediaPreview({ source: () => current.value, identity: () => id.value }))!
    const old = preview.load(); id.value = 'shot-2'; current.value = source('media-2'); const next = preview.load()
    replies[0]!(json('media-1')); await old
    expect(preview.url.value).toBeNull(); expect(preview.loading.value).toBe(true)
    preview.deactivate(); replies[1]!(json('media-2')); await next
    expect(preview.url.value).toBeNull(); expect(preview.loading.value).toBe(false)
    await preview.load(); expect(replies).toHaveLength(2)
    preview.activate(); const restored = preview.load(); replies[2]!(json('media-2')); await restored
    expect(preview.url.value).toBe('/signed-video'); scope.stop()
  })
  test('wrong media identity fails closed; retry can preview an authorized image', async () => {
    const scope = effectScope(); vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json('other')).mockResolvedValueOnce(json('media-1', '/image', 'image/png')))
    const preview = scope.run(() => useCanvasMediaPreview({ source: () => source(), identity: () => 'shot' }))!
    await preview.load(); expect(preview.error.value).toContain('响应不完整'); expect(preview.url.value).toBeNull()
    await preview.load(); expect(preview.isImage.value).toBe(true); expect(preview.error.value).toBe('')
    scope.stop()
  })
  test('a request deadline exposes retry without generating media', async () => {
    vi.useFakeTimers(); const scope = effectScope()
    const fetchMock = vi.fn((_url, init: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new Error('aborted')))
    }))
    vi.stubGlobal('fetch', fetchMock)
    const preview = scope.run(() => useCanvasMediaPreview({ source: () => source(), identity: () => 'shot' }))!
    const loading = preview.load(); await vi.advanceTimersByTimeAsync(15_000); await loading
    expect(preview.error.value).toContain('超时'); expect(preview.loading.value).toBe(false)
    expect(fetchMock.mock.calls).toHaveLength(1); scope.stop()
  })
})
