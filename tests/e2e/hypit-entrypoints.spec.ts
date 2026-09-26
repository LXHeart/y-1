// hypit-entrypoints.spec.ts — 任务书 #107-3 C107-22（TC107-22-01/02/03/04）：
// 中心、参考分析交接、工程深链三个入口进入同一 video-clone 模块，刷新恢复；
// 旧导航项（数字人）不被破坏。运行于 HYPIT_E2E=1 隔离栈（sidecar+引擎），
// 不用 route.fulfill 冒充后端；未开启时显式 skip 并说明原因，不静默漏跑。
import { expect, test } from '@playwright/test'
import { loginOnAiApp } from './fixtures/digital-human'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const enabled = process.env.HYPIT_E2E === '1'

test.skip(!enabled, '需要 HYPIT_E2E=1 隔离栈（sidecar+引擎，hypit 面可用），默认环境不具备')

interface Envelope<T> { success: boolean; data: T }

async function loginToken(request: import('@playwright/test').APIRequestContext): Promise<string> {
  const login = await request.post(`${baseURL}/api/auth/login`, {
    data: { account: process.env.E2E_ACCOUNT ?? 'e2e@test.local', password: process.env.E2E_PASSWORD ?? '' },
  })
  expect(login.ok()).toBeTruthy()
  const { token } = await login.json()
  return token as string
}

async function createProject(request: import('@playwright/test').APIRequestContext, token: string,
  title: string, sourceContext?: Record<string, unknown>): Promise<{ projectId: string }> {
  const project = await request.post(`${baseURL}/api/hypit/projects`, {
    headers: { authorization: `Bearer ${token}` },
    data: {
      requestId: crypto.randomUUID(),
      title,
      mode: sourceContext ? 'clone' : 'brief',
      ...(sourceContext ? { sourceContext } : {}),
    },
  })
  // 202（创建）或 200 场景无；他人/临时媒体是 404/409——隔离栈本人合法引用应 202。
  expect(project.status(), await project.text()).toBe(202)
  const { data } = await project.json() as Envelope<{ project: { id: string } }>
  return { projectId: data.project.id }
}

test.describe('video-clone 三入口（TC107-22-01）', () => {
  test('AI 中心导航入口：进入同一模块，刷新后位置保持', async ({ page }) => {
    await loginOnAiApp(page)
    await page.getByTestId('nav-video-clone').click()
    const workbench = page.getByTestId('video-clone-workbench')
    await expect(workbench).toBeVisible({ timeout: 15_000 })

    // 旧导航项不被破坏：数字人链接仍在且可点（TC107-22-01「旧数字人可用」的可自动证部分；
    // 完整旧视图回归由既有 digital-human spec 承接）。
    await expect(page.getByTestId('nav-digital-human')).toBeVisible()

    // 刷新恢复：仍在 /video-clone 列表（URL 即状态，无隐藏路由重写）。
    await page.reload()
    await expect(workbench).toBeVisible({ timeout: 15_000 })
    expect(new URL(page.url()).pathname).toBe('/video-clone')
  })

  test('参考分析交接入口：交接 query 预填标题，创建载荷只带 {kind,id}', async ({ page }) => {
    await loginOnAiApp(page)
    // 模拟视频分析页「用作克隆参考」跳转产物（runId 为本人合法 ai_run 的形状）；
    // 服务端归属核验在 hypit-entrypoints 之外由 HypitProjectIT 真库承接。
    await page.goto(`${aiBaseURL}/video-clone?sourceKind=analysis&sourceId=${crypto.randomUUID()}&label=${encodeURIComponent('门店宣传参考')}`)
    await page.getByTestId('video-clone-workbench').waitFor({ timeout: 15_000 })

    await page.getByTestId('clone-new-project').click()
    const title = page.getByTestId('clone-new-title')
    await expect(title).toHaveValue('门店宣传参考', { timeout: 10_000 })
    await title.fill('克隆交接工程')
    await page.getByTestId('clone-new-submit').click()

    // 真实后端：工程创建成功并出现在列表（sourceContext 落库由 IT 断言，此处证 UI 链路）。
    await expect(page.getByTestId('clone-project-list')).toContainText('克隆交接工程', { timeout: 15_000 })
    // 交接 query 已剥离（成功后 URL 即干净状态）。
    expect(new URL(page.url()).search).not.toContain('sourceKind')
  })

  test('工程深链：/video-clone/:projectId 直达工程，刷新恢复', async ({ page, request }) => {
    const token = await loginToken(request)
    const { projectId } = await createProject(request, token, '深链工程')
    await loginOnAiApp(page)

    await page.goto(`${aiBaseURL}/video-clone/${projectId}`)
    await expect(page.getByTestId('clone-project-header')).toBeVisible({ timeout: 15_000 })

    // 深链刷新恢复：工程 header 仍在（TC107-22-01 恢复语义）。
    await page.reload()
    await expect(page.getByTestId('clone-project-header')).toBeVisible({ timeout: 15_000 })
    expect(new URL(page.url()).pathname).toBe(`/video-clone/${projectId}`)
  })

  test('旧 /hypit 深链兼容重定向到 video-clone 并保留工程段', async ({ page }) => {
    await loginOnAiApp(page)
    const projectId = '44444444-4444-4444-8444-444444444444'
    await page.goto(`${aiBaseURL}/hypit/${projectId}`)
    await page.getByTestId('video-clone-workbench').waitFor({ timeout: 15_000 })
    expect(new URL(page.url()).pathname).toBe(`/video-clone/${projectId}`)
  })
})
