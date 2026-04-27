<template>
  <Teleport to="body">
    <div v-if="visible" class="login-overlay">
      <div class="login-modal glass-card" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <header class="login-header">
          <div>
            <p class="login-kicker">账号认证</p>
            <h2 id="login-title" class="login-title">{{ modalTitle }}</h2>
            <p class="login-subtitle">设置中的模型、密钥和首页热点配置都会按账号隔离保存。</p>
          </div>
          <button class="login-close-btn" type="button" aria-label="关闭登录弹窗" @click="emit('close')">
            &times;
          </button>
        </header>

        <div class="login-mode-switch" role="tablist" aria-label="认证模式切换">
          <button
            class="login-mode-btn"
            :class="{ 'login-mode-btn-active': mode === 'login' }"
            type="button"
            :aria-selected="mode === 'login'"
            @click="mode = 'login'"
          >
            登录
          </button>
          <button
            class="login-mode-btn"
            :class="{ 'login-mode-btn-active': mode === 'register' }"
            type="button"
            :aria-selected="mode === 'register'"
            @click="mode = 'register'"
          >
            注册
          </button>
        </div>

        <p v-if="message" class="login-message">{{ message }}</p>

        <form class="login-form" autocomplete="off" @submit.prevent="handleSubmit">
          <label class="login-label" for="login-email">邮箱</label>
          <input
            id="login-email"
            v-model.trim="email"
            class="login-input"
            type="text"
            inputmode="email"
            autocomplete="off"
            autocapitalize="off"
            autocorrect="off"
            spellcheck="false"
            placeholder="you@example.com"
            required
          >

          <template v-if="mode === 'register'">
            <label class="login-label" for="login-display-name">显示名称</label>
            <input
              id="login-display-name"
              v-model.trim="displayName"
              class="login-input"
              type="text"
              autocomplete="off"
              autocapitalize="off"
              autocorrect="off"
              spellcheck="false"
              placeholder="请输入显示名称"
              required
            >
          </template>

          <label class="login-label" for="login-password">密码</label>
          <input
            id="login-password"
            v-model="password"
            class="login-input"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="new-password"
            :placeholder="mode === 'register' ? '至少 8 位密码' : '请输入密码'"
            required
          >

          <template v-if="mode === 'register'">
            <label class="login-label" for="login-confirm-password">确认密码</label>
            <input
              id="login-confirm-password"
              v-model="confirmPassword"
              class="login-input"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="off"
              placeholder="请再次输入密码"
              required
            >

            <label class="login-label" for="login-captcha">图形验证码</label>
            <div class="login-captcha-row">
              <input
                id="login-captcha"
                v-model.trim="captchaCode"
                class="login-input login-captcha-input"
                type="text"
                autocomplete="off"
                autocapitalize="off"
                autocorrect="off"
                spellcheck="false"
                maxlength="4"
                placeholder="4 位图形验证码"
                required
              >
              <button
                v-if="captchaSvg"
                class="login-captcha-img"
                type="button"
                title="点击刷新验证码"
                @click="refreshCaptcha"
                v-html="captchaSvg"
              ></button>
            </div>

            <label class="login-label" for="login-verification-code">邮箱验证码</label>
            <div class="login-code-row">
              <input
                id="login-verification-code"
                v-model.trim="verificationCode"
                class="login-input login-code-input"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                placeholder="6 位验证码"
                required
              >
              <button
                class="login-code-btn"
                type="button"
                :disabled="!canSendCode || codeCooldown > 0"
                @click="handleSendCode"
              >
                {{ codeCooldown > 0 ? `${codeCooldown}s` : '获取验证码' }}
              </button>
            </div>
          </template>

          <label class="login-checkbox">
            <input v-model="showPassword" type="checkbox">
            <span>显示密码</span>
          </label>

          <p v-if="error" class="login-error">{{ error }}</p>

          <footer class="login-footer">
            <button class="login-secondary-btn" type="button" :disabled="submitting" @click="emit('close')">取消</button>
            <button class="login-primary-btn" type="submit" :disabled="submitting || !canSubmit">
              {{ submitting ? submitLabelBusy : submitLabelIdle }}
            </button>
          </footer>
        </form>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AuthMode, LoginFormValues, RegisterFormValues } from '../types/auth'

const props = defineProps<{
  visible: boolean
  submitting: boolean
  error: string
  message?: string
}>()

const emit = defineEmits<{
  close: []
  submit: [values: LoginFormValues]
  register: [values: RegisterFormValues]
  sendCode: [email: string, captchaCode: string]
}>()

const mode = ref<AuthMode>('login')
const email = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const verificationCode = ref('')
const showPassword = ref(false)
const codeCooldown = ref(0)
const captchaCode = ref('')
const captchaSvg = ref('')
let cooldownTimer: ReturnType<typeof setInterval> | null = null

