// 任务书 #92 C-04 双主题截图：文章 / 图片生成 / 朋友圈 三类创作面的工作区自动保存与恢复态。
// 用法：npm run dev 后 `node scripts/acceptance/shot-92-c04.mjs`。截图写 docs/任务书/evidence/92/（§8.8）。
// dev 的 /article 深链会回退到草场壳——三类页面一律从 /ai.html 走 SPA 内导航（与真实用户路径一致）。
import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = process.env.GRASS_BASE || 'http://localhost:5173'
const OUT = 'docs/任务书/evidence/92'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const createdDrafts = []

async function intercept(context) {
  await context.route('**/api/auth/me', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { user: { id: 'shot-user', email: 'shot@fixture.local', displayName: '截图用户', role: 'user' } } }),
  }))
  await context.route('**/api/creation-drafts', (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      const draft = {
        id: `draft-${createdDrafts.length + 1}`, title: body.title || '未命名草稿',
        capability: body.capability, status: 'draft', version: 1,
        workspace: body.workspace, resultAssetIds: [], runIds: [],
        updatedAt: new Date().toISOString(),
      }
      createdDrafts.push(draft)
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: draft }) })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: { items: [] } }) })
  })
  await context.route('**/api/creation-drafts/**', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { id: 'draft-1', title: 'fixture', capability: 'article', status: 'draft', version: 2, workspace: {}, resultAssetIds: [], runIds: [], updatedAt: new Date().toISOString() } }),
  }))
  await context.route('**/api/credits/**', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { balance: 12 } }),
  }))
}

async function startCreation(page, platform, form, topic) {
  await page.getByRole('button', { name: platform, exact: false }).first().click()
  await page.locator(`[aria-label="内容形式"] button`, { hasText: form }).click()
  await page.locator('.source-option', { hasText: '独立创作' }).click()
  await page.fill('textarea[name="creation-topic"]', topic)
  await page.locator('button.primary-command').click()
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } })
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    await intercept(context)
    const page = await context.newPage()

    // ---- 文章：配置 → 开始创作 → 输入自动保存（AC-301 链路） ----
    await page.goto('/ai.html')
    await startCreation(page, '公众号', '图文', '秋日第一杯奶茶上新')
    await page.waitForURL(/article/, { timeout: 15_000 })
    await page.fill('textarea, input[type="text"]', '秋日奶茶探店正文的第一个字')
    const articleBadge = page.locator('.save-badge')
    await articleBadge.waitFor({ timeout: 15_000 })
    await articleBadge.filter({ hasText: '已保存' }).waitFor({ timeout: 5_000 }).catch(() => {})
    ok(`C-04 ${theme} 文章：自动保存`, (await articleBadge.first().textContent()).includes('已保存'))
    await page.screenshot({ path: `${OUT}/c04-article-${theme}.png`, fullPage: false })

    // 回创作中心再进图片生成板块
    await page.locator('button', { hasText: '返回创作中心' }).first().click()
    await page.getByRole('tab', { name: '图片生成' }).waitFor({ timeout: 10_000 })

    // ---- 朋友圈 ----
    const momentsPage = await context.newPage()
    await momentsPage.goto('/ai.html')
    await startCreation(momentsPage, '朋友圈', '图片 + 文字', '周末门店亲子活动')
    await momentsPage.waitForURL(/moments/, { timeout: 15_000 })
    await momentsPage.fill('#moments-topic', '周末亲子活动加场')
    const momentsBadge = momentsPage.locator('.save-badge')
    await momentsBadge.filter({ hasText: '已保存' }).waitFor({ timeout: 5_000 }).catch(() => {})
    ok(`C-04 ${theme} 朋友圈：自动保存`, (await momentsBadge.first().textContent()).includes('已保存'))
    await momentsPage.screenshot({ path: `${OUT}/c04-moments-${theme}.png`, fullPage: false })
    await momentsPage.close()

    // ---- 图片生成（板块直进） ----
    await page.getByRole('tab', { name: '图片生成' }).click()
    const prompt = page.locator('textarea.prompt-input')
    await prompt.waitFor({ timeout: 10_000 })
    await prompt.fill('为门店生成一张暖色调门头照')
    const imageBadge = page.locator('.save-badge')
    await imageBadge.filter({ hasText: '已保存' }).waitFor({ timeout: 5_000 }).catch(() => {})
    ok(`C-04 ${theme} 图片：自动保存`, (await imageBadge.first().textContent()).includes('已保存'))
    await page.screenshot({ path: `${OUT}/c04-image-${theme}.png`, fullPage: false })
    await context.close()
  }
  await browser.close()
  const failed = results.filter((item) => !item.pass)
  if (failed.length) {
    console.error(`FAILED ${failed.length}/${results.length}`)
    process.exit(1)
  }
  console.log(`ALL PASS ${results.length}/${results.length}（drafts: ${createdDrafts.length}）`)
}

await main()
