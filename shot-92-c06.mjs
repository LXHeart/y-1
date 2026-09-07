// 任务书 #92 C-06 双主题截图：运行记录面板（成本摘要 + 失败行重试/继续编辑出口）与
// 创作助手面板（关联项目标识）。截图写 docs/任务书/evidence/92/（§8.8）。
import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = process.env.GRASS_BASE || 'http://localhost:5173'
const OUT = 'docs/任务书/evidence/92'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const runs = [
  {
    runId: 'run-failed', capability: 'text', provider: 'qwen', model: 'qwen-plus', status: 'failed',
    actualCents: null, startedAt: new Date(Date.now() - 3600_000).toISOString(), completedAt: null,
    taskContext: { runId: 'run-failed', capability: 'text', provider: 'qwen', model: 'qwen-plus',
      resolutionType: 'PLATFORM', priceTableVersion: 'v1', platformModelVersion: 3,
      fallbackAuthorized: true, startedAt: new Date().toISOString() },
    content: null, inputTokens: null, outputTokens: null,
  },
  {
    runId: 'run-ok', capability: 'image_generation', provider: 'qwen', model: 'qwen-image', status: 'completed',
    actualCents: 6, startedAt: new Date(Date.now() - 7200_000).toISOString(),
    completedAt: new Date(Date.now() - 7100_000).toISOString(),
    taskContext: { runId: 'run-ok', capability: 'image_generation', provider: 'qwen', model: 'qwen-image',
      resolutionType: 'BYOK', priceTableVersion: 'v1', platformModelVersion: null,
      fallbackAuthorized: false, startedAt: new Date().toISOString() },
    content: null, inputTokens: 210, outputTokens: 96,
  },
]

async function intercept(context) {
  await context.route('**/api/auth/me', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { user: { id: 'shot-user', email: 'shot@fixture.local', displayName: '截图用户', role: 'user' } } }),
  }))
  await context.route('**/api/credits/**', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { balance: 12 } }),
  }))
  // 控制面端点回非信封裸 JSON（useAiControlPlane 自带解析约定）
  await context.route('**/api/ai/runs', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(runs),
  }))
  await context.route('**/api/creation-drafts**', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { items: [] } }),
  }))
  await context.route('**/api/homepage/hot-items', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { provider: '60s', items: [], groups: [], fetchedAt: new Date().toISOString() } }),
  }))
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } })
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    await intercept(context)
    const page = await context.newPage()

    await page.goto('/ai.html?capability=video&taskId=demo-task-92')
    await page.getByRole('tab', { name: '运行记录' }).click()
    const retry = page.locator('[data-testid="retry-run"]')
    await retry.waitFor({ timeout: 15_000 })
    ok(`C-06 ${theme}：失败行重试出口`, await retry.count() === 1)
    ok(`C-06 ${theme}：成本摘要`, (await page.locator('[data-testid="token-summary"]').textContent()).includes('210 入 / 96 出'))
    await page.screenshot({ path: `${OUT}/c06-runs-${theme}.png`, fullPage: false })

    await page.getByRole('tab', { name: '创作助手' }).click()
    await page.locator('.assistant').waitFor({ timeout: 10_000 })
    await page.screenshot({ path: `${OUT}/c06-assistant-${theme}.png`, fullPage: false })
    await context.close()
  }
  await browser.close()
  const failed = results.filter((item) => !item.pass)
  if (failed.length) {
    console.error(`FAILED ${failed.length}/${results.length}`)
    process.exit(1)
  }
  console.log(`ALL PASS ${results.length}/${results.length}`)
}

await main()
