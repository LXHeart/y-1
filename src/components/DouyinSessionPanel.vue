<template>
  <section class="session-panel glass-card">
    <header class="session-header">
      <div>
        <p class="eyebrow">登录增强</p>
        <h2 class="session-title">扫码后再试解析</h2>
      </div>
      <span class="session-status" :class="`status-${session?.status || 'missing'}`">
        {{ statusText }}
      </span>
    </header>

    <p class="session-desc">
      少数链接会命中抖音校验页。此时可在后端建立本地登录态，提高提取成功率。
    </p>

    <div v-if="session?.qrImageUrl && shouldShowQr" class="qr-card">
      <img :src="session.qrImageUrl" alt="抖音扫码二维码" class="qr-image">
      <p class="qr-hint">请使用抖音 App 扫码，并在手机上确认登录。</p>
    </div>

    <div class="session-copy" role="status" aria-live="polite">
      <p class="copy-title">{{ guidanceTitle }}</p>
      <p class="copy-body">{{ guidanceCopy }}</p>
      <p v-if="actionHint" class="copy-action">{{ actionHint }}</p>
      <p v-if="showRawMessage && session?.message" class="copy-meta">{{ session.message }}</p>
      <p v-if="session?.lastAuthenticatedAt" class="copy-meta">最近登录：{{ formatDate(session.lastAuthenticatedAt) }}</p>
      <p v-if="session?.lastUsedAt" class="copy-meta">最近复用：{{ formatDate(session.lastUsedAt) }}</p>
    </div>

    <p v-if="error" class="session-error">{{ error }}</p>

    <div class="session-actions">
      <button class="btn-primary" :disabled="loading" @click="$emit('start')">
        {{ primaryActionText }}
      </button>
      <button class="btn-secondary" :disabled="loading" @click="$emit('refresh')">
        刷新状态
      </button>
      <button
        v-if="session?.hasPersistedSession || shouldShowQr"
        class="btn-secondary"
        :disabled="loading"
        @click="$emit('logout')"
      >
        断开连接
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DouyinSessionState } from '../types/douyin'

const props = defineProps<{
  session: DouyinSessionState | null
  loading: boolean
  error: string
}>()

defineEmits<{
  start: []
  refresh: []
  logout: []
}>()

const statusText = computed(() => {
  switch (props.session?.status) {
    case 'launching':
      return '启动中'
    case 'qr_ready':
      return '待扫码'
    case 'waiting_for_confirm':
      return '待确认'
    case 'authenticated':
      return '已连接'
    case 'expired':
      return '已失效'
    case 'error':
      return '异常'
    default:
      return '未连接'
  }
})

const shouldShowQr = computed(() => props.session?.status === 'qr_ready' || props.session?.status === 'waiting_for_confirm')
const sessionStateKey = computed(() => props.session?.detailCode || props.session?.status || 'missing')

const guidanceTitle = computed(() => {
  switch (sessionStateKey.value) {
    case 'authenticated':
      return '当前已启用登录增强'
    case 'launching':
      return '正在准备二维码'
    case 'qr_ready':
      return '扫码后可直接重试'
    case 'waiting_for_confirm':
      return '请在手机上确认登录'
    case 'timeout':
    case 'session_expired':
      return '登录态已失效'
    case 'login_failed':
      return '登录增强暂时异常'
    default:
      return '当前未启用登录增强'
  }
})

const guidanceCopy = computed(() => {
  switch (sessionStateKey.value) {
    case 'authenticated':
      return '登录态只保存在后端本地文件中。现在可以回到上方重新提取视频。'
    case 'launching':
      return '后端正在打开抖音网页登录页，稍后会显示二维码。'
    case 'qr_ready':
      return '扫码并确认后，后端会保存可复用的本地登录态。'
    case 'waiting_for_confirm':
      return '二维码已经被扫描，请到手机端完成确认。'
    case 'timeout':
    case 'session_expired':
      return '旧的登录态已不可用，需要重新扫码。'
    case 'login_failed':
      return '你可以先继续尝试匿名提取，也可以稍后重试登录增强。'
    default:
      return '先尝试匿名提取；只有遇到校验页或失败时，再使用这个增强。'
  }
})

