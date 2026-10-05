import { execFileSync, spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * C107F2-02 契约测试（W019）：统一 Compose 组合与启停路由（F01）。
 *
 * - TC-F2-02-04 配置预检：显式启用但密钥缺省/31 字符 → 非零+明确缺项+零容器变更。
 * - TC-F2-02-01 启用组合：隔离栈（DH+Hypit）真实启动，登录态 projects=200、
 *   三入口存活、共享容器 labels 含 Hypit overlay。
 * - TC-F2-02-03 防覆盖：已启用工程被漏 overlay 组合重建 → 预检非零、容器未被覆盖。
 * - TC-F2-02-02 默认禁用：未启用组合 → capabilities 表示 disabled、业务路由 404、
 *   不误启动 broker。
 *
 * 需要本机 Docker（隔离工程 y1-hypit-fix2-e2e，专用端口/卷/网络；不触碰 y-1 主栈）。
 * 生产模式只做 plan/precheck 可审核输出，绝不操作正在运行的栈。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const WRAPPER = 'scripts/acceptance/hypit-compose.sh'
const PROJECT = process.env.HYPIT_C02_PROJECT || 'y1-hypit-fix2-e2e'
// 本卡的破坏性重置场景只在 fresh 守卫会话内执行：守卫入口（V-07 --fresh）向进程树
// 注入 LOCAL_STACK_TOKEN；裸 vitest（test:coverage 等）无守卫会话 → 四组 describe
// 整体跳过（双路径语义，收集期判定）。此前无守卫直接探锁，会在与其他契约测试的
// 守卫会话并发时踩「锁记录不可读」竞态（2026-10-01 test:coverage 实录）。
// A token also exists for ordinary guarded unit runs. Only a verified fresh session may start this stack.
const inGuardedFreshSession = Boolean(process.env.LOCAL_STACK_TOKEN)
  && spawnSync(process.execPath, ['scripts/local-stack.mjs', 'session', '--project', PROJECT, '--fresh'],
    { cwd: REPOSITORY_ROOT, stdio: 'pipe' }).status === 0
const describeInFreshSession = describe.skipIf(!inGuardedFreshSession)
beforeAll(() => {
  if (!inGuardedFreshSession) return
  execFileSync(process.execPath, ['scripts/local-stack.mjs', 'session', '--project', PROJECT, '--fresh'], { cwd: REPOSITORY_ROOT, stdio: 'pipe' })
})
const BASE = 'http://127.0.0.1:18080'
const OPS_BASE = 'http://127.0.0.1:18081'
const AI_BASE = 'http://127.0.0.1:18082'
const EVIDENCE = 'test-artifacts/task-107/fix2/C02'
// 唯一隔离栈冷启动，JVM 与真实依赖逐个构建/就绪；预算 40 分钟。
const LONG_TIMEOUT = 40 * 60 * 1000

/** 异步执行 wrapper：长 up/down 不能用 spawnSync——同步阻塞 worker 事件循环会
 * 打掉 vitest 的 RPC 心跳（onTaskUpdate 超时→未处理错误→全绿也 exit 1）。 */
async function wrap(args: string[], env: Record<string, string> = {}, timeoutMs = 60_000) {
  const { spawn } = await import('node:child_process')
  return new Promise<{ status: number | null; stdout: string; stderr: string }>((resolve, reject) => {
    const scopedArgs = args.includes('--test') ? [...args, '--project-name', PROJECT] : args
    const child = spawn('bash', [WRAPPER, ...scopedArgs], {
      cwd: REPOSITORY_ROOT,
      env: { ...process.env, ...env },
    })
    let stdout = ''
    let stderr = ''
    let settled = false
    const timer = setTimeout(() => {
      child.kill('SIGTERM')
    }, timeoutMs)
    child.stdout.on('data', (d: Buffer) => { stdout += d })
    child.stderr.on('data', (d: Buffer) => { stderr += d })
    child.on('error', (err) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(err)
    })
    child.on('close', (code) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ status: code, stdout, stderr })
    })
  })
}

function docker(args: string[], timeoutMs = 60_000) {
  return execFileSync('docker', args, { cwd: REPOSITORY_ROOT, encoding: 'utf8', timeout: timeoutMs })
}

function projectContainerNames(): string[] {
  const out = docker([
    'ps', '-a', '--filter', `label=com.docker.compose.project=${PROJECT}`, '--format', '{{.Names}}',
  ])
  return out.split('\n').map((s) => s.trim()).filter(Boolean)
}

