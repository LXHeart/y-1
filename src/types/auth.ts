export interface AuthUser {
  id: string
  email: string
  displayName?: string
  role: string
  /** GL-P2-ADMIN-001：后端角色数组（多值，来自 backend_role 表）。旧用户可能缺失 → 视为空数组。 */
  roles?: string[]
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

/** 登录/注册时选择进入的身份（PRD：登录时区分身份，不在登录后引导选择）。 */
export type LoginIdentity = 'merchant' | 'recommender'

export interface LoginFormValues {
  email: string
  password: string
  /** 用户端登录弹窗必带（withIdentityChoice）；治理台等内部端无此选择。 */
  identity?: LoginIdentity
}

/** 注册只建统一账号（不选身份）；业务身份在登录/注册提交时选定，登录成功后开通并激活。 */
export interface RegisterFormValues {
  email: string
  displayName: string
  password: string
  confirmPassword: string
  verificationCode: string
  identity?: LoginIdentity
}
