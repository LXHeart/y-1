// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useVoiceChat } from './useVoiceChat'
import { useHoldRecorder } from './useHoldRecorder'
import { useReplySpeech, speechChunks } from './useReplySpeech'
import { conversationPrompt, reply, transcribe } from './chat-api'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
const answer = { content: '你好。', runId: 'r', provider: 'configured', model: 'text-model' }

describe('聊天状态与契约', () => {
  test('仅成功回答进入上下文，阻止重复提交，失败保留草稿', async () => {
    const waiting = deferred<typeof answer>()
    const provider = vi.fn(() => waiting.promise)
    const chat = useVoiceChat({ reply: provider, transcribe: vi.fn() })
    chat.draft.value = '第一句'
    const first = chat.send()
    await chat.send()
    expect(provider).toHaveBeenCalledTimes(1)
    waiting.resolve(answer); await first
    expect(chat.messages.value.map(m => m.text)).toEqual(['第一句', '你好。'])
    expect(chat.draft.value).toBe('')
    provider.mockRejectedValueOnce(new Error('积分不足'))
    chat.draft.value = '第二句'; await chat.send()
    expect(chat.draft.value).toBe('第二句')
    expect(chat.messages.value).toHaveLength(2)
    expect(chat.error.value).toBe('积分不足')
  })
  test('取消与换账号清空后，迟到回答不恢复消息', async () => {
    const waiting = deferred<typeof answer>()
    const chat = useVoiceChat({ reply: () => waiting.promise, transcribe: vi.fn() })
    chat.draft.value = '私有问题'; const pending = chat.send()
    chat.reset(); waiting.resolve(answer); await pending
    expect(chat.messages.value).toEqual([])
    expect(chat.draft.value).toBe('')
  })
  test('语音只填草稿，不自动调用文字模型；取消后不写入', async () => {
    const provider = vi.fn()
    const chat = useVoiceChat({ reply: provider, transcribe: async () => '识别结果' })
    chat.draft.value = '原文'
    await chat.acceptRecording(new File(['x'], 'a.webm'))
    expect(chat.draft.value).toBe('原文\n识别结果')
    expect(provider).not.toHaveBeenCalled()
    const waiting = deferred<string>()
    const other = useVoiceChat({ reply: provider, transcribe: () => waiting.promise })
    const pending = other.acceptRecording(new File(['x'], 'a.webm'))
    other.cancel(); waiting.resolve('旧结果'); await pending
    expect(other.draft.value).toBe('')
  })
  test('超时释放等待，拒绝超长问题，保留最近完整上下文', async () => {
    vi.useFakeTimers()
    const provider = vi.fn(() => new Promise<typeof answer>(() => {}))
    const chat = useVoiceChat({ reply: provider, transcribe: vi.fn() })
    chat.draft.value = 'a'.repeat(2001); await chat.send()
    expect(provider).not.toHaveBeenCalled()
    chat.draft.value = '问题'; void chat.send()
    await vi.advanceTimersByTimeAsync(120000)
    expect(chat.busy.value).toBe(false)
    expect(chat.error.value).toContain('超时')
    const history = Array.from({ length: 20 }, (_, i) => ({ role: (i % 2 ? 'assistant' : 'user') as 'user' | 'assistant', text: String(i) }))
    const prompt = conversationPrompt(history, '最新')
    const data = JSON.parse(prompt.slice(prompt.indexOf('\n') + 1))
    expect(data).toHaveLength(13)
    expect(data[0]).toEqual({ role: 'user', text: '8' })
    expect(data.at(-1).text).toBe('最新')
  })
  test('文本请求走裸 JSON、cookie、现有计费且不自动回落；空回复报错', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(answer)))
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    expect(await reply([], '问题', signal)).toEqual(answer)
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe('/api/ai/runs')
    expect(init.credentials).toBe('include')
    expect(JSON.parse(init.body)).toMatchObject({ capability: 'text', allowFallback: false })
    expect(init.signal).toBe(signal)
    fetch.mockResolvedValueOnce(new Response('{}'))
    await expect(reply([], '问题', signal)).rejects.toThrow('没有返回文字')
  })
  test('录音遵循票据、签名上传、确认、转写；拒绝演示结果', async () => {
    const env = (data: unknown) => new Response(JSON.stringify({ success: true, data }))
    const fetch = vi.fn()
      .mockResolvedValueOnce(env({ id: 'media', uploadUrl: 'https://storage.example/audio', method: 'PUT' }))
      .mockResolvedValueOnce(new Response(''))
      .mockResolvedValueOnce(env({ id: 'media' }))
      .mockResolvedValueOnce(env({ status: 'completed', text: '演示', sandbox: true }))
    vi.stubGlobal('fetch', fetch)
    await expect(transcribe(new File(['audio'], 'a.webm', { type: 'audio/webm' }), new AbortController().signal)).rejects.toThrow('演示模型')
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(['/api/media/upload-tickets', 'https://storage.example/audio', '/api/media/media/confirm', '/api/speech/transcriptions'])
    expect(fetch.mock.calls[1]![1].credentials).toBeUndefined()
  })
})

