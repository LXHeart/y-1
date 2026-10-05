import { computed, ref } from 'vue'
import * as api from './chat-api'

export function useVoiceChat(deps = { reply: api.reply, transcribe: api.transcribe }) {
  const messages = ref<api.ChatMessage[]>([])
  const draft = ref('')
  const pendingQuestion = ref('')
  const phase = ref<'idle' | 'replying' | 'transcribing'>('idle')
  const error = ref('')
  const notice = ref('')
  const model = ref('')
  let generation = 0
  let controller: AbortController | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined
  const busy = computed(() => phase.value !== 'idle')

  function cancel() {
    generation++
    controller?.abort()
    clearTimeout(timeout)
    controller = undefined
    if (phase.value === 'replying') notice.value = '已停止等待。服务端可能已执行，请在运行记录中核对用量。'
    if (phase.value === 'transcribing') notice.value = '已取消本次转写等待；已上传的音频按现有素材规则保留。'
    phase.value = 'idle'
    pendingQuestion.value = ''
  }
  function begin(next: typeof phase.value) {
    error.value = ''; notice.value = ''; phase.value = next
    controller = new AbortController()
    const ticket = ++generation
    timeout = setTimeout(() => {
      if (ticket !== generation) return
      cancel()
      error.value = '等待超时，请检查运行记录或语音转写记录后再决定是否重试。'
    }, 120000)
    return { ticket, signal: controller.signal }
  }
  function finish(ticket: number) {
    if (ticket !== generation) return
    clearTimeout(timeout)
    phase.value = 'idle'; pendingQuestion.value = ''; controller = undefined
  }
  async function send(): Promise<string | undefined> {
    const question = draft.value.trim()
    if (busy.value || !question) return
    if (question.length > 2000) { error.value = '每条消息最多 2000 字。'; return }
    const { ticket, signal } = begin('replying')
    pendingQuestion.value = question
    try {
      const result = await deps.reply(messages.value, question, signal)
      if (ticket !== generation) return
      messages.value.push({ role: 'user', text: question }, { role: 'assistant', text: result.content })
      // Bound memory as well as provider context. No browser persistence across accounts.
      messages.value = messages.value.slice(-40)
      draft.value = ''; model.value = `${result.provider} / ${result.model}`
      return result.content
    } catch (cause) {
      if (ticket === generation) error.value = cause instanceof Error ? cause.message : '回答失败，请重试。'
    } finally { finish(ticket) }
  }
  async function acceptRecording(file: File) {
    if (busy.value) return
    const { ticket, signal } = begin('transcribing')
    const previous = draft.value
    try {
      const text = await deps.transcribe(file, signal)
      if (ticket !== generation) return
      draft.value = [previous.trim(), text].filter(Boolean).join('\n')
      notice.value = '已转为文字，请检查后发送。'
    } catch (cause) {
      if (ticket === generation) error.value = cause instanceof Error ? cause.message : '转写失败，请重试。'
    } finally { finish(ticket) }
  }
  function reset() {
    cancel(); messages.value = []; draft.value = ''; error.value = ''; notice.value = ''; model.value = ''
  }
  return { messages, draft, pendingQuestion, phase, busy, error, notice, model, send, acceptRecording, cancel, reset }
}
