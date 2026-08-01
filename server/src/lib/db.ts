import { Pool, type PoolClient, type QueryResult, type QueryResultRow } from 'pg'
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

/**
 * 事务内查询句柄。与 {@link queryDb} 同签名，便于服务层在事务内外复用同一份 SQL。
 */
export interface DbTransaction {
  query<T extends QueryResultRow>(
    text: string,
    params?: readonly unknown[],
  ): Promise<QueryResult<T>>
}

/**
 * 在单个连接的事务中执行 `handler`：成功 commit，抛错 rollback 后原样抛出。
 *
 * 用于余额与流水必须同生同死的写入（积分扣减/退款）——两条语句分开跑会在中途失败时留下半账。
 */
export async function withDbTransaction<T>(
  handler: (tx: DbTransaction) => Promise<T>,
): Promise<T> {
  const client: PoolClient = await getDbPool().connect()

  try {
    await client.query('BEGIN')
    const result = await handler({
      query: <R extends QueryResultRow>(text: string, params: readonly unknown[] = []) =>
        client.query<R>(text, [...params]),
    })
    await client.query('COMMIT')
    return result
  } catch (error: unknown) {
    try {
      await client.query('ROLLBACK')
    } catch (rollbackError: unknown) {
      logger.error({ err: rollbackError }, 'Failed to rollback transaction')
    }
    throw error
  } finally {
    client.release()
  }
}

export async function closeDbPool(): Promise<void> {
  if (!dbPool) {
    return
  }

  const pool = dbPool
  dbPool = null
  await pool.end()
}
