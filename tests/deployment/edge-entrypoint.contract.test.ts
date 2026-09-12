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

    // 两套互斥头：report-only 为 map 默认值，enforce 显式切换；策略内容除一处刻意
    // 差异外一致——report-only 不带 frame-ancestors（该指令在 report-only 下被规范
    // 忽略且 WebKit 会为它打 error 级控制台消息，打碎 #92 AC-601 零控制台错误门；
    // 框护由恒发的 X-Frame-Options DENY 与 enforce 头承担）。
    const enforcedMap = nginxMapBlock(nginx, '$csp_policy_enforced')
    const reportOnlyMap = nginxMapBlock(nginx, '$csp_policy_report_only')
    expect(enforcedMap).toMatch(/default\s+"";/)
    expect(enforcedMap).toMatch(/"enforce"\s+"default-src 'self';/)
    expect(reportOnlyMap).toMatch(/default\s+"default-src 'self';/)
    expect(reportOnlyMap).toMatch(/"enforce"\s+"";/)
    // 锁定上述刻意差异：enforce 带 frame-ancestors，report-only 不带。
    expect(enforcedMap).toContain("frame-ancestors 'none'")
    expect(reportOnlyMap).not.toContain('frame-ancestors')
    expect(nginx).toContain('add_header Content-Security-Policy $csp_policy_enforced always;')
    expect(nginx).toContain('add_header Content-Security-Policy-Report-Only $csp_policy_report_only always;')
    for (const hardening of ["object-src 'none'", "base-uri 'self'", "form-action 'self'", 'report-uri /csp-report']) {
      expect(nginx).toContain(hardening)
    }
    // script-src 不允许 unsafe-inline；style-src 的 unsafe-inline 是记录在案的折衷。
    expect(nginx).toContain("script-src 'self' 'sha256-")
    expect(nginx).not.toContain("script-src 'self' 'unsafe-inline'")

    // index.html / ops.html / ai.html（治理台与 AI 创作中心入口）的内联防 FOUC 脚本必须与 nginx
    // hash 严格同步（改一处不改另一处会在 enforce 下被打断）；三个入口共用同一段脚本即同一个 hash。
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

    // 任务书 #76 卡 B：ai.html 第三入口（AI 创作中心独立应用）——内联脚本逐字节一致复用同一
    // hash，入口脚本指向 AI 应用；CSP 与镜像转发断言见下方 82 端口 server 校验。
    const aiHtml = readRepositoryFile('ai.html')
    const aiInline = aiHtml.match(/<script>(.*?)<\/script>/s)
    expect(aiInline).not.toBeNull()
    const aiHash = createHash('sha256').update(aiInline![1], 'utf8').digest('base64')
    expect(nginx).toContain(`'sha256-${aiHash}'`)
    expect(aiInline![1]).toBe(inline![1])
    expect(aiHtml).toContain('src="/src/ai/main.ts"')
    expect(aiHtml).toContain('data-app="ai"')

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

  it('serves the AI creation app as a fully mirrored third origin (task #76)', () => {
    const nginx = readRepositoryFile('nginx.conf')

    // 第三 server 块完整镜像：入口回退 ai.html、assets 404 自救、app-config 三块同构。
    expect(nginx).toContain('listen 82;')
    expect(nginx).toContain('index ai.html;')
    expect(nginx).toContain('try_files $uri $uri/ /ai.html;')
    const aiAssets = nginxLocation(nginx, '^~ /assets/')
    expect(aiAssets).toContain('try_files $uri =404;')
    const matched = nginx.match(/location = \/app-config\.js \{[\s\S]*?\n {2}\}/g) ?? []
    expect(matched.length).toBe(3)
    for (const block of matched) {
      expect(block).toContain('default_type application/javascript;')
      expect(block).toContain('${AI_APP_ORIGIN}')
      expect(block).toContain('${GRASSLAND_ORIGIN}')
    }
    expect(nginx).toContain('/app-config.js "no-store";')
    expect(nginx).toMatch(/\/ai\.html\s+"no-cache";/)

    // compose：第三端口映射 + 跨应用 origin 运行时注入。
    const compose = readRepositoryFile('docker-compose.yml')
    expect(compose).toContain('- "${AI_FRONTEND_PORT:-8084}:82"')
    expect(compose).toContain('AI_APP_ORIGIN: ${AI_APP_ORIGIN:-http://127.0.0.1:8084}')
    expect(compose).toContain('GRASSLAND_ORIGIN: ${GRASSLAND_ORIGIN:-http://127.0.0.1:8080}')
    expect(readRepositoryFile('Dockerfile.frontend')).toContain('EXPOSE 80 81 82')
    expect(readRepositoryFile('vite.config.ts')).toContain("ai: resolve(__dirname, 'ai.html')")
  })

  it('registers the video canvas professional mode on both creation entrypoints (task #100)', () => {
    // 任务书 #100 C100-08：画布专业模式是双创作入口共享视图——草场（index.html）与
    // AI 创作中心（ai.html）各自的路由表都注册 video-canvas 且指向同一组件（不复制视图）；
    // 治理台（ops.html）不加载画布业务。深链契约 ?storyboard={id}[&draft={id}] 由
    // useVideoCanvasUrlState 归一（URL 只是定位器，权限在服务端）。
    const grasslandRouter = readRepositoryFile('src/router/index.ts')
    const canvasRoute = /path:\s*'video-canvas',\s*\n\s*name:\s*'video-canvas',\s*\n\s*component:\s*\(\)\s*=>\s*import\('\.\.\/views\/video-canvas\/VideoCanvasView\.vue'\)/
    expect(grasslandRouter).toMatch(canvasRoute)

    const aiRouter = readRepositoryFile('src/ai/router.ts')
    expect(aiRouter).toMatch(/path:\s*'video-canvas',\s*\n\s*name:\s*'video-canvas',\s*\n\s*component:\s*\(\)\s*=>\s*import\('\.\.\/views\/video-canvas\/VideoCanvasView\.vue'\)/)

    // 双入口同构：无任一入口私有化的画布副本；治理台不引用画布视图。
    expect(readRepositoryFile('src/ops/main.ts')).not.toContain('video-canvas')
    const opsSources = ['src/ops/router.ts', 'src/ops/main.ts']
    for (const source of opsSources) {
      if (existsSync(resolve(REPOSITORY_ROOT, source))) {
        expect(readRepositoryFile(source), source).not.toMatch(/VideoCanvasView/)
      }
    }

    // 快速模式（video-production）与画布（video-canvas）在草场路由并存——共享分镜数据互切。
    expect(grasslandRouter).toMatch(/path:\s*'video-production'/)
    expect(aiRouter).toMatch(/path:\s*'video-production'/)
  })

  it('serves the independent canvas document on the creation-drafts route without a new gateway switch (task #100 C100-09)', () => {
    // 任务书 #100 API-08/09：GET/PUT /api/creation-drafts/{id}/canvas 复用 creation-drafts
    // 前缀与 EDGE_ROUTE_CREATION_DRAFTS_INTELLIGENCE——不新增网关开关；上游恒为 intelligence。
    const edgeRoutes = readRepositoryFile('platform-java/services/edge-bff/src/main/resources/application.yml')
    const creationDraftsRoute = /- path: \/api\/creation-drafts\n\s+upstream: intelligence\n\s+enabled: \$\{EDGE_ROUTE_CREATION_DRAFTS_INTELLIGENCE:true\}/
    expect(edgeRoutes).toMatch(creationDraftsRoute)
    expect(edgeRoutes).not.toContain('creation-drafts-canvas')

    const controller = readRepositoryFile('platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationcanvas/CreationCanvasController.java')
    expect(controller).toContain('@GetMapping("/api/creation-drafts/{id}/canvas")')
    expect(controller).toContain('@PutMapping("/api/creation-drafts/{id}/canvas")')

    // 三入口不回归：治理台仍不加载画布；前端消费走共享类型契约。
    const types = readRepositoryFile('src/types/video-canvas.ts')
    expect(types).toContain('export interface CanvasDocument {')
  })

  it('aggregates every canvas route onto existing gateway prefixes with no new switches (task #100 C100-19)', () => {
    // 任务书 #100 集成收口（§6.6）：C100-09～18 新增画布端点全部骑既有前缀与开关——
    // AI 计划（API-13/14/15）复用 creation-assistant；画布文档（API-08/09）复用
    // creation-drafts；每镜来源（API-10）与独立方案（API-11/12）骑 video-production
    // storyboards 前缀组。上游恒为 intelligence，本任务不引入新网关路由或开关。
    const edgeRoutes = readRepositoryFile('platform-java/services/edge-bff/src/main/resources/application.yml')
    expect(edgeRoutes).toMatch(/- path: \/api\/creation-assistant\n\s+upstream: intelligence\n\s+enabled: \$\{EDGE_ROUTE_CREATION_ASSISTANT_INTELLIGENCE:true\}/)
    expect(edgeRoutes).toMatch(/- path: \/api\/creation-drafts\n\s+upstream: intelligence\n\s+enabled: \$\{EDGE_ROUTE_CREATION_DRAFTS_INTELLIGENCE:true\}/)
    for (const method of ['GET', 'PATCH', 'POST']) {
      expect(edgeRoutes).toMatch(new RegExp(
        `- method: ${method}\n\\s+path: /api/video-production/storyboards\n\\s+upstream: intelligence\n\\s+enabled: \\$\\{EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE:true\\}`))
    }
    // 新路由黑名单：画布线没有自己的网关开关或路由段
    for (const banned of ['canvas-plans', 'canvas-documents', 'canvas-variants', 'canvas-sources', 'EDGE_ROUTE_CANVAS']) {
      expect(edgeRoutes, banned).not.toContain(banned)
    }

    // 控制器 wire 对齐：三组端点真实存在于既有前缀下
    const plans = readRepositoryFile('platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationcanvas/CanvasAgentPlanController.java')
    expect(plans).toContain('@PostMapping("/api/creation-assistant/canvas/plans")')
    expect(plans).toContain('@GetMapping("/api/creation-assistant/canvas/plans/{id}")')
    expect(plans).toContain('@PostMapping("/api/creation-assistant/canvas/plans/{id}/apply")')
    const variants = readRepositoryFile('platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/videoproduction/VideoStoryboardVariantController.java')
    expect(variants).toContain('@PostMapping("/api/video-production/storyboards/{id}/variants")')
    expect(variants).toContain('@GetMapping("/api/video-production/storyboards/{id}/variants")')
    const sources = readRepositoryFile('platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/videoproduction/VideoShotSourceController.java')
    expect(sources).toContain('@PatchMapping("/api/video-production/storyboards/{id}/sources")')

    // 三入口不回归：双创作入口路由表仍指向同一画布视图（非复制组件），治理台零画布引用
    for (const routerPath of ['src/router/index.ts', 'src/ai/router.ts']) {
      expect(readRepositoryFile(routerPath), routerPath)
        .toMatch(/component:\s*\(\)\s*=>\s*import\('\.\.\/views\/video-canvas\/VideoCanvasView\.vue'\)/)
    }
    const opsRouter = 'src/ops/router.ts'
    if (existsSync(resolve(REPOSITORY_ROOT, opsRouter))) {
      expect(readRepositoryFile(opsRouter)).not.toMatch(/VideoCanvasView|video-canvas/)
    }
    expect(readRepositoryFile('src/ops/main.ts')).not.toContain('video-canvas')
  })

  it('fails canvas routes closed when their carrier flags are off (task #100 C100-20)', () => {
    // 任务书 #100 兼容边界（§6.4/§7.4）：画布端点没有独立开关——分镜编辑/绑定/方案/来源
    // 骑 EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE，画布文档骑 CREATION_DRAFTS，AI 计划骑
    // CREATION_ASSISTANT。三旗默认 true；关旗 = 整族 fail-closed（回退边界：关视频脚本
    // 开关同时切断画布，不允许画布单独存活或单独回退）。
    const edgeRoutes = readRepositoryFile('platform-java/services/edge-bff/src/main/resources/application.yml')
    for (const flag of ['EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE', 'EDGE_ROUTE_CREATION_DRAFTS_INTELLIGENCE',
      'EDGE_ROUTE_CREATION_ASSISTANT_INTELLIGENCE']) {
      expect(edgeRoutes, flag).toContain(`enabled: \${${flag}:true}`)
    }

    // flag=false 演练已登记在 EdgeFailClosedIT：三旗关 → 画布各族端点 404（方法与子路径
    // 都收口）。读取该 IT 的属性与用例体，防止后续改路由时悄悄丢掉这层回退保护。
    const failClosed = readRepositoryFile(
      'platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/EdgeFailClosedIT.java')
    for (const flag of ['EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE=false', 'EDGE_ROUTE_CREATION_DRAFTS_INTELLIGENCE=false',
      'EDGE_ROUTE_CREATION_ASSISTANT_INTELLIGENCE=false']) {
      expect(failClosed, flag).toContain(`"${flag}`)
    }
    expect(failClosed).toContain('/api/video-production/storyboards/storyboard-1')
    expect(failClosed).toContain('/api/video-production/storyboards/storyboard-1/sources')
    expect(failClosed).toContain('/api/creation-drafts/draft-1/canvas')
    expect(failClosed).toContain('/api/creation-assistant/canvas/plans')

    // 方法收口登记在 JavaRouteManifestGateTest：画布代表路由按方法解析，未声明方法
    // （storyboards DELETE / shots PATCH）fail-closed。
    const manifestGate = readRepositoryFile(
      'platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/JavaRouteManifestGateTest.java')
    for (const entry of ['"/api/video-production/storyboards/storyboard-1/variants"',
      '"/api/creation-drafts/draft-1/canvas"', '"/api/creation-assistant/canvas/plans"']) {
      expect(manifestGate, entry).toContain(entry)
    }
    expect(manifestGate).toContain('Arguments.of("DELETE", "/api/video-production/storyboards/storyboard-1/variants")')
    expect(manifestGate).toContain('Arguments.of("PATCH", "/api/video-production/shots/shot-1/content")')
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
      'quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z',
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
      'IMAGE_GENERATION_BASE_URL', 'AI_PLATFORM_MODEL_TRUSTED_OPENAI_COMPATIBLE_ORIGINS',
      // 任务书 #59：这些 *_PROVIDER/*_MODEL 已无读取方，但值写着 qwen——留着会诱导运维在治理台
      // 换成协议方言名后来 compose「同步」，改完毫无效果却以为切换完成。
      'BILIBILI_ANALYSIS_PROVIDER', 'DOUYIN_ANALYSIS_PROVIDER', 'KYB_DOCUMENT_ANALYSIS_PROVIDER',
      'KYB_DOCUMENT_ANALYSIS_MODEL', 'IMAGE_GENERATION_PROVIDER',
      // #59 收尾：Express 时代 per-user 视频分析设置一族 + 无对应字段的图片平台版本号，Java 侧零绑定类
      'IMAGE_GENERATION_PLATFORM_MODEL_VERSION', 'ANALYSIS_SETTINGS_ALLOW_REMOTE_WRITE',
      'COZE_ANALYSIS_BASE_URL', 'QWEN_ANALYSIS_BASE_URL', 'VIDEO_ANALYSIS_API_BASE_URL',
      'VIDEO_ANALYSIS_API_TOKEN', 'VIDEO_ANALYSIS_API_TIMEOUT_MS']) {
      expect(compose, banned).not.toContain(banned)
    }
    // 两个部署开关取代原 ai.verification.provider / ai.store-media-moderation.provider 哨兵
    expect(compose).toContain('AI_VERIFICATION_ENABLED: ${AI_VERIFICATION_ENABLED:-true}')
    expect(compose).toContain('AI_STORE_MEDIA_MODERATION_ENABLED: ${AI_STORE_MEDIA_MODERATION_ENABLED:-true}')
    // 计价线必须真的透传：模板声明 + application.yml 占位符 + compose 透传，三处齐了才生效（#59 收尾）
    expect(compose).toContain('IMAGE_GENERATION_PRICING_VERSION: ${IMAGE_GENERATION_PRICING_VERSION:-image-config-v1}')
    expect(compose).toContain('IMAGE_GENERATION_UNIT_PRICE_CENTS: ${IMAGE_GENERATION_UNIT_PRICE_CENTS:-80}')

    for (const template of ['.env.example', '.env.docker.example']) {
      const environment = readRepositoryFile(template)
      const names = environment
        .split(/\r?\n/)
        .filter((line) => /^[A-Z][A-Z0-9_]*=/.test(line))
        .map((line) => line.slice(0, line.indexOf('=')))

      expect(new Set(names).size, template).toBe(names.length)
      expect(environment, template).toContain('AI_PROVIDER_ALLOW_SANDBOX=true')
      expect(environment, template).toContain('治理台')
      for (const banned of ['QWEN_BASE_URL=', 'QWEN_API_KEY=', 'AI_SPEECH_PROVIDER=', 'AI_EMBEDDING_PROVIDER=',
        'IMAGE_GENERATION_PROVIDER=', 'KYB_DOCUMENT_ANALYSIS_PROVIDER=', 'KYB_DOCUMENT_ANALYSIS_MODEL=',
        // #59 收尾：两份模板里这一族全是死配置（含历史泄漏过的 VIDEO_ANALYSIS_API_TOKEN）
        'IMAGE_GENERATION_PLATFORM_MODEL_VERSION=', 'ANALYSIS_SETTINGS_ALLOW_REMOTE_WRITE=',
        'COZE_ANALYSIS_BASE_URL=', 'COZE_ANALYSIS_API_TOKEN=', 'QWEN_ANALYSIS_BASE_URL=',
        'QWEN_ANALYSIS_API_KEY=', 'QWEN_ANALYSIS_MODEL=', 'VIDEO_ANALYSIS_API_BASE_URL=',
        'VIDEO_ANALYSIS_API_PATH=', 'VIDEO_ANALYSIS_API_TOKEN=', 'VIDEO_ANALYSIS_API_TIMEOUT_MS=']) {
        expect(environment, `${template} 不应再含 ${banned}`).not.toContain(banned)
      }
      expect(environment, template).toContain('AI_VERIFICATION_ENABLED=true')
      expect(environment, template).toContain('AI_STORE_MEDIA_MODERATION_ENABLED=true')
      // 计价线两项保留且仍被声明（compose 透传 + yml 占位符已补齐）
      expect(environment, template).toContain('IMAGE_GENERATION_PRICING_VERSION=image-config-v1')
      expect(environment, template).toContain('IMAGE_GENERATION_UNIT_PRICE_CENTS=80')
    }
  })

  it('documents the fail-closed Edge deployment contract', () => {
    const readme = readRepositoryFile('README.md')

    expect(readme).toContain('`API_UPSTREAM` 必须保持 `edge-bff:8080`')
    expect(readme).toContain('up -d --no-deps --force-recreate edge-bff')
    expect(readme).not.toContain('API_UPSTREAM=backend:3000')
  })
})
