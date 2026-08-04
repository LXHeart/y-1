import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../../..')

interface ComposePort {
  host_ip?: string
  published?: string
  target?: number
}

interface ComposeService {
  depends_on?: Record<string, unknown>
  environment?: Record<string, string>
  healthcheck?: { test?: string[] }
  image?: string
  ports?: ComposePort[]
}

interface ComposeConfig {
  services: Record<string, ComposeService>
}

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

function nginxLocation(config: string, location: string): string {
  const marker = `location ${location} {`
  const start = config.indexOf(marker)
  if (start < 0) {
    throw new Error(`Missing nginx location: ${location}`)
  }

  let depth = 0
  for (let index = start + marker.length - 1; index < config.length; index += 1) {
    if (config[index] === '{') depth += 1
    if (config[index] === '}') depth -= 1
    if (depth === 0) return config.slice(start, index + 1)
  }

  throw new Error(`Unclosed nginx location: ${location}`)
}

function composeConfig(): ComposeConfig {
  const output = execFileSync(
    'docker',
    ['compose', '--env-file', '.env.docker.example', 'config', '--format', 'json'],
    {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
      env: {
        ...process.env,
        MINIO_ROOT_USER: 'test-minio-root',
        MINIO_ROOT_PASSWORD: 'test-minio-root-secret',
        MINIO_ACCESS_KEY: 'test-minio-app',
        MINIO_SECRET_KEY: 'test-minio-app-secret',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  )
  return JSON.parse(output) as ComposeConfig
}

function expectLoopbackOnly(ports: ComposePort[] | undefined): void {
  expect(ports?.length).toBeGreaterThan(0)
  for (const port of ports ?? []) {
    expect(['127.0.0.1', '::1']).toContain(port.host_ip)
  }
}

describe('Edge BFF deployment entrypoint contract', () => {
  it('routes public API and health traffic through Edge while keeping internal paths closed', () => {
    const nginx = readRepositoryFile('nginx.conf')

    const apiLocations = [
      nginxLocation(nginx, '= /api'),
      nginxLocation(nginx, '/api/'),
    ]

    for (const location of apiLocations) {
      expect(location).toContain('proxy_pass http://${API_UPSTREAM};')
      expect(location).toContain('client_max_body_size 32m;')
      expect(location).toContain('proxy_request_buffering off;')
      expect(location).toContain('proxy_buffering off;')
      expect(location).toContain('proxy_set_header Host $http_host;')
      expect(location).toContain('proxy_set_header X-Forwarded-Proto "${PUBLIC_FORWARDED_PROTO}";')
    }
    expect(nginx).toContain('set_real_ip_from ${TRUSTED_PROXY_CIDR};')
    expect(nginx).toContain('real_ip_header X-Forwarded-For;')
    expect(nginx).toContain('real_ip_recursive on;')
    const healthLocation = nginxLocation(nginx, '/health')
    expect(healthLocation).toContain('proxy_pass http://${API_UPSTREAM}/health;')
    expect(healthLocation).toContain('proxy_set_header Host $http_host;')
    expect(nginxLocation(nginx, '= /internal')).toContain('return 404;')
    expect(nginxLocation(nginx, '/internal/')).toContain('return 404;')
    expect(nginxLocation(nginx, '= /api/internal')).toContain('return 404;')
    expect(nginxLocation(nginx, '/api/internal/')).toContain('return 404;')
    expect(readRepositoryFile('Dockerfile.frontend')).toContain('/etc/nginx/templates/default.conf.template')
  })

  it('starts the complete Edge routing graph in the default Compose stack', () => {
    const compose = composeConfig()
    const requiredServices = [
      'edge-bff',
      'identity-service',
      'marketplace-service',
      'finance-service',
      'trust-service',
      'intelligence-service',
    ]

    expect(Object.keys(compose.services)).toEqual(expect.arrayContaining(requiredServices))
    expect(compose.services.frontend.depends_on).toHaveProperty('edge-bff')
    expect(compose.services.frontend.environment?.API_UPSTREAM).toBe('edge-bff:8080')
    expect(compose.services.frontend.environment?.PUBLIC_FORWARDED_PROTO).toBe('https')
    expect(compose.services.frontend.environment?.TRUSTED_PROXY_CIDR).toBe('127.0.0.1/32')
    expect(compose.services['edge-bff'].depends_on).toEqual(expect.objectContaining({
      backend: expect.anything(),
      'identity-service': expect.anything(),
      'marketplace-service': expect.anything(),
      'finance-service': expect.anything(),
      'trust-service': expect.anything(),
      'intelligence-service': expect.anything(),
    }))
  })

  it('passes every RouteManifest feature flag into the Edge container', () => {
    const compose = composeConfig()
    const manifest = readRepositoryFile(
      'platform-java/services/edge-bff/src/main/resources/application.yml',
    )
    const expectedFlags = new Map(
      [...manifest.matchAll(/\$\{(EDGE_ROUTE_[A-Z0-9_]+):(true|false)\}/g)]
        .map(([, name, defaultValue]) => [name, defaultValue]),
    )

    expect(expectedFlags.size).toBeGreaterThan(0)
    for (const [name, defaultValue] of expectedFlags) {
      expect(compose.services['edge-bff'].environment?.[name], name).toBe(defaultValue)
    }
  })

  it('does not publish bypass ports beyond the local development host', () => {
    const compose = composeConfig()

    for (const service of [
      'backend',
      'edge-bff',
      'kafka',
      'redis',
      'minio',
      'temporal',
      'postgres-local',
    ]) {
      expectLoopbackOnly(compose.services[service].ports)
    }
  })

  it('requires object-storage credentials and limits the public presigned proxy', () => {
    const compose = readRepositoryFile('docker-compose.yml')
    const nginx = readRepositoryFile('nginx.conf')

    for (const variable of [
      'MINIO_ROOT_USER',
      'MINIO_ROOT_PASSWORD',
      'MINIO_ACCESS_KEY',
      'MINIO_SECRET_KEY',
    ]) {
      expect(compose).toContain(`\${${variable}:?`)
    }
    expect(nginx).toContain('location ^~ /minio/admin/')
    expect(nginx).toContain('$request_method !~ ^(GET|HEAD|PUT|OPTIONS)$')
  })

  it('keeps known MinIO credentials and the direct API port out of environment templates', () => {
    for (const template of ['.env.example', '.env.docker.example']) {
      const environment = readRepositoryFile(template)

      expect(environment).not.toContain('minioadmin')
      expect(environment).toMatch(/MINIO_PUBLIC_BASE_URL=http:\/\/(?:localhost|your-host):9002/)
      for (const variable of [
        'MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD',
        'MINIO_ACCESS_KEY',
        'MINIO_SECRET_KEY',
      ]) {
        expect(environment).toContain(`${variable}=\n`)
      }
    }
  })

  it('does not fall back to known MinIO credentials when Intelligence starts directly', () => {
    const application = readRepositoryFile(
      'platform-java/services/intelligence-service/src/main/resources/application.yml',
    )

    expect(application).toContain('access-key: ${MINIO_ACCESS_KEY:}')
    expect(application).toContain('secret-key: ${MINIO_SECRET_KEY:}')
    expect(application).not.toContain('minioadmin')
  })

  it('pins infrastructure images and waits for MinIO readiness', () => {
    const compose = composeConfig()

    expect(compose.services.minio.image).toBe(
      'minio/minio:RELEASE.2025-09-07T16-13-09Z',
    )
    expect(compose.services.temporal.image).toBe('temporalio/temporal:1.8.1')
    expect(compose.services.minio.healthcheck?.test?.join(' ')).toContain(
      'http://localhost:9000/minio/health/live',
    )
    expect(compose.services['intelligence-service'].depends_on)
      .toHaveProperty('minio.condition', 'service_healthy')
    expect(compose.services['marketplace-service'].depends_on)
      .toHaveProperty('temporal.condition', 'service_healthy')
    expect(compose.services['trust-service'].depends_on)
      .toHaveProperty('temporal.condition', 'service_healthy')
    expect(compose.services['marketplace-service'].depends_on).not.toHaveProperty('minio')
  })

  it('keeps environment assignments unique and documents required Java model settings', () => {
    for (const template of ['.env.example', '.env.docker.example']) {
      const environment = readRepositoryFile(template)
      const names = environment
        .split(/\r?\n/)
        .filter((line) => /^[A-Z][A-Z0-9_]*=/.test(line))
        .map((line) => line.slice(0, line.indexOf('=')))

      expect(new Set(names).size, template).toBe(names.length)
      expect(environment, template).toContain(
        'QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1',
      )
      expect(environment, template).toContain('QWEN_API_KEY=replace-with-qwen-api-key')
    }
  })

  it('documents whole-entry rollback without starting a failed Edge dependency graph', () => {
    expect(readRepositoryFile('README.md')).toContain(
      'up -d --no-deps --force-recreate frontend',
    )
  })
})