const actionHint = computed(() => {
  switch (sessionStateKey.value) {
    case 'launching':
      return '请稍等二维码加载。'
    case 'qr_ready':
      return '扫码后请留在本页，状态会自动轮询更新。'
    case 'waiting_for_confirm':
      return '确认完成后状态会自动变成“已连接”。'
    case 'timeout':
    case 'session_expired':
      return '重新扫码即可恢复。'
    default:
      return ''
  }
})

const showRawMessage = computed(() => sessionStateKey.value === 'timeout' || sessionStateKey.value === 'session_expired' || sessionStateKey.value === 'login_failed')

const primaryActionText = computed(() => {
  if (props.session?.status === 'authenticated') {
    return '重新连接'
  }

  if (props.session?.status === 'waiting_for_confirm') {
    return '重新生成二维码'
  }

  if (props.session?.status === 'expired' || props.session?.status === 'error') {
    return '重新扫码'
  }

  return '连接抖音'
})

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.session-panel {
  border-radius: var(--radius-md);
  padding: 20px;
  display: grid;
  gap: 16px;
}

.session-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  margin: 0 0 6px;
  font-size: 0.76rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.session-title {
  margin: 0;
  font-size: 1.1rem;
}

.session-status {
  align-self: flex-start;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.06);
  color: var(--color-text-secondary);
  font-size: 0.76rem;
  font-weight: 700;
}

.status-qr_ready,
.status-waiting_for_confirm {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.3);
  background: rgba(251, 191, 36, 0.12);
}

.status-authenticated {
  color: var(--color-success);
  border-color: rgba(52, 211, 153, 0.3);
  background: rgba(52, 211, 153, 0.12);
}

.status-expired,
.status-error {
  color: var(--color-danger);
  border-color: rgba(251, 113, 133, 0.3);
  background: rgba(251, 113, 133, 0.12);
}

.session-desc,
.copy-title,
.copy-body,
.copy-action,
.copy-meta,
.qr-hint,
.session-error {
  margin: 0;
}

.session-desc,
.copy-body,
.copy-action,
.copy-meta,
.qr-hint {
  color: var(--color-text-secondary);
}

.copy-title {
  font-weight: 700;
}

.copy-action {
  color: var(--color-text);
}

.copy-meta {
  font-size: 0.88rem;
  color: var(--color-text-muted);
}

.session-copy {
  display: grid;
  gap: 8px;
}

.qr-card {
  display: grid;
  gap: 10px;
  justify-items: center;
  padding: 18px;
  border-radius: var(--radius-sm);
  border: 1px dashed rgba(255,255,255,0.18);
  background: rgba(255,255,255,0.03);
}

.qr-image {
  width: min(240px, 100%);
  aspect-ratio: 1;
  border-radius: 12px;
  background: white;
  padding: 10px;
}

.session-error {
  padding: 12px 14px;
  border-radius: 12px;
  color: var(--color-danger);
  background: rgba(251, 113, 133, 0.08);
  border: 1px solid rgba(251, 113, 133, 0.2);
}

.session-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.btn-primary,
.btn-secondary {
  min-height: 42px;
  padding: 0 16px;
  border-radius: 999px;
  cursor: pointer;
  transition: transform 150ms ease, opacity 150ms ease, background 150ms ease;
}

.btn-primary {
  background: var(--gradient-accent);
  color: white;
  font-weight: 700;
}

.btn-secondary {
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.04);
  color: var(--color-text);
}

.btn-primary:hover:not(:disabled),
.btn-secondary:hover:not(:disabled) {
  transform: translateY(-1px);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
