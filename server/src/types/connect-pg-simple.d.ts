declare module 'connect-pg-simple' {
  import type session from 'express-session'
  import type { Pool } from 'pg'

  interface PgStoreOptions {
    pool: Pool
    tableName?: string
    createTableIfMissing?: boolean
  }

  interface PgSessionStoreConstructor {
    new (options: PgStoreOptions): session.Store
  }

  export default function connectPgSimple(sessionModule: typeof session): PgSessionStoreConstructor
}
