import { execFileSync, spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
// Shared by all worktrees and all guarded checkouts for this OS user. No bypass flag.
const lockDir = join(homedir(), '.cache', 'grassland-local-stack', 'lock')
const recordPath = join(lockDir, 'owner.json')
export const active = (c) => ['running', 'restarting', 'paused'].includes(c.state)
export const label = (c, key) => c.labels?.[`com.docker.compose.${key}`] || ''
export const say = (message) => process.stderr.write(`[local-stack] ${message}\n`)
export const fail = (message) => { throw new Error(message) }

export function capture(command, args) {
  try {
    return execFileSync(command, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000, maxBuffer: 16 * 1024 * 1024 }).trim()
  } catch {
    // Never echo config output, environment, or Docker arguments containing secrets.
    fail(`${command} 查询失败；无法确认运行状态，拒绝继续。`)
  }
}

export function containers() {
  const ids = capture('docker', ['ps', '-aq']).split(/\s+/).filter(Boolean)
  if (!ids.length) return []
  const format = '{"id":{{json .Id}},"name":{{json .Name}},"state":{{json .State.Status}},"exitCode":{{json .State.ExitCode}},"image":{{json .Config.Image}},"labels":{{json .Config.Labels}}}'
  const result = []
  for (let i = 0; i < ids.length; i += 100) {
    result.push(...capture('docker', ['inspect', '--format', format, ...ids.slice(i, i + 100)]).split('\n').filter(Boolean).map((line) => JSON.parse(line)))
  }
  return result
}

