import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 数字人部署拓扑契约（任务书 #105H C105H-01 / TC105H-01-01～04）。
 *
 * 覆盖自动可证部分：默认关闭、同源代理片段形状、TLS/TURN 模板边界、Redis 容量与资源限额。
 * 条件真实项不在本文件冒充：过期证书真实握手、真实 TURN 中继、浏览器实测 WS/SSE 分块
 * 属 H02 三浏览器 e2e / H03 实机门禁（K12：Fake 证明协议、真机证明设备行为，互不替代）。
 * compose config 只验证静态结构，不视为服务启动通过。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

interface ComposePort {
  host_ip?: string
  published?: string
  target?: number
}

interface ComposeService {
  command?: string[]
  deploy?: { resources?: { limits?: Record<string, string | number> } }
  environment?: Record<string, string>
  healthcheck?: { test?: string[] }
  image?: string
  networks?: Record<string, unknown> | string[]
  ports?: ComposePort[]
  profiles?: string[]
  tmpfs?: string[]
}

interface ComposeConfig {
  networks?: Record<string, { internal?: boolean }>
  services: Record<string, ComposeService>
}

function composeConfigWith(files: string[], extraEnv: Record<string, string> = {}): ComposeConfig {
  const output = execFileSync(
    'docker',
    [
      'compose',
      '--env-file',
      '.env.docker.example',
      ...files.flatMap((file) => ['-f', file]),
      'config',
      '--format',
      'json',
    ],
    {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
      env: {
        ...process.env,
        MINIO_ROOT_USER: 'test-minio-root',
        MINIO_ROOT_PASSWORD: 'test-minio-root-secret',
        MINIO_ACCESS_KEY: 'test-minio-app',
        MINIO_SECRET_KEY: 'test-minio-app-secret',
        ...extraEnv,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  )
  return JSON.parse(output) as ComposeConfig
}

const PRODUCTION_OVERLAY_FILES = ['docker-compose.yml', 'deploy/digital-human/compose.production.yml']
const TEST_OVERLAY_FILES = ['docker-compose.yml', 'deploy/digital-human/compose.test.yml']

/** 内部端口域（K13.2 端口契约 + Redis）：DH 拓扑服务不得发布到宿主。
 * （基线 compose 的业务 redis 洞见回环发布属既有开发栈口径，由 edge-entrypoint 契约约束，
 * 不在 DH 拓扑断言范围。） */
const DH_TOPOLOGY_SERVICES = ['dh-runtime', 'dh-redis', 'intelligence-service', 'frontend']
const INTERNAL_TARGET_PORTS = [9080, 9143, 9443, 6379]

function publishedTargets(config: ComposeConfig): Array<{ service: string; target: number | undefined }> {
  return Object.entries(config.services)
    .filter(([name]) => DH_TOPOLOGY_SERVICES.includes(name))
    .flatMap(([service, definition]) => (definition.ports ?? []).map((port) => ({ service, target: port.target })))
}

/** 提取 nginx 片段/配置中一个 location 块的完整文本（花括号计数；从 marker 结束后的第一个
 * `{` 起算——marker 内的正则自身可含量词花括号 `{8}`，不能计入块深度）。 */
function nginxLocationBlock(config: string, locationMarker: string): string {
  const start = config.indexOf(locationMarker)
  if (start < 0) {
    throw new Error(`Missing nginx location marker: ${locationMarker}`)
  }
  let depth = 0
  for (let index = config.indexOf('{', start + locationMarker.length); index < config.length; index += 1) {
    if (config[index] === '{') depth += 1
    if (config[index] === '}') depth -= 1
    if (depth === 0) return config.slice(start, index + 1)
  }
  throw new Error(`Unclosed nginx location: ${locationMarker}`)
}

const UUID_SESSION_PATH = String.raw`^/api/digital-human/sessions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`

describe('digital-human deployment topology (task #105H C105H-01)', () => {
  it('tc105h_01_01 无额外 env 的生产 overlay：功能关闭、内部端口不公开、无 secret 示例值', () => {
    const config = composeConfigWith(PRODUCTION_OVERLAY_FILES)

    // 逐开关参数化（K10 默认全关）：DH_ENABLED / DH_REAL_PROVIDERS_ENABLED / DH_INTERNAL_ENABLED
    // 显式 false，DH_AUDIO_UPSTREAM 解析为空（AI 入口 WS 路径 404，不升级）。
    expect(config.services['dh-runtime'].environment?.DH_ENABLED).toBe('false')
    expect(config.services['dh-runtime'].environment?.DH_REAL_PROVIDERS_ENABLED).toBe('false')
    expect(config.services['intelligence-service'].environment?.DH_INTERNAL_ENABLED).toBe('false')
    expect(config.services.frontend.environment?.DH_AUDIO_UPSTREAM ?? '').toBe('')

    // 内部 mTLS 端口（9143/9443）、runtime 音频面（9080）与 content Redis（6379）不发布到宿主。
    const targets = publishedTargets(config)
    expect(targets.length).toBeGreaterThan(0)
    for (const { service, target } of targets) {
      expect(INTERNAL_TARGET_PORTS, `${service} 不得发布内部端口`).not.toContain(target)
    }

    // TURN 藏在 profile 后：默认渲染不含 dh-turn（默认关闭的第五个开关）。
    expect(config.services).not.toHaveProperty('dh-turn')

    // 片段默认关闭语义：上游为空只拒本路径（404），不影响三入口其他路由。
    const fragment = read('deploy/digital-human/nginx.locations.conf')
    expect(fragment).toContain('if ($dh_audio_upstream = "")')
    expect(nginxLocationBlock(fragment, `location ~ "${UUID_SESSION_PATH}/audio$"`)).toContain('return 404;')

    // 环境样例无真实密钥：secret 一律文件路径/占位，无 PEM/长随机串。
    const envExample = read('deploy/digital-human/.env.example')
    expect(envExample).toContain('DH_AUDIO_UPSTREAM=')
    expect(envExample).toMatch(/DH_ALLOWED_AI_ORIGINS=https:\/\/ai\.example\.org/)
    for (const deployFile of [
      'deploy/digital-human/.env.example',
      'deploy/digital-human/compose.production.yml',
      'deploy/digital-human/turnserver.conf.example',
    ]) {
      const source = read(deployFile)
      expect(source, deployFile).not.toContain('BEGIN RSA PRIVATE KEY')
      expect(source, deployFile).not.toContain('BEGIN PRIVATE KEY')
      expect(source, deployFile).not.toContain('BEGIN CERTIFICATE')
      expect(source, deployFile).not.toMatch(/[A-Za-z0-9+/]{44}={0,2}\n/)
    }
  })

  it('tc105h_01_02 同源代理：精确 UUID WS 升级、SSE 不聚合、内部路径拒绝、三入口不回归', () => {
    const nginx = read('nginx.conf')
    const fragment = read('deploy/digital-human/nginx.locations.conf')

    // include 只装配在 AI server(82)：用户端 80 / 治理端 81 不出现 DH 入口（端间差异不扩散）。
    const serverBlockOf = (port: number): string => {
      const start = nginx.indexOf(`  listen ${port};`)
      expect(start, `server 块 listen ${port} 应存在`).toBeGreaterThanOrEqual(0)
      const next = nginx.indexOf('  listen ', start + 1)
      return nginx.slice(start, next > 0 ? next : undefined)
    }
    const aiBlock = serverBlockOf(82)
    expect(aiBlock).toContain('include /etc/nginx/dh-locations.conf;')
    // 片段不经 envsubst：上游用主模板 set 的运行期变量（runtime 未启动不拖垮启动解析）。
    expect(aiBlock).toContain('set $dh_edge_upstream "${API_UPSTREAM}";')
    expect(aiBlock).toContain('set $dh_audio_upstream "${DH_AUDIO_UPSTREAM}";')
    for (const [label, block] of [['80', serverBlockOf(80)], ['81', serverBlockOf(81)]] as const) {
      expect(block, `端口 ${label} 不装配 DH 片段`).not.toContain('dh-locations.conf')
      expect(block, `端口 ${label} 不注入 DH 上游变量`).not.toContain('$dh_audio_upstream')
    }

    // 镜像常驻分发：缺 ENV 兜底会让 envsubst 残留 ${DH_AUDIO_UPSTREAM} 字面量导致三入口宕机。
    const frontendImage = read('Dockerfile.frontend')
    expect(frontendImage).toContain('ENV DH_AUDIO_UPSTREAM=""')
    expect(frontendImage).toContain('COPY deploy/digital-human/nginx.locations.conf /etc/nginx/dh-locations.conf')
    // 基础 compose 不注入 DH_AUDIO_UPSTREAM（缺省栈关闭；只有 overlay 注入）。
    expect(read('docker-compose.yml')).not.toContain('DH_AUDIO_UPSTREAM')

    // 音频 WS：精确 UUID session 路径（引号包住 {量词}），Upgrade 头、可变上游 + resolver。
    const audioLocation = nginxLocationBlock(fragment, `location ~ "${UUID_SESSION_PATH}/audio$"`)
    expect(audioLocation).toContain(`"${UUID_SESSION_PATH}/audio$"`)
    expect(audioLocation).toContain('proxy_set_header Upgrade $http_upgrade;')
    expect(audioLocation).toContain('proxy_set_header Connection "upgrade";')
    expect(audioLocation).toContain('proxy_pass http://$dh_audio_upstream;')
    expect(audioLocation).toContain('resolver 127.0.0.11')

    // SSE：逐块穿透不聚合（参数化三项传输语义）+ 长读超时；上游仍是 Edge（控制面经 Edge）。
    const eventsLocation = nginxLocationBlock(fragment, `location ~ "${UUID_SESSION_PATH}/events$"`)
    expect(eventsLocation).toContain(`"${UUID_SESSION_PATH}/events$"`)
    for (const passthrough of ['proxy_buffering off;', 'proxy_cache off;', 'chunked_transfer_encoding on;']) {
      expect(eventsLocation, `SSE 事件流必须 ${passthrough}`).toContain(passthrough)
    }
    expect(eventsLocation).toContain('proxy_read_timeout 3600s;')
    expect(eventsLocation).toContain('proxy_pass http://$dh_edge_upstream;')

    // 内部通道路径拒绝（K07.2：mTLS 端点不经公网入口）。
    expect(fragment).toContain('location /internal/digital-human/')
    expect(nginxLocationBlock(fragment, 'location /internal/digital-human/')).toContain('return 404;')

    // 测试栈接线：真实 frontend(82) 注入音频上游（K13.3「启用后 WS 连接」的拓扑前提）；
    // runtime 健康检查对准实际服务的音频面端口。
    const testCompose = composeConfigWith(TEST_OVERLAY_FILES)
    expect(testCompose.services.frontend.environment?.DH_AUDIO_UPSTREAM).toBe('dh-runtime:9080')
    expect(testCompose.services['dh-runtime'].healthcheck?.test?.join(' ')).toContain('9080/health')
    const runtimeImage = read('platform-realtime/digital-human/Dockerfile')
    expect(runtimeImage).toContain('grassland_dh.app:create_audio')
    expect(runtimeImage).toContain('EXPOSE 9443 9080')

    // 原三入口不回归：既有断言在同文件 edge-entrypoint 契约（本测试不重复展开），此处锁
    // AI server 原有 location 集在 include 之后仍然完整。
    for (const kept of ['location = /app-config.js', 'location ^~ /assets/', 'location /api/', 'location /health']) {
      expect(aiBlock, `AI server 保留 ${kept}`).toContain(kept)
    }
  })

  it('tc105h_01_03 TLS/TURN 负例：短期 secret 文件化、有界 relay、内部 mTLS 材料不落仓库', () => {
    const turn = read('deploy/digital-human/turnserver.conf.example')

    // TURN 长期 secret 只经文件注入（use-auth-secret + static-auth-secret-file），
    // 不允许内联字面量（`static-auth-secret=` 一旦带值就是下发/入库事故）。
    expect(turn).toContain('use-auth-secret')
    expect(turn).toContain('static-auth-secret-file=/run/secrets/turn-secret.txt')
    expect(turn).not.toMatch(/^static-auth-secret=.+$/m)
    expect(turn).toMatch(/^min-port=49160$/m)
    expect(turn).toMatch(/^max-port=49200$/m)
    // 参数化安全基线：关 CLI/组播/回环对端、拒绝 RFC1918 目标、禁旧 TLS。
    for (const guard of ['no-cli', 'no-multicast-peers', 'no-loopback-peers', 'no-tlsv1', 'no-tlsv1_1']) {
      expect(turn, `TURN 必须启用 ${guard}`).toMatch(new RegExp(`^${guard}$`, 'm'))
    }
    for (const denied of ['10.0.0.0-10.255.255.255', '172.16.0.0-172.31.255.255', '192.168.0.0-192.168.255.255']) {
      expect(turn, `TURN 必须拒绝内网段 ${denied}`).toContain(`denied-peer-ip=${denied}`)
    }
    // TCP/TLS 回落监听在模板内（真实 fallback 行为属 H03 实机/网络门禁，不在静态测试冒充）。
    expect(turn).toMatch(/^tls-listening-port=5349$/m)

    // compose 的 TURN 端口发布与模板一致且有界（profile 渲染后核对）。
    const turnConfig = composeConfigWith(PRODUCTION_OVERLAY_FILES, { COMPOSE_PROFILES: 'dh-turn' })
    const turnPorts = turnConfig.services['dh-turn']?.ports ?? []
    expect(turnPorts.length).toBeGreaterThan(0)
    for (const port of turnPorts) {
      const target = port.target ?? 0
      const inListen = target === 3478
      const inRelayRange = target >= 49160 && target <= 49200
      expect(inListen || inRelayRange, `TURN 端口 ${target} 只允许监听口 3478 与有界 relay 段 49160-49200`).toBe(true)
    }
    // 未开 profile 时 dh-turn 不存在（默认关闭）。
    expect(composeConfigWith(PRODUCTION_OVERLAY_FILES).services).not.toHaveProperty('dh-turn')

    // mTLS：证书与 SAN 双向固定（K13.2）；仓库/部署文件只有路径引用，无私钥材料。
    const certScript = read('scripts/acceptance/task-105-test-certificates.sh')
    expect(certScript).toContain('digital-human-runtime')
    expect(certScript).toContain('intelligence-service')
    for (const deployFile of ['deploy/digital-human/compose.production.yml', 'deploy/digital-human/compose.test.yml']) {
      const source = read(deployFile)
      expect(source, deployFile).toContain('secrets:')
      expect(source, deployFile).not.toMatch(/(-{5}BEGIN [A-Z ]*KEY-{5})/)
    }
    const prodConfig = composeConfigWith(PRODUCTION_OVERLAY_FILES)
    expect(prodConfig.services['dh-runtime'].environment?.DH_INTERNAL_CLIENT_CERT_FILE).toBe('/run/secrets/dh-runtime.crt')
    expect(prodConfig.services['intelligence-service'].environment?.DH_INTERNAL_TLS_CERT_FILE).toBe('/run/secrets/dh-internal-intelligence.crt')
    // 内部 mTLS 端口不随 overlay 发布（与 tc105h_01_01 的全量断言互补，此处点名 dh-runtime/dh-redis）。
    expect(prodConfig.services['dh-runtime'].ports).toBeUndefined()
    expect(prodConfig.services['dh-redis'].ports).toBeUndefined()

    // 运行手册承载到期检测/轮换命令（运维实际口径）。
    const runbook = read('docs/运维/数字人工作台运行手册.md')
    expect(runbook).toContain('openssl x509 -checkend')
    expect(runbook).toContain('subjectAltName')
    expect(runbook).toContain('static-auth-secret-file')
  })

  it('tc105h_01_04 Redis 与限额：无持久化 + 拒写淘汰 + tmpfs；媒体网关限额与会话上限=1', () => {
    // 两套 overlay 参数化：Redis 容量语义一致（测试栈与生产不允许不同口径）。
    for (const [label, files] of [['test', TEST_OVERLAY_FILES], ['production', PRODUCTION_OVERLAY_FILES]] as const) {
      const config = composeConfigWith(files)
      const redisCommand = config.services['dh-redis'].command ?? []
      const joined = redisCommand.join(' ')
      expect(redisCommand[0], `${label} dh-redis 跑 redis-server`).toBe('redis-server')
      // 无持久化（K05 禁 AOF/RDB/备份）。
      expect(joined, `${label} 关 RDB 快照`).toContain('--save  ')
      expect(joined, `${label} 关 AOF`).toContain('--appendonly no')
      // maxmemory + 明确拒写淘汰：写满错误可观测（fail closed）。
      expect(joined, `${label} 设置 maxmemory`).toMatch(/--maxmemory \d+(mb|gb)/)
      expect(joined, `${label} 拒写淘汰 noeviction`).toContain('--maxmemory-policy noeviction')
      // 参数化负例：任何静默驱逐策略都等于悄悄丢协调状态（重启后不恢复未保存正文
      // 的可观测前提），一律禁止。
      for (const silentPolicy of ['allkeys-lru', 'allkeys-lfu', 'allkeys-random', 'volatile-lru', 'volatile-rlu', 'volatile-ttl']) {
        expect(joined, `${label} 禁止静默淘汰 ${silentPolicy}`).not.toContain(silentPolicy)
      }
      // tmpfs 数据目录：不落盘、容器销毁即无残留；且无持久化卷。
      expect(config.services['dh-redis'].tmpfs, `${label} dh-redis 数据目录 tmpfs`).toContain('/data')
      expect(JSON.stringify(config.services['dh-redis']), `${label} dh-redis 无持久化卷`).not.toMatch(/"volumes":\[/)
      // 网络隔离：dh 服务只在 dh-internal（internal 网络无出站网关；config 渲染 networks 为 map）。
      expect(config.networks?.['dh-internal']?.internal, `${label} dh-internal 为 internal 网络`).toBe(true)
      const redisNetworks = config.services['dh-redis'].networks
      expect(Object.keys(redisNetworks ?? {}), `${label} dh-redis 只在 dh-internal`).toEqual(['dh-internal'])
      // 资源限额：CPU/内存上限齐备（K10 拓扑：初期 1 实例媒体网关，限额先行；
      // compose config 把 memory 归一为字节数，按数值断言）。
      const limits = config.services['dh-runtime'].deploy?.resources?.limits
      expect(Number(limits?.cpus), `${label} dh-runtime CPU 上限`).toBeGreaterThan(0)
      expect(Number(limits?.memory), `${label} dh-runtime 内存上限`).toBeGreaterThan(0)
      const redisLimits = config.services['dh-redis'].deploy?.resources?.limits
      expect(Number(redisLimits?.cpus), `${label} dh-redis CPU 上限`).toBeGreaterThan(0)
      expect(Number(redisLimits?.memory), `${label} dh-redis 内存上限`).toBeGreaterThan(0)
    }

    // 生产会话全局上限默认 1（K10：提高并发前须实测并更新 approved capacity）。
    const prodConfig = composeConfigWith(PRODUCTION_OVERLAY_FILES)
    expect(prodConfig.services['dh-runtime'].environment?.DH_MAX_SESSIONS_GLOBAL).toBe('1')

    // 行为边界归属（不在静态测试冒充）：「重启不恢复未保存正文/容量满错误可观测/多一并发
    // 拒绝」的运行时证明由 Python runtime 测试（dh_capacity_full 4429 等）与 H02 三浏览器
    // e2e 承担；本测试锁死其拓扑前提（noeviction 拒写 + tmpfs 不落盘 + max=1）。
  })
})
