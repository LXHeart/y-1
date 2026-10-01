// hypit-fix2-recovery.spec.ts — 107-fix-2 C107F2-38：执行并发、故障注入、重启与完整恢复演练。
//
// 前置（V-07 卡级编排负责）：隔离栈 y1-hypit-fix2-e2e 已 up + 合成账号已 seed +
// 受控文本模型 fixture sidecar（W003 对本 spec 与 journey 同样注入 overlay）。
// 真实链路承诺：浏览器/入口/Nginx/Edge/Java/broker/runner/PG/文件全部真实；
// 仅外部商业模型最后一跳是 fixture（seedFix2TextModel，治理台路由/计费不变）。
//
// 编排纪律（卡步骤 4 + 本机资源约束）：
//   - docker 操作只作用于 y1-hypit-fix2-e2e / y1-hypit-fix2-restore 两个项目的
//     明确命名容器；动词白名单 = kill/start/stop/restart/exec/network
//     disconnect|connect/volume create|rm/run（run 仅限同镜像第二 worker 与
//     backup/restore 工具容器，结束即释放）。Compose 级启停一律经
//     scripts/acceptance/hypit-compose.sh 守卫入口，不裸跑 compose up/down。
//   - TC-F2-38-03 做栈轮换：备份→停主栈→恢复栈（新项目名/新卷/新 PG）→浏览器
//     核验→清恢复栈→重启主栈复核「原栈不受影响」。任意时刻只有一套应用栈在跑。
//   - 禁止日志/截图输出秘密：令牌/密码只经参数与内存传递，不写证据文件。
//
// TC-F2-38-01 双 worker 执行中 kill → 租约被接管、同 operation 不重复收费、单终态。
// TC-F2-38-02 fixture 接受后断 broker 网络 → 恢复重启后按原 receipt 收敛、不新提交。
// TC-F2-38-04 两客户端同 head/hash 并发保存与反馈 → CAS/批次原子，不混合丢写。
// TC-F2-38-03 排空备份 → 新 PG/卷恢复 → 浏览器重开核验（hash 一致、零新
//              generation、原栈不受影响）。【栈轮换重卡，置于文件末尾】
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { OWNER_A, seedFix2TextModel } from './fixtures/hypit-fix2'

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:18080'
// AI 创作壳是独立 origin（frontend:82 → 守卫隔离档 18082，与 c36/entrypoints 同源），
// 不是 BASE 的用户端 18080——round-13 实录：默认回落 BASE 登录能成（同一套登录
// 组件）但停在用户端首页，AI 壳的 nav-video-clone 永不出现。
const AI_BASE = process.env.AI_BASE_URL ?? 'http://127.0.0.1:18082'
const PASSWORD = process.env.E2E_PASSWORD ?? 'test-password-2026'
const EVIDENCE = resolve('test-artifacts/task-107/fix2/C38')
const REQUEST_TIMEOUT_MS = 60_000
const LEASE_TAKEOVER_BUDGET_MS = 8 * 60_000

const PROJECT = 'y1-hypit-fix2-e2e'
const RESTORE_PROJECT = 'y1-hypit-fix2-restore'
const MAIN_INTEL = `${PROJECT}-intelligence-service-1`
const MAIN_BROKER = `${PROJECT}-hypit-backend-1`
const MAIN_PG = `${PROJECT}-postgres-local-1`
const MAIN_FIXTURE = `${PROJECT}-hypit-fix2-text-provider-1`
const NETWORK = `${PROJECT}_default`
const WORKER_B = `${PROJECT}-intelligence-worker-b`
const WORKER_B_FIXTURE = `${PROJECT}-intelligence-worker-b-fixture`
const UP_SERVICES = ['frontend', 'hypit-backend', 'hypit-author-runner', 'redis']

mkdirSync(EVIDENCE, { recursive: true })
for (const tc of ['TC-F2-38-01', 'TC-F2-38-02', 'TC-F2-38-03', 'TC-F2-38-04']) {
  mkdirSync(resolve(EVIDENCE, tc), { recursive: true })
}

// ── 编排原语（固定参数组，无 shell 拼接；秘密不落证据） ──────────────────

function run(cmd: string, args: string[], opts: { ok?: boolean; timeout?: number; env?: NodeJS.ProcessEnv } = {}): string {
  try {
    return execFileSync(cmd, args, {
      encoding: 'utf8', timeout: opts.timeout ?? 120_000, stdio: ['ignore', 'pipe', 'pipe'],
      ...(opts.env ? { env: opts.env } : {}),
    }).trim()
  } catch (error) {
    if (opts.ok) return ''
    // round-3 实录：backup.sh 的 die() 走 stdout，只看 stderr 会把真实死因
    // 整个吞掉——两侧尾部都带出来。cause 经运行时赋值（tsconfig lib=ES2020
    // 尚无 Error 构造器 options 类型）。
    const e = error as { stderr?: string; stdout?: string; status?: number }
    const wrapped = new Error(`${cmd} ${args.join(' ')} 退出 ${e.status}:`
      + ` stderr=${(e.stderr ?? '').slice(-300)} stdout=${(e.stdout ?? '').slice(-400)}`)
    ;(wrapped as { cause?: unknown }).cause = error
    throw wrapped
  }
}

/** 探测专用：失败返回 null（不与 run 的 ok 语义混用）。 */
function probe(cmd: string, args: string[]): string | null {
  try {
    return execFileSync(cmd, args, { encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  } catch {
    return null
  }
}

let pgCreds: { user: string; db: string } | null = null
function psql(sql: string): string {
  if (pgCreds === null) {
    pgCreds = {
      user: run('docker', ['exec', MAIN_PG, 'printenv', 'POSTGRES_USER']),
      db: run('docker', ['exec', MAIN_PG, 'printenv', 'POSTGRES_DB']),
    }
  }
  return run('docker', ['exec', MAIN_PG, 'psql', '-U', pgCreds.user, '-d', pgCreds.db, '-tAc', sql])
}

function psqlInt(sql: string): number {
  return Number(psql(sql))
}

function brokerSql(sql: string): string {
  // round-5 勘误：docker exec 不会把宿主进程环境带进容器——SQL 必须用 -e 旗标
  // 显式注入（env 传递静默失效 → prepare(undefined) ERR_INVALID_ARG_TYPE）。
  return run('docker', ['exec', '-e', `SQL=${sql}`, MAIN_BROKER, 'node', '-e',
    "const {DatabaseSync}=require('node:sqlite');"
    + "const db=new DatabaseSync('/data/hypit/bridge.sqlite');"
    + "const r=db.prepare(process.env.SQL).all();process.stdout.write(JSON.stringify(r))",
  ], { timeout: 30_000 })
}

type FixtureCall = { family: string; at: string }

function fixtureCalls(): FixtureCall[] {
  const raw = probe('docker', ['exec', MAIN_FIXTURE, 'node', '-e',
    "fetch('http://127.0.0.1:19099/__calls').then(r=>r.text()).then(t=>process.stdout.write(t)).catch(()=>process.exit(1))",
  ])
  if (raw === null) throw new Error('fixture /__calls 不可达（sidecar 未随栈启动？检查 W003 fixture 注入）')
  return JSON.parse(raw) as FixtureCall[]
}

function dockerRunConfig(name: string): { image: string; env: string[]; entrypoint: string[] | null; cmd: string[] | null } {
  const image = run('docker', ['inspect', name, '--format', '{{.Config.Image}}'])
  const inspectJson = run('docker', ['inspect', name, '--format',
    '{"env":{{json .Config.Env}},"entrypoint":{{json .Config.Entrypoint}},"cmd":{{json .Config.Cmd}}}'])
  const parsed = JSON.parse(inspectJson) as { env: string[]; entrypoint: string[] | null; cmd: string[] | null }
  return { image, ...parsed }
}

function waitUntil(label: string, timeoutMs: number, probeFn: () => string | null | Promise<string | null>,
  intervalMs = 500): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    const deadline = Date.now() + timeoutMs
    const tick = async () => {
      let value: string | null = null
      try { value = await probeFn() } catch { /* 轮询异常按未就绪处理 */ }
      if (value !== null && value !== '') { resolvePromise(value); return }
      if (Date.now() > deadline) { reject(new Error(`${label} 未在 ${timeoutMs}ms 内就绪`)); return }
      setTimeout(tick, intervalMs)
    }
    void tick()
  })
}

