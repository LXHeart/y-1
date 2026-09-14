#!/usr/bin/env node
/** Usage: node scripts/acceptance/verify-creation-studio.mjs --phase m1|m2|m3
 * Complete isolated browser acceptance. V-LIVE-IMAGE and V-LIVE-WECHAT require separate authorized runs.
 */
import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { startStudioServers } from './creation-studio-server.mjs'

const args = process.argv.slice(2)
const phase = args[args.indexOf('--phase') + 1]?.toLowerCase()
if (!['m1', 'm2', 'm3'].includes(phase) || args.includes('--live-account')) {
  console.error('用法：--phase m1|m2|m3。本脚本仅做隔离验收；--live-account 不会触发真实 V-LIVE-WECHAT 写入。')
  process.exit(2)
}
const basePort = Number(process.env.STUDIO_UI_PORT || 28080)
const base = 'http://127.0.0.1:' + (basePort + 2)
let close = null
try {
  const health = await fetch(base + '/__studio-fixture-health').then(response => response.json()).catch(() => null)
  if (health?.kind !== 'creation-studio-fixture' || health.networkProxy !== false) close = await startStudioServers(basePort)
  const parameters = ['node_modules/@playwright/test/cli.js', 'test', '--config=playwright.config.ts',
    'tests/e2e/creation-studio.spec.ts', '--project=chromium', '--workers=1', '--grep', phase.toUpperCase()]
  const code = await new Promise((resolveExit, reject) => {
    const child = spawn(process.execPath, parameters, { stdio: 'inherit',
      env: { ...process.env, AI_BASE_URL: base, BASE_URL: 'http://127.0.0.1:' + basePort } })
    child.once('error', reject); child.once('exit', status => resolveExit(status ?? 1))
  })
  const directory = resolve('test-artifacts/task-101/acceptance', phase)
  await mkdir(directory, { recursive: true })
  await writeFile(resolve(directory, 'result.json'), JSON.stringify({
    phase, status: code === 0 ? 'PASS' : 'FAIL', exitCode: code, completedAt: new Date().toISOString(),
    uiFixture: true, realModel: 'NOT_RUN', realWechat: 'NOT_RUN', command: [process.execPath, ...parameters],
  }, null, 2))
  process.exitCode = code
} finally { if (close) await close() }
