import { createAdminRequestSchema } from '../schemas/auth.js'
import { createUser, findUserByEmail } from '../services/user.service.js'
import { logger } from '../lib/logger.js'

function parseArgs(argv: string[]): Record<string, string> {
  return argv.reduce<Record<string, string>>((result, entry) => {
    const [key, value] = entry.split('=')
    if (key.startsWith('--') && value) {
      return {
        ...result,
        [key.slice(2)]: value,
      }
    }

    return result
  }, {})
}

async function run(): Promise<void> {
  const args = parseArgs(process.argv.slice(2))
  const input = createAdminRequestSchema.parse({
    email: args.email,
    password: args.password,
    displayName: args.name,
  })

  const existing = await findUserByEmail(input.email)
  if (existing) {
    logger.info({ email: existing.email, userId: existing.id }, 'Admin user already exists')
    return
  }

  const user = await createUser({
    email: input.email,
    password: input.password,
    displayName: input.displayName,
    role: 'admin',
  })

  logger.info({ email: user.email, userId: user.id }, 'Admin user created')
}

run().catch((error: unknown) => {
  logger.error({ err: error }, 'Failed to create admin user')
  process.exitCode = 1
})
