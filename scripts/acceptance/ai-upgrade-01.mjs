// Deterministic browser acceptance with API fixtures; no model invocation or external writes.
import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

const base = process.env.AI_UPGRADE_BASE || 'http://127.0.0.1:5173'
const out = 'test-artifacts/ai-upgrade-01'
await mkdir(out, { recursive: true })
const browser = await chromium.launch()
const errors = []
const shots = []

async function start(page, platform, form, topic) {
  await page.goto(`${base}/ai.html`)
  await page.getByTestId('auth-pill').waitFor()
  await page.getByRole('button', { name: platform, exact: false }).first().click()
  await page.locator('[aria-label="内容形式"] button', { hasText: form }).click()
  await page.locator('.source-option', { hasText: '独立创作' }).click()
  await page.locator('textarea[name="creation-topic"]').fill(topic)
  await page.locator('button.primary-command').click()
}

try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    for (const theme of ['light', 'dark']) {
      const context = await browser.newContext({ viewport })
      const projects = new Map()
      let conflict = false
      let puts = 0
      await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
      await context.route('**/api/**', async route => {
        const request = route.request()
        const url = new URL(request.url())
        const path = url.pathname
        const send = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
          body: JSON.stringify(status >= 400 ? { success: false, error: data } : { success: true, data }) })
        if (path === '/api/auth/me') return send({ user: { id: 'upgrade-fixture', email: 'fixture@example.test', displayName: '验收用户', role: 'user' } })
        if (path.startsWith('/api/credits')) return send({ balance: 100 })
        if (path === '/api/creation-drafts' && request.method() === 'POST') {
          const body = request.postDataJSON()
          const draft = { ...body, id: randomUUID(), status: 'draft', version: 1,
            resultAssetIds: [], runIds: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
          projects.set(draft.id, draft)
          return send(draft)
        }
        if (path === '/api/creation-drafts') return send({ items: [...projects.values()], nextCursor: null })
        if (path.startsWith('/api/creation-drafts/')) {
          const id = path.split('/')[3]
          const draft = projects.get(id)
          if (!draft) return send('草稿不存在', 404)
          if (request.method() === 'PUT') {
            puts += 1
            if (conflict) { conflict = false; draft.version += 1; return send('草稿已在其他设备修改', 409) }
            const body = request.postDataJSON()
            if (body.expectedVersion !== draft.version) return send('版本冲突', 409)
            Object.assign(draft, body, { version: draft.version + 1 })
          }
          return send(draft)
        }
        if (path.includes('style-skills')) return send({ TITLE_FORMULA: [], GENRE: [], STYLE: [] })
        if (path.includes('style-preferences')) return send({ preferences: [] })
        if (path.includes('capabilities')) return send({ video: { available: false }, slideshow: { available: false }, tts: { available: false } })
        return send([])
      })
      // Vite's shared dev origin needs the AI HTML entry for deep-link refreshes.
      await context.route(/\/(article|moments|image|video-production)(\?.*)?$/, async route => {
        if (route.request().resourceType() !== 'document') return route.continue()
        return route.fulfill({ response: await route.fetch({ url: `${base}/ai.html` }) })
      })
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      async function shot(name) {
        await page.evaluate(() => document.fonts.ready)
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)
        expect(overflow, `${name} horizontal overflow`).toBe(false)
        const file = `${out}/${name}-${theme}-${viewport.width}.png`
        await page.screenshot({ path: file, fullPage: true })
        shots.push(file)
      }

      await start(page, '公众号', '图文', '秋季新品说明')
      await page.locator('.creation-brief summary').click()
      await page.getByLabel('补充要求', { exact: true }).fill('保留原始数字，不增加未经确认的经历。')
      await expect(page.locator('.save-badge')).toContainText('已保存')
      const article = [...projects.values()].find(item => item.capability === 'article')
      expect(article.workspace.inputs.brief.extraInstructions).toContain('原始数字')
      expect(article.workspace.inputs.content).toBeUndefined()
      await shot('article')

      conflict = true
      await page.getByPlaceholder(/输入你想创作的主题/).fill('本地待合并主题')
      await expect(page.locator('.save-badge')).toContainText('其他设备修改')
      const writesBefore = puts
      await shot('article-conflict')
      await page.getByRole('button', { name: '保留当前编辑', exact: true }).click()
      await expect(page.locator('.save-badge')).toContainText('已保存')
      expect(puts).toBe(writesBefore + 1)
      await page.goto(`${base}/article?draft=${article.id}`)
      await expect(page.getByPlaceholder(/输入你想创作的主题/)).toHaveValue('本地待合并主题')
      await page.locator('.creation-brief summary').click()
      await expect(page.getByLabel('补充要求', { exact: true })).toHaveValue('保留原始数字，不增加未经确认的经历。')
      await shot('article-restored')

      await start(page, '大众点评', '图文', '门店真实体验')
      await page.getByLabel('表达身份').selectOption('consumer')
      await page.getByLabel('已确认的经历', { exact: true }).fill('周六午间到店，排队约 20 分钟。')
      await page.getByLabel('已确认的优点', { exact: true }).fill('上菜后食物温度合适。')
      await page.getByLabel('已确认的不足', { exact: true }).fill('排队提示不清楚。')
      await shot('review')

      await start(page, '朋友圈', '图片 + 文字', '周末活动记录')
      await page.locator('.creation-brief summary').click()
      await page.getByLabel('补充要求', { exact: true }).fill('活动时间保持周六下午两点。')
      await expect(page.locator('.save-badge')).toContainText('已保存')
      await shot('moments')

      await page.goto(`${base}/ai.html`)
      await page.getByRole('tab', { name: '图片生成' }).click()
      await page.locator('textarea.prompt-input').fill('明亮的门店产品陈列，正面拍摄。')
      await expect(page.locator('.save-badge')).toContainText('已保存')
      await shot('image-studio')
      await page.getByRole('tab', { name: '视频工坊' }).click()
      await shot('video-studio')
      await page.getByRole('tab', { name: '最近项目' }).click()
      await expect(page.getByTestId('recent-list')).toBeVisible()
      await shot('recent')
      await page.getByRole('tab', { name: '创作助手' }).click()
      await page.locator('.as-body').waitFor()
      await shot('assistant')
      await context.close()
    }
  }
  expect(errors).toEqual([])
  console.log(`PASS: ${shots.length} screenshots; saved Brief, explicit conflict resolution and refresh restoration verified.`)
} finally {
  await browser.close()
}
