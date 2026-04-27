export interface AuthUser {
  id: string
  email: string
  displayName?: string
  role: string
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

export interface LoginFormValues {
  email: string
  password: string
}

export interface RegisterFormValues {
  email: string
  displayName: string
  password: string
  confirmPassword: string
  verificationCode: string
}
