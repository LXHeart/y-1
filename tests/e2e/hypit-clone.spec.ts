// hypit-clone.spec.ts — 任务书 #107-3 C107-24（TC107-24-06 基础无 key 链 + TC107-22-04）。
// 真实隔离栈（HYPIT_E2E=1，scripts/acceptance/ci-e2e-107.sh）：创建克隆工程 →
// 文件树/变更集 → 审片评论 → 变体批次 → 结果归档；全部断言打真实响应，
// 不用 route.fulfill 冒充后端；未开启时显式 skip 并说明原因。
import { expect, test } from '@playwright/test'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const enabled = process.env.HYPIT_E2E === '1'

test.skip(!enabled, '需要 HYPIT_E2E=1 隔离栈（真实 broker + intelligence HYPIT 面），默认环境不具备')

interface Envelope<T> { success: boolean; data: T; code?: string }

const auth = (token: string) => ({ authorization: `Bearer ${token}` })

async function loginToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  const login = await request.post(`${baseURL}/api/auth/login`, {
    data: { account: process.env.E2E_ACCOUNT ?? 'e2e@test.local', password: process.env.E2E_PASSWORD ?? '' },
  })
  expect(login.ok()).toBeTruthy()
  const { token } = await login.json()
  return token as string
}

test.describe('视频克隆用户主链（TC107-24-06 基础无 key 链的 API 面）', () => {
  test('创建 clone 工程 → 文件树可读 → 审片评论 CAS → 变体批次建立', async ({ request }) => {
    const token = await loginToken(request)

    // 1) 创建（provision 由真实 broker 完成；非 202 即栈故障，不静默）。
    const created = await request.post(`${baseURL}/api/hypit/projects`, {
      headers: auth(token),
      data: { requestId: crypto.randomUUID(), title: 'e2e 主链工程', mode: 'clone' },
    })
    expect(created.status()).toBe(202)
    const { data: projectData } = await created.json() as Envelope<{ project: { id: string; status: string } }>
    const projectId = projectData.project.id

    // 2) 文件树可读（broker provision 产物，非 mock）。
    const files = await request.get(`${baseURL}/api/hypit/projects/${projectId}/files`, { headers: auth(token) })
    expect(files.status()).toBe(200)
    const { data: filesData } = await files.json() as Envelope<{ revision: number; files: Array<{ path: string }> }>
    expect(filesData.revision).toBeGreaterThan(0)

    // 3) 审片评论：add → 读取回显（服务端 FEEDBACK 单一真相）。
    const mutated = await request.post(`${baseURL}/api/hypit/projects/${projectId}/feedback`, {
      headers: auth(token),
      data: {
        requestId: crypto.randomUUID(),
        expectedHash: null,
        mutations: [{ type: 'add', comment: { id: crypto.randomUUID(), run: 'main.svrun', at: 1.5, text: 'e2e 评论' } }],
      },
    })
    expect(mutated.status()).toBe(200)
    const { data: feedback } = await mutated.json() as Envelope<{ comments: Array<{ text: string }>; hash: string }>
    expect(feedback.comments.some((comment) => comment.text === 'e2e 评论')).toBe(true)

    // 4) 变体批次：两轴交叉积入队（attempt 独立）。
    const variants = await request.post(`${baseURL}/api/hypit/projects/${projectId}/variants`, {
      headers: auth(token),
      data: {
        requestId: crypto.randomUUID(),
        baseRunFile: 'main.svrun',
        axes: [{ key: 'caption.size', values: ['40px', '48px'] }, { key: 'sound.gain', values: ['-6', '0'] }],
      },
    })
    expect(variants.status()).toBe(202)
    const { data: variantData } = await variants.json() as Envelope<{ items: Array<{ ordinal: number }> }>
    expect(variantData.items.length).toBe(4) // 2x2 真交叉积

    // 5) 清理：删除工程（终态收口，不留给下个引擎脏状态）。
    const removed = await request.delete(`${baseURL}/api/hypit/projects/${projectId}`, { headers: auth(token) })
    expect([200, 409]).toContain(removed.status())
  })

  // C107F-01 / TC-F01-04：工程包导出导入 roundtrip（真实 broker 全链）。
  // 导出=owner 面；导入=operator 面（未配置 operator 的隔离栈上 403 如实断言门禁）。
  test('工程包 export → import roundtrip（TC-F01-04）', async ({ request }) => {
    const token = await loginToken(request)

    const created = await request.post(`${baseURL}/api/hypit/projects`, {
      headers: auth(token),
      data: { requestId: crypto.randomUUID(), title: 'e2e 导出源工程', mode: 'clone' },
    })
    expect(created.status()).toBe(202)
    const { data: projectData } = await created.json() as Envelope<{ project: { id: string } }>
    const projectId = projectData.project.id

    try {
      // 1) 导出：真实 broker 打包（dispatcher project-package.export 路由）。
      const exported = await request.post(`${baseURL}/api/hypit/projects/${projectId}/export`, {
        headers: auth(token),
        data: { requestId: crypto.randomUUID(), title: 'e2e 导出', runFile: 'main.svrun' },
      })
      expect(exported.status()).toBe(202)
      const { data: receipt } = await exported.json() as Envelope<{
        artifactRoot: string
        fileCount: number
        manifest: { format: string; files: Array<{ path: string; sha256: string }> }
      }>
      expect(receipt.artifactRoot.startsWith('project-exports/')).toBe(true)
      expect(receipt.manifest.format).toBe('y1.hypit-project@1')
      expect(receipt.fileCount).toBe(receipt.manifest.files.length)
      expect(receipt.manifest.files.some((file) => file.path === 'main.svrun')).toBe(true)

      // 2) 导入：operator 门（未配置 HYPIT_E2E_OPERATOR_ACCOUNT 的栈如实 403；
      //    配置了 operator 账号的栈走全量导入，revision=2 + fileCount 一致）。
      const imported = await request.post(`${baseURL}/api/hypit/imports`, {
        headers: auth(token),
        data: { requestId: crypto.randomUUID(), artifactRoot: receipt.artifactRoot },
      })
      if (imported.status() === 202) {
        const { data: importReceipt } = await imported.json() as Envelope<{
          projectId: string
          revision: number
          fileCount: number
        }>
        expect(importReceipt.projectId).not.toBe(projectId)
        expect(importReceipt.revision).toBe(2)
        expect(importReceipt.fileCount).toBe(receipt.fileCount)
      } else {
        // 未配置 operator 的隔离栈：导入面拒绝非 operator，门禁如实。
        expect(imported.status()).toBe(403)
        const body = await imported.json() as { error?: { code?: string } }
        expect(body.error?.code).toBe('hypit_operator_required')
      }
    } finally {
      await request.delete(`${baseURL}/api/hypit/projects/${projectId}`, { headers: auth(token) })
    }
  })
})
