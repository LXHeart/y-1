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
    data: { email: process.env.E2E_ACCOUNT ?? 'e2e@test.local', password: process.env.E2E_PASSWORD ?? '' },
    // 移动端 token 模式（GL-P3-IDENTITY-001）：带 X-Device-Info 才签发
    // data.tokens.access_token；Web 会话模式只发 Set-Cookie 无 body token。
    headers: { 'X-Device-Info': 'e2e-api-client' },
  })
  if (!login.ok()) {
    throw new Error(`登录失败 status=${login.status()} body=${(await login.text()).slice(0, 200)} account=${process.env.E2E_ACCOUNT ?? 'e2e@test.local'}`)
  }
  const body = await login.json() as { data?: { tokens?: { access_token?: string } } }
  const token = body?.data?.tokens?.access_token
  expect(token, 'token 模式登录应签发 access_token').toBeTruthy()
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
    //    C107F2-30 起 mutate 是 AcceptedJob 语义：202 + 命令结果体（comments/hash）。
    const mutated = await request.post(`${baseURL}/api/hypit/projects/${projectId}/feedback`, {
      headers: auth(token),
      data: {
        requestId: crypto.randomUUID(),
        expectedHash: null,
        mutations: [{ type: 'add', comment: { id: crypto.randomUUID(), run: 'main.svrun', at: 1.5, text: 'e2e 评论' } }],
      },
    })
    expect(mutated.status()).toBe(202)
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
    // 502 属代理层瞬态（r2 webkit 首分钟实录：无服务端处理错误）——失败时带响应体，
    // 便于区分 nginx 502 与 edge 502，不为绿而重试掩盖。
    expect(variants.status(), `variants body: ${await variants.text().catch(() => '<no-body>')}`).toBe(202)
    const { data: variantData } = await variants.json() as Envelope<{ items: Array<{ ordinal: number }> }>
    expect(variantData.items.length).toBe(4) // 2x2 真交叉积

    // 5) 清理：删除工程（终态收口，不留给下个引擎脏状态）。
    const removed = await request.delete(`${baseURL}/api/hypit/projects/${projectId}`, { headers: auth(token) })
    expect([200, 409]).toContain(removed.status())
  })

  // C107F-01 / TC-F01-04：工程包导出导入 roundtrip（真实 broker 全链）。
  // C107F2-30/37 契约：导出 202 AcceptedJob（exportId）→ 轮询 download 元数据 →
  // GET package 取真实 zip → multipart 上传导入（普通 owner，新工程 owner=调用方）。
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
      // 1) 导出（C107F2-30/37 契约）：202 AcceptedJob——回执 exportId/jobId，不再回
      //    artifactRoot；产物经 GET /exports/{id} 轮询到 download 元数据。
      const exported = await request.post(`${baseURL}/api/hypit/projects/${projectId}/export`, {
        headers: auth(token),
        data: { requestId: crypto.randomUUID(), title: 'e2e 导出', runFile: 'main.svrun' },
      })
      expect(exported.status()).toBe(202)
      const { data: exportReceipt } = await exported.json() as Envelope<{
        jobId: string
        exportId: string
        status: string
      }>
      expect(exportReceipt.exportId).toBeTruthy()

      // 2) 轮询导出状态（真实 broker 打包；空工程秒级，60s 上限防挂死）。
      let download: { downloadPath: string; sizeBytes: number; sha256?: string } | undefined
      for (let attempt = 0; attempt < 60 && download === undefined; attempt += 1) {
        const status = await request.get(`${baseURL}/api/hypit/exports/${exportReceipt.exportId}`, {
          headers: auth(token),
        })
        expect(status.status()).toBe(200)
        const { data } = await status.json() as Envelope<{
          status: string
          download?: { downloadPath: string; sizeBytes: number; sha256?: string }
        }>
        expect(data.status).not.toBe('failed')
        download = data.download
        if (download === undefined) await new Promise((resolve) => setTimeout(resolve, 1000))
      }
      expect(download, '导出应在 60s 内就绪').toBeTruthy()
      expect(download!.sizeBytes).toBeGreaterThan(0)

      // 3) 下载 zip 流（真实 application/zip；Range/ETag 契约由 broker 产出）。
      const pkg = await request.get(`${baseURL}/api/hypit/exports/${exportReceipt.exportId}/package`, {
        headers: auth(token),
      })
      expect(pkg.status()).toBe(200)
      const zipBytes = Buffer.from(await pkg.body())
      expect(zipBytes.byteLength).toBe(download!.sizeBytes)

      // 4) 导入（C107F2-30：multipart 上传，普通 owner；旧 JSON artifactRoot 已 415）。
      const imported = await request.post(`${baseURL}/api/hypit/imports`, {
        headers: auth(token),
        multipart: {
          requestId: crypto.randomUUID(),
          file: { name: 'exported.zip', mimeType: 'application/zip', buffer: zipBytes },
          title: 'e2e 导入工程',
        },
      })
      expect(imported.status()).toBe(202)
      const { data: importReceipt } = await imported.json() as Envelope<{
        jobId: string
        projectId: string
        status: string
      }>
      expect(importReceipt.projectId).not.toBe(projectId)
    } finally {
      await request.delete(`${baseURL}/api/hypit/projects/${projectId}`, { headers: auth(token) })
    }
  })
})