function waitHealthy(name: string, timeoutMs = 240_000): Promise<void> {
  return waitUntil(`容器 ${name} 健康`, timeoutMs, () => {
    const status = probe('docker', ['inspect', name, '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'])
    return status === 'healthy' ? status : null
  }, 1000).then(() => undefined, async (error) => {
    // 无 HEALTHCHECK 的容器回退到启动日志判据（Spring Started 行）。
    const logs = probe('docker', ['logs', name])
    if (logs !== null && /Started .*Application|Tomcat started|Netty started/.test(logs)) return
    throw error
  })
}

/**
 * broker（Node/tsx，无 compose healthcheck）的就绪判据：容器内 loopback healthz。
 * waitHealthy 的日志兜底是 Spring 启动行，对 broker 恒不匹配（420s 假等）。
 */
function brokerReady(container: string): boolean {
  return probe('docker', ['exec', container, 'node', '-e',
    "fetch('http://127.0.0.1:9240/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))",
  ]) !== null
}

async function waitBrokerReady(container: string, timeoutMs = 420_000): Promise<void> {
  await waitUntil(`broker ${container} healthz 就绪`, timeoutMs,
    () => (brokerReady(container) ? 'ok' : null), 2000)
}

/**
 * C38（round-2 实录）：文本 fixture 以 network_mode: service:intelligence-service
 * 共享主 worker 网络命名空间——主 worker 被 kill/start 或 restart 后拿到新 netns，
 * fixture 被滞留在旧 netns（127.0.0.1:19099 不可达 → planner「AI provider 调用
 * 失败」）。主 worker 每次重启后必须连带重启 fixture 让它重新挂进当前 netns。
 */
function fixtureHealth(): string | null {
  return probe('docker', ['exec', MAIN_FIXTURE, 'node', '-e',
    "fetch('http://127.0.0.1:19099/__health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))",
  ]) === null ? null : 'ok'
}

async function restartMainWithFixture(): Promise<void> {
  run('docker', ['restart', MAIN_FIXTURE], { ok: true })
  await waitHealthy(MAIN_INTEL)
  // netns 属主被 compose 重建（恢复演练重建主 intelligence——容器换新 ID）后，
  // 旧 fixture 的 network_mode 引用悬空：docker restart 报 joining network
  // namespace ... No such container 且容器停留 Exited，永远起不来（V-10
  // 2026-10-01 实录连续两轮 TC-01/02 由此连锁）。按孪生 fixture 同款 rm+重造，
  // 挂回当前属主 netns；token 与 stage 注入的宿主 env 同源。
  if (fixtureHealth() === null) {
    const intelId = probe('docker', ['inspect', MAIN_INTEL, '--format', '{{.Id}}'])
    const fixtureToken = process.env.HYPIT_FIX2_PROVIDER_TOKEN
    if (intelId === null) throw new Error(`${MAIN_INTEL} 不可查，主 fixture 无法重挂 netns`)
    if (fixtureToken === undefined || fixtureToken === '') {
      throw new Error('HYPIT_FIX2_PROVIDER_TOKEN 未注入宿主 env，无法重造主 fixture')
    }
    run('docker', ['rm', '-f', MAIN_FIXTURE], { ok: true })
    run('docker', ['run', '-d', '--init', '--read-only', '--name', MAIN_FIXTURE,
      '--network', `container:${intelId}`,
      '-e', 'HYPIT_FIX2_PROVIDER_PORT=19099',
      '-e', `HYPIT_FIX2_PROVIDER_TOKEN=${fixtureToken}`,
      '-v', `${resolve('tests/e2e/fixtures/hypit-fix2-text-provider.mjs')}:/fixture/hypit-fix2-text-provider.mjs:ro`,
      'node:20-bookworm', 'node', '/fixture/hypit-fix2-text-provider.mjs'])
  }
  await waitUntil('主 fixture 重新挂回 netns', 60_000, () => fixtureHealth(), 1000)
}

/**
 * worker-b 克隆有自己的 netns，主 fixture（主 worker loopback）对它不可达；
 * 在 worker-b 的 netns 里起一个同源 fixture 孪生（同 token 同端口），让克隆内
 * 的 LLM 路由（localhost:19099）照常命中受控 fixture。worker-b 每次启动后调用。
 */
function startWorkerBFixture(): void {
  run('docker', ['rm', '-f', WORKER_B_FIXTURE], { ok: true })
  const fixtureToken = run('docker', ['exec', MAIN_FIXTURE, 'printenv', 'HYPIT_FIX2_PROVIDER_TOKEN'])
  run('docker', [
    'run', '-d', '--name', WORKER_B_FIXTURE, '--network', `container:${WORKER_B}`,
    '-e', 'HYPIT_FIX2_PROVIDER_PORT=19099',
    '-e', `HYPIT_FIX2_PROVIDER_TOKEN=${fixtureToken}`,
    '-v', `${resolve('tests/e2e/fixtures/hypit-fix2-text-provider.mjs')}:/fixture/hypit-fix2-text-provider.mjs:ro`,
    'node:20-bookworm', 'node', '/fixture/hypit-fix2-text-provider.mjs',
  ])
}

// ── API 原语（与 hypit-fix2-api-render.spec.ts 同形：真实 HTTP 面签名） ──

async function loginCookie(request: APIRequestContext, email = OWNER_A): Promise<string> {
  const res = await request.post(`${BASE}/api/auth/login`,
    { data: { email, password: PASSWORD }, timeout: REQUEST_TIMEOUT_MS })
  expect(res.status(), `登录失败 ${email}`).toBe(200)
  return (res.headers()['set-cookie'] ?? '').split(';')[0]
}

async function api(request: APIRequestContext, cookie: string, method: string,
  path: string, data?: unknown): Promise<{ status: number; body: any }> {
  const res = await request.fetch(`${BASE}${path}`, {
    method, headers: { cookie }, timeout: REQUEST_TIMEOUT_MS,
    ...(data === undefined ? {} : { data }),
  })
  let body: any
  try { body = await res.json() } catch { body = await res.text().catch(() => null) }
  return { status: res.status(), body }
}

