import 'express-session'
import type { LoginAttemptOutcome, SessionUser } from '../lib/auth.js'

declare global {
  namespace Express {
    interface Request {
      authUser?: SessionUser
      loginAttemptOutcome?: LoginAttemptOutcome
      notifyLoginAttemptOutcomeSet?: () => void
    }
  }
}

declare module 'express-session' {
  interface SessionData {
    user?: SessionUser
  }
}

export {}
