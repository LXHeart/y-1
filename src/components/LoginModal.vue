<template>
  <Teleport to="body">
    <div v-if="visible" class="login-overlay">
      <div class="login-modal" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <!-- 地平线（signature）：紫=商家播种 → 苗绿=推荐官耕耘，平台门前的第一根线 -->
        <div class="login-horizon" aria-hidden="true"></div>

        <header class="login-header">
          <div class="login-brand">
            <svg class="login-brand-mark" width="34" height="34" viewBox="0 0 36 36" fill="none" aria-hidden="true">
              <defs>
                <linearGradient id="login-mark-grad" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-primary)"/>
                  <stop offset="0.5" style="stop-color: var(--color-primary)"/>
                  <stop offset="1" style="stop-color: var(--color-primary)"/>
                </linearGradient>
              </defs>
              <rect width="36" height="36" rx="8" fill="url(#login-mark-grad)"/>
              <path d="M11 10.5C11 9.67 11.67 9 12.5 9C12.9 9 13.27 9.16 13.53 9.43L23.53 18.43C24.15 19 24.15 19.97 23.53 20.54C23.27 20.78 22.93 20.91 22.57 20.91H12.5C11.67 20.91 11 20.24 11 19.41V10.5Z" fill="rgba(255,255,255,0.95)"/>
              <rect x="11" y="23" width="14" height="1.8" rx="0.9" fill="rgba(255,255,255,0.5)"/>
              <rect x="11" y="26.2" width="9" height="1.8" rx="0.9" fill="rgba(255,255,255,0.35)"/>
            </svg>
            <div class="login-brand-copy">
              <h2 id="login-title" class="login-title">{{ modalTitle }}</h2>
              <p class="login-subtitle">{{ subtitle }}</p>
            </div>
          </div>
          <button class="login-close-btn" type="button" aria-label="关闭登录弹窗" @click="emit('close')">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M3.5 3.5l9 9M12.5 3.5l-9 9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
            </svg>
          </button>
        </header>

        <div v-if="!hideRegister" class="login-mode-switch" role="tablist" aria-label="认证模式切换">
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
          <!-- 任务书 #49：登录接受「账号名或邮箱」双标识（子账号无邮箱）；注册仍是邮箱（验证码走邮箱） -->
          <label class="login-label" for="login-email">{{ mode === 'login' ? '账号名或邮箱' : '邮箱' }}</label>
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
            :placeholder="mode === 'login' ? '账号名（如 caoyuan-zhangsan）或邮箱' : 'you@example.com'"
            required
          >

          <template v-if="mode === 'register'">
            <label class="login-label" for="login-display-name">昵称</label>
            <input
              id="login-display-name"
              v-model.trim="displayName"
              class="login-input"
              type="text"
              autocomplete="off"
              autocapitalize="off"
              autocorrect="off"
              spellcheck="false"
              placeholder="草场上怎么称呼你"
              required
            >
          </template>

          <label class="login-label" for="login-password">密码</label>
          <div class="login-password-field">
            <input
              id="login-password"
              v-model="password"
              class="login-input login-password-input"
              :type="showPassword ? 'text' : 'password'"
              :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
              :placeholder="mode === 'register' ? '至少 8 位密码' : '请输入密码'"
              required
            >
            <button
              class="login-eye-btn"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :aria-pressed="showPassword"
              @click="showPassword = !showPassword"
            >
              <svg v-if="showPassword" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8s-2.4 4.5-6.5 4.5S1.5 8 1.5 8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                <circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.3"/>
              </svg>
              <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M1.5 8s2.4-4.5 6.5-4.5c1.5 0 2.8.6 3.8 1.4M14.5 8s-2.4 4.5-6.5 4.5c-1.5 0-2.8-.6-3.8-1.4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                <circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.3"/>
                <path d="M2 14L14 2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              </svg>
            </button>
          </div>

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
                placeholder="4 位"
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
                placeholder="6 位"
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

          <p v-if="error" class="login-error" role="alert">{{ error }}</p>

          <button class="login-primary-btn" type="submit" :disabled="submitting || !canSubmit">
            {{ submitting ? submitLabelBusy : submitLabelIdle }}
          </button>

          <p v-if="mode === 'register'" class="login-agreement-note">
            注册即代表同意<a class="login-agreement-link" href="/docs/user-agreement" target="_blank" rel="noopener">《用户协议》</a>与<a class="login-agreement-link" href="/docs/privacy-policy" target="_blank" rel="noopener">《隐私政策》</a>
          </p>
        </form>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AuthMode, LoginFormValues, RegisterFormValues } from '../types/auth'
import { requestText } from '../composables/grassland-http'

