<template>
  <section class="voice-chat gl-field" aria-labelledby="voice-chat-title">
    <header class="voice-chat-heading">
      <div>
        <p class="voice-chat-kicker">自由对话</p>
        <h1 id="voice-chat-title">打字，或按住说话</h1>
        <p class="voice-chat-muted">聊想法、改文案，也可以让回答读给你听。</p>
      </div>
      <button type="button" :disabled="busy || recording" @click="clearConversation">清空本页对话</button>
    </header>

    <p v-if="!currentUser" class="gl-alert" role="status">登录后可生成回答和转写语音。<button type="button" @click="emit('request-login')">登录 / 注册</button></p>
    <div class="voice-chat-options">
      <label><input v-model="autoRead" type="checkbox" :disabled="!speech.supported" /> 自动朗读回答</label>
      <button v-if="speaking" type="button" @click="speech.stop">停止朗读</button>
      <span class="voice-chat-muted">{{ speech.supported ? '使用浏览器 / 系统音色' : '此浏览器不支持朗读' }}</span>
    </div>
    <p class="voice-chat-muted voice-chat-note">文字生成与语音转写沿用账户模型配置及现有计费。</p>

    <div class="voice-chat-messages" role="log" aria-label="对话记录" aria-live="polite" :aria-busy="phase === 'replying'">
      <div v-if="!messages.length && !pendingQuestion" class="voice-chat-empty">
        <h2>从一句话开始</h2>
        <p>例如：帮我把这段介绍改得更像日常聊天。</p>
        <p class="voice-chat-muted">语音会先转成文字，检查后再发送。</p>
      </div>
      <article v-for="(message, index) in messages" :key="index" class="voice-chat-message" :class="{ 'voice-chat-message-user': message.role === 'user' }">
        <strong>{{ message.role === 'user' ? '你' : '草场助手' }}</strong>
        <p>{{ message.text }}</p>
        <button v-if="message.role === 'assistant'" type="button" :disabled="!speech.supported || recording || busy" @click="speech.speak(message.text)">朗读</button>
      </article>
      <article v-if="pendingQuestion" class="voice-chat-message voice-chat-message-user">
        <strong>你</strong><p>{{ pendingQuestion }}</p>
      </article>
      <p v-if="phase === 'replying'" role="status">正在思考，回答完成后显示…</p>
    </div>
    <p v-if="model" class="voice-chat-muted">最近回答：{{ model }}</p>

    <form class="voice-chat-composer" @submit.prevent="sendMessage">
      <label for="voice-chat-input">消息</label>
      <textarea id="voice-chat-input" v-model="draft" rows="4" :disabled="busy || recording" aria-describedby="voice-chat-help" placeholder="输入你想聊的内容…" @keydown="onInputKey" />
      <p id="voice-chat-help" class="voice-chat-muted">每条最多 2000 字。Enter 换行，Ctrl / ⌘ + Enter 发送。按住说话最多 60 秒，松开后转写。</p>
      <div class="voice-chat-actions">
        <button type="button" class="voice-chat-hold" :disabled="busy || !currentUser" :aria-pressed="recording" @pointerdown="holdPointer" @pointerup="releasePointer" @pointercancel="cancelRecording" @lostpointercapture="cancelRecording" @keydown="holdKey" @keyup="releaseKey" @blur="cancelRecording" @contextmenu.prevent>
          {{ micState === 'requesting' ? '请允许麦克风…' : micState === 'recording' ? '松开，转为文字' : '按住说话' }}
        </button>
        <button v-if="recording" type="button" @click="cancelRecording">取消录音</button>
        <button v-if="busy" type="button" @click="chat.cancel">停止等待</button>
        <button type="submit" class="gl-btn-primary" :disabled="!currentUser || busy || recording || !draft.trim() || draft.length > 2000">{{ phase === 'replying' ? '正在回答…' : '发送消息' }}</button>
      </div>
      <p v-if="phase === 'transcribing'" role="status">正在上传并识别语音…</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="draft.length > 2000" class="gl-alert gl-alert-error" role="alert">消息超过 2000 字，请精简后发送。</p>
      <p v-if="error || micError || speechError" class="gl-alert gl-alert-error" role="alert">{{ error || micError || speechError }}</p>
    </form>
    <details class="voice-chat-muted">
      <summary>音色与对话保留说明</summary>
      <p>朗读使用设备可用音色，部分音色需要联网。本页展示最近 20 轮对话，每次携带最近最多 6 轮上下文，较长内容会减少携带轮数。刷新后清空本页对话；运行记录和上传音频按现有服务端规则保留。</p>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useAuth } from '../../composables/useAuth'
import { useVoiceChat } from './useVoiceChat'
import { useHoldRecorder } from './useHoldRecorder'
import { useReplySpeech } from './useReplySpeech'
const emit = defineEmits<{ 'request-login': [] }>()
const chat = useVoiceChat()
const { messages, draft, pendingQuestion, phase, busy, error, notice, model } = chat
const speech = useReplySpeech()
const { speaking, error: speechError } = speech
const mic = useHoldRecorder(file => { void chat.acceptRecording(file) })
const { state: micState, error: micError } = mic
const recording = computed(() => micState.value !== 'idle')
const autoRead = ref(true)
const { currentUser } = useAuth()
let pointer: number | undefined
let keyboardHeld = false
async function sendMessage() {
  if (recording.value || !currentUser.value) return
  speech.stop()
  const answer = await chat.send()
  if (answer && autoRead.value) speech.speak(answer)
}
function startRecording() {
  if (busy.value || !currentUser.value) return
  speech.stop()
  void mic.start()
}
function holdPointer(event: PointerEvent) {
  if (event.button !== 0 || pointer !== undefined || keyboardHeld || busy.value) return
  event.preventDefault()
  pointer = event.pointerId
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  startRecording()
}
function releasePointer(event: PointerEvent) {
  if (pointer !== event.pointerId) return
  pointer = undefined
  mic.stop(true)
}
function holdKey(event: KeyboardEvent) {
  if (![' ', 'Enter'].includes(event.key)) return
  event.preventDefault()
  if (event.repeat || keyboardHeld || pointer !== undefined || busy.value) return
  keyboardHeld = true; startRecording()
}
function releaseKey(event: KeyboardEvent) {
  if (![' ', 'Enter'].includes(event.key) || !keyboardHeld) return
  event.preventDefault(); keyboardHeld = false; mic.stop(true)
}
function cancelRecording() {
  // lostpointercapture after a normal pointerup must not discard its pending onstop result.
  if (pointer === undefined && !keyboardHeld && !recording.value) return
  pointer = undefined; keyboardHeld = false; mic.stop(false)
}
function clearConversation() { mic.stop(false); speech.stop(); chat.reset(); micError.value = ''; speechError.value = '' }
function suspend() { pointer = undefined; keyboardHeld = false; mic.stop(false); speech.stop(); chat.cancel() }
function visibility() { if (document.hidden) suspend() }
function onInputKey(event: KeyboardEvent) {
  if (!event.isComposing && event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault(); void sendMessage()
  }
}
function attach() { window.addEventListener('blur', suspend); document.addEventListener('visibilitychange', visibility) }
function detach() { window.removeEventListener('blur', suspend); document.removeEventListener('visibilitychange', visibility); suspend() }
watch(autoRead, enabled => { if (!enabled) speech.stop() })
watch(() => currentUser.value?.id, () => clearConversation())
onMounted(attach)
onActivated(attach)
onDeactivated(detach)
onUnmounted(detach)
</script>
