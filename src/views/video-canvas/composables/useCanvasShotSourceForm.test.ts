// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useCanvasShotSourceForm } from './useCanvasShotSourceForm'

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
    vi.unstubAllGlobals()
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
