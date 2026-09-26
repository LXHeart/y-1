import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Hypit 部署拓扑契约（任务书 #107-3 C107-23 / TC107-23-01～04 的自动可证部分）。
 *
 * 覆盖：三层 compose 叠加静态结构（profile 门禁 / 隔离键 / env 表默认值）、
 * 备份恢复脚本契约、runtime 隔离测试的存在性。compose config 只验证静态结构，
 * 不视为服务启动通过；真实启动/备份恢复/恶意组件实跑由 verify-107-full.sh 在
 * HYPIT_FULL_E2E=1 下执行（V14），未开启时如实 NOT_RUN。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

interface ComposeService {
  build?: { dockerfile: string }
  cap_drop?: string[]
  environment?: Record<string, string>
  image?: string
  network_mode?: string
  profiles?: string[]
  read_only?: boolean
  security_opt?: string[]
  user?: string
  volumes?: Array<Record<string, unknown> | string>
}

interface ComposeConfig {
  services: Record<string, ComposeService>
  volumes?: Record<string, unknown>
}

/** 生产 overlay 叠加后的完整 config（dummy 密钥仅满足 :? 插值，不做真实启动）。 */
function composeConfigWith(files: string[], extraEnv: Record<string, string> = {},
  extraArgs: string[] = []): ComposeConfig {
  // 自动满足叠加栈的全部 :? 必填插值（dummy 值；config 不做真实连接）。
  const required = new Set<string>()
  for (const file of files) {
    const text = readFileSync(resolve(REPOSITORY_ROOT, file), 'utf8')
    for (const match of text.matchAll(/\$\{([A-Z0-9_]+):\?/g)) required.add(match[1])
  }
  const dummies = Object.fromEntries([...required].map((name) => [name, name.endsWith('_FILE') ? '/tmp/dummy' : 'dummy']))
  const output = execFileSync(
    'docker',
    [
      'compose',
      '--env-file',
      '.env.docker.example',
      ...files.flatMap((file) => ['-f', file]),
      ...extraArgs,
      'config',
      '--format',
      'json',
    ],
    {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
      env: { ...process.env, ...dummies, CRYPTO_KEK_BASE64: 'x'.repeat(44), ...extraEnv },
    },
  )
  return JSON.parse(output) as ComposeConfig
}

const PROD_STACK = ['docker-compose.yml', 'docker-compose.production.yml', 'deploy/hypit/compose.production.yml']
const FULL_STACK = [...PROD_STACK, 'deploy/hypit/compose.full.yml']

describe('hypit compose 生产栈（TC107-23-01 基础/完整 config）', () => {
  it('基础生产栈不出现 hypit 容器（默认全关，叠加才生效）', () => {
    const base = composeConfigWith(['docker-compose.yml', 'docker-compose.production.yml'])
    expect(base.services['hypit-backend']).toBeUndefined()
    expect(base.services['hypit-author-runner']).toBeUndefined()
  })

  it('叠加后 hypit 服务挂 profiles 门禁；config 不带 --profile 时服务被剔除（=默认 up 不创建）', () => {
    const config = composeConfigWith(PROD_STACK)
    expect(config.services['hypit-backend']).toBeUndefined()
    expect(config.services['hypit-author-runner']).toBeUndefined()
    // 带 --profile 才进入有效配置（token 由调用方提供）。
    const enabled = composeConfigWith(PROD_STACK, { HYPIT_INTERNAL_TOKEN: 'dummy-token-0123456789abcdef' },
      ['--profile', 'hypit'])
    expect(enabled.services['hypit-backend']?.profiles).toContain('hypit')
    expect(enabled.services['hypit-author-runner']?.profiles).toContain('hypit')
    // 业务闸默认 false（fail-closed）。
    expect(config.services['intelligence-service']?.environment?.HYPIT_ENABLED).toBe('false')
    // 容器内部固定地址（实现端口 9240；§13.3 登记与任务书 8090 的漂移）。
    expect(config.services['intelligence-service']?.environment?.HYPIT_SIDECAR_BASE_URL).toBe(
      'http://hypit-backend:9240')
  })

  it('hypit profile 启用时 HYPIT_INTERNAL_TOKEN 缺失即硬失败（:? 语义）', () => {
    let exit: number | null = 0
    try {
      execFileSync('docker', [
        'compose', '--env-file', '.env.docker.example',
        ...PROD_STACK.flatMap((file) => ['-f', file]), '--profile', 'hypit', 'config', '-q',
      ], { cwd: REPOSITORY_ROOT, encoding: 'utf8', env: { ...process.env, HYPIT_INTERNAL_TOKEN: '' } })
    } catch (error) {
      exit = (error as { status: number }).status ?? 1
    }
    expect(exit).not.toBe(0)
  })

  it('环境参数固定表：默认值与任务书 §5.2 逐字一致（端口 9240 为实现登记值）', () => {
    const config = composeConfigWith(PROD_STACK, { HYPIT_INTERNAL_TOKEN: 'dummy-token-0123456789abcdef' },
      ['--profile', 'hypit'])
    const backend = config.services['hypit-backend']?.environment ?? {}
    expect(backend.HYPIT_STUDIO_MAX_SESSIONS).toBe('2')
    expect(backend.HYPIT_STUDIO_IDLE_SECONDS).toBe('1800')
    expect(backend.HYPIT_STUDIO_TTL_SECONDS).toBe('28800')
    expect(backend.HYPIT_DATA_ROOT).toBe('/data/hypit')
    expect(backend.HYPIT_HOST_STATE_ROOT).toBe('/data/hypit/host')
    expect(backend.HYPIT_BACKEND_PORT).toBe('9240')
    const intelligence = config.services['intelligence-service']?.environment ?? {}
    expect(intelligence.HYPIT_MAX_UPLOAD_BYTES).toBe('524288000')
    expect(intelligence.HYPIT_MAX_PROJECT_ARCHIVE_BYTES).toBe('1073741824')
    expect(intelligence.HYPIT_MAX_PROJECT_EXPANDED_BYTES).toBe('4294967296')
    expect(intelligence.HYPIT_PREVIEW_TTL_SECONDS).toBe('1800')
  })
})

describe('hypit runner 隔离（TC107-23-02 K10.4 静态面）', () => {
  it('runner：无网络/非 root/只读根/全弃权/no-new-privileges/tmpfs 配额', () => {
    const config = composeConfigWith(FULL_STACK, { HYPIT_INTERNAL_TOKEN: 'dummy-token-0123456789abcdef' },
      ['--profile', 'hypit'])
    const runner = config.services['hypit-author-runner']
    expect(runner?.network_mode).toBe('none')
    expect(runner?.user).toBe('10001:10001')
    expect(runner?.read_only).toBe(true)
    expect(runner?.cap_drop).toContain('ALL')
    expect(runner?.security_opt).toContain('no-new-privileges:true')
    // 唯一共享面：socket 目录 + 槽卷 + runner 状态；不含 HYPIT_INTERNAL_TOKEN。
    expect(runner?.environment?.HYPIT_INTERNAL_TOKEN).toBeUndefined()
    const volumes = JSON.stringify(runner?.volumes ?? [])
    expect(volumes).toContain('runner-sockets')
    // 逃逸阻断实跑（恶意测试组件）在 verify-107-full.sh 的隔离用例（HYPIT_FULL_E2E）。
    expect(existsSync(resolve(REPOSITORY_ROOT, 'scripts/acceptance/verify-107-full.sh'))).toBe(true)
  })

  it('B 侧 runner 隔离测试在场：恶意组件（读宿主/token env/连 internal/写 Distribution）拒绝', () => {
    // 静态守护：恶意面用例必须存在且断言拒绝路径（C107-06 落地，node:test 风格，实跑归 V04）。
    const isolation = read('platform-hypit/backend/tests/engine/runner-isolation.test.ts')
    expect(isolation).toContain('test(')
    expect(isolation).toContain('malicious author package cannot read host files or broker secrets')
  })
})

describe('hypit full 变体与备份恢复（TC107-23-03/04 静态面）', () => {
  it('full overlay：程序环境/浏览器缓存/模型/Result 卷（大资产不进镜像层）', () => {
    const config = composeConfigWith(FULL_STACK, { HYPIT_INTERNAL_TOKEN: 'dummy-token-0123456789abcdef' },
      ['--profile', 'hypit'])
    const volumes = JSON.stringify(config.services['hypit-backend']?.volumes ?? [])
    for (const needle of ['hypit-programs', 'hypit-browsers', 'hypit-models', 'hypit-results']) {
      expect(volumes).toContain(needle)
    }
  })

  it('backup.sh：维护模式→PG 快照→数据根打包→manifest→恢复副作用（finally 兜底）', () => {
    const script = read('deploy/hypit/backup.sh')
    expect(script).toContain('maintenance/enter')
    expect(script).toContain('pg_dump')
    expect(script).toContain('manifest.json')
    expect(script).toContain('trap restore_maintenance EXIT')
    // 网络不可达时拒绝备份（不做无排空保障的热拷贝）。
    expect(script).toContain('拒绝在无排空保障下备份')
  })

  it('restore.sh：只落空目标 + sha256 校验 + 恢复后核对 + 零新 generation', () => {
    const script = read('deploy/hypit/restore.sh')
    expect(script).toContain('非空')
    expect(script).toContain('dataSha256')
    expect(script).toContain('restore-report.json')
    expect(script).toContain('newGenerationsTriggered": 0')
  })
})
