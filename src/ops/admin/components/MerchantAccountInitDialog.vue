<template>
  <GlModal v-if="open" title="初始化商家账号" persistent @close="$emit('close')">
    <template v-if="!result">
      <p class="init-hint">
        商家身份由平台初始化（唯一来源）：创建全新邮箱账号、发放一次性初始密码；
        商家主体与 KYB 认证由商家首次登录后自行完成。
      </p>

      <label class="field-label">
        商家邮箱（仅支持全新邮箱）
        <input
          :value="email"
          type="email"
          class="field-input"
          placeholder="merchant@example.com"
          autocomplete="off"
          data-testid="merchant-init-email"
          @input="$emit('update:email', ($event.target as HTMLInputElement).value)"
        >
      </label>
      <p v-if="emailError" class="error-msg field-error" role="alert">{{ emailError }}</p>

      <label class="field-label">
        姓名
        <input
          :value="displayName"
          type="text"
          class="field-input"
          maxlength="80"
          placeholder="商家联系人姓名"
          autocomplete="off"
          data-testid="merchant-init-name"
          @input="$emit('update:displayName', ($event.target as HTMLInputElement).value)"
        >
      </label>

      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
    </template>

    <template v-else>
      <div class="init-success">
        <p class="init-success-title">商家账号已创建</p>
        <p class="init-success-meta">{{ result.email }} · {{ result.displayName }}</p>
        <div class="init-password-row">
          <code class="init-password" data-testid="initial-password">{{ result.initialPassword }}</code>
          <button
            class="refresh-btn"
            type="button"
            data-testid="copy-initial-password"
            @click="copyPassword"
          >
            {{ copied ? '已复制' : '复制密码' }}
          </button>
        </div>
        <p class="init-warning">
          仅展示一次，请立即复制并线下交付；商家首次登录将被要求修改密码。
        </p>
      </div>
    </template>

    <template #actions>
      <button v-if="!result" class="btn-cancel" type="button" :disabled="submitting" @click="$emit('close')">
        取消
      </button>
      <button v-if="!result" class="btn-confirm" type="button" :disabled="submitting" @click="$emit('submit')">
        {{ submitting ? '创建中...' : '创建账号' }}
      </button>
      <button v-else class="btn-confirm" type="button" data-testid="init-done" @click="$emit('close')">
        完成
      </button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'

const props = defineProps<{
  open: boolean
  email: string
  displayName: string
  /** 邮箱字段级错误（409 已注册等）；与提交级 error 分开呈现。 */
  emailError: string
  error: string
  submitting: boolean
  /** 初始化结果（initialPassword 为一次性明文，仅本次展示）。 */
  result: {
    userId: string
    email: string
    displayName: string
    initialPassword: string
  } | null
}>()

defineEmits<{
  close: []
  'update:email': [value: string]
  'update:displayName': [value: string]
  submit: []
}>()

const copied = ref(false)

async function copyPassword(): Promise<void> {
  if (!props.result) return
  try {
    await navigator.clipboard.writeText(props.result.initialPassword)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // 降级：选中文本，让管理员手动复制
    const el = document.querySelector('[data-testid="initial-password"]')
    if (!el) return
    const range = document.createRange()
    range.selectNodeContents(el)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  }
}
</script>

<style scoped>
.init-hint {
  margin: 0 0 14px;
  color: var(--color-text-muted);
  font-size: 0.82rem;
  line-height: 1.6;
}

.field-error {
  margin: 4px 0 0;
}

.init-success {
  display: grid;
  gap: 10px;
  justify-items: start;
}

.init-success-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--color-text);
}

.init-success-meta {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.init-password-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.init-password {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-sm);
  background: var(--color-surface-highlight);
  font-family: var(--font-mono);
  font-size: 0.9rem;
  letter-spacing: 0.06em;
  color: var(--color-text);
  user-select: all;
}

.init-password-row .refresh-btn {
  flex-shrink: 0;
}

.init-warning {
  margin: 0;
  color: var(--color-warning);
  font-size: 0.78rem;
  line-height: 1.6;
}
</style>
