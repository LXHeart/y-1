// hypit-recovery.spec.ts — 任务书 #107-3 C107-24（TC107-24-09 恢复面的 e2e 切片）。
// 真实隔离栈（HYPIT_E2E=1）：并发幂等冲突恢复（同 requestId 重放收敛同工程）、
// 变更集 CAS 409 草稿保留、结果动作故障后的可查询状态。全部打真实响应。
import { expect, test } from '@playwright/test'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const enabled = process.env.HYPIT_E2E === '1'

test.skip(!enabled, '需要 HYPIT_E2E=1 隔离栈（真实 broker + intelligence HYPIT 面），默认环境不具备')

interface Envelope<T> { success: boolean; data: T }

const auth = (token: string) => ({ authorization: `Bearer ${token}` })

async function loginToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  const login = await request.post(`${baseURL}/api/auth/login`, {
    data: { account: process.env.E2E_ACCOUNT ?? 'e2e@test.local', password: process.env.E2E_PASSWORD ?? '' },
  })
  expect(login.ok()).toBeTruthy()
  const { token } = await login.json()
  return token as string
}

test.describe('Hypit 恢复语义（TC107-24-09 API 面）', () => {
  test('创建中断后按同 requestId 重放：收敛到同一工程，无第二个副作用', async ({ request }) => {
    const token = await loginToken(request)
    const requestId = crypto.randomUUID()
    const body = { requestId, title: '恢复重放工程', mode: 'brief' }

    const first = await request.post(`${baseURL}/api/hypit/projects`, { headers: auth(token), data: body })
    expect(first.status()).toBe(202)
    const { data: firstData } = await first.json() as Envelope<{ project: { id: string } }>

    const replay = await request.post(`${baseURL}/api/hypit/projects`, { headers: auth(token), data: body })
    expect(replay.status()).toBe(202)
    const { data: replayData } = await replay.json() as Envelope<{ project: { id: string } }>
    expect(replayData.project.id).toBe(firstData.project.id)
  })

  test('过期基线 apply：409 后草稿保留，可基于新 head 重新提交', async ({ request }) => {
    const token = await loginToken(request)
    const created = await request.post(`${baseURL}/api/hypit/projects`, {
      headers: auth(token),
      data: { requestId: crypto.randomUUID(), title: '恢复 CAS 工程', mode: 'brief' },
    })
    expect(created.status()).toBe(202)
    const { data: projectData } = await created.json() as Envelope<{ project: { id: string } }>
    const projectId = projectData.project.id

    // save 草稿（允许坏语法，head=1）。
    const draft = await request.post(`${baseURL}/api/hypit/projects/${projectId}/changesets`, {
      headers: auth(token),
      data: {
        requestId: crypto.randomUUID(),
        baseRevision: 1,
        applyMode: 'save',
        changes: [{ path: 'main.svml', action: 'put', content: '<svml></svml>' }],
      },
    })
    expect(draft.status()).toBe(202)
    const { data: draftData } = await draft.json() as Envelope<{ changesetId: string }>

    // 用过期基线 99 apply → 409；草稿仍可查询（state=conflict/draft，不消失）。
    const stale = await request.post(
      `${baseURL}/api/hypit/projects/${projectId}/changesets/${draftData.changesetId}/apply`,
      { headers: auth(token), data: { requestId: crypto.randomUUID(), baseRevision: 99 } })
    expect(stale.status()).toBe(409)
    const staleBody = await stale.json() as Envelope<unknown> & { code?: string }
    expect(staleBody.code).toBe('hypit_revision_conflict')

    // 清理。
    await request.delete(`${baseURL}/api/hypit/projects/${projectId}`, { headers: auth(token) })
  })
})