describe('录音生命周期', () => {
  function setup() {
    const trackStop = vi.fn()
    const stream = { getTracks: () => [{ stop: trackStop }] } as unknown as MediaStream
    const recorder = {
      state: 'inactive', mimeType: 'audio/webm;codecs=opus', ondataavailable: null, onstop: null, onerror: null,
      start(this: { state: string }) { this.state = 'recording' },
      stop(this: { state: string }) { this.state = 'inactive' },
    } as unknown as MediaRecorder
    const completed = vi.fn()
    const getUserMedia = vi.fn(async () => stream)
    const mic = useHoldRecorder(completed, { getUserMedia, create: () => recorder, supported: () => true })
    return { mic, recorder, stream, getUserMedia, trackStop, completed }
  }
  test('授权前松手，授权后立即释放麦克风且不录音', async () => {
    const s = setup(); const grant = deferred<MediaStream>()
    s.getUserMedia.mockReturnValueOnce(grant.promise)
    const starting = s.mic.start(); s.mic.stop(true)
    grant.resolve(s.stream); await starting
    expect(s.trackStop).toHaveBeenCalledOnce()
    expect(s.recorder.state).toBe('inactive')
    expect(s.completed).not.toHaveBeenCalled()
  })
  test('正常松开生成服务端支持的 MIME；切页丢弃迟到 stop', async () => {
    vi.useFakeTimers()
    const s = setup(); await s.mic.start()
    await vi.advanceTimersByTimeAsync(500)
    s.recorder.ondataavailable?.({ data: new Blob(['audio']) } as BlobEvent)
    s.mic.stop(true)
    s.recorder.onstop?.(new Event('stop'))
    expect(s.completed.mock.calls[0]![0].type).toBe('audio/webm')
    expect(s.trackStop).toHaveBeenCalledOnce()
    await s.mic.start(); await vi.advanceTimersByTimeAsync(500)
    s.mic.stop(true); s.mic.stop(false)
    s.recorder.onstop?.(new Event('stop'))
    expect(s.completed).toHaveBeenCalledOnce()
  })
  test('录音达到上限自动停止；拒绝权限时可重试', async () => {
    vi.useFakeTimers()
    const s = setup()
    s.getUserMedia.mockRejectedValueOnce(new Error('denied'))
    await s.mic.start()
    expect(s.mic.error.value).toContain('权限')
    await s.mic.start(); await vi.advanceTimersByTimeAsync(60000)
    expect(s.mic.state.value).toBe('idle')
    expect(s.trackStop).toHaveBeenCalledOnce()
  })
})

describe('回答朗读', () => {
  test('分段无丢字；停止后旧回调不会继续朗读', () => {
    const text = '一段长回复。'.repeat(100)
    expect(speechChunks(text).join('')).toBe(text)
    const synthesis = { getVoices: () => [], speak: vi.fn(), cancel: vi.fn() }
    const speech = useReplySpeech({ synthesis: synthesis as unknown as SpeechSynthesis, utterance: text => ({ text }) as SpeechSynthesisUtterance })
    speech.speak(text)
    const first = synthesis.speak.mock.calls[0]![0]
    first.onend()
    expect(synthesis.speak).toHaveBeenCalledTimes(2)
    speech.stop(); first.onend()
    expect(synthesis.speak).toHaveBeenCalledTimes(2)
    expect(speech.speaking.value).toBe(false)
  })
  test('不支持及播放失败均给可理解提示', () => {
    const speech = useReplySpeech({ synthesis: undefined, utterance: vi.fn() })
    speech.speak('你好'); expect(speech.error.value).toContain('不支持')
  })
})
