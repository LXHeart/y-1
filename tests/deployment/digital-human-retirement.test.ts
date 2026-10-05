import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('实时数字人客户端退役', () => {
  it('移除浏览器采集、连接和录制专用实现，保留服务端收尾契约', () => {
    expect(existsSync('src/views/digital-human')).toBe(false)
    expect(existsSync('src/composables/useDigitalHumanApi.ts')).toBe(false)
    expect(existsSync('contracts/digital-human.v1.json')).toBe(true)
  })
  it.each(['ci-e2e-105-all.sh', 'ci-e2e-105-s1.sh', 'verify-105-fix-2.sh'])('旧入口 %s 明确失败，不再自动起栈', name => {
    try { execFileSync('bash', [`scripts/acceptance/${name}`], { stdio: 'pipe' }); throw new Error('unexpected success') }
    catch (error) {
      expect((error as { status: number }).status).toBe(2)
      expect(String((error as { stderr: Buffer }).stderr)).toContain('已退役')
    }
  })
  it('CI 不再调度被删除的实时客户端验收', () => {
    const workflow = readFileSync('.github/workflows/ci.yml', 'utf8')
    expect(workflow).not.toContain('ci-e2e-105-all.sh')
    expect(workflow).not.toContain('dh-fake:')
  })
  it.each(['dh-runtime', 'dh-turn', 'dh-redis'])('资源入口拒绝启动退役服务 %s，且不触碰 Docker', service => {
    try {
      execFileSync(process.execPath, ['scripts/local-stack.mjs', 'compose', '-p', 'retired-probe', '-f', 'docker-compose.yml', '--', 'up', service], { stdio: 'pipe' })
      throw new Error('unexpected success')
    } catch (error) {
      expect(String((error as { stderr: Buffer }).stderr)).toContain('实时数字人已退役')
    }
  })

})