async function createCloneProject(request: APIRequestContext, cookie: string, title: string): Promise<string> {
  const created = await api(request, cookie, 'POST', '/api/hypit/projects',
    { requestId: crypto.randomUUID(), title, mode: 'clone' })
  expect(created.status, `建工程: ${JSON.stringify(created.body)}`).toBe(202)
  const projectId = created.body?.data?.project?.id
  const jobId = created.body?.data?.job?.jobId
  const terminal = await pollJob(request, cookie, jobId, 'provision')
  expect(terminal.body?.data?.state ?? terminal.body?.data?.job?.state).toBe('succeeded')
  return projectId as string
}

async function pollJob(request: APIRequestContext, cookie: string, jobId: string | undefined,
  what: string, budgetMs = 12 * 60_000): Promise<{ status: number; body: any }> {
  expect(jobId, `${what} job 应存在`).toBeTruthy()
  const deadline = Date.now() + budgetMs
  let last: { status: number; body: any } = { status: 0, body: null }
  while (Date.now() < deadline) {
    last = await api(request, cookie, 'GET', `/api/hypit/jobs/${jobId}`)
    const state = last.body?.data?.state ?? last.body?.data?.job?.state
    if (state === 'succeeded' || state === 'failed' || state === 'cancelled') return last
    await new Promise((r) => setTimeout(r, 3000))
  }
  throw new Error(`${what} job ${jobId} 未在 ${budgetMs}ms 内到终态：${JSON.stringify(last.body)}`)
}

function jobState(jobId: string): string {
  return psql(`select state from hypit_job where id='${jobId}'`)
}

/**
 * 计费幂等的真实机制层（round-3 勘误）：本地 agent 链不落 hypit_execution——
 * 该表是外部执行桥（远程 Need 计费），全栈恒 0 行，经它 join 的结算断言是
 * 空洞前提。真实的防重复计费在 ai_run.operation_id（HypitAgentStepService 按
 * jobId|slot|inputHash 确定性派生）与结算 consume_operation_id：接管/断网重放
 * 复用同一 operation，绝不产生重复行。
 */
function duplicateRunOperations(): number {
  return psqlInt(`select count(*) from (select operation_id from ai_run`
    + ` where operation_id is not null group by operation_id having count(*) > 1) d`)
}

function duplicateSettlementOperations(): number {
  return psqlInt(`select count(*) from (select consume_operation_id from ai_credit_usage_settlement`
    + ` where consume_operation_id is not null group by consume_operation_id having count(*) > 1) d`)
}

/** fixture 控制面（/__hold、/__release）：端口仅 netns 内可达，经 docker exec 直呼。 */
function fixtureControl(endpoint: string, body: Record<string, unknown>, ok = false): void {
  run('docker', ['exec', MAIN_FIXTURE, 'node', '-e',
    `fetch('http://127.0.0.1:19099${endpoint}',{method:'POST',headers:{'content-type':'application/json'},`
    + `body:${JSON.stringify(JSON.stringify(body))}})`
    + `.then(r=>r.ok?process.exit(0):process.exit(1)).catch(()=>process.exit(1))`,
  ], ok ? { ok: true } : {})
}

function writeEvidence(tc: string, name: string, content: string): void {
  writeFileSync(resolve(EVIDENCE, tc, name), content)
}

