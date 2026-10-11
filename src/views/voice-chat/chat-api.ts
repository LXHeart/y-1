import { request, requestRaw, putToPresignedUrl } from '../../composables/grassland-http'
import type { MediaUploadTicket, MediaMetadata, SpeechTranscription } from '../../types/grassland'

export interface ChatMessage { role: 'user' | 'assistant'; text: string }
export interface ChatReply { content: string; runId: string; provider: string; model: string }

/** Existing synchronous text-run contract. Keep complete recent pairs, never truncate the new question. */
export function conversationPrompt(history: ChatMessage[], question: string): string {
  const recent: ChatMessage[] = []
  let size = question.length
  for (let i = history.length - 2; i >= 0 && recent.length < 12; i -= 2) {
    const pair = history.slice(i, i + 2)
    if (pair[0]?.role !== 'user' || pair[1]?.role !== 'assistant') continue
    size += pair[0].text.length + pair[1].text.length
    if (size > 24000) break
    recent.unshift(...pair)
  }
  return '请继续下面的对话，只回答最后一条用户消息。默认用简洁自然的中文回答，除非用户要求其他语言。消息以 JSON 数据提供。\n'
    + JSON.stringify([...recent, { role: 'user', text: question }])
}

export async function reply(history: ChatMessage[], question: string, signal: AbortSignal): Promise<ChatReply> {
  const result = await requestRaw<ChatReply>('/api/ai/runs', {
    method: 'POST', signal,
    // 无 BYOK 时授权回落平台模型（与创作助手问答引导同语义；平台形态按 AI_RUN_TEXT 计积分）。
    body: JSON.stringify({ capability: 'text', prompt: conversationPrompt(history, question), maxTokens: 1024, allowFallback: true }),
  })
  if (!result || typeof result.content !== 'string' || !result.content.trim()) {
    throw new Error('模型没有返回文字，请检查模型配置后重试。')
  }
  return result
}

export async function transcribe(file: File, signal: AbortSignal): Promise<string> {
  const ticket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
    method: 'POST', signal,
    body: JSON.stringify({ contentType: file.type, purpose: 'speech_audio', sizeBytes: file.size }),
  })
  signal.throwIfAborted()
  // Reuse the signed-upload transport; account credentials must never be sent to the object-store URL.
  await putToPresignedUrl(ticket, file, signal)
  const media = await request<MediaMetadata>(`/api/media/${encodeURIComponent(ticket.id)}/confirm`, { method: 'POST', signal })
  const result = await request<SpeechTranscription>('/api/speech/transcriptions', {
    method: 'POST', signal, body: JSON.stringify({ mediaId: media.id, language: 'auto' }),
  })
  if (result.sandbox) throw new Error('当前语音识别使用演示模型，请配置真实识别模型后再试。')
  if (result.status !== 'completed' || !result.text?.trim()) {
    throw new Error(result.status === 'processing' ? '转写尚未完成，请到语音转写记录中查看。' : '没有识别到文字，请重新录音或直接输入。')
  }
  return result.text.trim()
}