function containerLabelFiles(name: string): string {
  return docker(['inspect', '--format',
    '{{index .Config.Labels "com.docker.compose.project.config_files"}}', name]).trim()
}

// seed 口令与 wrapper 同源：隔离栈一次性口令持久在 test-artifacts 的 env 文件
// （跨调用稳定；e2e-seed 的 upsert 对已存在账号不覆盖口令，故必须用同一口令）。
// 显式 E2E_PASSWORD 优先；env 文件缺失时回落共享默认口令。
function stackSeedPassword(): string {
  if (process.env.E2E_PASSWORD) return process.env.E2E_PASSWORD
  try {
    const envFile = readFileSync(resolve(REPOSITORY_ROOT, process.env.TEST_ENV_FILE || 'test-artifacts/task-107/fix2/isolated-stack.env'), 'utf8')
    const m = /^E2E_PASSWORD=(.*)$/m.exec(envFile)
    if (m && m[1]) return m[1]
  } catch { /* env 文件不存在（未起过栈）→ 默认口令 */ }
  return 'test-password-2026'
}
const SEED_PASSWORD = stackSeedPassword()

async function login(): Promise<string> {
  // down/up 换栈瞬间，undici 连接池里指向旧 edge 容器的 keep-alive 套接字会被重置
  // （TypeError: fetch failed / other side closed）——网络层失败重试一次，不动断言语义。
  let res: Response
  try {
    res = await fetchLogin()
  } catch (first) {
    await new Promise((r) => setTimeout(r, 1500))
    try {
      res = await fetchLogin()
    } catch (second) {
      const failure = new Error(`登录请求两次网络失败：${String(second)}（首轮 ${String(first)}）`)
      ;(failure as { cause?: unknown }).cause = second
      throw failure
    }
  }
  expect(res.status, `登录失败 ${res.status}: ${await res.text()}`).toBe(200)
  const cookie = res.headers.get('set-cookie') ?? ''
  // 会话 cookie 名为 y1.sid（SESSION_COOKIE_NAME），匹配它或通用 session/token 命名。
  expect(cookie).toMatch(/y1\.sid|session|token/i)
  return cookie.split(';')[0]
}

async function fetchLogin(): Promise<Response> {
  return fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'e2e-merchant@test.local', password: SEED_PASSWORD }),
  })
}

if (inGuardedFreshSession) mkdirSync(resolve(REPOSITORY_ROOT, EVIDENCE), { recursive: true })
function saveLog(name: string, content: string) {
  writeFileSync(resolve(REPOSITORY_ROOT, EVIDENCE, name), content)
}

// ── TC-F2-02-04：密钥预检（无 Docker 副作用；先跑，栈尚未启动） ───────────
describeInFreshSession('TC-F2-02-04 显式启用但密钥缺省/31 字符 → 明确缺项且零容器变更', () => {
  const guardProject = 'fix2-c02-precheck-guard'
  it('缺省：非零、逐项点名密钥、不产生任何容器变更', async () => {
    const r = await wrap(['up', '--enable-hypit', '--project-name', guardProject], {
      // 只清空本组目标条件（两个密钥），其余环境保留。
      HYPIT_INTERNAL_TOKEN: '', HYPIT_STUDIO_TICKET_SECRET: '',
    })
    expect(r.status, `stdout:${r.stdout}\nstderr:${r.stderr}`).toBe(1)
    expect(r.stdout + r.stderr).toContain('HYPIT_INTERNAL_TOKEN')
    expect(r.stdout + r.stderr).toContain('HYPIT_STUDIO_TICKET_SECRET')
    expect(r.stdout + r.stderr).toContain('未执行任何 docker 变更')
    const guards = docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${guardProject}`]).trim()
    expect(guards, '预检失败不得创建任何容器').toBe('')
  })

  it('31 字符：点名长度不足，仍零容器变更', async () => {
    const short = 'a'.repeat(31)
    const r = await wrap(['up', '--enable-hypit', '--project-name', guardProject], {
      HYPIT_INTERNAL_TOKEN: short, HYPIT_STUDIO_TICKET_SECRET: short,
    })
    expect(r.status).toBe(1)
    expect(r.stdout + r.stderr).toContain('长度 31 < 32')
    expect(r.stdout + r.stderr).not.toMatch(/密钥值|[a-f0-9]{32,}/) // 日志不含秘密
    const guards = docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${guardProject}`]).trim()
    expect(guards).toBe('')
  })

  it('plan 输出固定组合次序与 fail-closed 语义（可审核、不执行）', async () => {
    const r = await wrap(['plan'])
    expect(r.status).toBe(0)
    expect(r.stdout).toContain('docker-compose.yml')
    expect(r.stdout).toContain('deploy/digital-human/compose.production.yml')
    expect(r.stdout).toContain('deploy/hypit/compose.production.yml')
    expect(r.stdout).toContain('hypit=disabled-fail-closed')
    // 值不输出（密钥安全）：仅当调用方环境真实持有该密钥时才断言
    // （未设置时 not.toContain('') 恒假）。
    const secret = process.env.HYPIT_STUDIO_TICKET_SECRET
    if (secret && secret.length >= 8) {
      expect(r.stdout).not.toContain(secret)
    }
  })
})

