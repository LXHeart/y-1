#!/usr/bin/env node
/* global process */
// Deliberately fake Docker CLI. All state lives in a per-test temporary directory.
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout } from 'node:timers/promises'

const args = process.argv.slice(2)
const path = process.env.FAKE_DOCKER_STATE
const state = JSON.parse(readFileSync(path, 'utf8'))
appendFileSync(`${path}.log`, `${JSON.stringify(args)}\n`)
const output = (data) => process.stdout.write(`${typeof data === 'string' ? data : JSON.stringify(data)}\n`)
const save = () => writeFileSync(path, JSON.stringify(state))
const filter = args[args.indexOf('--filter') + 1]
const projectFilter = args.includes('--filter') ? filter.replace('label=com.docker.compose.project=', '') : null
if (process.env.FAKE_FAIL === args[0]) process.exit(7)
if (args[0] === 'ps') output(state.containers.filter((c) => !projectFilter || c.labels['com.docker.compose.project'] === projectFilter).map((c) => c.id).join('\n'))
else if (args[0] === 'inspect') {
  const ids = args.slice(3)
  for (const c of state.containers.filter((c) => ids.includes(c.id))) output(c)
} else if (['volume', 'network'].includes(args[0])) {
  const key = `${args[0]}s`
  if (args[1] === 'ls') output(state[key].filter((v) => !projectFilter || v.project === projectFilter).map((v) => v.name).join('\n'))
  else if (args[1] === 'rm') { state[key] = state[key].filter((v) => !args.slice(2).includes(v.name)); save() }
  else process.exit(9)
} else if (args[0] === 'stop' || args[0] === 'rm') {
  if (args[0] === 'rm') state.containers = state.containers.filter((c) => !args.includes(c.id))
  else state.containers.forEach((c) => { if (args.includes(c.id)) c.state = 'exited' })
  save()
} else if (args[0] === 'wait') {
  state.containers.forEach((c) => { if (args.includes(c.id)) { c.state = 'exited'; c.exitCode = Number(process.env.FAKE_JOB_EXIT || 0) } })
  save(); output(process.env.FAKE_JOB_EXIT || '0')
} else if (args[0] === 'compose') {
  const projectAt = args.findIndex((v) => v === '-p' || v === '--project-name')
  const project = args[projectAt + 1]
  const commandAt = args.findIndex((v) => ['config', 'up', 'build', 'stop', 'restart', 'run', 'ps', 'exec', 'logs'].includes(v))
  const action = args[commandAt]
  const services = args.slice(commandAt + 1).filter((v, i, values) => !v.startsWith('-') && values[i - 1] !== '--wait-timeout')
  if (process.env.FAKE_FAIL === action && action !== 'up') process.exit(7)
  const model = structuredClone(state.model)
  for (const [name, volume] of Object.entries(model.volumes || {})) volume.name ||= `${project}_${name}`
  if (action === 'config') output(model)
  else if (action === 'build') {
    if (process.env.FAKE_BUILD_WAIT_MS) await setTimeout(Number(process.env.FAKE_BUILD_WAIT_MS))
  } else if (action === 'up' || action === 'run') {
    const start = (name) => {
      const service = model.services[name]
      if (!service) process.exit(8)
      for (const dep of Object.keys(service.depends_on || {})) start(dep)
      let c = state.containers.find((c) => c.labels['com.docker.compose.project'] === project && c.labels['com.docker.compose.service'] === name)
      if (!c) {
        c = { id: `${project}-${name}`, name: `/${project}-${name}-1`, image: 'fixture:test', labels: {
          'com.docker.compose.project': project,
          'com.docker.compose.service': name,
          'com.docker.compose.project.working_dir': process.env.FAKE_ROOT,
          'com.docker.compose.project.config_files': resolve(process.env.FAKE_ROOT, 'docker-compose.yml'),
        } }
        state.containers.push(c)
      }
      c.state = 'running'
      for (const mount of service.volumes || []) {
        if (mount.type !== 'volume') continue
        const volume = model.volumes[mount.source]
        if (!state.volumes.some((v) => v.name === volume.name)) state.volumes.push({ name: volume.name, project })
      }
      if (!state.networks.some((n) => n.name === `${project}_default`)) state.networks.push({ name: `${project}_default`, project })
    }
    services.forEach(start); save()
    if (process.env.FAKE_FAIL === 'up') process.exit(23)
  } else if (action === 'stop') {
    state.containers.forEach((c) => { if (c.labels['com.docker.compose.project'] === project && services.includes(c.labels['com.docker.compose.service'])) c.state = 'exited' }); save()
  } else if (!['ps', 'logs', 'exec', 'restart'].includes(action)) process.exit(9)
} else process.exit(9)
