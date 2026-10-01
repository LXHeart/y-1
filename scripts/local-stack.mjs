#!/usr/bin/env node
import { active, acquire, belongs, capture, checkStack, cleanup, containers, currentOwner, dependencyOrder, execute, fail, inventory, label, operation, release, saveOwner, say } from './lib/local-stack-guard.mjs'

const help = `本地启动守卫（不改变生产部署入口）
  node scripts/local-stack.mjs compose --project-name NAME -f FILE [--env-file FILE] [--profile NAME] -- up [--build] SERVICE...
  node scripts/local-stack.mjs compose <同上组合> -- build|stop SERVICE...
  node scripts/local-stack.mjs compose <同上组合> -- config|ps|logs ...
  node scripts/local-stack.mjs run --project NAME [--docker] [--fresh] [--cleanup] [--keep-existing] -- COMMAND...
  node scripts/local-stack.mjs reset   # 仅 fresh 隔离验收会话内部使用

up/build 必须显式指定服务；依赖自动展开，构建/启动串行。
run 持有跨会话锁直至命令结束；--docker 启动前盘点；--cleanup 用完停止新增服务。
--fresh 拒绝既有容器/卷，仅允许重置本会话新建的隔离数据；默认保留数据。
--keep-existing 仅供在已运行栈上增补专门的观测验收，打印保留清单，不启动第二套栈。
`

function split(args) {
  const at = args.indexOf('--')
  if (at < 0) fail('缺少 -- 分隔符；用 --help 查看用法。')
  return [args.slice(0, at), args.slice(at + 1)]
}
function options(args, booleans = []) {
  const out = {}
  for (let i = 0; i < args.length; i++) {
    const key = args[i]
    if (booleans.includes(key)) out[key] = true
    else if (['--project', '--entry'].includes(key) && args[i + 1] && !args[i + 1].startsWith('-')) out[key] = args[++i]
    else fail(`未知或缺值参数 ${key}`)
  }
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(out['--project'] || '')) fail('必须显式提供有效项目名。')
  return out
}

async function withLease(project, settings, fn) {
  const lease = acquire(project, settings)
  let code = 1, interrupted = 0
  const interrupt = () => { interrupted = 130 }, terminate = () => { interrupted = 143 }
  process.on('SIGINT', interrupt); process.on('SIGTERM', terminate)
  try {
    code = await fn(lease.owner)
    if (interrupted) code = interrupted
    return code
  } finally {
    try {
      if (lease.owned) {
        try {
          const owner = currentOwner()
          if (owner.cleanup || code !== 0) await cleanup(owner, { remove: !!owner.fresh })
        } finally { release(lease) }
      }
    } finally { process.off('SIGINT', interrupt); process.off('SIGTERM', terminate) }
  }
}

async function run(args) {
  const [flags, command] = split(args)
  const opts = options(flags, ['--docker', '--fresh', '--cleanup', '--keep-existing'])
  if (!command.length) fail('缺少要执行的命令。')
  return withLease(opts['--project'], { fresh: !!opts['--fresh'], cleanup: !!opts['--cleanup'], keepExisting: !!opts['--keep-existing'] }, async (owner) => {
    if (opts['--docker'] || opts['--fresh']) inventory(owner)
    say(`持有互斥锁：${owner.project}；重型任务串行。`)
    return execute(command[0], command.slice(1), { ...process.env, LOCAL_STACK_ENTRY: opts['--entry'] || '', COMPOSE_PARALLEL_LIMIT: '1', E2E_WORKERS: '1' })
  })
}

function composeFlags(args) {
  if ((process.env.COMPOSE_PROFILES || '').split(',').includes('*')) fail('禁止通过 COMPOSE_PROFILES 启用全部 profiles。')
  let project = '', files = 0
  const allowed = new Set(['--project-name', '-p', '--file', '-f', '--env-file', '--profile', '--project-directory'])
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1]
    if (!allowed.has(key) || !value || value.startsWith('-')) fail(`不支持或缺值的 Compose 参数 ${key}`)
    if (['-p', '--project-name'].includes(key)) { if (project) fail('项目名不能重复。'); project = value }
    if (['-f', '--file'].includes(key)) files++
    if (key === '--profile' && value === '*') fail('禁止启用全部 profiles。')
  }
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(project) || !files) fail('必须显式指定 Compose 项目名和文件组合。')
  return project
}

function selection(action, args) {
  const services = [], flags = []
  const accepted = {
    up: ['-d', '--detach', '--wait', '--build', '--no-build', '--no-recreate'],
    build: ['--pull', '--no-cache'],
    run: ['--rm', '-T'],
    stop: [], start: [], restart: [], kill: [],
  }[action]
  if (!accepted) fail(`不支持的变更命令 ${action}`)
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (action === 'up' && arg === '--wait-timeout') {
      const value = args[++i]
      if (!/^\d+$/.test(value || '') || !Number.isSafeInteger(Number(value)) || Number(value) < 1) fail('--wait-timeout 必须是正整数秒数。')
      flags.push(arg, value)
    } else if (arg.startsWith('-')) {
      if (!accepted.includes(arg)) fail(`不支持 ${action} 参数 ${arg}；禁止绕过依赖或扩大启动范围。`)
      flags.push(arg)
    } else if (/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(arg)) services.push(arg)
    else fail(`非法服务名 ${arg}`)
  }
  if (!services.length) fail(`${action} 必须显式列出服务，拒绝全量启动/构建。`)
  if (flags.includes('--build') && flags.includes('--no-build')) fail('--build 与 --no-build 不能同时使用。')
  if (action === 'run' && services.length !== 1) fail('run 仅允许一个明确服务，不接受额外命令。')
  return { services, flags }
}