// RedisAssertionReplayGuard 需要共享 Redis；它未在 Compose depends_on 中声明，
// 启用/禁用两阶段都显式选入，保留真实登录和内部断言链。
// ── TC-F2-02-01：真实启用组合（隔离栈） ─────────────────────────────────
describeInFreshSession('TC-F2-02-01 隔离栈启用 DH/Hypit → 三入口存活、projects=200、labels 含 overlay', () => {
  it('真实启动隔离栈（wrapper --test --enable-hypit --enable-dh up）', { timeout: LONG_TIMEOUT }, async () => {
    const r = await wrap(['--test', '--enable-hypit', '--enable-dh', 'up', 'frontend', 'redis', 'hypit-backend', 'hypit-author-runner', 'dh-runtime'], {}, LONG_TIMEOUT - 30_000)
    saveLog('up-enabled.log', `exit=${r.status}\n${r.stdout}\n${r.stderr}`)
    expect(r.status, `up 失败：${r.stdout.slice(-2000)}\n${r.stderr.slice(-2000)}`).toBe(0)
  })

  it('注入合成账号并登录', { timeout: 120_000 }, async () => {
    const seed = await wrap(['--test', 'seed-accounts'], { E2E_PASSWORD: SEED_PASSWORD }, 120_000)
    saveLog('seed.log', `exit=${seed.status}\n${seed.stdout}\n${seed.stderr}`)
    expect(seed.status, `seed 失败：${seed.stdout}\n${seed.stderr}`).toBe(0)
    const cookie = await login()
    expect(cookie.length).toBeGreaterThan(0)
    saveLog('login.txt', `login ok (cookie 长度 ${cookie.length}，值不落证据)`)
  })

  it('登录态 GET /api/hypit/projects = 200（启用组合业务路由真实接通）', { timeout: 30_000 }, async () => {
    const cookie = await login()
    const res = await fetch(`${BASE}/api/hypit/projects`, { headers: { cookie } })
    const body = await res.text()
    saveLog('projects-enabled.json', `status=${res.status}\n${body}`)
    expect(res.status, body).toBe(200)
    expect(JSON.parse(body).success).toBe(true)
  })

  it('三入口存活（用户端/治理台/AI 创作端）', { timeout: 30_000 }, async () => {
    for (const [label, url] of [['用户端', BASE], ['治理台', OPS_BASE], ['AI 创作端', AI_BASE]] as const) {
      const res = await fetch(url)
      expect(res.status, `${label} ${url} 未存活`).toBe(200)
    }
  })

  it('共享容器 labels 含 Hypit overlay（F01 修复证据）', { timeout: 60_000 }, () => {
    const names = projectContainerNames()
    expect(names.length, '隔离栈应有容器在运行').toBeGreaterThan(0)
    saveLog('containers.txt', names.join('\n'))
    for (const shared of names.filter((n) => /frontend|edge-bff|intelligence-service/.test(n))) {
      const files = containerLabelFiles(shared)
      expect(files, `${shared} labels 缺 Hypit overlay`).toContain('deploy/hypit/compose.test.yml')
    }
    // broker 真实启动（启用组合）。
    expect(names.some((n) => n.includes('hypit-backend')), '启用组合应启动 hypit-backend').toBe(true)
  })
})

