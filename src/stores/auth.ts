import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AuthUser, LoginFormValues, RegisterFormValues } from '../types/auth'
import { GrasslandHttpError, request, requestText } from '../composables/grassland-http'

export const useAuthStore = defineStore('auth', () => {
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

  const isAuthenticated = computed(() => currentUser.value !== null)

  /**
   * 首登强制改密态（任务书 #48）：主体直建/重置密码的子账号登录成功但未改密时为 true。
   * 路由守卫据此锁到改密页；服务端还有 edge 的 428 硬闸兜底，前端只是体验层。
   */
  const mustChangePassword = computed(() => currentUser.value?.mustChangePassword === true)

  /**
   * 当前账号的后台角色集合（GL-P2-ADMIN-001）。优先读 roles 数组；旧用户缺失时回落单值 role 兜底
   * （admin → platform_admin / customer_service → customer_service），保证向后兼容。
   */
  const backendRoles = computed<string[]>(() => {
    const u = currentUser.value
    if (!u) return []
    if (u.roles && u.roles.length > 0) return u.roles
    if (u.role === 'admin') return ['platform_admin']
    if (u.role === 'customer_service') return ['customer_service']
    return []
  })

  function hasBackendRole(role: string): boolean {
    const roles = backendRoles.value
    if (roles.includes('platform_admin')) return true
    return roles.includes(role)
  }

  function clearLoginError(): void { loginError.value = '' }
  function clearRegisterError(): void { registerError.value = '' }
  function clearLogoutError(): void { logoutError.value = '' }
  function clearSendCodeError(): void { sendCodeError.value = '' }

  async function fetchCaptchaSvg(): Promise<string> {
    try {
      return await requestText('/api/auth/captcha')
    } catch {
      throw new Error('获取验证码失败')
    }
  }

  async function sendVerificationCode(email: string, captchaCode: string): Promise<boolean> {
    sendingCode.value = true
    sendCodeError.value = ''
    try {
      await request<{ sent: boolean }>('/api/auth/send-code', {
        method: 'POST',
        body: JSON.stringify({ email, captchaCode }),
      }, { fallbackError: '验证码发送失败' })
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
      const data = await request<{ user: AuthUser }>('/api/auth/me')
      currentUser.value = data.user
      loaded.value = true
      return true
    } catch (error: unknown) {
      // 401 = 未登录，是正常态而非错误（与原实现的显式状态分支等价）。
      if (error instanceof GrasslandHttpError && error.status === 401) {
        currentUser.value = null
        loaded.value = true
        return true
      }
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
      // identity 是登录后的进入身份（前端编排用），不属于认证 API 契约，发送前剥离
      const { email, password } = values
      const data = await request<{ user: AuthUser }>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      }, { fallbackError: '登录失败' })
      currentUser.value = data.user
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
      const { email, displayName, password, confirmPassword, verificationCode } = values
      const data = await request<{ user: AuthUser }>('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email, displayName, password, confirmPassword, verificationCode }),
      }, { fallbackError: '注册失败' })
      currentUser.value = data.user
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

  /**
   * 本人改密（任务书 #48）：首登强制态下 currentPassword 可省（后端 account_flag 放行）；
   * 成功即清除当前登录对象的标记，路由守卫随即放行业务页。
   */
  async function changePassword(newPassword: string, currentPassword?: string): Promise<boolean> {
    try {
      await request<{ success: true }>('/api/auth/change-password', {
        method: 'POST',
        body: JSON.stringify(currentPassword ? { currentPassword, newPassword } : { newPassword }),
      }, { fallbackError: '修改密码失败' })
      if (currentUser.value) currentUser.value.mustChangePassword = false
      return true
    } catch (error: unknown) {
      throw error instanceof Error ? error : new Error('修改密码失败')
    }
  }

  async function logout(): Promise<boolean> {
    loggingOut.value = true
    logoutError.value = ''
    try {
      await request<{ loggedOut: true }>('/api/auth/logout', { method: 'POST' })
      currentUser.value = null
      loaded.value = true
      return true
    } catch (error: unknown) {
      if (error instanceof GrasslandHttpError && error.status === 401) {
        currentUser.value = null
        loaded.value = true
        return true
      }
      logoutError.value = error instanceof Error ? error.message : '退出登录失败'
      return false
    } finally {
      loggingOut.value = false
    }
  }

  return {
    currentUser,
    isAuthenticated,
    mustChangePassword,
    changePassword,
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
})