function commonDir(path) {
  if (!path || !existsSync(path)) return ''
  try {
    const dir = execFileSync('git', ['-C', path, 'rev-parse', '--git-common-dir'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
    return realpathSync(resolve(path, dir))
  } catch { return '' }
}
const repository = commonDir(root) || root

export function belongs(c) {
  const paths = [label(c, 'project.working_dir'), ...label(c, 'project.config_files').split(',').filter(Boolean).map(dirname)]
  return paths.some((path) => path === root || commonDir(path) === repository)
}

export function checkStack(project, all) {
  for (const c of all) {
    const p = label(c, 'project')
    if (p === project && !belongs(c)) fail(`项目名 ${project} 已被其他目录或归属不明的容器 ${c.name} 使用；拒绝接管。`)
    // Legacy projects remain recognizable after a checkout has been removed.
    const legacy = /^(y-1|y1-e2e(?:-.*)?|y1-hypit-.*|hypit-verify|grassland-otel-smoke)$/.test(p)
    const standalone = !p && /^(grassland[/-]|grassland-ci\/)/.test(c.image || '')
    if (active(c) && p !== project && (belongs(c) || legacy || standalone)) {
      fail(`检测到另一套本项目栈 ${p || '(无 Compose 标签)'}：${c.name} (${c.state})。目标为 ${project}；先复用或确认闲置后停止旧栈，禁止换端口另起一套。`)
    }
  }
  const other = [...new Set(all.filter((c) => active(c) && !belongs(c)).map((c) => label(c, 'project') || c.name))]
  if (other.length) say(`其他项目/未知资源仍在运行（不自动停止）：${other.join(', ')}`)
}

function readOwner() {
  try { return JSON.parse(readFileSync(recordPath, 'utf8')) } catch { fail(`锁记录不可读：${recordPath}。可能有启动正在进行；拒绝抢锁。`) }
}
function alive(pid) {
  try { process.kill(pid, 0); return true } catch (e) { return e.code !== 'ESRCH' }
}
export function saveOwner(owner) {
  const tmp = join(lockDir, `owner-${randomUUID()}.tmp`)
  writeFileSync(tmp, JSON.stringify(owner), { mode: 0o600 })
  renameSync(tmp, recordPath)
}
export function currentOwner() {
  const owner = readOwner()
  if (!process.env.LOCAL_STACK_TOKEN || owner.token !== process.env.LOCAL_STACK_TOKEN || !alive(owner.pid) || owner.repository !== repository) {
    fail('启动守卫会话无效或已结束；请从受保护入口重新执行。')
  }
  return owner
}

export function acquire(project, options = {}) {
  if (process.env.LOCAL_STACK_TOKEN) {
    const owner = currentOwner()
    if (owner.project !== project) {
      // C107F2-38（TC-F2-38-03 栈轮换）：受控换绑——灾备演练需要「停主栈 → 新项目
      // 名恢复栈」的轮换，单栈不变量不破（仅当当前绑定项目零活跃容器才允许换绑，
      // 任意时刻仍只有一套应用栈），换绑历史记入 owner.rotated 供出口清理覆盖。
      const stillUp = containers().filter((c) => label(c, 'project') === owner.project && active(c))
      if (stillUp.length) {
        fail(`当前流程已绑定 ${owner.project} 且仍有 ${stillUp.length} 个活跃容器，`
          + `不能嵌套启动 ${project}；轮换前必须先显式停掉当前栈。`)
      }
      owner.rotated = [...(owner.rotated ?? []), owner.project]
      owner.project = project
      owner.baseline = null
      owner.volumes = []
      owner.networks = []
      saveOwner(owner)
      say(`栈轮换：${owner.rotated[owner.rotated.length - 1]} → ${project}（旧栈已全部停止，保留数据卷）`)
    }
    if (options.fresh && !owner.fresh) fail('父流程不是 fresh 隔离验收，禁止子流程扩大重置权限。')
    if (options.cleanup && !owner.cleanup) fail('子流程需要退出清理，请在最外层 run 添加 --cleanup。')
    if (options.keepExisting && !owner.keepExisting) fail('子流程需要保留基线，请在最外层明确 --keep-existing。')
    return { owner, owned: false }
  }
  mkdirSync(dirname(lockDir), { recursive: true, mode: 0o700 })
  try { mkdirSync(lockDir, { mode: 0o700 }) } catch (e) {
    if (e.code !== 'EEXIST') throw e
    const old = readOwner()
    fail(`重型流程互斥锁已被占用：PID ${old.pid}，项目 ${old.project}，目录 ${old.root}。${alive(old.pid) ? '等待该流程结束。' : `进程已退出；确认无遗留子进程后处理锁目录 ${lockDir}，不会自动抢占。`}`)
  }
  const owner = { pid: process.pid, token: randomUUID(), project, repository, root, started: new Date().toISOString(), baseline: null, volumes: [], networks: [], allowed: [], ...options }
  try { saveOwner(owner) } catch (e) { rmSync(lockDir, { recursive: true, force: true }); throw e }
  process.env.LOCAL_STACK_TOKEN = owner.token
  return { owner, owned: true }
}
export function release(lease) {
  if (lease.owned && readOwner().token === lease.owner.token) rmSync(lockDir, { recursive: true })
}

export async function operation(fn) {
  currentOwner()
  const path = join(lockDir, 'operation')
  try { mkdirSync(path) } catch (error) {
    if (error.code === 'EEXIST') fail('同一验收会话内已有启动/构建操作；子流程也必须串行。')
    throw error
  }
  try { return await fn() } finally { rmSync(path, { recursive: true }) }
}

export function inventory(owner, all = containers()) {
  checkStack(owner.project, all)
  if (owner.baseline === null) {
    owner.baseline = all.filter((c) => label(c, 'project') === owner.project)
    owner.volumes = capture('docker', ['volume', 'ls', '-q']).split('\n').filter(Boolean)
    owner.networks = capture('docker', ['network', 'ls', '-q']).split('\n').filter(Boolean)
    if (owner.fresh && (owner.baseline.length || capture('docker', ['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${owner.project}`]))) {
      fail(`隔离验收要求空项目 ${owner.project}，已有容器或数据卷；拒绝删除旧数据来重置。`)
    }
    saveOwner(owner)
  }
  return all
}

export function dependencyOrder(model, services) {
  const result = [], visiting = new Set(), done = new Set()
  function visit(name) {
    if (done.has(name)) return
    if (visiting.has(name)) fail(`依赖循环：${name}`)
    const service = model.services?.[name]
    if (!service) fail(`配置中不存在服务 ${name}（请核对 profile/overlay）。`)
    visiting.add(name)
    const deps = service.depends_on || {}
    for (const dep of Array.isArray(deps) ? deps : Object.keys(deps)) {
      if (deps[dep]?.required === false && !model.services[dep]) continue
      visit(dep)
    }
    for (const ref of [service.network_mode, service.pid, service.ipc]) if (ref?.startsWith('service:')) visit(ref.slice(8))
    for (const ref of service.volumes_from || []) if (!ref.startsWith('container:')) visit(ref.split(':')[0])
    for (const ref of service.links || []) visit(ref.split(':')[0])
    visiting.delete(name); done.add(name); result.push(name)
  }
  services.forEach(visit)
  return result
}

export async function execute(command, args, env = process.env, timeoutMs = 0) {
  return new Promise((resolvePromise, reject) => {
    // A separate process group lets Ctrl-C/TERM reach shell grandchildren as well.
    const child = spawn(command, args, { stdio: 'inherit', env, detached: process.platform !== 'win32' })
    let signalCode = 0, timer, deadline
    const forward = (signal) => {
      signalCode = signal === 'SIGINT' ? 130 : 143
      try { process.kill(process.platform === 'win32' ? child.pid : -child.pid, signal) } catch { /* child already exited */ }
      timer ||= setTimeout(() => {
        try { process.kill(process.platform === 'win32' ? child.pid : -child.pid, 'SIGKILL') } catch { /* exited */ }
      }, 10000)
    }
    const interrupt = () => forward('SIGINT'), terminate = () => forward('SIGTERM')
    process.on('SIGINT', interrupt); process.on('SIGTERM', terminate)
    if (timeoutMs) deadline = setTimeout(() => { forward('SIGTERM'); signalCode = 124 }, timeoutMs)
    const clear = () => { clearTimeout(timer); clearTimeout(deadline); process.off('SIGINT', interrupt); process.off('SIGTERM', terminate) }
    child.on('error', (error) => { clear(); reject(error) })
    child.on('close', (code, signal) => { clear(); resolvePromise(signalCode || code || (signal ? 1 : 0)) })
  })
}

export async function cleanup(owner, { remove = false } = {}) {
  // C107F2-38：轮换过的项目先清——它们的所有活跃容器都是本流程启动的（换绑前提
  // 是零活跃容器），与基线无关也要停掉（保留数据卷）。
  const all = containers()
  for (const rotated of owner.rotated ?? []) {
    const extras = all.filter((c) => label(c, 'project') === rotated && belongs(c) && active(c))
    if (extras.length) {
      const code = await execute('docker', ['stop', ...extras.map((c) => c.id)])
      if (code) fail('轮换项目清理容器失败；已保留数据，请检查残留资源。')
      say(`已停止轮换项目 ${rotated} 的容器（保留数据卷）：${extras.map((c) => label(c, 'service') || c.name).join(', ')}`)
    }
  }
  if (owner.baseline === null) return
  const baseline = new Set(owner.baseline.filter(active).map((c) => label(c, 'service') || c.id))
  const target = all.filter((c) => label(c, 'project') === owner.project && belongs(c))
  const added = target.filter((c) => !baseline.has(label(c, 'service') || c.id))
  if (remove && !owner.fresh) fail('只有已确认全新的隔离验收会话可以重置容器/数据卷。')
  const ids = added.filter((c) => remove || active(c)).map((c) => c.id)
  if (ids.length) {
    const code = await execute('docker', [remove ? 'rm' : 'stop', ...(remove ? ['-f'] : []), ...ids])
    if (code) fail('清理容器失败；已保留数据，请检查残留资源。')
  }
  if (remove) {
    for (const [kind, original] of [['volume', owner.volumes], ['network', owner.networks]]) {
      const addedNames = capture('docker', [kind, 'ls', '-q', '--filter', `label=com.docker.compose.project=${owner.project}`]).split('\n').filter((s) => s && !original.includes(s))
      if (addedNames.length && await execute('docker', [kind, 'rm', ...addedNames])) fail(`隔离验收 ${kind} 清理失败。`)
    }
  }
  say(`已${remove ? '移除本次隔离验收资源' : '停止本次新增运行服务（保留数据卷）'}：${added.map((c) => label(c, 'service') || c.name).join(', ') || '无'}`)
}
