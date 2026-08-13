import { Pool, type QueryResult, type QueryResultRow } from 'pg'

const databaseUrl = process.env.DATABASE_URL?.trim()

if (!databaseUrl) {
  throw new Error('DATABASE_URL is required')
}

const pool = new Pool({
  connectionString: databaseUrl,
  max: 4,
  idleTimeoutMillis: 30_000,
})

export function queryDb<T extends QueryResultRow>(
  text: string,
  params: readonly unknown[] = [],
): Promise<QueryResult<T>> {
  return pool.query<T>(text, [...params])
}

export function closeDbPool(): Promise<void> {
  return pool.end()
}
