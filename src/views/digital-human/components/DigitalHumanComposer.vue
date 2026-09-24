<template>
  <section class="dh-composer" aria-labelledby="dh-composer-title" data-testid="dh-composer">
    <h3 id="dh-composer-title" class="dh-composer-title">输入</h3>

    <div class="gl-field dh-composer-field">
      <label class="field-label" for="dh-composer-input">{{ inputLabel }}</label>
      <textarea
        id="dh-composer-input"
        v-model="text"
        rows="3"
        :disabled="inputDisabled"
        :placeholder="placeholder"
        data-testid="dh-composer-input"
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @keydown.enter.exact.prevent="onEnter"
      />
      <p v-if="composerError" class="gl-alert gl-alert-error" role="alert" data-testid="dh-composer-error">{{ composerError }}</p>
      <p v-if="hint" class="dh-hint">{{ hint }}</p>

      <div class="gl-actions">
        <button
          type="button"
          class="gl-btn-primary"
          :disabled="!canSend"
          data-testid="dh-composer-send"
          @click="sendText"
        >
          {{ submitting ? '发送中…' : '发送' }}
        </button>

        <!-- 麦克风三态：开始（显式点击）→ 提交/取消；发言期间文字发送禁用（E-04 步骤5）。 -->
        <button
          v-if="micState === 'idle' || micState === 'error'"
          type="button"
          class="gl-btn-secondary"
          :disabled="!micReady || sessionState !== 'ready'"
          :title="micReady ? '按一下开始录音，再按一下提交' : '等待媒体就绪后可语音输入'"
          data-testid="dh-composer-mic-start"
          @click="emit('mic-start')"
        >
          {{ micState === 'error' ? '重新录音' : '按住说话' }}
        </button>
        <button
          v-else-if="micState === 'recording'"
          type="button"
          class="gl-btn-primary"
          data-testid="dh-composer-mic-submit"
          @click="emit('mic-submit')"
        >
          提交本段
        </button>
        <button
          v-if="micState === 'recording' || micState === 'requesting'"
          type="button"
          class="gl-btn-secondary"
          data-testid="dh-composer-mic-abort"
          @click="emit('mic-abort')"
        >
          取消
        </button>

        <button
          v-if="sessionState === 'responding' || sessionState === 'speaking'"
          type="button"
          class="gl-btn-secondary"
          :disabled="interrupting"
          data-testid="dh-composer-interrupt"
          @click="emit('interrupt')"
        >
          {{ interrupting ? '打断中…' : '打断并说话' }}
        </button>
      </div>

      <p v-if="micState === 'recording'" class="dh-hint" role="status" data-testid="dh-mic-status">
        录音中——最长 60 秒，到限自动提交；再点「提交本段」提前结束。
      </p>
      <p v-else-if="micState === 'transcribing'" class="dh-hint" role="status" data-testid="dh-mic-status">
        正在转写这段语音…
      </p>
      <p v-else-if="micState === 'requesting'" class="dh-hint" role="status" data-testid="dh-mic-status">
        正在请求麦克风权限…
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：文字/麦克风/打断控件；IME 组合期 Enter 不提交；发言期文字发送禁用。
import { computed, ref } from 'vue'
import type { MicState } from '../composables/useDigitalHumanMicrophone'

const props = defineProps<{
  sessionState: string
  micState: MicState
  micReady: boolean
  submitting?: boolean
  interrupting?: boolean
  composerError?: string | null
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'mic-start'): void
  (e: 'mic-submit'): void
  (e: 'mic-abort'): void
  (e: 'interrupt'): void
}>()

const text = ref('')
const composing = ref(false)

/** 发言/转写期间普通 send 禁用（E-04 步骤5）；拒权（error）后仍可继续文字；非 ready 不可发。 */
const micBusy = computed(() => ['requesting', 'recording', 'transcribing'].includes(props.micState))
const inputDisabled = computed(() => props.sessionState !== 'ready' || micBusy.value)

const canSend = computed(() =>
  !inputDisabled.value && !props.submitting && text.value.trim().length > 0)

const inputLabel = computed(() => (props.sessionState === 'ready' ? '对数字人说' : '等待会话就绪'))

const placeholder = computed(() => {
  if (props.sessionState !== 'ready') return '会话就绪后即可输入…'
  if (props.micState !== 'idle') return '语音输入中——结束后可继续文字'
  return '输入内容，Enter 发送（输入法组合中不提交）'
})

const hint = computed(() => {
  if (props.micState === 'recording') return '正在录音，文字输入暂停。'
  return null
})

/** Enter 提交；IME 组合期（composing）不提交。 */
function onEnter(): void {
  if (composing.value) return
  sendText()
}

function sendText(): void {
  if (!canSend.value) return
  const payload = text.value.trim()
  emit('send', payload)
  text.value = ''
}
</script>

<style scoped>
.dh-composer { display: grid; gap: var(--space-xs); }
.dh-composer-title {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-composer-field { display: grid; gap: var(--space-xs); }
</style>
