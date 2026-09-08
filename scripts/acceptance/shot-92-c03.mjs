// 任务书 #92 C-03 双主题截图：最近项目列表（能力 chip/标题/来源/状态徽标/相对时间 + 空态 + 撤销条）。
// 用法：npm run dev 后 `node scripts/acceptance/shot-92-c03.mjs`。截图写 docs/任务书/evidence/92/（§8.8）。
// 数据全部走 Playwright 路由拦截的 fixture（无真实账号/密钥）；dev 无后端，API 拦截是唯一数据面。
import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = process.env.GRASS_BASE || 'http://localhost:5173'
const OUT = 'docs/任务书/evidence/92'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

function project(id, overrides = {}) {
  return {
    id, title: `项目${id}`, capability: 'article', status: 'draft', version: 3,
    workspace: { currentStep: 'editor', sourceLabel: '江畔门店' },
    resultAssetIds: [], runIds: [], updatedAt: '2026-09-07T10:00:00Z', ...overrides,
  }
}

const FIXTURES = [
  project('newest', { title: '秋季探店短视频脚本', capability: 'video', status: 'in_progress', updatedAt: new Date(Date.now() - 25 * 60_000).toISOString() }),
  project('middle', { title: '朋友圈上新文案', capability: 'moments', status: 'completed', updatedAt: new Date(Date.now() - 5 * 3600_000).toISOString() }),
  project('oldest', { title: '春季门店推文', updatedAt: new Date(Date.now() - 3 * 86400_000).toISOString() }),
]

async function intercept(page, items = FIXTURES) {
  await page.route('**/api/auth/me', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { user: { id: 'shot-user', email: 'shot@fixture.local', displayName: '截图用户', role: 'user' } } }),
  }))
  await page.route('**/api/creation-drafts**', (route) => {
    console.log('  [api]', route.request().method(), route.request().url().split('/api/')[1])
    return route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { items } }),
  })
  })
  await page.route('**/api/credits/**', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { balance: 12 } }),
  }))
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } })
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    const page = await context.newPage()
    await intercept(page)

    await page.goto('/ai.html')
    await page.getByRole('tab', { name: '最近项目' }).click()
    const list = page.locator('[data-testid="recent-list"]')
    await list.waitFor({ timeout: 15_000 })
    const rows = page.locator('[data-project-id]')
    ok(`C-03 ${theme}：三条降序`, await rows.count() === 3)
    ok(`C-03 ${theme}：能力/状态/来源`, (await rows.first().locator('.project-capability').textContent()) === '视频'
      && (await rows.first().locator('.project-status').textContent()) === '进行中'
      && (await rows.first().locator('.project-meta').textContent()).includes('江畔门店'))
    await page.screenshot({ path: `${OUT}/c03-recent-${theme}.png`, fullPage: false })

    // 归档 + 撤销条（交互态留存）
    await rows.first().locator('button[aria-label^="删除项目"]').click()
    await page.locator('[data-testid="recent-confirm-delete"]').click()
    await page.locator('[data-testid="recent-undo"]').waitFor({ timeout: 5_000 })
    ok(`C-03 ${theme}：撤销条出现`, (await page.locator('[data-testid="recent-undo"]').textContent()).includes('秋季探店短视频脚本'))
    await page.screenshot({ path: `${OUT}/c03-recent-undo-${theme}.png`, fullPage: false })

    // 空态
    const emptyPage = await context.newPage()
    await intercept(emptyPage, [])
    await emptyPage.goto('/ai.html')
    await emptyPage.getByRole('tab', { name: '最近项目' }).click()
    await emptyPage.locator('[data-testid="recent-empty"]').waitFor({ timeout: 15_000 })
    ok(`C-03 ${theme}：空态引导`, (await emptyPage.locator('[data-testid="recent-empty"]').textContent()).includes('还没有创作项目'))
    await emptyPage.screenshot({ path: `${OUT}/c03-recent-empty-${theme}.png`, fullPage: false })
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
