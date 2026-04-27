import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { getDbPool } from '../lib/db.js'
import { logger } from '../lib/logger.js'

const sqlDir = resolve(process.cwd(), 'server/sql')

async function run(): Promise<void> {
  const pool = getDbPool()
  const client = await pool.connect()

  try {
    await client.query('begin')
    await client.query(`
      create table if not exists schema_migrations (
        version text primary key,
        applied_at timestamptz not null default now()
      )
    `)
    await client.query('commit')
  } catch (error: unknown) {
    await client.query('rollback')
    throw error
  } finally {
    client.release()
  }

  const files = readdirSync(sqlDir)
    .filter((fileName) => fileName.endsWith('.sql'))
    .sort((left, right) => left.localeCompare(right))

  for (const fileName of files) {
    const migration = await pool.connect()

    try {
      await migration.query('begin')
      const existing = await migration.query<{ version: string }>(
        'select version from schema_migrations where version = $1 limit 1',
        [fileName],
      )

      if (existing.rows[0]) {
        await migration.query('rollback')
        continue
      }

      const sql = readFileSync(resolve(sqlDir, fileName), 'utf-8')
      await migration.query(sql)
      await migration.query('insert into schema_migrations (version) values ($1)', [fileName])
      await migration.query('commit')
      logger.info({ version: fileName }, 'Applied SQL migration')
    } catch (error: unknown) {
      await migration.query('rollback')
      throw error
    } finally {
      migration.release()
    }
  }

  await pool.end()
}

run().catch((error: unknown) => {
  logger.error({ err: error }, 'Failed to run SQL migrations')
  process.exitCode = 1
})
