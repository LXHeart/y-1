<template>
  <div class="speech-transcription-panel">
    <header class="panel-intro">
      <h3>语音转写</h3>
      <p>上传音频文件（mp3 / m4a / wav / webm / ogg，≤25MB、15 分钟内），生成 Sandbox 转写文本。</p>
    </header>

    <div class="upload-row">
      <label class="file-label">
        选择音频
        <input
          type="file"
          accept=".mp3,.m4a,.wav,.webm,.ogg,audio/mpeg,audio/mp4,audio/wav,audio/x-wav,audio/webm,audio/ogg"
          :disabled="busy"
          @change="onFileChange"
        >
      </label>
      <select v-model="language" :disabled="busy" aria-label="识别语言">
        <option value="auto">自动检测语言</option>
        <option value="zh-CN">中文（zh-CN）</option>
        <option value="en-US">英语（en-US）</option>
      </select>
    </div>

    <p v-if="file" class="file-preview" data-testid="speech-file-preview">
      {{ file.name }} · {{ formatSize(file.size) }}
      <button type="button" class="secondary-command" :disabled="busy" @click="removeFile">移除</button>
    </p>

    <p v-if="validationError" class="error-state" role="alert">{{ validationError }}</p>
    <p v-if="actionError" class="error-state" role="alert">{{ actionError }}</p>
    <p v-if="sharedError" class="error-state" role="alert">{{ sharedError }}</p>
    <p v-if="copied" class="copy-hint" aria-live="polite">已复制转写文本</p>
    <p v-if="busy" aria-live="polite">{{ busyLabel }}</p>

    <button
      type="button"
      class="primary-command"
      :disabled="!file || busy || !!validationError"
      @click="transcribe"
    >
      {{ result ? '重新转写' : '开始转写' }}
    </button>

    <section v-if="result" class="transcription-result" aria-live="polite">
      <header>
        <strong>转写结果</strong>
        <span v-if="result.sandbox" class="sandbox-badge">Sandbox</span>
        <span>{{ result.language }}</span>
        <span>{{ (result.durationMs / 1000).toFixed(1) }} 秒</span>
        <button type="button" class="secondary-command" @click="copyText">复制</button>
      </header>
      <textarea :value="result.text ?? ''" readonly rows="6" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { SpeechLanguage, SpeechTranscription } from '../types/grassland'

const MAX_AUDIO_BYTES = 25 * 1024 * 1024
const ACCEPTED_MIME = new Set([
  'audio/mpeg', 'audio/mp4', 'audio/wav', 'audio/x-wav', 'audio/webm', 'audio/ogg',
])

const { uploadSpeechAudio, createSpeechTranscription, error: sharedError } = useGrassland()

const file = ref<File | null>(null)
const language = ref<SpeechLanguage>('auto')
const uploadedMediaId = ref<string | null>(null)
const result = ref<SpeechTranscription | null>(null)
const validationError = ref('')
const actionError = ref('')
const copied = ref(false)
const uploading = ref(false)
const transcribing = ref(false)

const busy = computed(() => uploading.value || transcribing.value)
const busyLabel = computed(() => (uploading.value ? '正在上传音频...' : '正在转写...'))

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0] ?? null
  actionError.value = ''
  copied.value = false
  result.value = null
  uploadedMediaId.value = null
  file.value = selected
  validationError.value = ''
  if (!selected) return
  if (selected.size > MAX_AUDIO_BYTES) {
    validationError.value = '音频不能超过 25MB'
    return
  }
  if (selected.type && !ACCEPTED_MIME.has(selected.type)) {
    validationError.value = '仅支持 mp3、m4a、wav、webm、ogg 音频文件'
  }
}

function removeFile(): void {
  file.value = null
  validationError.value = ''
  actionError.value = ''
  copied.value = false
  uploadedMediaId.value = null
  result.value = null
  const input = document.querySelector<HTMLInputElement>('.speech-transcription-panel input[type="file"]')
  if (input) input.value = ''
}

async function transcribe(): Promise<void> {
  const selected = file.value
  if (!selected || busy.value || validationError.value) return
  actionError.value = ''
  copied.value = false
  try {
    if (!uploadedMediaId.value) {
      uploading.value = true
      const mediaId = await uploadSpeechAudio(selected)
      if (!mediaId) return // 错误已落到 error 通道，由 useGrassland 展示
      uploadedMediaId.value = mediaId
    }
    transcribing.value = true
    const transcription = await createSpeechTranscription(uploadedMediaId.value, language.value)
    if (!transcription) return
    result.value = transcription
  } finally {
    uploading.value = false
    transcribing.value = false
  }
}

async function copyText(): Promise<void> {
  const text = result.value?.text
  if (!text || !navigator.clipboard) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
  } catch {
    copied.value = false
  }
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
  return `${Math.max(1, Math.round(bytes / 1024))}KB`
}
</script>

<style scoped>
.speech-transcription-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 560px;
}

.upload-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.file-label input {
  margin-left: 8px;
}

.file-preview {
  display: flex;
  gap: 8px;
  align-items: center;
}

.transcription-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.transcription-result header {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.sandbox-badge {
  border: 1px solid currentColor;
  border-radius: 4px;
  padding: 0 6px;
  font-size: 12px;
}

.transcription-result textarea {
  width: 100%;
  box-sizing: border-box;
}

.error-state {
  color: #b3261e;
}

.copy-hint {
  color: #1b7f3b;
}
</style>
