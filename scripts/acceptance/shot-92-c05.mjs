// 任务书 #92 C-05 双主题截图：视频工坊封面工作台——底图、标题输入、保存徽标、
// 「存入素材库」成功后的结果资产 chip（AC-401/402 可见面）。
// 用法：npm run dev 后 `node scripts/acceptance/shot-92-c05.mjs`。截图写 docs/任务书/evidence/92/（§8.8）。
// 上传三步链（upload-ticket → presigned PUT → confirm）与素材登记全部走路由拦截 fixture。
import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = process.env.GRASS_BASE || 'http://localhost:5173'
const OUT = 'docs/任务书/evidence/92'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

async function intercept(context) {
  await context.route('**/api/auth/me', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: { user: { id: 'shot-user', email: 'shot@fixture.local', displayName: '截图用户', role: 'user' } } }),
  }))
  await context.route('**/api/credits/**', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { balance: 12 } }),
  }))
  await context.route('**/api/creation-drafts', (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: {
        id: 'draft-video-shot', title: body.title || '视频封面', capability: 'video', status: 'draft', version: 1,
        workspace: body.workspace, resultAssetIds: [], runIds: [], updatedAt: new Date().toISOString(),
      } }) })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: { items: [] } }) })
  })
  await context.route('**/api/creation-drafts/**', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: {
      id: 'draft-video-shot', title: '视频封面', capability: 'video', status: 'in_progress', version: 2,
      workspace: { capability: 'video', currentStep: 'cover' }, resultAssetIds: ['asset-shot-1'], runIds: [],
      updatedAt: new Date().toISOString(),
    } }),
  }))
  // 上传三步链 + 素材登记
  await context.route('**/api/media/upload-tickets', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { id: 'media-shot', uploadUrl: 'http://localhost:5173/fixture-put' } }),
  }))
  await context.route('**/fixture-put', (route) => route.fulfill({ status: 200, body: '' }))
  await context.route('**/api/media/media-shot/confirm', (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ success: true, data: { id: 'media-shot' } }),
  }))
  await context.route('**/api/content-assets', (route) => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: {
        id: 'asset-shot-1', title: '视频封面', mediaId: 'media-shot', libraryType: 'personal',
      } }) })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: { items: [] } }) })
  })
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  // 底图 fixture（1x1 PNG）
  const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
  fs.writeFileSync('/tmp/92-cover-base.png', Buffer.from(pngBase64, 'base64'))

  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } })
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    await intercept(context)
    const page = await context.newPage()

    await page.goto('/ai.html')
    await page.getByRole('tab', { name: '视频工坊' }).click()
    await page.getByRole('button', { name: '封面工作台' }).click()
    await page.getByRole('button', { name: '本地图片' }).click()
    await page.locator('input[type="file"][accept="image/*"]').first().setInputFiles('/tmp/92-cover-base.png')
    await page.fill('.vs-cover-controls input[type="text"]', '暖色调门头特写')
    const badge = page.locator('.save-badge')
    await badge.filter({ hasText: '已保存' }).waitFor({ timeout: 5_000 }).catch(() => {})
    ok(`C-05 ${theme}：封面字段自动保存`, (await badge.first().textContent()).includes('已保存'))
    await page.screenshot({ path: `${OUT}/c05-video-cover-${theme}.png`, fullPage: false })

    // 存入素材库 → 结果资产 chip（AC-402 可见面）
    await page.getByRole('button', { name: '存入素材库' }).click()
    const chip = page.locator('[data-testid="result-assets-chip"]')
    await chip.waitFor({ timeout: 8_000 })
    ok(`C-05 ${theme}：结果资产 chip`, (await chip.textContent()).includes('已存 1 项'))
    await page.screenshot({ path: `${OUT}/c05-video-result-${theme}.png`, fullPage: false })
    await context.close()
  }
  await browser.close()
  fs.rmSync('/tmp/92-cover-base.png', { force: true })
  const failed = results.filter((item) => !item.pass)
  if (failed.length) {
    console.error(`FAILED ${failed.length}/${results.length}`)
    process.exit(1)
  }
  console.log(`ALL PASS ${results.length}/${results.length}`)
}

await main()
