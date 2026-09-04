export interface AuthUser {
  id: string
  email: string
  /** 任务书 #49：子账号的完整登录名（前缀-登录名）；普通邮箱账号缺失。 */
  username?: string
  /** 任务书 #49：email 是否为真实邮箱（false = 未绑定的占位符，UI 不当联系方式展示）。 */
  hasEmail?: boolean
  displayName?: string
  role: string
  /** GL-P2-ADMIN-001：后端角色数组（多值，来自 backend_role 表）。旧用户可能缺失 → 视为空数组。 */
  roles?: string[]
  /** 任务书 #48：管理员代建/重置后的首登强制改密态；改密成功即清除 */
  mustChangePassword?: boolean
}

export interface AuthSuccessResponse {
  success: true
  data: {
    user: AuthUser
  }
}

export interface AuthLogoutResponse {
  success: true
  data: {
    loggedOut: true
  }
}

export interface AuthErrorResponse {
  success: false
  error: string
}

export type AuthMode = 'login' | 'register'
export type AuthMeResponse = AuthSuccessResponse | AuthErrorResponse
export type AuthLoginResponse = AuthSuccessResponse | AuthErrorResponse
export type AuthRegisterResponse = AuthSuccessResponse | AuthErrorResponse
export type AuthLogoutApiResponse = AuthLogoutResponse | AuthErrorResponse

/** 登录/注册均不带身份：进入身份按账号已有档案自动落地（2026-09-04 身份模型改版）。 */
export interface LoginFormValues {
  email: string
  password: string
}

/** 注册即推荐官（服务端事务内建 recommender 档案）；商家账号由平台治理台初始化。 */
export interface RegisterFormValues {
  email: string
  displayName: string
  password: string
  confirmPassword: string
  verificationCode: string
}