// ── TC-F2-02-03：防共享配置覆盖（依赖 TC-01 的在运行栈） ─────────────────
describeInFreshSession('TC-F2-02-03 已启用工程被漏 Hypit 组合重建 → 预检非零、配置未被覆盖', () => {
  it('漏 overlay 组合的 up 被预检拒绝', { timeout: 120_000 }, async () => {
    const before = projectContainerNames().map((n) => ({ name: n, files: containerLabelFiles(n) }))
    expect(before.some((c) => c.files.includes('deploy/hypit/compose.test.yml'))).toBe(true)
    const r = await wrap(['--test', 'up'], {}, 90_000) // 无 --enable-hypit/--enable-dh：组合缺两个 overlay
    expect(r.status, `stdout:${r.stdout}\nstderr:${r.stderr}`).toBe(1)
    expect(r.stdout + r.stderr).toContain('静默移除')
    // 运行配置未被覆盖：容器集合与 labels 原样。
    const after = projectContainerNames().map((n) => ({ name: n, files: containerLabelFiles(n) }))
    expect(after).toEqual(before)
  })

  it('显式 --disable-hypit 允许停用：precheck 放行并显示受影响模块', { timeout: 120_000 }, async () => {
    const r = await wrap(['--test', 'precheck', '--disable-hypit', '--disable-dh'])
    expect(r.status, `stdout:${r.stdout}\nstderr:${r.stderr}`).toBe(0)
    expect(r.stdout + r.stderr).not.toContain('PRECHECK-FAIL')
    expect(r.stdout + r.stderr).toContain('显式停用')
    expect(r.stdout + r.stderr).toContain('受影响')
    // precheck 不改变容器（只读检查）。
    const names = projectContainerNames()
    expect(names.some((n) => n.includes('hypit-backend'))).toBe(true)
  })
})

// ── TC-F2-02-02：默认禁用（fail-closed） ────────────────────────────────
describeInFreshSession('TC-F2-02-02 未启用组合 → capabilities 表示 disabled、业务 404、不误启动 broker', () => {
  it('拆除后以纯 base 组合重启（无 enable 旗标）', { timeout: LONG_TIMEOUT }, async () => {
    const down = await wrap(['--test', 'reset'], {}, 600_000)
    saveLog('down.log', `exit=${down.status}\n${down.stdout}\n${down.stderr}`)
    expect(projectContainerNames(), 'down 后应无容器').toEqual([])
    const up = await wrap(['--test', 'up', 'frontend', 'redis'], {}, LONG_TIMEOUT - 60_000)
    saveLog('up-disabled.log', `exit=${up.status}\n${up.stdout}\n${up.stderr}`)
    expect(up.status, `up 失败：${up.stdout.slice(-2000)}\n${up.stderr.slice(-2000)}`).toBe(0)
  })

  it('capabilities 正确表示 disabled（登录可读）；projects 业务路由 404', { timeout: 120_000 }, async () => {
    const seed = await wrap(['--test', 'seed-accounts'], { E2E_PASSWORD: SEED_PASSWORD }, 120_000)
    expect(seed.status).toBe(0)
    const cookie = await login()
    const cap = await fetch(`${BASE}/api/hypit/capabilities`, { headers: { cookie } })
    const capBody = await cap.text()
    saveLog('capabilities-disabled.json', `status=${cap.status}\n${capBody}`)
    expect(cap.status).toBe(200)
    const data = JSON.parse(capBody).data
    expect(data.enabled).toBe(false)
    const projects = await fetch(`${BASE}/api/hypit/projects`, { headers: { cookie } })
    saveLog('projects-disabled.txt', `status=${projects.status}`)
    expect(projects.status, 'Edge 旗标默认 false → 业务路由 404（fail-closed）').toBe(404)
  })

  it('未误启动 broker：隔离工程无 hypit 容器', () => {
    const names = projectContainerNames()
    saveLog('containers-disabled.txt', names.join('\n'))
    // 匹配服务段而非工程名前缀（工程名 y1-hypit-fix2-e2e 本身含 "hypit"）。
    expect(
      names.some((n) => n.includes('hypit-backend') || n.includes('hypit-author-runner')),
      '未启用组合不得创建 hypit 容器',
    ).toBe(false)
  })
})

// 外层 verify-107-fix-2 --card C107F2-02 持有 fresh 会话并在结束时清理。
afterAll(() => {
  if (!inGuardedFreshSession) return
  saveLog('CLEANUP.txt', `隔离栈由外层守卫在验收结束后清理，证据目录 ${EVIDENCE}`)
})
