/**
 * 登录弹窗控制器（任务书 #76 抽取）：登录/注册/验证码的弹窗状态与提交接线，
 * 草场壳（DefaultLayout）与 AI 应用壳（AiAppLayout）双挂载，禁止复制接线代码。
 *
 * 壳差异经回调注入：注册成功后的去向（草场推工作台；AI 应用原地停留）与
 * 登录/注册成功后的提示文案由各壳自理。
 */
import { ref } from 'vue'
import { useAuth } from './useAuth'
import type { LoginFormValues, RegisterFormValues } from '../types/auth'

export interface LoginModalControllerOptions {
  onLoginSuccess?: () => void
  onRegisterSuccess?: () => void
}

export function useLoginModalController(options: LoginModalControllerOptions = {}) {
  const showLoginModal = ref(false)
  const loginModalMounted = ref(false)
  const loginModalMessage = ref('')

  const {
    loggingIn, registering,
    loginError, registerError, sendCodeError,
    clearLoginError, clearRegisterError, clearSendCodeError,
    sendVerificationCode, login, register,
  } = useAuth()

  function openLoginModal(message = ''): void {
    loginModalMounted.value = true
    clearLoginError(); clearRegisterError()
    loginModalMessage.value = message
    showLoginModal.value = true
  }

  function closeLoginModal(): void {
    clearLoginError(); clearRegisterError()
    showLoginModal.value = false
    loginModalMessage.value = ''
  }

  async function handleLogin(values: LoginFormValues): Promise<void> {
    const ok = await login(values)
    if (!ok) return
    closeLoginModal()
    options.onLoginSuccess?.()
  }

  async function handleRegister(values: RegisterFormValues): Promise<void> {
    const ok = await register(values)
    if (!ok) return
    closeLoginModal()
    options.onRegisterSuccess?.()
  }

  async function handleSendCode(email: string, captchaCode: string): Promise<void> {
    clearSendCodeError()
    await sendVerificationCode(email, captchaCode)
  }

  return {
    showLoginModal, loginModalMounted, loginModalMessage,
    loggingIn, registering,
    loginError, registerError, sendCodeError,
    openLoginModal, closeLoginModal, handleLogin, handleRegister, handleSendCode,
  }
}