/** 浏览器登录（与 journey fixture 同形：真实 UI 登录链）。 */
async function loginViaBrowser(page: Page, email: string): Promise<void> {
  await page.goto(`${AI_BASE}/`)
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(PASSWORD)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

test.describe('C107F2-38 执行并发、故障注入、重启与完整恢复', () => {
  // 上一轮的恢复演练会重建主 intelligence（netns 属主换 ID）——主 fixture 若被
  // 悬空遗留为 Exited，本轮所有依赖受控文本模型的用例开局即死。每例开局自愈。
  test.beforeEach(async () => {
    if (fixtureHealth() === null) await restartMainWithFixture()
  })

  // ═════════════════════════════════════════════════════════════════
  // TC-F2-38-01：双 worker 执行中 kill → 租约接管
  // ═════════════════════════════════════════════════════════════════

  test('TC-F2-38-01 双worker执行中kill：接管后同operation单次收费、合法owner收敛终态', async ({ request }) => {
    test.setTimeout(20 * 60_000)
    await seedFix2TextModel(request)

    // ① 第二 worker：同镜像同网络临时容器（显式复制健康探测；不挂 service
    //    别名，不参与 intelligence-service 的 DNS 轮询，端口不发布）。
    //    ENTRYPOINT 携带全部 JVM 参数（java --enable-native-access… -jar app.jar），
    //    --entrypoint 只能替换单个可执行文件，其余参数必须并入 cmd。
    run('docker', ['rm', '-f', WORKER_B], { ok: true })
    const config = dockerRunConfig(MAIN_INTEL)
    const env = config.env.filter((item) => !item.startsWith('HOSTNAME='))
    const cloneCmd = [...(config.entrypoint ? config.entrypoint.slice(1) : []), ...(config.cmd ?? [])]
    run('docker', [
      'run', '-d', '--name', WORKER_B, '--network', NETWORK,
      '--health-cmd', 'wget -q -O /dev/null http://127.0.0.1:9082/actuator/health/readiness',
      '--health-interval', '10s', '--health-timeout', '5s',
      '--health-start-period', '60s', '--health-retries', '60',
      ...env.flatMap((item) => ['-e', item]),
      ...(config.entrypoint ? ['--entrypoint', config.entrypoint[0]] : []),
      config.image,
      ...cloneCmd,
    ])
    try {
      await waitHealthy(WORKER_B)
      // ② 确定性认领：先只留主 worker 可认领。
      run('docker', ['stop', WORKER_B])

      const cookie = await loginCookie(request)
      let projectId = await createCloneProject(request, cookie, 'C38-租约接管')
      let jobId = ''
      let killedMidFlight = false

      for (let attempt = 0; attempt < 3 && !killedMidFlight; attempt++) {
        if (attempt > 0) projectId = await createCloneProject(request, cookie, `C38-租约接管-${attempt}`)
        const job = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/agent-jobs`, {
          requestId: crypto.randomUUID(), intent: 'author',
          brief: '双色动效复刻，输出 3 秒竖版源码', assetIds: [], baseRevision: 1,
        })
        expect(job.status, `author job: ${JSON.stringify(job.body)}`).toBe(202)
        jobId = job.body?.data?.jobId ?? job.body?.data?.job?.jobId
        expect(jobId).toBeTruthy()

        // ③ 屏障：PG 观察到 running（租约已发出）即刻 SIGKILL 持有者；若窗口
        //    错过（job 已终态）则重启主 worker 换工程重试——不靠 sleep 碰运气。
        //    psql 异常（kill 竞态窗口）由 waitUntil 捕获按未就绪重试。
        await waitUntil('job 进入 running', 30_000, () => {
          const state = psql(`select state from hypit_job where id='${jobId}'`)
          return state === 'running' ? state : null
        }, 200)
        run('docker', ['kill', MAIN_INTEL])
        await new Promise((r) => setTimeout(r, 800))
        killedMidFlight = !['succeeded', 'failed', 'cancelled'].includes(jobState(jobId))
        if (!killedMidFlight) {
          // 错过窗口：重启主 worker 换工程重试；fixture 必须连带重挂（netns 陷阱）。
          run('docker', ['start', MAIN_INTEL])
          await restartMainWithFixture()
        }
      }
      expect(killedMidFlight, '三次尝试内必须命中执行中窗口（否则接管断言失去意义）').toBe(true)

      // ④ 存活 worker 上线接管（租约 90s 过期 + 90s 周期）。克隆 netns 内起
      //    fixture 孪生（同 token），planner 的 localhost:19099 路由照常可用。
      run('docker', ['start', WORKER_B])
      startWorkerBFixture()
      await waitUntil('worker-b 内 fixture 孪生就绪', 60_000, () =>
        probe('docker', ['exec', WORKER_B, 'wget', '-q', '-O', '/dev/null', '--timeout=5',
          'http://127.0.0.1:19099/__health']) !== null ? 'ok' : null, 1000)
      const terminalState = await waitUntil('接管后 job 终态', LEASE_TAKEOVER_BUDGET_MS,
        () => {
          const state = jobState(jobId)
          return ['succeeded', 'failed', 'cancelled'].includes(state) ? state : null
        }, 2000)
      writeEvidence('TC-F2-38-01', 'job-terminal.txt', `jobId=${jobId}\nprojectId=${projectId}\nstate=${terminalState}\n`)

      // ⑤ Then：同 operation 不重复收费（真实机制层=ai_run.operation_id 确定性
      //    派生 + 结算 consume_operation_id；hypit_execution 是外部执行桥表，
      //    本地 agent 链不落行——round-3 实录该口径恒 0 属断言前提错误）、
      //    恰一个新 revision、apply 恰一次。
      expect(terminalState).toBe('succeeded')
      const dupRuns = duplicateRunOperations()
      expect(dupRuns, '接管重放不产生重复 operation 的 ai_run（计费幂等层）').toBe(0)
      const dupSettles = duplicateSettlementOperations()
      expect(dupSettles, '同 operation 无重复结算行').toBe(0)
      const maxRev = psqlInt(`select max(number) from hypit_revision where project_id='${projectId}'`)
      expect(maxRev, '作者产物恰好落成一个新 revision').toBe(2)
      const applyRows = JSON.parse(brokerSql(`select command_id,state from commands where project_id='${projectId}' and kind='workspace.apply'`) || '[]') as Array<{ state: string }>
      expect(applyRows.length, '该工程 workspace.apply 恰好一次（接管重放同一命令面）').toBe(1)
      expect(applyRows[0].state).toBe('succeeded')
      const ownerAccount = psql(`select account_id from hypit_project where id='${projectId}'`)
      expect(ownerAccount, '终态收敛在合法 owner 名下').toBeTruthy()
      writeEvidence('TC-F2-38-01', 'invariants.txt',
        `dupRuns=${dupRuns} dupSettlements=${dupSettles} maxRevision=${maxRev} owner=${ownerAccount}\n`)
    } finally {
      run('docker', ['rm', '-f', WORKER_B, WORKER_B_FIXTURE], { ok: true })
      if (probe('docker', ['inspect', MAIN_INTEL, '--format', '{{.State.Running}}']) !== 'true') {
        run('docker', ['start', MAIN_INTEL])
      }
      await restartMainWithFixture()
    }
  })

  // ═════════════════════════════════════════════════════════════════
  // TC-F2-38-02：fixture 接受后断 broker 网络 → 恢复后按原 receipt 收敛
  // ═════════════════════════════════════════════════════════════════

  test('TC-F2-38-02 断broker网络结果未知：恢复重启后查询原receipt收敛，不新提交', async ({ request }) => {
    test.setTimeout(20 * 60_000)
    await seedFix2TextModel(request)
    const cookie = await loginCookie(request)

    let projectId = ''
    let jobId = ''
    let faultBite = ''

    for (let attempt = 0; attempt < 3 && faultBite === ''; attempt++) {
      projectId = await createCloneProject(request, cookie, `C38-断网-${attempt}`)
      // agent author 链的 LLM 面只有 planner 家族（fixture /__calls 实测：
      // author=0/planner=N——mutation 的 changes 来自 planner 响应，judge 是
      // 确定性判定不调模型），屏障以 planner 增量为准。
      const before = fixtureCalls().filter((call) => call.family === 'planner').length

      const job = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/agent-jobs`, {
        requestId: crypto.randomUUID(), intent: 'author',
        brief: '双色动效复刻，输出 3 秒竖版源码', assetIds: [], baseRevision: 1,
      })
      jobId = job.body?.data?.jobId ?? job.body?.data?.job?.jobId
      expect(jobId).toBeTruthy()

      // ① 确定性屏障（round-3 勘误：fixture 原版在同一个 tick 里记录调用并发送
      //    应答，观测增量与 apply 之间的窗口为零——docker network disconnect
      //    （数百 ms）永远追不上，三次尝试全部错过、job 全部 succeeded）。
      //    改用 fixture /__hold：planner 调用到达即计入 /__calls（LLM 已接受）
      //    但应答被扣住——断网动作有整秒窗口，断点必然落在「fixture 已接受、
      //    写入面结果未知」的 Given 上。
      try {
        fixtureControl('/__hold', { family: 'planner' })
        await waitUntil('planner 已被 fixture 接受（扣在 hold 上）', 30_000, () =>
          fixtureCalls().filter((call) => call.family === 'planner').length > before ? 'ok' : null, 200)
        run('docker', ['network', 'disconnect', NETWORK, MAIN_BROKER])
        fixtureControl('/__release', {})
        // ② 故障必须真实咬合：planner 应答后 author 走 changeset create→validated
        //    check→apply，check 派发面对断网 sidecar 如实上抛 hypit_broker_unreachable
        //    （C107F2-38 产品修复：不再伪装成 compile_failed），worker 按 maintenance
        //    同类暂缓并在事件流留 deferred 痕迹——这就是咬合证据。
        //    未咬合超时 reject → catch 后重连换工程重试。
        try {
          // 100s：断网咬合的首个证据可能要等 sidecar 客户端 60s block 超时
          // （实弹实录：连接池里的静默断连让 POST 挂满 60s 才失败）。
          faultBite = await waitUntil('断网咬合证据', 100_000, () => {
            const deferred = psql(`select coalesce(string_agg(type || ':' || payload::text, ' | '), '')`
              + ` from hypit_job_event where job_id='${jobId}'`
              + ` and (payload::text like '%hypit_broker_unreachable%' or payload::text like '%sidecar unreachable%')`)
            if (deferred) return deferred.slice(0, 160)
            const logs = probe('docker', ['logs', MAIN_INTEL, '--since', '2m'])
            if (logs !== null) {
              const hit = logs.match(/hypit sidecar unreachable[^\n]*/)
              if (hit !== null) return hit[0]
            }
            return null
          }, 1000)
          // 咬合即恢复连接：replan 修复轮需要 broker 回来才能收敛（连接晚一拍
          // 就会多烧一轮修复预算，极端情况烧到终态 failed）。
          run('docker', ['network', 'connect', '--alias', 'hypit-backend', NETWORK, MAIN_BROKER])
        } catch { /* 本轮未咬合：finally 重连，进入下一次尝试 */ }
      } finally {
        // hold 幂等释放（容错：fixture 重启中也不让清理路径炸掉主断言），连接恢复
        // （显式别名保持 DNS 可解析）。
        fixtureControl('/__release', {}, true)
        run('docker', ['network', 'connect', '--alias', 'hypit-backend', NETWORK, MAIN_BROKER], { ok: true })
      }
    }
    expect(faultBite, '三次尝试内断网必须真实咬合（unknown 被观测，防伪绿）').toBeTruthy()
    writeEvidence('TC-F2-38-02', 'fault-bite.txt', `${faultBite}\n`)

    // DNS 恢复确认 → 重启 intelligence（恢复连接重启；fixture 连带重挂 netns）。
    await waitUntil('broker DNS 恢复', 60_000, () =>
      probe('docker', ['exec', MAIN_INTEL, 'wget', '-q', '-O', '/dev/null', '--timeout=5',
        'http://hypit-backend:9240/healthz']) !== null ? 'ok' : null)
    run('docker', ['restart', MAIN_INTEL])
    await restartMainWithFixture()
    await waitUntil('重启后 broker DNS', 60_000, () =>
      probe('docker', ['exec', MAIN_INTEL, 'wget', '-q', '-O', '/dev/null', '--timeout=5',
        'http://hypit-backend:9240/healthz']) !== null ? 'ok' : null)

    // ③ Then：恢复后收敛到终态；workspace.apply 只有原命令一次（不新提交）。
    const terminal = await pollJob(request, cookie, jobId, 'author 断网恢复', LEASE_TAKEOVER_BUDGET_MS)
    expect(terminal.body?.data?.state ?? terminal.body?.data?.job?.state).toBe('succeeded')

    const applyRows = JSON.parse(brokerSql(`select command_id,state from commands where project_id='${projectId}' and kind='workspace.apply' order by created_at`) || '[]') as Array<{ command_id: string; state: string }>
    expect(applyRows.length, 'workspace.apply 只有一次提交（unknown 后按原 receipt 收敛，不新命令）').toBe(1)
    expect(applyRows[0].state).toBe('succeeded')
    const maxRev = psqlInt(`select max(number) from hypit_revision where project_id='${projectId}'`)
    expect(maxRev).toBe(2)
    expect(duplicateRunOperations(), '断网重放不产生重复 operation 的 ai_run').toBe(0)
    expect(duplicateSettlementOperations(), '断网重放不重复结算').toBe(0)
    writeEvidence('TC-F2-38-02', 'convergence.txt',
      `jobId=${jobId}\nprojectId=${projectId}\napply=${JSON.stringify(applyRows)}\nmaxRevision=${maxRev}\n`)
  })

  // ═════════════════════════════════════════════════════════════════
  // TC-F2-38-04：同 head/hash 并发保存与反馈 → CAS 与批次原子性
  // ═════════════════════════════════════════════════════════════════

  test('TC-F2-38-04 两客户端同head/hash：受控并发保存与反馈修改不混合丢写', async ({ request }) => {
    test.setTimeout(10 * 60_000)
    const cookie = await loginCookie(request)
    const projectId = await createCloneProject(request, cookie, 'C38-并发CAS')

    // 基线：一个已发布 revision（手动 validated changeset，与 api-render 配方同形）。
    const svmlRead = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/file?path=main.svml`)
    expect(svmlRead.status).toBe(200)
    const baseHash = svmlRead.body?.data?.hash
    const baseline = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets`, {
      requestId: crypto.randomUUID(), baseRevision: 1, applyMode: 'validated',
      changes: [{ path: 'main.svml', action: 'put', baseHash,
        content: `${svmlRead.body?.data?.content ?? ''}\n<!-- C38 baseline -->` }],
    })
    expect(baseline.status).toBe(202)
    const baselineApply = await api(request, cookie, 'POST',
      `/api/hypit/projects/${projectId}/changesets/${baseline.body?.data?.changesetId}/apply`,
      { requestId: crypto.randomUUID(), baseRevision: 1 })
    expect(baselineApply.status).toBe(202)
    // 屏障：baseline revision 2 真实可见后再并发写（apply 202 异步发布窗口）。
    await waitUntil('baseline revision 2 发布', 30_000, () => {
      const n = psqlInt(`select coalesce(max(number),0) from hypit_revision where project_id='${projectId}'`)
      return n >= 2 ? String(n) : null
    }, 500)

    // ① 受控并发保存：两客户端同 baseRevision=2、内容不同（Promise.all 提交屏障）。
    const mkChangeset = async (marker: string) => {
      const created = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets`, {
        requestId: crypto.randomUUID(), baseRevision: 2, applyMode: 'save',
        changes: [{ path: 'notes.md', action: 'put', content: `# 并发写入 ${marker}\n` }],
      })
      expect(created.status).toBe(202)
      return created.body?.data?.changesetId as string
    }
    const [csA, csB] = await Promise.all([mkChangeset('client-A'), mkChangeset('client-B')])
    const applyBoth = await Promise.all([
      api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets/${csA}/apply`,
        { requestId: crypto.randomUUID(), baseRevision: 2 }),
      api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets/${csB}/apply`,
        { requestId: crypto.randomUUID(), baseRevision: 2 }),
    ])
    const statuses = applyBoth.map((r) => r.status).sort()
    expect(statuses, `并发 apply 应恰一成一拒: ${JSON.stringify(applyBoth.map((r) => r.body))}`).toEqual([202, 409])
    expect(applyBoth.find((r) => r.status === 409)?.body?.code).toBe('hypit_revision_conflict')
    // CAS 语义：head 恰好 +1；两份 changeset 行都在（输家不消失，落 conflict 态）。
    // 产品面没有 GET /changesets/{id} 读端点（只有 create/apply），行态用 PG 断言。
    const maxRev = psqlInt(`select max(number) from hypit_revision where project_id='${projectId}'`)
    expect(maxRev).toBe(3)
    const winnerIsA = applyBoth[0].status === 202
    const winnerCs = winnerIsA ? csA : csB
    const loserCs = winnerIsA ? csB : csA
    const winnerState = psql(`select state from hypit_changeset where id='${winnerCs}'`)
    expect(winnerState, '赢家 changeset 落 applied').toBe('applied')
    const loserRow = psql(`select state || '/' || coalesce(applied_revision::text,'-') from hypit_changeset where id='${loserCs}'`)
    expect(loserRow.startsWith('conflict'), `输家 changeset 保留为 conflict（不消失）: ${loserRow}`).toBe(true)

    // ② 受控并发反馈：同 expectedHash 两批不同 mutations（上游 add 语义），整批原子。
    const fbRead = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/feedback?run=main.svrun`)
    expect(fbRead.status, `feedback 读: ${JSON.stringify(fbRead.body)}`).toBe(200)
    const hash0 = fbRead.body?.data?.hash
    expect(hash0).toBeTruthy()
    const mutate = (marker: string) => api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/feedback`, {
      requestId: crypto.randomUUID(), run: 'main.svrun', expectedHash: hash0,
      mutations: [
        { type: 'add', comment: { id: crypto.randomUUID(), run: 'main.svrun', at: 1, text: `C38 并发反馈 ${marker} #1` } },
        { type: 'add', comment: { id: crypto.randomUUID(), run: 'main.svrun', at: 2, text: `C38 并发反馈 ${marker} #2` } },
      ],
    })
    const fbBoth = await Promise.all([mutate('client-A'), mutate('client-B')])
    // 反馈 mutation 是异步应用面：赢家 200/202 皆可（round-3 实录 202），归一后断言。
    const fbStatuses = fbBoth.map((r) => (r.status === 202 ? 200 : r.status)).sort()
    expect(fbStatuses, `并发反馈应恰一成一拒: ${JSON.stringify(fbBoth.map((r) => r.body))}`).toEqual([200, 409])
    expect(fbBoth.find((r) => r.status === 409)?.body?.code).toBe('hypit_revision_conflict')
    const fbWinner = fbBoth.find((r) => r.status === 200 || r.status === 202)
    expect(Number(fbWinner?.body?.data?.applied ?? 0), '赢家整批生效（applied=2）').toBe(2)
    const fbAfter = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/feedback?run=main.svrun`)
    const hash1 = fbAfter.body?.data?.hash
    expect(hash1).toBeTruthy()
    expect(hash1, '反馈 hash 恰前进一次').not.toBe(hash0)
    const bodies = JSON.stringify(fbAfter.body)
    const fbWinnerMarker = fbWinner === fbBoth[0] ? 'client-A' : 'client-B'
    expect(bodies).toContain(`C38 并发反馈 ${fbWinnerMarker} #1`)
    expect(bodies).toContain(`C38 并发反馈 ${fbWinnerMarker} #2`)
    const fbLoserMarker = fbWinnerMarker === 'client-A' ? 'client-B' : 'client-A'
    expect(bodies, `输家整批不落（不混合）: ${bodies}`).not.toContain(`C38 并发反馈 ${fbLoserMarker} #1`)

    writeEvidence('TC-F2-38-04', 'concurrency.txt',
      `applyStatuses=${statuses} feedbackStatuses=${fbStatuses} maxRevision=${maxRev}\nhash0=${hash0}\nhash1=${hash1}\n`)
    await api(request, cookie, 'DELETE', `/api/hypit/projects/${projectId}`)
  })

  // ═════════════════════════════════════════════════════════════════
  // TC-F2-38-03：完整灾备（栈轮换；置于文件末尾）
  // ═════════════════════════════════════════════════════════════════

  test('TC-F2-38-03 排空备份→新PG/卷恢复→浏览器重开：hash一致、零新generation、原栈不受影响', async ({ request, page }) => {
    test.setTimeout(45 * 60_000)
    const cookie = await loginCookie(request)

    // ① 历史作品 + 两 revision：validated changeset（rev2）→ 真实构建产物。
    const projectId = await createCloneProject(request, cookie, 'C38-灾备源工程')
    const svmlRead = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/file?path=main.svml`)
    const baseHash = svmlRead.body?.data?.hash
    const AUTHORED_SVML = readFileSync(resolve('tests/e2e/hypit-fix2-api-render.spec.ts'), 'utf8')
      .match(/const MAIN_SVML = `([\s\S]*?)`/)?.[1]
    expect(AUTHORED_SVML).toBeTruthy()
    const changeset = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets`, {
      requestId: crypto.randomUUID(), baseRevision: 1, applyMode: 'validated',
      changes: [{ path: 'main.svml', action: 'put', baseHash, content: AUTHORED_SVML }],
    })
    expect(changeset.status).toBe(202)
    const applied = await api(request, cookie, 'POST',
      `/api/hypit/projects/${projectId}/changesets/${changeset.body?.data?.changesetId}/apply`,
      { requestId: crypto.randomUUID(), baseRevision: 1 })
    expect(applied.status, `validated apply: ${JSON.stringify(applied.body)}`).toBe(202)
    // 屏障：authored rev2 可见后再 plan/build（apply 202 异步发布窗口）。
    await waitUntil('authored revision 2 发布', 30_000, () => {
      const n = psqlInt(`select coalesce(max(number),0) from hypit_revision where project_id='${projectId}'`)
      return n >= 2 ? String(n) : null
    }, 500)

    const plan = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/plan`,
      { requestId: crypto.randomUUID(), runFile: 'main.svrun' })
    expect(plan.status).toBe(200)
    const submit = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/builds`, {
      requestId: crypto.randomUUID(), planId: plan.body?.data?.plan?.id, grantId: null, title: 'C38-灾备',
    })
    expect([200, 202]).toContain(submit.status)
    const buildId = submit.body?.data?.build?.id
    const terminal = await pollJob(request, cookie,
      submit.body?.data?.job?.jobId ?? submit.body?.data?.job?.id, 'build', 15 * 60_000)
    expect(terminal.body?.data?.state ?? terminal.body?.data?.job?.state).toBe('succeeded')

    // 基线指纹（重读自 PG/文件面，不信内存）。
    const pgOf = (name: string) => ({
      user: run('docker', ['exec', name, 'printenv', 'POSTGRES_USER']),
      db: run('docker', ['exec', name, 'printenv', 'POSTGRES_DB']),
    })
    const mainPg = pgOf(MAIN_PG)
    const psqlOn = (pg: { user: string; db: string }, container: string, sql: string) =>
      run('docker', ['exec', container, 'psql', '-U', pg.user, '-d', pg.db, '-tAc', sql])
    const baseline = {
      projectId,
      buildId,
      revisionHashes: psqlOn(mainPg, MAIN_PG,
        `select string_agg(manifest_hash, ',' order by number) from hypit_revision where project_id='${projectId}'`),
      buildCount: Number(psqlOn(mainPg, MAIN_PG,
        `select count(*) from hypit_build where project_id='${projectId}'`)),
      ownerAccount: psqlOn(mainPg, MAIN_PG,
        `select account_id from hypit_project where id='${projectId}'`),
    }
    const outputs = await api(request, cookie, 'GET', `/api/hypit/builds/${buildId}/outputs`)
    const media = (outputs.body?.data?.items ?? outputs.body?.data?.outputs ?? [])
      .find((item: any) => /video|\.mp4/.test(String(item.name ?? item.kind ?? '')))
    expect(media, '应有视频产物').toBeTruthy()
    const download = await request.fetch(
      `${BASE}/api/hypit/builds/${buildId}/output?name=${encodeURIComponent(String(media.name))}`,
      { headers: { cookie }, timeout: REQUEST_TIMEOUT_MS })
    expect(download.status()).toBe(200)
    const payload = await download.json()
    const mp4Bytes = Buffer.from(String(payload?.data?.dataBase64 ?? ''), 'base64')
    const mp4Sha = createHash('sha256').update(mp4Bytes).digest('hex')
    writeEvidence('TC-F2-38-03', 'baseline.txt',
      `${JSON.stringify({ ...baseline, ownerAccount: '<owner-uuid>' })}\nmp4 sha256=${mp4Sha} size=${mp4Bytes.length}\n`)

    // ② 前置：注入一条「死进程遗留」在途（round-3 实录：broker 进程死在
    //    runKind 中途的行无人重放，updated_at 停在派发瞬间——排空永不收敛，
    //    backup.sh 被永久阻塞。C107F2-38 产品修复=维护窗开启时把静默超龄
    //    在途回收为 unknown）。直插 bridge.sqlite 一条 2h 龄 dispatching，
    //    验收修复真实生效（测试自含，不依赖历史遗留垃圾）。
    const orphanId = 'java-export-c38-orphan-probe'
    run('docker', ['exec', MAIN_BROKER, 'node', '-e',
      "const{DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('/data/hypit/bridge.sqlite');"
      + "db.prepare(\"INSERT OR REPLACE INTO commands(command_id,kind,payload_hash,project_id,state,created_at,updated_at) \""
      + "+ \"VALUES(?,?,?,?,?,?,?)\").run("
      + `'${orphanId}','project-package.export','orphan-probe-hash',null,'dispatching',`
      + "new Date(Date.now()-2*3600000).toISOString(),new Date(Date.now()-2*3600000).toISOString());"
      + "db.close()",
    ])

    // ③ 真实排空备份：工具容器（postgres 镜像 + apk 补 python3/curl）内原样运行
    //    deploy/hypit/backup.sh——维护租约/排空/pg_dump/数据伞/manifest 全部真实。
    const backupDir = resolve(EVIDENCE, 'TC-F2-38-03', 'backup')
    mkdirSync(resolve(backupDir, 'pg'), { recursive: true })
    const pgPassword = run('docker', ['exec', MAIN_PG, 'printenv', 'POSTGRES_PASSWORD'])
    const internalToken = run('docker', ['exec', MAIN_BROKER, 'printenv', 'HYPIT_INTERNAL_TOKEN'])
    const pgImage = run('docker', ['inspect', MAIN_PG, '--format', '{{.Config.Image}}'])
    run('docker', ['run', '--rm', '--network', NETWORK,
      '-v', `${resolve('deploy/hypit')}:/hypit-scripts:ro`,
      '-v', `${PROJECT}_hypit-test-data:/data`,
      '-v', `${backupDir}:/out`,
      '-e', 'HYPIT_DATA_ROOT=/data/hypit',
      '-e', `HYPIT_PG_DSN=postgres://${pgCreds?.user ?? mainPg.user}:${pgPassword}@postgres-local:5432/${mainPg.db}`,
      '-e', 'HYPIT_BACKEND_URL=http://hypit-backend:9240',
      '-e', `HYPIT_INTERNAL_TOKEN=${internalToken}`,
      pgImage, 'sh', '-c',
      // postgres:18-alpine 是 musl 基座：无 apt-get 也无预装 bash——外层用 sh，
      // 先 apk 补 bash/python3/curl（backup.sh 依赖）再原样执行；apk 的 stderr
      // 不吞（出网失败要可见）。
      'apk add --no-cache bash python3 curl >/dev/null && bash /hypit-scripts/backup.sh /out',
    ], { timeout: 600_000 })
    const manifest = readFileSync(resolve(backupDir, 'manifest.json'), 'utf8')
    expect(manifest).toContain('"complete": true')
    writeEvidence('TC-F2-38-03', 'backup-manifest.json', manifest)
    // 排空能收敛本身就证明崩失在途回收生效；显式断言落 unknown 留证据。
    const orphanSwept = JSON.parse(brokerSql(
      `select state,error_code from commands where command_id='${orphanId}'`) || '[]') as Array<{ state: string; error_code: string }>
    expect(orphanSwept[0]?.state, '死进程遗留 in-flight 被维护窗回收为 unknown（排空可收敛）').toBe('unknown')
    expect(orphanSwept[0]?.error_code).toBe('stale_dispatch')
    writeEvidence('TC-F2-38-03', 'stale-sweep.txt', `${JSON.stringify(orphanSwept)}\n`)

    // ④ 栈轮换（任意时刻只有一套应用栈）：停主栈 → 恢复栈新项目名/新卷/新 PG。
    const running = run('docker', ['ps', '--filter', `label=com.docker.compose.project=${PROJECT}`,
      '--format', '{{.Label "com.docker.compose.service"}}']).split('\n').filter(Boolean)
    const stopList = [...new Set([...running, ...UP_SERVICES, 'hypit-fix2-text-provider'])]
    run('bash', ['scripts/acceptance/hypit-compose.sh', '--test', '--enable-hypit', '--disable-dh',
      'stop', ...stopList], { timeout: 300_000 })
    try {
      const restorePgContainer = `${RESTORE_PROJECT}-postgres-local-1`
      const restoreNet = `${RESTORE_PROJECT}_default`
      const restoreVolume = `${RESTORE_PROJECT}_hypit-test-data`
      // round-9/13 实录：上一轮失败/中断会把恢复栈的容器与卷留在机上——卷残留撞
      // restore.sh「/data 非空」（volume create 对既有卷是 no-op），容器残留占
      // 端口挡主栈与下一轮拉栈。「新 PG/新卷」是本 TC 的目标条件，按 compose
      // 项目标签精确清掉恢复项目自有的全部容器与卷（不触碰主栈 y1-hypit-fix2-e2e
      // 的任何资源）。
      for (const id of run('docker', ['ps', '-aq', '--filter',
        `label=com.docker.compose.project=${RESTORE_PROJECT}`]).split('\n').filter(Boolean)) {
        run('docker', ['rm', '-f', id], { ok: true })
      }
      for (const vol of run('docker', ['volume', 'ls', '-q', '--filter',
        `label=com.docker.compose.project=${RESTORE_PROJECT}`]).split('\n').filter(Boolean)) {
        run('docker', ['volume', 'rm', vol], { ok: true })
      }
      // round-14 实录：旧代码创建的恢复数据卷不带项目标签（label 清理漏掉），
      // 而 volume create 对已存在卷是 no-op——不补标签不清数据，restore.sh 撞
      // 「/data 非空」。按名显式删一道（不存在时 ok 吞掉）。
      run('docker', ['volume', 'rm', restoreVolume], { ok: true })
      // round-12 实录：无显式 image: 的构建服务按 compose 项目名派生镜像
      // （<project>-<service>:latest）——恢复栈是独立项目名，主栈镜像救不了它，
      // no-build 路径首次 up 撞「No such image: y1-hypit-fix2-restore-<service>」。
      // --build 路径全层缓存命中即可产出同名镜像无此问题；此处等价补齐：把主栈
      // 已构建镜像按恢复项目名补标签（仅缺失时；两项目同一构建上下文，内容一致）。
      const allImages = run('docker', ['images', '--format', '{{.Repository}}:{{.Tag}}'])
        .split('\n').filter(name => name.startsWith(`${PROJECT}-`) && name.endsWith(':latest'))
      const restoreOwned = new Set(run('docker', ['images', '--format', '{{.Repository}}:{{.Tag}}'])
        .split('\n').filter(name => name.startsWith(`${RESTORE_PROJECT}-`)))
      for (const name of allImages) {
        const alias = `${RESTORE_PROJECT}${name.slice(PROJECT.length)}`
        if (!restoreOwned.has(alias)) {
          run('docker', ['tag', name, alias], { ok: true })
        }
      }
      run('bash', ['scripts/acceptance/hypit-compose.sh', '--test', '--enable-hypit', '--disable-dh',
        '--project-name', RESTORE_PROJECT, 'up', 'postgres-local'], { timeout: 300_000 })
      // 恢复数据卷由本测试显式创建：带上 compose 项目标签，失败/中断后的下一轮
      // 才能被上面的按标签清理完整回收（裸 volume create 无标签会漏）。
      run('docker', ['volume', 'create', '--label', `com.docker.compose.project=${RESTORE_PROJECT}`,
        restoreVolume], { ok: true })
      const restorePg = pgOf(restorePgContainer)
      const restorePassword = run('docker', ['exec', restorePgContainer, 'printenv', 'POSTGRES_PASSWORD'])
      run('docker', ['run', '--rm', '--network', restoreNet,
        '-v', `${resolve('deploy/hypit')}:/hypit-scripts:ro`,
        '-v', `${restoreVolume}:/data`,
        // restore.sh 契约：恢复报告（restore-report.json）写在备份目录旁——不能 :ro。
        '-v', `${backupDir}:/backup`,
        '-e', `HYPIT_PG_DSN=postgres://${restorePg.user}:${restorePassword}@postgres-local:5432/${restorePg.db}`,
        pgImage, 'sh', '-c',
        // round-17 实录：apk 拉包失败曾被 >/dev/null 2>&1 && 链静默吞成空输出退出 2
        // （瞬时 DNS 故障窗无从归因）——失败单独报因、专用退出码 9，不再吞。
        'apk add --no-cache bash python3 curl >/dev/null 2>&1 || { echo "apk add failed (container egress)" >&2; exit 9; }; '
        + 'bash /hypit-scripts/restore.sh /backup /data',
      ], { timeout: 600_000 })

      // ⑤ 恢复栈拉起（主栈已停，端口复用）→ 浏览器重开核验。
      run('bash', ['scripts/acceptance/hypit-compose.sh', '--test', '--enable-hypit', '--disable-dh',
        '--project-name', RESTORE_PROJECT, 'up', ...UP_SERVICES], { timeout: 600_000 })
      await waitBrokerReady(`${RESTORE_PROJECT}-hypit-backend-1`)
      // round-15 实录：备份在维护窗内快照，归档 umbrella 里的 broker 命令库带着
      // 租约行——恢复出的 broker 开机 fail-closed（results.export 等新命令一律
      // 503 "maintenance mode"）。恢复运行手册步骤：broker 起后凭备份 manifest
      // 记录的 leaseId 退出维护窗，恢复读写。
      const backupManifest = JSON.parse(readFileSync(`${backupDir}/manifest.json`, 'utf8')) as {
        maintenance?: { leaseId?: string }
      }
      const restoreLeaseId = backupManifest.maintenance?.leaseId ?? ''
      if (restoreLeaseId) {
        // maintenance 端点要 Bearer HYPIT_INTERNAL_TOKEN（backup.sh 同款）；令牌在
        // 容器环境里就地读取，不回传宿主命令行/测试日志（secret 不落证据）。
        const exitBody = run('docker', ['exec', `${RESTORE_PROJECT}-hypit-backend-1`, 'node', '-e',
          `const leaseId=process.argv[1];const token=process.env.HYPIT_INTERNAL_TOKEN;`
          + `fetch('http://127.0.0.1:9240/internal/v1/maintenance/exit',{method:'POST',`
          + `headers:{authorization:'Bearer '+token,'content-type':'application/json'},`
          + `body:JSON.stringify({leaseId})})`
          + `.then(async r=>process.stdout.write(String(r.status)+' '+await r.text()))`
          + `.catch(e=>{process.stderr.write(String(e));process.exit(1)})`,
          restoreLeaseId])
        if (!exitBody.startsWith('200')) {
          throw new Error(`恢复栈退出维护窗失败（leaseId=${restoreLeaseId.slice(0, 8)}…）：${exitBody.slice(0, 200)}`)
        }
      }
      await waitHealthy(`${RESTORE_PROJECT}-intelligence-service-1`, 420_000)
      await loginViaBrowser(page, OWNER_A)
      await page.getByTestId('nav-video-clone').click()
      await page.getByTestId('video-clone-workbench').waitFor({ timeout: 90_000 })
      await expect(page.getByRole('button', { name: /C38-灾备源工程/ }).first())
        .toBeVisible({ timeout: 90_000 })

      // 恢复栈 PG 重读：revision hash/构建数/owner 与基线一致（零新 generation）。
      const restoredHashes = psqlOn(restorePg, restorePgContainer,
        `select string_agg(manifest_hash, ',' order by number) from hypit_revision where project_id='${projectId}'`)
      expect(restoredHashes).toBe(baseline.revisionHashes)
      const restoredBuilds = Number(psqlOn(restorePg, restorePgContainer,
        `select count(*) from hypit_build where project_id='${projectId}'`))
      expect(restoredBuilds).toBe(baseline.buildCount)
      const restoredOwner = psqlOn(restorePg, restorePgContainer,
        `select account_id from hypit_project where id='${projectId}'`)
      expect(restoredOwner).toBe(baseline.ownerAccount)

      // 浏览器面下载同一产物：字节 hash 一致（不重新生成）。
      const restoredCookie = await loginCookie(request)
      const restoredDownload = await request.fetch(
        `${BASE}/api/hypit/builds/${buildId}/output?name=${encodeURIComponent(String(media.name))}`,
        { headers: { cookie: restoredCookie }, timeout: REQUEST_TIMEOUT_MS })
      expect(restoredDownload.status()).toBe(200)
      const restoredPayload = await restoredDownload.json()
      const restoredSha = createHash('sha256')
        .update(Buffer.from(String(restoredPayload?.data?.dataBase64 ?? ''), 'base64')).digest('hex')
      expect(restoredSha, '恢复栈产物字节一致（零新 generation）').toBe(mp4Sha)
      writeEvidence('TC-F2-38-03', 'restored.txt',
        `mp4 sha256=${restoredSha}\nrevisionHashes=一致\nbuildCount=${restoredBuilds}\n`)
    } finally {
      // ⑥ 清恢复栈（仅本阶段新建资源）：受控 stop 保留数据卷——reset 只对
      //    --fresh 会话开放（守卫铁律），非 fresh 流程用精确 stop 轮换回主栈。
      //    round-13 实录：⑤ 的恢复全栈占着 18080 系端口，只停 postgres 会让 ⑦
      //    主栈 re-up 撞端口绑定——按运行集全量停（与 ④ 停主栈同法：实测运行
      //    服务 + 显式清单取并集）。
      const restoreRunning = run('docker', ['ps', '--filter',
        `label=com.docker.compose.project=${RESTORE_PROJECT}`,
        '--format', '{{.Label "com.docker.compose.service"}}'], { ok: true })
        .split('\n').filter(Boolean)
      if (restoreRunning.length > 0) {
        run('bash', ['scripts/acceptance/hypit-compose.sh', '--test', '--enable-hypit', '--disable-dh',
          '--project-name', RESTORE_PROJECT, 'stop', ...new Set([...restoreRunning, ...UP_SERVICES])],
          { ok: true, timeout: 300_000 })
      }
    }
    run('bash', ['scripts/acceptance/hypit-compose.sh', '--test', '--enable-hypit', '--disable-dh',
      'up', ...UP_SERVICES], { timeout: 600_000 })
    await waitBrokerReady(MAIN_BROKER)
    await waitHealthy(MAIN_INTEL, 420_000)
    const afterRev = psqlOn(mainPg, MAIN_PG,
      `select string_agg(manifest_hash, ',' order by number) from hypit_revision where project_id='${projectId}'`)
    expect(afterRev, '原栈数据卷未被灾备演练触碰').toBe(baseline.revisionHashes)
    const afterOutputs = await api(request, cookie, 'GET', `/api/hypit/builds/${buildId}/outputs`)
    expect(afterOutputs.status, '原栈产物仍可读（原栈不受影响）').toBe(200)
    writeEvidence('TC-F2-38-03', 'main-stack-intact.txt', `revisionHashes=一致 buildId=${buildId} outputs可读\n`)
  })
})
