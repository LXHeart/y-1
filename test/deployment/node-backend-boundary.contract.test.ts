import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('Java-only backend and Node frontend boundary', () => {
  it('keeps Node package scripts limited to frontend and quality tooling', () => {
    const packageJson = JSON.parse(readRepositoryFile('package.json')) as {
      scripts?: Record<string, string>
      dependencies?: Record<string, string>
      devDependencies?: Record<string, string>
    }
    const scripts = packageJson.scripts ?? {}
    const allowedScripts = new Set([
      'dev', 'dev:client', 'build', 'build:client', 'preview', 'typecheck', 'lint',
      'test', 'test:coverage', 'coverage:changed', 'security:secrets', 'docs:status',
      'e2e', 'e2e:ci', 'e2e:seed:auth', 'e2e:seed',
    ])

    expect(Object.keys(scripts)).toEqual(expect.arrayContaining([
      'dev:client', 'build:client', 'typecheck', 'test', 'e2e', 'e2e:seed',
    ]))
    expect(Object.keys(scripts).filter((name) => !allowedScripts.has(name))).toEqual([])
    expect(JSON.stringify(scripts)).not.toMatch(/(?:express|fastify|koa|nestjs|node\s+.*(?:server|worker)|(?:dev|start|build):server)/i)
    expect(packageJson.dependencies ?? {}).not.toHaveProperty('express')
    expect(packageJson.devDependencies ?? {}).not.toHaveProperty('express')
  })

  it('does not retain an Express fallback target in the frontend dev proxy', () => {
    const vite = readRepositoryFile('vite.config.ts')
    expect(vite).toContain("target: process.env.VITE_API_TARGET || 'http://localhost:8081'")
    expect(vite).not.toMatch(/(?:Express|legacy|localhost:3000|回退)/i)

    const localEnv = readRepositoryFile('.env.example')
    expect(localEnv).toContain('VITE_API_TARGET=http://localhost:8081')
    expect(localEnv).toContain('PUBLIC_BACKEND_ORIGIN=http://localhost:8081')
    expect(localEnv).not.toMatch(/^(?:PORT=3000|.*localhost:3000)/m)
  })

  it('removes Node HTTP service artifacts and backend deployment targets', () => {
    expect(existsSync(resolve(REPOSITORY_ROOT, 'server'))).toBe(false)
    expect(existsSync(resolve(REPOSITORY_ROOT, 'Dockerfile.backend'))).toBe(false)

    for (const path of ['docker-compose.yml', 'docker-compose.production.yml', 'scripts/ci-e2e.sh', 'scripts/production-release.sh']) {
      const source = readRepositoryFile(path)
      expect(source, path).not.toMatch(/^\s*backend:/m)
      expect(source, path).not.toMatch(/(?:BACKEND_PORT|API_UPSTREAM=backend:|npm\s+(?:run\s+)?(?:dev|start|build):server)/i)
    }

    const identityConfig = readRepositoryFile(
      'platform-java/services/identity-service/src/main/resources/application.yml',
    )
    expect(identityConfig).toContain('url: ${R2DBC_DATABASE_URL:}')
    expect(identityConfig).not.toContain('LEGACY_DATABASE_R2DBC_URL')
  })

  it('keeps the production Edge manifest Java-only and fail-closed by default', () => {
    const manifest = readRepositoryFile(
      'platform-java/services/edge-bff/src/main/resources/application.yml',
    )

    expect(manifest).toContain('default-upstream: fail-closed')
    expect(manifest).not.toMatch(/legacy|express|node\s+(?:server|worker)|backend:/i)

    const upstreamNames = [...manifest.matchAll(/^\s{4}([a-z][a-z0-9-]+):\s*\$\{/gm)]
      .map(([, name]) => name)
    expect(upstreamNames).toEqual(expect.arrayContaining([
      'identity', 'marketplace', 'finance', 'trust', 'intelligence',
    ]))
    expect(upstreamNames).not.toContain('backend')
    expect(upstreamNames).not.toContain('legacy')
  })

  it('keeps frontend platform rules on the shared versioned contract', () => {
    const contract = JSON.parse(readRepositoryFile('contracts/platform-format-rules.json')) as {
      version?: string
      platforms?: Array<Record<string, unknown>>
    }
    expect(contract.version).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(contract.platforms).toHaveLength(9)
    expect(readRepositoryFile('src/config/platform-format-rules.ts'))
      .toContain("../../contracts/platform-format-rules.json")
    expect(readRepositoryFile('platform-java/services/intelligence-service/build.gradle.kts'))
      .toContain('../contracts/platform-format-rules.json')
  })

  it('allows Node in frontend and Java Playwright driver images only', () => {
    const frontendDockerfile = readRepositoryFile('Dockerfile.frontend')
    expect(frontendDockerfile).toContain('FROM node:20-bookworm AS build')
    expect(frontendDockerfile).toContain('RUN npm run build:client')
    expect(frontendDockerfile).not.toMatch(/EXPOSE\s+3\d{3}/)

    const intelligenceDockerfile = readRepositoryFile('platform-java/services/intelligence-service/Dockerfile')
    expect(intelligenceDockerfile).toContain('FROM eclipse-temurin:25-jre-alpine')
    expect(intelligenceDockerfile).toContain('apk add --no-cache nodejs chromium ffmpeg')
    expect(intelligenceDockerfile).toContain('ENTRYPOINT ["java"')
    expect(intelligenceDockerfile).toContain('playwright-core')
    expect(intelligenceDockerfile).not.toMatch(/(?:node\s+server|npm\s+(?:run\s+)?(?:start|dev|serve))/i)
  })
})