async function compose(args) {
  const [global, command] = split(args)
  const project = composeFlags(global), [action, ...rest] = command
  const base = ['compose', '--parallel', '1', ...global]
  if (['config', 'ps', 'logs'].includes(action)) return execute('docker', [...base, ...command])
  if (action === 'exec') return withLease(project, {}, async (owner) => operation(async () => {
    inventory(owner)
    return execute('docker', [...base, ...command])
  }))
  if (action === 'down' || action === 'rm') fail('本地守卫不提供全栈删除；请显式 stop 服务。空项目隔离验收使用受保护的 reset。')
  const { services, flags } = selection(action, rest)
  return withLease(project, {}, async (owner) => operation(async () => {
    const stopping = action === 'stop'
    const all = containers()
    if (stopping) {
      // Stopping a confirmed project is allowed even when two stacks already exist.
      for (const c of all.filter((c) => label(c, 'project') === project)) if (!belongs(c)) fail('目标栈归属不明，拒绝停止。')
    } else inventory(owner, all)
    const model = JSON.parse(capture('docker', [...base, 'config', '--format', 'json']))
    const existingOnly = ['start', 'restart', 'kill'].includes(action)
    const ordered = stopping || existingOnly ? services : dependencyOrder(model, services)
    if (ordered.some((s) => !model.services?.[s])) fail('请求包含未知服务。')
    if (existingOnly && services.some((s) => !all.some((c) => label(c, 'project') === project && label(c, 'service') === s))) fail(`${action} 只允许操作目标项目已有容器，不能隐式创建新服务。`)
    if (!stopping) {
      checkStack(project, all)
      const permitted = new Set([...ordered, ...owner.allowed])
      const extras = all.filter((c) => active(c) && label(c, 'project') === project && !permitted.has(label(c, 'service')))
      if (extras.length && !owner.keepExisting && action !== 'build') fail(`当前栈还有本阶段清单以外的服务：${extras.map((c) => label(c, 'service')).join(', ')}。确认闲置后显式停止，不能无意扩大运行集。`)
      if (extras.length) say(`保留已有服务：${extras.map((c) => label(c, 'service')).join(', ')}`)
      if (owner.fresh) {
        for (const service of ordered) for (const mount of model.services[service].volumes || []) {
          if (mount.type !== 'volume') continue
          const volume = model.volumes?.[mount.source]
          if (!volume || volume.external || owner.volumes.includes(volume.name || `${project}_${mount.source}`)) fail('隔离验收引用外部或既有数据卷，拒绝写入/重置。')
        }
      }
      owner.allowed = [...permitted]; saveOwner(owner)
    }
    say(`project=${project}，请求=${services.join(', ')}；含依赖=${ordered.join(', ')}`)
    const build = action === 'build' || flags.includes('--build')
    if (build) for (const service of ordered.filter((s) => model.services[s].build)) {
      const code = await execute('docker', [...base, 'build', ...(action === 'build' ? flags : []), service])
      if (code) return code
    }
    if (action === 'build') return 0
    if (action === 'up') {
      const waitIndex = flags.indexOf('--wait-timeout')
      const timeout = waitIndex < 0 ? '600' : flags[waitIndex + 1]
      // Wait for each dependency before starting its dependants, including sibling JVMs.
      // Keep Compose's health/completed checks; never use --no-deps to bypass them.
      const completed = new Set(Object.values(model.services).flatMap((s) => Object.entries(s.depends_on || {})
        .filter(([, dependency]) => dependency.condition === 'service_completed_successfully').map(([name]) => name)))
      for (const service of ordered) {
        const waitFlags = completed.has(service) ? [] : ['--wait', '--wait-timeout', timeout]
        const code = await execute('docker', [...base, 'up', '-d', ...waitFlags, '--no-build', ...(flags.includes('--no-recreate') ? ['--no-recreate'] : []), service])
        if (code) return code
        if (completed.has(service)) {
          const jobs = containers().filter((c) => label(c, 'project') === project && label(c, 'service') === service)
          if (!jobs.length) fail(`初始化服务 ${service} 未创建容器。`)
          const waited = await execute('docker', ['wait', ...jobs.map((c) => c.id)], process.env, Number(timeout) * 1000)
          if (waited) return waited
          const finished = containers().filter((c) => jobs.some((job) => job.id === c.id))
          if (finished.length !== jobs.length || finished.some((c) => c.state !== 'exited' || c.exitCode !== 0)) fail(`初始化服务 ${service} 未成功完成；停止后续启动。`)
        }
      }
      return 0
    }
    return execute('docker', [...base, action, ...flags, ...services])
  }))
}

try {
  const [mode, ...args] = process.argv.slice(2)
  if (!mode || ['--help', '-h'].includes(mode)) process.stdout.write(help)
  else if (mode === 'run') process.exitCode = await run(args)
  else if (mode === 'compose') process.exitCode = await compose(args)
  else if (mode === 'session' || mode === 'reset') {
    const owner = currentOwner()
    const opts = args.length ? options(args, ['--fresh']) : {}
    if (opts['--project'] && opts['--project'] !== owner.project) fail('会话项目与请求项目不一致。')
    if (opts['--fresh'] && !owner.fresh) fail('必须从 fresh 隔离验收入口执行。')
    if (mode === 'reset') await operation(() => cleanup(owner, { remove: true }))
  }
  else if (mode === 'check') {
    const opts = options(args)
    checkStack(opts['--project'], containers())
    say(`单栈检查通过：${opts['--project']}（只读，不持锁，不代表后续启动已获准）。`)
  } else fail(`未知命令 ${mode}；运行 --help 查看用法。`)
} catch (error) {
  say(`拒绝/失败：${error.message}`)
  process.exitCode = 1
}
