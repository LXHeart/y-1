// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useCanvasShotSourceForm } from './useCanvasShotSourceForm'
import { effectScope, nextTick, ref } from 'vue'
import type { ShotMediaSource } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-13/C100-20（来源编辑装配补缺）：选项取数 + API-10 保存回路。
 * 权威校验在服务端（ffprobe）——客户端只负责就地上报 409/4xx 错误与成功后的权威刷新。
 */

function jsonResponse(body: unknown, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

type FetchLike = (url: string, init?: RequestInit) => Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>

function listResponse(items: Array<{ mediaId: string; title: string; status: string; mimeType: string }>) {
  const fn: FetchLike = async () => jsonResponse({
    success: true,
    data: { items: items.map((item, index) => ({
      id: `asset-${index + 1}`, mediaId: item.mediaId, title: item.title,
      status: item.status, mimeType: item.mimeType, validUntil: null,
    })) },
  })
  return vi.fn(fn)
}

function makeForm(overrides: Partial<{
  storyboardId: string
  editVersion: number
  reload: () => Promise<void>
}> = {}) {
  const reload = overrides.reload ?? vi.fn(async () => undefined)
  const form = useCanvasShotSourceForm({
    authenticated: () => true,
    storyboardId: () => overrides.storyboardId ?? 'sb-1',
    editVersion: () => overrides.editVersion ?? 3,
    reload,
  })
  return { form, reload }
}

describe('#100 C100-13/C100-20：每镜制作来源表单（useCanvasShotSourceForm）', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test.each([
    { label: 'WebKit empty track list', audioTracks: { length: 0 }, mozHasAudio: undefined, expected: true },
    { label: 'positive track evidence', audioTracks: { length: 1 }, mozHasAudio: undefined, expected: true },
    { label: 'explicit silent metadata', audioTracks: { length: 0 }, mozHasAudio: false, expected: false },
    { label: 'Firefox audio evidence', audioTracks: undefined, mozHasAudio: true, expected: true },
    { label: 'unsupported audio metadata', audioTracks: undefined, mozHasAudio: undefined, expected: true },
  ])('TC102-051/052：$label does not confuse unavailable browser metadata with confirmed silence', async ({ audioTracks, mozHasAudio, expected }) => {
    const video = document.createElement('video')
    vi.spyOn(video, 'load').mockImplementation(() => {})
    Object.defineProperties(video, {
      duration: { value: 10, configurable: true },
      audioTracks: { value: audioTracks, configurable: true },
      mozHasAudio: { value: mozHasAudio, configurable: true },
    })
    vi.spyOn(document, 'createElement').mockReturnValueOnce(video)
    vi.stubGlobal('fetch', vi.fn(async (url: string) => jsonResponse({ success: true, data: url.includes('download-url')
      ? { downloadUrl: '/real-audio-source' }
      : { items: [{ id: 'asset-1', mediaId: 'm-1', title: '实拍', status: 'active', mimeType: 'video/mp4', validUntil: null }] } })))
    const scope = effectScope()
    const form = scope.run(() => makeForm().form)!
    await vi.waitFor(() => expect(form.options.value).toHaveLength(1))
    form.selectMedia('m-1')
    await vi.waitFor(() => expect(video.src).toContain('/real-audio-source'))
    video.dispatchEvent(new Event('loadedmetadata'))
    await vi.waitFor(() => expect(form.probeLoading?.value).toBe(false))
    expect(form.selectedMedia.value).toMatchObject({ durationMs: 10_000, hasAudio: expected })
    scope.stop()
  })

  test('TC102-051：来源按shot恢复，同一媒体的迟到元数据也不能写入下一镜', async () => {
    const scope = effectScope()
    const current = ref<{ id: string; source: ShotMediaSource }>({ id: 'shot-1', source: { kind: 'own-media', mediaId: 'm-1', trimStartMs: 500, trimEndMs: 5500, audioMode: 'source' } })
    const replies: Array<(value: ReturnType<typeof jsonResponse>) => void> = []
    const fetchMock = vi.fn(async (url: string) => url.includes('download-url')
      ? new Promise<ReturnType<typeof jsonResponse>>(resolve => replies.push(resolve))
      : jsonResponse({ success: true, data: { items: [{ id: 'asset-1', mediaId: 'm-1', title: '实拍', status: 'active', mimeType: 'video/mp4', validUntil: null }] } }))
    vi.stubGlobal('fetch', fetchMock)
    const form = scope.run(() => useCanvasShotSourceForm({ authenticated: () => true, storyboardId: () => 'sb', editVersion: () => 1,
      currentShot: () => current.value, reload: async () => {} }))!
    await vi.waitFor(() => expect(replies).toHaveLength(1))
    expect(form.selectedMediaId.value).toBe('m-1')
    current.value = { id: 'shot-2', source: { kind: 'own-media', mediaId: 'm-1', trimStartMs: 1250, trimEndMs: 6250, audioMode: 'mute' } }
    await nextTick(); expect(replies).toHaveLength(2)
    replies[0]!(jsonResponse({ success: true, data: { downloadUrl: '/old-shot' } }))
    await nextTick(); expect(form.selectedMedia.value?.durationMs).toBeNull()
    const video = document.createElement('video'); const load = vi.spyOn(video, 'load').mockImplementation(() => {})
    Object.defineProperty(video, 'duration', { value: 8.125, configurable: true })
    const create = vi.spyOn(document, 'createElement'); create.mockReturnValueOnce(video)
    replies[1]!(jsonResponse({ success: true, data: { downloadUrl: '/current-shot' } }))
    await vi.waitFor(() => expect(video.src).toContain('/current-shot'))
    video.dispatchEvent(new Event('loadedmetadata'))
    await vi.waitFor(() => expect(form.selectedMedia.value?.durationMs).toBe(8125))
    expect(form.dirty.value).toBe(false); expect(load).toHaveBeenCalled()
    scope.stop()
  })

  test('TC102-049/051：flush排空在途保存后的新来源，并读取刷新后的版本', async () => {
    const scope = effectScope(); let version = 3; const writes: Array<{ expectedEditVersion: number; sources: Array<{ source: ShotMediaSource }> }> = []
    let finish!: (value: ReturnType<typeof jsonResponse>) => void
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method !== 'PATCH') return jsonResponse({ success: true, data: { items: [] } })
      writes.push(JSON.parse(String(init.body)))
      if (writes.length === 1) return new Promise<ReturnType<typeof jsonResponse>>(resolve => { finish = resolve })
      return jsonResponse({ success: true, data: {} })
    }))
    const form = scope.run(() => useCanvasShotSourceForm({ authenticated: () => true, storyboardId: () => 'sb', editVersion: () => version,
      reload: async () => { version++ } }))!
    const first: ShotMediaSource = { kind: 'own-media', mediaId: 'm-1', trimStartMs: 500, trimEndMs: 5500, audioMode: 'source' }
    const saving = form.save('shot-1', first)
    form.stage('shot-1', { ...first, mediaId: 'm-2', trimStartMs: 1250, trimEndMs: 6250 }, true)
    finish(jsonResponse({ success: true, data: {} })); expect(await saving).toBe(true)
    expect(writes.map(write => write.expectedEditVersion)).toEqual([3, 4])
    expect(writes[1]?.sources[0]?.source).toMatchObject({ mediaId: 'm-2', trimStartMs: 1250, trimEndMs: 6250 })
    expect(form.dirty.value).toBe(false); scope.stop()
  })

  test('选项来自个人内容资产库；image 素材不触发下载探测', async () => {
    const fetchMock = listResponse([
      { mediaId: 'm-1', title: '海报图', status: 'active', mimeType: 'image/png' },
      { mediaId: 'm-2', title: '未生效视频', status: 'pending_review', mimeType: 'video/mp4' },
    ])
    vi.stubGlobal('fetch', fetchMock)
    const { form } = makeForm()
    await vi.waitFor(() => expect(form.options.value).toHaveLength(2))
    expect((fetchMock.mock.calls[0] as [string])[0]).toContain('/api/content-assets?libraryType=personal')

    form.selectedMediaId.value = 'm-1'
    await vi.waitFor(() => expect(form.selectedMedia.value).not.toBeNull())
    expect(form.selectedMedia.value).toMatchObject({ id: 'm-1', isImage: true, durationMs: null })
    // 图片素材无裁剪/音轨探测，不再请求下载 URL
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('保存走 API-10 单镜批量：URL/方法/expectedEditVersion/载荷正确，成功后权威刷新', async () => {
    const fetchMock = listResponse([])
    vi.stubGlobal('fetch', fetchMock)
    const { form, reload } = makeForm({ storyboardId: 'sb-9', editVersion: 7 })
    await vi.waitFor(() => expect(form.optionsLoading.value).toBe(false))

    fetchMock.mockImplementationOnce(async () => jsonResponse(
      { success: true, data: { storyboardId: 'sb-9', editVersion: 8, sources: [] } }))
    const ok = await form.save('shot-1', { kind: 'own-media', mediaId: 'm-1', trimStartMs: 0, trimEndMs: 5000, audioMode: 'mute' })
    expect(ok).toBe(true)

    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as [string, RequestInit]
    expect(call[0]).toBe('/api/video-production/storyboards/sb-9/sources')
    expect(call[1].method).toBe('PATCH')
    expect(JSON.parse(call[1].body as string)).toEqual({
      expectedEditVersion: 7,
      sources: [{ shotId: 'shot-1', source: { kind: 'own-media', mediaId: 'm-1', trimStartMs: 0, trimEndMs: 5000, audioMode: 'mute' } }],
    })
    expect(reload).toHaveBeenCalled()
    expect(form.error.value).toBe('')
  })

  test('409 版本冲突：保留表单输入与冲突提示，不刷新；4xx 服务端错误就地展示', async () => {
    const fetchMock = listResponse([])
    vi.stubGlobal('fetch', fetchMock)
    const { form, reload } = makeForm()

    fetchMock.mockImplementationOnce(async () =>
      jsonResponse({ success: false, error: 'conflict' }, false, 409))
    expect(await form.save('shot-1', { kind: 'generated' })).toBe(false)
    expect(form.conflict.value).toBe(true)
    expect(form.error.value).toContain('版本已变化')
    expect(reload).not.toHaveBeenCalled()

    fetchMock.mockImplementationOnce(async () =>
      jsonResponse({ success: false, error: '素材实测时长不足以截取该镜时长' }, false, 400))
    expect(await form.save('shot-1', { kind: 'generated' })).toBe(false)
    expect(form.error.value).toContain('素材实测时长')
  })
})
