import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

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

/** 提取 `map "..." $name { ... }` 块（策略值里可能含 `}`，不能按首个 `}` 截断）。 */
function nginxMapBlock(config: string, mapName: string): string {
  const marker = new RegExp(`map "[^"]+" ${mapName.replace('$', '\\$')} \\{`)
  const match = marker.exec(config)
  if (!match || match.index === undefined) {
    throw new Error(`Missing nginx map: ${mapName}`)
  }
  const start = match.index + match[0].length
  const end = config.indexOf('\n}', start)
  if (end < 0) {
    throw new Error(`Unclosed nginx map: ${mapName}`)
  }
  return config.slice(start, end)
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

  it('ships CSP with a report-only default, an enforce switch, and a hash-pinned inline bootstrap', () => {
    const nginx = readRepositoryFile('nginx.conf')

    // 两套互斥头：report-only 为 map 默认值，enforce 显式切换；同一份策略内容。
    const enforcedMap = nginxMapBlock(nginx, '$csp_policy_enforced')
    const reportOnlyMap = nginxMapBlock(nginx, '$csp_policy_report_only')
    expect(enforcedMap).toMatch(/default\s+"";/)
    expect(enforcedMap).toMatch(/"enforce"\s+"default-src 'self';/)
    expect(reportOnlyMap).toMatch(/default\s+"default-src 'self';/)
    expect(reportOnlyMap).toMatch(/"enforce"\s+"";/)
    expect(nginx).toContain('add_header Content-Security-Policy $csp_policy_enforced always;')
    expect(nginx).toContain('add_header Content-Security-Policy-Report-Only $csp_policy_report_only always;')
    for (const hardening of ["object-src 'none'", "base-uri 'self'", "form-action 'self'", 'report-uri /csp-report']) {
      expect(nginx).toContain(hardening)
    }
    // script-src 不允许 unsafe-inline；style-src 的 unsafe-inline 是记录在案的折衷。
    expect(nginx).toContain("script-src 'self' 'sha256-")
    expect(nginx).not.toContain("script-src 'self' 'unsafe-inline'")

    // index.html 与 ops.html（治理台入口）的内联防 FOUC 脚本必须与 nginx hash 严格同步
    // （改一处不改另一处会在 enforce 下被打断）；两个入口共用同一段脚本即同一个 hash。
    const indexHtml = readRepositoryFile('index.html')
    const inline = indexHtml.match(/<script>(.*?)<\/script>/s)
    expect(inline).not.toBeNull()
    const hash = createHash('sha256').update(inline![1], 'utf8').digest('base64')
    expect(nginx).toContain(`'sha256-${hash}'`)

    const opsHtml = readRepositoryFile('ops.html')
    const opsInline = opsHtml.match(/<script>(.*?)<\/script>/s)
    expect(opsInline).not.toBeNull()
    const opsHash = createHash('sha256').update(opsInline![1], 'utf8').digest('base64')
    expect(nginx).toContain(`'sha256-${opsHash}'`)
    // ops.html 入口脚本指向治理台应用
    expect(opsHtml).toContain('src="/src/ops/main.ts"')

    // 报告端点：只收 POST、反代到 edge、清掉转发链身份头。
    const reportLocation = nginxLocation(nginx, '= /csp-report')
    expect(reportLocation).toContain('return 405;')
    expect(reportLocation).toContain('proxy_pass http://${API_UPSTREAM}/api/csp-report;')
    expect(reportLocation).toContain('proxy_set_header X-Grassland-Identity "";')

    // Compose 默认 report-only，且保留 enforce 与跨源补充开关。
    const compose = readRepositoryFile('docker-compose.yml')
    expect(compose).toContain('CSP_MODE: ${CSP_MODE:-report-only}')
    expect(compose).toContain('CSP_EXTRA_ORIGINS: ${CSP_EXTRA_ORIGINS:-}')
    expect(readRepositoryFile('.env.docker.example')).toContain('CSP_MODE=report-only')
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
    expect(compose.services).not.toHaveProperty('backend')
    expect(compose.services.frontend.depends_on).toHaveProperty('edge-bff')
    expect(compose.services.frontend.environment?.API_UPSTREAM).toBe('edge-bff:8080')
    expect(compose.services.frontend.environment?.PUBLIC_FORWARDED_PROTO).toBe('https')
    expect(compose.services.frontend.environment?.TRUSTED_PROXY_CIDR).toBe('127.0.0.1/32')
    expect(compose.services['edge-bff'].depends_on).toEqual(expect.objectContaining({
      'identity-service': expect.anything(),
      'marketplace-service': expect.anything(),
      'finance-service': expect.anything(),
      'trust-service': expect.anything(),
      'intelligence-service': expect.anything(),
    }))
    expect(compose.services['edge-bff'].depends_on).not.toHaveProperty('backend')
  })

  it('requires readiness probes before the public frontend is considered healthy', () => {
    const compose = composeConfig()
    const applicationServices = [
      'edge-bff',
      'identity-service',
      'marketplace-service',
      'finance-service',
      'trust-service',
      'intelligence-service',
    ]

    for (const service of applicationServices) {
      const healthcheck = compose.services[service].healthcheck?.test ?? []
      expect(healthcheck.join(' '), service).toContain('/actuator/health/readiness')
      expect(healthcheck, service).toContain('wget')
      expect(healthcheck, `${service} Alpine runtime has no bash`).not.toContain('bash')

      const dockerfile = readRepositoryFile(`platform-java/services/${service}/Dockerfile`)
      expect(dockerfile, `${service} readiness client`).toMatch(/apk add --no-cache[^\n]*wget/)
    }

    const frontendHealthcheck = compose.services.frontend.healthcheck?.test ?? []
    expect(frontendHealthcheck.join(' ')).toContain('http://127.0.0.1/health')
    expect(compose.services.frontend.depends_on?.['edge-bff']).toEqual(expect.anything())
  })

  it('normalizes and returns request, trace, and correlation identifiers at the Edge boundary', () => {
    const filter = readRepositoryFile(
      'platform-java/services/edge-bff/src/main/java/com/grassland/edge/observability/RequestCorrelationFilter.java',
    )
    expect(filter).toContain('X-Request-Id')
    expect(filter).toContain('X-Trace-Id')
    expect(filter).toContain('X-Correlation-Id')
    expect(filter).toContain('normalizeRequestId')
    expect(filter).toContain('normalizeTraceId')
    expect(filter).toContain('exchange.getResponse().getHeaders().set')
    expect(filter).toContain('@Order(Ordered.HIGHEST_PRECEDENCE)')
  })

  it('makes production deploy and rollback wait for every application readiness probe', () => {
    const release = readRepositoryFile('scripts/production-release.sh')
    expect(release).toContain('HEALTH_SERVICES=(frontend edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)')
    expect(release).toContain(".State.Health.Status")
    expect(release).toContain('wait_for_compose_health')
    expect(release).toContain('wait_for_health "${PUBLIC_HEALTH_URL:?PUBLIC_HEALTH_URL is required}"')
  })

  it('keeps the Express backend out of CI E2E and production releases', () => {
    const runner = readRepositoryFile('scripts/ci-e2e.sh')
    const release = readRepositoryFile('scripts/production-release.sh')
    const releaseServices = release.match(/^SERVICES=\(([^)]*)\)$/m)?.[1]?.split(/\s+/) ?? []

    expect(runner).not.toContain('BACKEND_PORT')
    expect(releaseServices).not.toContain('backend')
    expect(releaseServices).toEqual(expect.arrayContaining([
      'frontend',
      'database-bootstrap',
      'edge-bff',
      'identity-service',
      'marketplace-service',
      'finance-service',
      'trust-service',
      'intelligence-service',
    ]))
    expect(readRepositoryFile('package.json')).not.toContain('"express"')
    expect(existsSync(resolve(REPOSITORY_ROOT, 'server'))).toBe(false)
    expect(existsSync(resolve(REPOSITORY_ROOT, 'Dockerfile.backend'))).toBe(false)
    expect(readRepositoryFile('package.json')).not.toMatch(/dev:server|build:server|start:server/)
    expect(readRepositoryFile('docker-compose.yml')).not.toMatch(/^\s*backend:/m)
    expect(readRepositoryFile('docker-compose.production.yml')).not.toMatch(/^\s*backend:/m)
  })

  it('keeps the retired generic internal API key out of the Java-only backend', () => {
    for (const path of [
      'docker-compose.yml',
      'docker-compose.production.yml',
      'scripts/ci-e2e.sh',
      'deploy/security/production-secret-contract.csv',
      'platform-java/services/finance-service/src/main/resources/application.yml',
      'platform-java/services/intelligence-service/src/main/resources/application.yml',
    ]) {
      const source = readRepositoryFile(path)
      expect(source, path).not.toContain('INTERNAL_API_KEY')
      expect(source, path).not.toContain('X-Internal-Key')
    }

    expect(existsSync(resolve(
      REPOSITORY_ROOT,
      'platform-java/services/finance-service/src/main/java/com/grassland/finance/credits/CreditsInternalAuthFilter.java',
    ))).toBe(false)
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

  it('provisions every Compose identity assertion secret in CI E2E', () => {
    const compose = readRepositoryFile('docker-compose.yml')
    const runner = readRepositoryFile('scripts/ci-e2e.sh')
    const expectedSecrets = new Set(
      [...compose.matchAll(/\$\{(IDENTITY_ASSERTION_KEY_[A-Z0-9_]+):-\}/g)]
        .map(([, name]) => name)
        .filter((name) => !name.endsWith('_PREVIOUS') && !name.endsWith('_PREVIOUS_KID')),
    )

    expect(expectedSecrets.size).toBeGreaterThan(0)
    for (const name of expectedSecrets) {
      expect(runner, name).toContain(name)
    }
  })

  it('documents Intelligence marketplace assertions and enables the AI quota benefit', () => {
    const compose = composeConfig()
    const localTemplate = readRepositoryFile('.env.example')
    const dockerTemplate = readRepositoryFile('.env.docker.example')

    for (const template of [localTemplate, dockerTemplate]) {
      expect(template).toContain(
        'IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_MARKETPLACE_KID=intelligence-service-marketplace-v1',
      )
      expect(template).toContain(
        'IDENTITY_ASSERTION_KEY_INTELLIGENCE_SERVICE_MARKETPLACE=replace-with-at-least-32-characters',
      )
      expect(template).toContain('AI_FREE_QUOTA_BASE_DAILY=2')
      expect(template).toContain('AI_FREE_QUOTA_ZONE_ID=Asia/Shanghai')
    }

    expect(compose.services['finance-service'].environment?.AI_FREE_QUOTA_BASE_DAILY).toBe('2')
  })

  it('keeps production settlement days safe and compresses them only in E2E', () => {
    const compose = composeConfig()
    const runner = readRepositoryFile('scripts/ci-e2e.sh')

    expect(compose.services['marketplace-service'].environment?.SETTLEMENT_DAY_SECONDS)
      .toBe('86400')
    expect(runner).toContain('export SETTLEMENT_DAY_SECONDS="${SETTLEMENT_DAY_SECONDS:-2}"')
  })

  it('seeds the CI database without overriding its host connection', () => {
    const runner = readRepositoryFile('scripts/ci-e2e.sh')

    expect(runner).toContain(
      'DATABASE_URL="$HOST_DATABASE_URL" npx tsx scripts/e2e-seed.ts',
    )
    expect(runner).not.toContain('DATABASE_URL="$HOST_DATABASE_URL" npm run e2e:seed\n')
  })

  it('seeds accepted applications with an immutable reputation entitlement snapshot', () => {
    const seed = readRepositoryFile('scripts/e2e-seed.ts')
    const acceptedApplicationInsert = seed.match(
      /INSERT INTO task_application\(([\s\S]*?)FROM new_tasks/,
    )?.[0]

    expect(acceptedApplicationInsert).toBeDefined()
    for (const column of [
      'reputation_level_at_accept',
      'reputation_policy_version_at_accept',
      'settlement_delay_days_at_accept',
      'commission_bonus_bps_at_accept',
      'premium_support_at_accept',
    ]) {
      expect(acceptedApplicationInsert, column).toContain(column)
    }
    expect(acceptedApplicationInsert).toContain("1, 1, 2, 0, false")
  })

  it('does not publish bypass ports beyond the local development host', () => {
    const compose = composeConfig()

    for (const service of [
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

  it('keeps environment assignments unique and documents the task #58 model config boundary', () => {
    const compose = readRepositoryFile('docker-compose.yml')
    // 任务书 #58：模型端点/凭据/受信端点一律经治理台控制面——env 只保留部署策略开关
    expect(compose).toContain('AI_PROVIDER_ALLOW_SANDBOX: ${AI_PROVIDER_ALLOW_SANDBOX:-true}')
    for (const banned of ['QWEN_BASE_URL', 'QWEN_API_KEY', 'AI_SPEECH_PROVIDER', 'AI_EMBEDDING_PROVIDER',
      'IMAGE_GENERATION_BASE_URL', 'AI_PLATFORM_MODEL_TRUSTED_OPENAI_COMPATIBLE_ORIGINS']) {
      expect(compose, banned).not.toContain(banned)
    }

    for (const template of ['.env.example', '.env.docker.example']) {
      const environment = readRepositoryFile(template)
      const names = environment
        .split(/\r?\n/)
        .filter((line) => /^[A-Z][A-Z0-9_]*=/.test(line))
        .map((line) => line.slice(0, line.indexOf('=')))

      expect(new Set(names).size, template).toBe(names.length)
      expect(environment, template).toContain('AI_PROVIDER_ALLOW_SANDBOX=true')
      expect(environment, template).toContain('治理台')
      for (const banned of ['QWEN_BASE_URL=', 'QWEN_API_KEY=', 'AI_SPEECH_PROVIDER=', 'AI_EMBEDDING_PROVIDER=']) {
        expect(environment, `${template} 不应再含 ${banned}`).not.toContain(banned)
      }
    }
  })

  it('documents the fail-closed Edge deployment contract', () => {
    const readme = readRepositoryFile('README.md')

    expect(readme).toContain('`API_UPSTREAM` 必须保持 `edge-bff:8080`')
    expect(readme).toContain('up -d --no-deps --force-recreate edge-bff')
    expect(readme).not.toContain('API_UPSTREAM=backend:3000')
  })
})