const modalTitle = computed(() => mode.value === 'login' ? '登录后管理你的专属配置' : '注册后立即保存你的专属配置')
const submitLabelIdle = computed(() => mode.value === 'login' ? '登录' : '注册并登录')
const submitLabelBusy = computed(() => mode.value === 'login' ? '登录中…' : '注册中…')
const canSubmit = computed(() => {
  if (mode.value === 'login') {
    return email.value.length > 0 && password.value.length > 0
  }

  return email.value.length > 0 && displayName.value.length > 0 && password.value.length > 0 && confirmPassword.value.length > 0 && verificationCode.value.length === 6
})

const canSendCode = computed(() => {
  return email.value.length > 0 && email.value.includes('@') && captchaCode.value.length > 0
})

async function refreshCaptcha(): Promise<void> {
  captchaCode.value = ''
  try {
    const response = await fetch('/api/auth/captcha', { credentials: 'include' })
    if (response.ok) {
      captchaSvg.value = await response.text()
    }
  } catch {
    captchaSvg.value = ''
  }
}

function startCooldown(): void {
  codeCooldown.value = 60
  if (cooldownTimer) clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    codeCooldown.value -= 1
    if (codeCooldown.value <= 0) {
      codeCooldown.value = 0
      if (cooldownTimer) {
        clearInterval(cooldownTimer)
        cooldownTimer = null
      }
    }
  }, 1000)
}

function handleSendCode(): void {
  if (!canSendCode.value || codeCooldown.value > 0) return
  emit('sendCode', email.value, captchaCode.value)
  startCooldown()
}

function resetForm(): void {
  mode.value = 'login'
  email.value = ''
  displayName.value = ''
  password.value = ''
  confirmPassword.value = ''
  verificationCode.value = ''
  captchaCode.value = ''
  captchaSvg.value = ''
  showPassword.value = false
  codeCooldown.value = 0
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
}

watch(() => props.visible, (visible) => {
  if (!visible) {
    return
  }

  resetForm()
})

watch(mode, (newMode) => {
  if (newMode === 'register') {
    void refreshCaptcha()
  }
})

function handleSubmit(): void {
  if (!canSubmit.value || props.submitting) {
    return
  }

  if (mode.value === 'register') {
    emit('register', {
      email: email.value,
      displayName: displayName.value,
      password: password.value,
      confirmPassword: confirmPassword.value,
      verificationCode: verificationCode.value,
    })
    return
  }

  emit('submit', {
    email: email.value,
    password: password.value,
  })
}
</script>

<style scoped>
.login-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--color-overlay);
  backdrop-filter: blur(10px);
}

.login-modal {
  width: min(100%, 460px);
  max-height: 90vh;
  overflow-y: auto;
  border: 1px solid var(--color-border);
}

.login-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.login-kicker {
  margin: 0 0 6px;
  color: var(--color-text-muted);
  font-size: 0.74rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.login-title {
  margin: 0;
  font-size: 1.24rem;
  line-height: 1.2;
}

.login-subtitle {
  margin: 10px 0 0;
  color: var(--color-text-secondary);
  font-size: 0.92rem;
  line-height: 1.55;
}

.login-close-btn {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 1.2rem;
  line-height: 1;
}

.login-close-btn:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.login-mode-switch {
  display: inline-flex;
  gap: 4px;
  margin-bottom: var(--space-md);
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-page);
}

.login-mode-btn {
  min-height: 36px;
  padding: 0 14px;
  border-radius: calc(var(--radius-md) - 4px);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  font-weight: 600;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.login-mode-btn-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.login-message {
  margin: 0 0 var(--space-md);
  padding: 10px 12px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-md);
  background: var(--color-surface-highlight);
  color: var(--color-text-secondary);
  font-size: 0.88rem;
}

.login-form {
  display: grid;
  gap: 12px;
}

.login-label {
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  font-weight: 600;
}

.login-input {
  width: 100%;
  min-height: 44px;
  padding: 11px 13px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  color: var(--color-text);
  outline: none;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.login-input:focus {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.login-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
}

.login-code-row,
.login-captcha-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.login-code-input,
.login-captcha-input {
  flex: 1;
  min-width: 0;
}

.login-code-btn,
.login-captcha-img {
  flex-shrink: 0;
  min-height: 44px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
}

.login-code-btn {
  padding: 0 14px;
  color: var(--color-text);
  font-size: 0.84rem;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.login-code-btn:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
}

.login-code-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.login-captcha-img {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.login-captcha-img:hover {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
}

.login-captcha-img :deep(svg) {
  display: block;
  width: 160px;
  height: 60px;
}

.login-error {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.86rem;
}

.login-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: var(--space-sm);
}

.login-secondary-btn,
.login-primary-btn {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  font-weight: 600;
  cursor: pointer;
  transition: transform var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.login-secondary-btn {
  background: var(--surface-card);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
}

.login-primary-btn {
  background: var(--color-accent);
  color: white;
}

.login-secondary-btn:hover,
.login-primary-btn:hover {
  transform: translateY(-1px);
}

.login-primary-btn:hover {
  background: var(--color-accent-2);
}

.login-secondary-btn:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
}

.login-secondary-btn:disabled,
.login-primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}
</style>
