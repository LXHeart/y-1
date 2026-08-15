// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useMomentsCreation } from './useMomentsCreation'

afterEach(() => vi.unstubAllGlobals())

/** 构造一条 SSE 响应（`data: <json>\n\n` 逐帧 + `[DONE]` 收尾，与后端 Sse.stream 一致）。 */
function sse(frames: Array<Record<string, unknown>>): Response {
  return new Response(new ReadableStream({
    start(controller) {
      const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
      lines.push('data: [DONE]', '')
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

function stubFetch(response: Response | (() => Response)) {
  const fn = vi.fn(async (_url: string, _init?: RequestInit) =>
    (typeof response === 'function' ? response() : response))
  vi.stubGlobal('fetch', fn)
  return fn
}

function imageFile(name = 'photo.png', type = 'image/png', size = 64): File {
  return new File([new Uint8Array(size)], name, { type })
}

describe('useMomentsCreation', () => {
  it('生成成功：progress 帧更新提示，result 帧落结果', async () => {
    stubFetch(sse([
      { type: 'progress', message: '正在生成朋友圈内容…' },
      {
        type: 'result',
        copy: '开业大吉，周末来店里坐坐☕',
        imageOrder: [{ index: 2, reason: '招牌先出' }, { index: 1, reason: '环境承接' }],
        captions: [{ index: 1, text: '门店环境' }, { index: 2, text: '招牌菜' }],
      },
    ]))
    const moments = useMomentsCreation()
    moments.topic.value = '新店开业'
    moments.style.value = 'store-visit'
    await moments.generate()

    expect(moments.error.value).toBe('')
    expect(moments.result.value?.copy).toBe('开业大吉，周末来店里坐坐☕')
    expect(moments.result.value?.imageOrder).toHaveLength(2)
    expect(moments.result.value?.imageOrder[0]).toEqual({ index: 2, reason: '招牌先出' })
    expect(moments.result.value?.captions[1]).toEqual({ index: 2, text: '招牌菜' })
    expect(moments.generating.value).toBe(false)
  })

  it('请求契约：POST /api/moments-generation/generate，风格与素材图 data URL 进 body', async () => {
    const fn = stubFetch(sse([{ type: 'result', copy: '文案', imageOrder: [], captions: [] }]))
    const moments = useMomentsCreation()
    moments.topic.value = '周年庆'
    moments.style.value = 'event'
    moments.feelings.value = '人流不错'
    await moments.addImages([imageFile('a.png'), imageFile('b.jpg', 'image/jpeg')])
    await moments.generate()

    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/api/moments-generation/generate')
    expect(init!.method).toBe('POST')
    const body = JSON.parse(init!.body as string)
    expect(body.topic).toBe('周年庆')
    expect(body.style).toBe('event')
    expect(body.feelings).toBe('人流不错')
    expect(body.images).toHaveLength(2)
    expect(body.images[0]).toMatch(/^data:image\/png;base64,/)
    expect(body.taskMode).toBeUndefined()
  })

  it('任务模式：bindCreationContext 后 taskMode 与 contextSnapshotId 进请求体', async () => {
    const fn = stubFetch(sse([{ type: 'result', copy: '任务文案', imageOrder: [], captions: [] }]))
    const moments = useMomentsCreation()
    moments.bindCreationContext(true, 'snapshot-1')
    moments.topic.value = '任务主题'
    moments.style.value = 'friends-share'
    await moments.generate()

    const body = JSON.parse(fn.mock.calls[0][1]!.body as string)
    expect(body.taskMode).toBe(true)
    expect(body.contextSnapshotId).toBe('snapshot-1')
  })

  it('流内 error 帧：置错并清结果', async () => {
    stubFetch(sse([{ type: 'error', error: '朋友圈内容生成失败' }]))
    const moments = useMomentsCreation()
    moments.topic.value = '主题'
    moments.style.value = 'lifestyle'
    await moments.generate()

    expect(moments.error.value).toBe('朋友圈内容生成失败')
    expect(moments.result.value).toBeNull()
  })

  it('SSE 前置失败（402 积分不足）：读 JSON error，不发流', async () => {
    stubFetch(new Response(JSON.stringify({ success: false, error: '积分不足' }), { status: 402 }))
    const moments = useMomentsCreation()
    moments.topic.value = '主题'
    moments.style.value = 'lifestyle'
    await moments.generate()

    expect(moments.error.value).toBe('积分不足')
    expect(moments.result.value).toBeNull()
  })

  it('缺主题或风格时不发请求', async () => {
    const fn = stubFetch(sse([{ type: 'result', copy: 'x', imageOrder: [], captions: [] }]))
    const moments = useMomentsCreation()
    await moments.generate()
    expect(fn).not.toHaveBeenCalled()
    expect(moments.error.value).toContain('主题')

    moments.topic.value = '有主题'
    await moments.generate()
    expect(fn).not.toHaveBeenCalled()
    expect(moments.error.value).toContain('风格')
  })

  it('addImages：超 9 张报错、非白名单类型报错、超 5MB 报错', async () => {
    const moments = useMomentsCreation()
    await moments.addImages(Array.from({ length: 10 }, () => imageFile()))
    expect(moments.images.value).toHaveLength(0)
    expect(moments.error.value).toBe('最多上传 9 张图片')

    moments.error.value = ''
    await moments.addImages([imageFile('a.gif', 'image/gif')])
    expect(moments.error.value).toBe('仅支持 JPG、PNG、WebP 图片')

    moments.error.value = ''
    await moments.addImages([imageFile('big.png', 'image/png', 5 * 1024 * 1024 + 1)])
    expect(moments.error.value).toBe('单张图片不能超过 5 MB')
  })

  it('removeImage 按 id 移除并重排序号', async () => {
    const moments = useMomentsCreation()
    await moments.addImages([imageFile('a.png'), imageFile('b.png')])
    moments.removeImage(moments.images.value[0].id)
    expect(moments.images.value).toHaveLength(1)
    expect(moments.images.value[0].name).toBe('b.png')
  })

  it('reset 清空全部状态', async () => {
    stubFetch(sse([{ type: 'result', copy: '文案', imageOrder: [], captions: [] }]))
    const moments = useMomentsCreation()
    moments.topic.value = '主题'
    moments.style.value = 'event'
    await moments.addImages([imageFile()])
    await moments.generate()
    moments.reset()

    expect(moments.topic.value).toBe('')
    expect(moments.style.value).toBe('')
    expect(moments.images.value).toHaveLength(0)
    expect(moments.result.value).toBeNull()
  })
})
