// hypit-studio.spec.ts — 任务书 #107-2 C107-12：Studio 会话端到端。
// 依赖完整隔离栈（sidecar + 引擎 + HYPIT_STUDIO_TICKET_SECRET），由
// HYPIT_E2E=1 显式开启；未开启时显式 skip 并说明原因，不静默漏跑。
import { test, expect } from '@playwright/test'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const enabled = process.env.HYPIT_E2E === '1'

test.skip(!enabled, '需要 HYPIT_E2E=1 隔离栈（sidecar+引擎+ticket secret），默认环境不具备')

test.describe('hypit studio sessions', () => {
  test('studio session issues a single-use ticket URL under the base path', async ({ request }) => {
    // 登录取 token（隔离栈固定账号）
    const login = await request.post(`${baseURL}/api/auth/login`, {
      data: { account: process.env.E2E_ACCOUNT ?? 'e2e@test.local', password: process.env.E2E_PASSWORD ?? '' },
    })
    expect(login.ok()).toBeTruthy()
    const { token } = await login.json()

    // 创建工程 → Studio 会话
    const project = await request.post(`${baseURL}/api/hypit/projects`, {
      headers: { authorization: `Bearer ${token}` },
      data: { requestId: crypto.randomUUID(), title: 'studio-e2e', mode: 'clone' },
    })
    expect(project.status()).toBe(202)
    const { data: projectData } = await project.json()
    const projectId = projectData.project.id

    const session = await request.post(`${baseURL}/api/hypit/projects/${projectId}/studio-sessions`, {
      headers: { authorization: `Bearer ${token}` },
      data: { requestId: crypto.randomUUID(), runFile: 'main.svrun', readOnly: true },
    })
    expect(session.status()).toBe(202)
    const { data: sessionData } = await session.json()
    expect(sessionData.ticketUrl).toContain('/studio/')
    expect(sessionData.sessionId).toBeTruthy()

    // 同 Run 二次打开复用活跃会话
    const again = await request.post(`${baseURL}/api/hypit/projects/${projectId}/studio-sessions`, {
      headers: { authorization: `Bearer ${token}` },
      data: { requestId: crypto.randomUUID(), runFile: 'main.svrun', readOnly: true },
    })
    const { data: againData } = await again.json()
    expect(againData.reused).toBe(true)
    expect(againData.sessionId).toBe(sessionData.sessionId)
  })

  test('cross-origin studio proxy is refused before any proxying', async ({ page }) => {
    const response = await page.request.get(`${baseURL}/studio/st-foreign/__studio/session`, {
      headers: { origin: 'https://evil.example' },
    })
    expect(response.status()).toBe(403)
  })
})
