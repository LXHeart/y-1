import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync, chmodSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
const runner = 'scripts/acceptance/verify-108.sh'
describe('108验收入口', () => {
  it.each([[], ['unknown'], ['it'], ['it', 'all'], ['unit', 'extra']].map(args => ({ args })))('非法参数不启动任何任务 $args', ({ args }) => {
    const result = spawnSync('bash', [runner, ...args], { encoding: 'utf8' })
    expect(result.status).toBe(2); expect(result.stderr).toContain('usage:')
  })
  it('unit/storage/e2e 经过互斥守卫，e2e 关闭无关侧栈且退出回收缓存', () => {
    const dir = mkdtempSync(join(tmpdir(), 'voice-runner-'))
    try {
      for (const name of ['node', 'docker']) {
        const file = join(dir, name)
        writeFileSync(file, '#!/bin/sh\nprintf "%s\\n" "'+name+' $*" "voice=$VOICE_E2E dh=$DH_E2E hypit=$HYPIT_E2E workers=$E2E_WORKERS"\n')
        chmodSync(file, 0o755)
      }
      const env = { ...process.env, PATH: `${dir}:${process.env.PATH}`, LOCAL_STACK_ENTRY: '', HYPIT_E2E: '1', DH_E2E: '1' }
      const unit = execFileSync('bash', [runner, 'unit'], { env, encoding: 'utf8' }); expect(unit).toContain('run --project y1-task108-unit'); expect(unit).not.toContain('--docker')
      const it = execFileSync('bash', [runner, 'it', 'storage'], { env, encoding: 'utf8' }); expect(it).toContain('--docker --cleanup')
      const e2e = execFileSync('bash', [runner, 'e2e'], { env, encoding: 'utf8' }); expect(e2e).toContain('y1-task108-e2e'); expect(e2e).toContain('--fresh --cleanup'); expect(e2e).toContain('dh=0 hypit=0 workers=1'); expect(e2e).toContain('builder prune -af --filter until=24h')
    } finally { rmSync(dir, { recursive: true, force: true }) }
  })
  it('公共E2E不拦截业务API；42份评估案例分母明确', () => {
    expect(readFileSync('tests/e2e/creation-voice.spec.ts', 'utf8')).not.toContain('route.fulfill')
    const cases = JSON.parse(readFileSync('tests/fixtures/creation-voice-cases.json', 'utf8')).cases
    expect(cases.filter((c: { cohort: string }) => c.cohort === 'main32')).toHaveLength(32)
    expect(cases).toHaveLength(42)
  })
})
