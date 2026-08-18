// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import SpeechTranscriptionPanel from './SpeechTranscriptionPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const ticket = {
  id: 'media-speech', objectKey: 'k', uploadUrl: 'http://localhost:9002/b/k',
  method: 'PUT', headers: {}, expiresAt: null,
}

const completedTranscription = {
  id: 'tr-1', mediaId: 'media-speech', status: 'completed' as const,
  text: '[Sandbox] language=zh-CN checksum=abc123def456',
  language: 'zh-CN' as const, durationMs: 12_000,
  provider: 'sandbox', model: 'sandbox-speech-v1', modelVersion: 1,
  aiRunId: 'run-1', sandbox: true,
  createdAt: '2026-08-18T10:00:00Z', completedAt: '2026-08-18T10:00:05Z',
}

function audioFile(sizeBytes = 4096): File {
  return new File([new Uint8Array(sizeBytes)], 'memo.mp3', { type: 'audio/mpeg' })
}

/** 三步上传 + 转写四段 fetch 路由。 */
function speechRoutes(overrides: Record<string, unknown> = {}) {
  return Object.assign({
    '/api/media/upload-tickets': ticket,
    '/api/media/media-speech/confirm': { id: 'media-speech', status: 'active' },
    '/api/speech/transcriptions': completedTranscription,
  }, overrides)
}

function stubFetch(routes: Record<string, unknown>, uploadUrlOk = true): ReturnType<typeof vi.fn> {
  return vi.fn(async (url: string | URL, init?: RequestInit) => {
    const path = typeof url === 'string' ? url : url.pathname + url.search
    if (path === ticket.uploadUrl) {
      return uploadUrlOk
        ? { ok: true, status: 200 } as Response
        : { ok: false, status: 403 } as unknown as Response
    }
    for (const [key, data] of Object.entries(routes)) {
      if (path.includes(key)) return envelope(data)
    }
    return envelope(null)
  })
}

async function selectFile(wrapper: ReturnType<typeof mount>, file: File) {
  const input = wrapper.find('input[type="file"]').element as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [file] })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
}

async function startTranscribe(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find((b) => b.text().includes('开始转写'))!.trigger('click')
  await flushPromises()
}

describe('SpeechTranscriptionPanel', () => {
  test('accept 白名单与 25MiB 单文件上限校验', async () => {
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    const input = wrapper.find('input[type="file"]')
    expect(input.attributes('accept')).toContain('.mp3')
    expect(input.attributes('accept')).toContain('audio/ogg')

    await selectFile(wrapper, audioFile(25 * 1024 * 1024 + 1))
    expect(wrapper.text()).toContain('音频不能超过 25MB')
    expect(wrapper.vm.$data ?? true).toBeTruthy()
  })

  test('选择文件后展示文件名与大小预览', async () => {
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())
    expect(wrapper.text()).toContain('memo.mp3')
    expect(wrapper.text()).toContain('4KB')
  })

  test('上传确认完成后按顺序调用转写并渲染 Sandbox 结果', async () => {
    const spy = stubFetch(speechRoutes())
    vi.stubGlobal('fetch', spy)
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())
    const language = wrapper.find('select')
    await language.setValue('zh-CN')

    await startTranscribe(wrapper)

    const paths = spy.mock.calls.map((c) => String(c[0]))
    expect(paths[0]).toBe('/api/media/upload-tickets')
    expect(paths[1]).toBe(ticket.uploadUrl)
    expect(paths[2]).toBe('/api/media/media-speech/confirm')
    expect(paths[3]).toBe('/api/speech/transcriptions')
    expect(JSON.parse(spy.mock.calls[3][1].body as string)).toEqual({
      mediaId: 'media-speech', language: 'zh-CN',
    })

    expect(wrapper.text()).toContain('Sandbox')
    expect(wrapper.text()).toContain('zh-CN')
    expect(wrapper.text()).toContain('12.0 秒')
    expect(wrapper.find('textarea').element.value).toContain('[Sandbox] language=zh-CN')
  })

  test('复制按钮调用 navigator.clipboard.writeText', async () => {
    vi.stubGlobal('fetch', stubFetch(speechRoutes()))
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())
    await startTranscribe(wrapper)

    await wrapper.findAll('button').find((b) => b.text().includes('复制'))!.trigger('click')

    expect(writeText).toHaveBeenCalledWith(completedTranscription.text)
  })

  test('处理中禁用重复提交', async () => {
    let releaseTranscription: (value: Response) => void = () => {}
    const pending = new Promise<Response>((resolve) => { releaseTranscription = resolve })
    const spy = vi.fn(async (url: string | URL) => {
      const path = typeof url === 'string' ? url : url.pathname + url.search
      if (path === ticket.uploadUrl) return { ok: true, status: 200 } as Response
      if (path.includes('/api/speech/transcriptions')) return pending
      if (path.includes('upload-tickets')) return envelope(ticket)
      return envelope({ id: 'media-speech', status: 'active' })
    })
    vi.stubGlobal('fetch', spy)
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())

    const submit = wrapper.findAll('button').find((b) => b.text().includes('开始转写'))!
    await submit.trigger('click')
    await flushPromises()

    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    expect(spy.mock.calls.filter((c) => String(c[0]).includes('/api/speech/transcriptions'))).toHaveLength(1)
    releaseTranscription(envelope(completedTranscription))
    await flushPromises()
  })

  test('转写失败保留已上传媒体并允许重试', async () => {
    let failedOnce = false
    const spy = vi.fn(async (url: string | URL) => {
      const path = typeof url === 'string' ? url : url.pathname + url.search
      if (path === ticket.uploadUrl) return { ok: true, status: 200 } as Response
      if (path.includes('/api/speech/transcriptions')) {
        if (!failedOnce) {
          failedOnce = true
          return new Response(JSON.stringify({ success: false, error: '语音识别服务调用失败' }), {
            status: 502, headers: { 'Content-Type': 'application/json' },
          })
        }
        return envelope(completedTranscription)
      }
      if (path.includes('upload-tickets')) return envelope(ticket)
      return envelope({ id: 'media-speech', status: 'active' })
    })
    vi.stubGlobal('fetch', spy)
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())
    await startTranscribe(wrapper)

    expect(wrapper.text()).toContain('语音识别服务调用失败')
    // 已上传媒体保留：重试不再走三步上传，只重发转写。
    await startTranscribe(wrapper)
    expect(wrapper.find('textarea').element.value).toContain('[Sandbox] language=zh-CN')
    const paths = spy.mock.calls.map((c) => String(c[0]))
    expect(paths.filter((p) => p.includes('upload-tickets'))).toHaveLength(1)
    expect(paths.filter((p) => p.includes('/api/speech/transcriptions'))).toHaveLength(2)
  })

  test('移除按钮清空选择与结果', async () => {
    vi.stubGlobal('fetch', stubFetch(speechRoutes()))
    const wrapper = mount(SpeechTranscriptionPanel, { props: { authenticated: true } })
    await selectFile(wrapper, audioFile())
    await startTranscribe(wrapper)
    expect(wrapper.find('textarea').element.value).toContain('[Sandbox] language=zh-CN')

    await wrapper.findAll('button').find((b) => b.text().includes('移除'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('memo.mp3')
    expect(wrapper.text()).not.toContain('[Sandbox] language=zh-CN')
  })
})