const props = defineProps<{
  visible: boolean
  submitting: boolean
  error: string
  message?: string
  /** 隐藏注册入口（治理台等内部端使用：运营账号由平台开通，不自助注册）。 */
  hideRegister?: boolean
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
const captchaCode = ref('')
const captchaSvg = ref('')
const showPassword = ref(false)
const codeCooldown = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

const modalTitle = computed(() => mode.value === 'login' ? '登录草场' : '注册草场账号')
const subtitle = computed(() => {
  if (props.hideRegister) return '平台运营与治理专用入口'
  return mode.value === 'login'
    ? '商家与推荐官共用登录入口，进入身份按账号自动判定'
    : '注册即推荐官；商家账号由平台开通'
})
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
    captchaSvg.value = await requestText('/api/auth/captcha')
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
/* 桌面居中卡片，移动端保持底部抽屉（单手可达） */
.login-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 0;
  background: var(--color-overlay);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

@media (min-width: 640px) {
  .login-overlay {
    align-items: center;
    padding: 24px;
  }
}

.login-modal {
  position: relative;
  width: 100%;
  max-width: 420px;
  max-height: calc(100dvh - 20px);
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--color-border);
  border-bottom: none;
  border-radius: var(--radius-xl) var(--radius-xl) 0 0;
  padding: 0 28px 24px;
  padding-bottom: calc(24px + env(safe-area-inset-bottom, 0px));
  background: var(--surface-card);
  box-shadow: var(--shadow-elevated);
  animation: slide-up-modal 0.32s var(--ease-out);
}

@media (min-width: 640px) {
  .login-modal {
    border-bottom: 1px solid var(--color-border);
    border-radius: var(--radius-xl);
    animation: fade-in-scale 0.28s var(--ease-out);
  }
}

@keyframes slide-up-modal {
  from { opacity: 0; transform: translateY(40px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes fade-in-scale {
  from { opacity: 0; transform: translateY(12px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

/* 地平线签名：商家紫 → 推荐官苗绿，通栏置顶 */
.login-horizon {
  height: 4px;
  margin: 0 -28px 22px;
  border-radius: var(--radius-xl) var(--radius-xl) 0 0;
  background: var(--gradient-field);
}

.login-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: 18px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.login-brand-mark {
  flex-shrink: 0;
  filter: drop-shadow(0 2px 10px color-mix(in srgb, var(--color-accent) 35%, transparent));
}

.login-brand-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.login-title {
  margin: 0;
  font-size: 1.18rem;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.2;
  color: var(--color-text);
}

.login-subtitle {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  line-height: 1.5;
}

.login-close-btn {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.login-close-btn:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.login-mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-bottom: 18px;
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
}

.login-mode-btn {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.login-mode-btn-active {
  background: var(--surface-card);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.12);
  color: var(--color-text);
}

.login-message {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-md);
  background: var(--color-surface-highlight);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.5;
}

.login-form {
  display: grid;
  gap: 7px;
}

.login-label {
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
  margin-top: 6px;
}

.login-input {
  width: 100%;
  min-height: 46px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 16px;
  font-family: inherit;
  box-sizing: border-box;
  transition: border-color var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.login-input::placeholder {
  color: var(--color-text-muted);
}

.login-input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

/* 密码框 + 眼睛切换（替代「显示密码」勾选） */
.login-password-field {
  position: relative;
}

.login-password-input {
  padding-right: 46px;
}

.login-eye-btn {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.login-eye-btn:hover {
  color: var(--color-text);
  background: var(--color-surface-hover);
}

.login-captcha-row,
.login-code-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.login-captcha-input,
.login-code-input {
  flex: 1;
  min-width: 0;
}

.login-captcha-img {
  flex-shrink: 0;
  min-height: 46px;
  padding: 0 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.login-captcha-img:hover {
  border-color: var(--color-border-hover);
}

.login-code-btn {
  flex-shrink: 0;
  min-height: 46px;
  padding: 0 14px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-accent-2);
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background var(--duration-fast) var(--ease-out);
}

.login-code-btn:hover:not(:disabled) {
  background: var(--color-surface-highlight);
}

.login-code-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.login-error {
  margin: 4px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
  color: var(--color-danger);
  font-size: 0.84rem;
  line-height: 1.5;
}

.login-primary-btn {
  width: 100%;
  min-height: 48px;
  margin-top: 12px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--gradient-accent);
  color: var(--color-on-accent);
  font-size: 0.95rem;
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow: 0 4px 16px color-mix(in srgb, var(--color-accent) 30%, transparent);
  transition: transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.login-primary-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 22px color-mix(in srgb, var(--color-accent) 40%, transparent);
}

.login-primary-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
}

.login-agreement-note {
  margin: 12px 0 0;
  color: var(--color-text-muted);
  font-size: 0.76rem;
  text-align: center;
  line-height: 1.6;
}

.login-agreement-link {
  color: var(--color-accent-2);
  text-decoration: none;
}

.login-agreement-link:hover {
  text-decoration: underline;
}

@media (prefers-reduced-motion: reduce) {
  .login-modal {
    animation: none;
  }
}
</style>
