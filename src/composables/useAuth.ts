import { computed, ref } from 'vue'
import type {
  AuthLoginResponse,
  AuthLogoutApiResponse,
  AuthMeResponse,
  AuthRegisterResponse,
  AuthUser,
  LoginFormValues,
  RegisterFormValues,
} from '../types/auth'

async function readApiError(response: Response, fallbackMessage: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''

  if (contentType.includes('application/json')) {
    const body = await response.json() as { error?: string }
    return body.error || fallbackMessage
  }

  const text = await response.text()
  return text.trim() || fallbackMessage
}

const currentUser = ref<AuthUser | null>(null)
const loading = ref(false)
const loaded = ref(false)
const loggingIn = ref(false)
const registering = ref(false)
const loggingOut = ref(false)
const loginError = ref('')
const registerError = ref('')
const logoutError = ref('')
const loadError = ref('')
const sendCodeError = ref('')
const sendingCode = ref(false)

export function useAuth() {
  const isAuthenticated = computed(() => currentUser.value !== null)

  /**
   * 当前账号的后台角色集合（GL-P2-ADMIN-001）。优先读 roles 数组；旧用户缺失时回落单值 role 兜底
   * （admin → platform_admin / customer_service → customer_service），保证向后兼容。
   */
  const backendRoles = computed<string[]>(() => {
    const u = currentUser.value
    if (!u) return []
    if (u.roles && u.roles.length > 0) return u.roles
    // 兜底：旧用户无 roles 数组，从单值 role 推导
    if (u.role === 'admin') return ['platform_admin']
    if (u.role === 'customer_service') return ['customer_service']
    return []
  })

  /** 是否持有指定后台角色（含 platform_admin 超集语义）。 */
  function hasBackendRole(role: string): boolean {
    const roles = backendRoles.value
    if (roles.includes('platform_admin')) return true
    return roles.includes(role)
  }

  function clearLoginError(): void {
    loginError.value = ''
  }

  function clearRegisterError(): void {
    registerError.value = ''
  }

  function clearLogoutError(): void {
    logoutError.value = ''
  }

  function clearSendCodeError(): void {
    sendCodeError.value = ''
  }

  async function fetchCaptchaSvg(): Promise<string> {
    const response = await fetch('/api/auth/captcha', { credentials: 'include' })
    if (!response.ok) {
      throw new Error('获取验证码失败')
    }
    return await response.text()
  }

  async function sendVerificationCode(email: string, captchaCode: string): Promise<boolean> {
    sendingCode.value = true
    sendCodeError.value = ''

    try {
      const response = await fetch('/api/auth/send-code', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, captchaCode }),
      })

      if (!response.ok) {
        throw new Error(await readApiError(response, `验证码发送失败（${response.status}）`))
      }

      const body = await response.json() as { success: boolean; error?: string }
      if (!body.success) {
        throw new Error(body.error || '验证码发送失败')
      }

      return true
    } catch (error: unknown) {
      sendCodeError.value = error instanceof Error ? error.message : '验证码发送失败'
      return false
    } finally {
      sendingCode.value = false
    }
  }

  async function loadCurrentUser(force = false): Promise<boolean> {
    if (loading.value || (loaded.value && !force)) {
      return loadError.value === ''
    }

    loading.value = true
    loadError.value = ''

    try {
      const response = await fetch('/api/auth/me', {
        credentials: 'include',
      })

      if (response.status === 401) {
        currentUser.value = null
        loaded.value = true
        return true
      }

      if (!response.ok) {
        throw new Error(`当前无法确认登录状态，请稍后重试（${response.status}）`)
      }

      const body = await response.json() as AuthMeResponse
      if (!body.success) {
        throw new Error(body.error || '当前无法确认登录状态，请稍后重试')
      }

      currentUser.value = body.data.user
      loaded.value = true
      return true
    } catch (error: unknown) {
      currentUser.value = null
      loaded.value = false
      loadError.value = error instanceof Error ? error.message : '当前无法确认登录状态，请稍后重试'
      return false
    } finally {
      loading.value = false
    }
  }

  async function login(values: LoginFormValues): Promise<boolean> {
    loggingIn.value = true
    loginError.value = ''
    registerError.value = ''
    logoutError.value = ''

    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      })

      if (!response.ok) {
        throw new Error(await readApiError(response, `登录失败（${response.status}）`))
      }

      const body = await response.json() as AuthLoginResponse
      if (!body.success) {
        throw new Error(body.error || '登录失败')
      }

      currentUser.value = body.data.user
      loaded.value = true
      return true
    } catch (error: unknown) {
      currentUser.value = null
      loginError.value = error instanceof Error ? error.message : '登录失败'
      return false
    } finally {
      loggingIn.value = false
    }
  }

  async function register(values: RegisterFormValues): Promise<boolean> {
    registering.value = true
    registerError.value = ''
    loginError.value = ''
    logoutError.value = ''

    try {
      const response = await fetch('/api/auth/register', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      })

      if (!response.ok) {
        throw new Error(await readApiError(response, `注册失败（${response.status}）`))
      }

      const body = await response.json() as AuthRegisterResponse
      if (!body.success) {
        throw new Error(body.error || '注册失败')
      }

      currentUser.value = body.data.user
      loaded.value = true
      return true
    } catch (error: unknown) {
      currentUser.value = null
      registerError.value = error instanceof Error ? error.message : '注册失败'
      return false
    } finally {
      registering.value = false
    }
  }

  async function logout(): Promise<boolean> {
    loggingOut.value = true
    logoutError.value = ''

    try {
      const response = await fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'include',
      })

      if (response.status === 401) {
        currentUser.value = null
        loaded.value = true
        return true
      }

      if (!response.ok) {
        throw new Error(await readApiError(response, `退出登录失败（${response.status}）`))
      }

      const body = await response.json() as AuthLogoutApiResponse
      if (!body.success) {
        throw new Error(body.error || '退出登录失败')
      }

      currentUser.value = null
      loaded.value = true
      return true
    } catch (error: unknown) {
      logoutError.value = error instanceof Error ? error.message : '退出登录失败'
      return false
    } finally {
      loggingOut.value = false
    }
  }

  return {
    currentUser,
    isAuthenticated,
    backendRoles,
    hasBackendRole,
    loading,
    loaded,
    loggingIn,
    registering,
    loggingOut,
    loginError,
    registerError,
    logoutError,
    loadError,
    clearLoginError,
    clearRegisterError,
    clearLogoutError,
    clearSendCodeError,
    fetchCaptchaSvg,
    sendVerificationCode,
    loadCurrentUser,
    login,
    register,
    logout,
    sendingCode,
    sendCodeError,
  }
}
