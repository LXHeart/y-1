import { Pool, type QueryResult, type QueryResultRow } from 'pg'
import { AppError } from './errors.js'
import { env } from './env.js'
import { logger } from './logger.js'

const DEFAULT_POOL_MAX = 10
const DEFAULT_IDLE_TIMEOUT_MS = 30_000

let dbPool: Pool | null = null

export function isDatabaseConfigured(): boolean {
  return Boolean(env.DATABASE_URL)
}

export function getDbPool(): Pool {
  if (!env.DATABASE_URL) {
    throw new AppError('PostgreSQL 未配置，请先设置 DATABASE_URL', 503)
  }

  if (dbPool) {
    return dbPool
  }

  dbPool = new Pool({
    connectionString: env.DATABASE_URL,
    max: DEFAULT_POOL_MAX,
    idleTimeoutMillis: DEFAULT_IDLE_TIMEOUT_MS,
  })

  dbPool.on('error', (error) => {
    logger.error({ err: error }, 'Unexpected PostgreSQL pool error')
  })

  return dbPool
}

export async function queryDb<T extends QueryResultRow>(
  text: string,
  params: readonly unknown[] = [],
): Promise<QueryResult<T>> {
  return getDbPool().query<T>(text, [...params])
}

export async function closeDbPool(): Promise<void> {
  if (!dbPool) {
    return
  }

  const pool = dbPool
  dbPool = null
  await pool.end()
}
