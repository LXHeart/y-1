import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import cases from '../fixtures/creation-voice-cases.json' with { type: 'json' }
import { data, fundVoiceCases, loginVoice, roles, seedVoice, voiceAiBase, voiceClientBase, voiceDraft } from './fixtures/creation-voice'

test('私有文风：公共入口确认学习、回访、隔离与清空', async ({ page, browser }, info) => {
  test.setTimeout(180_000)
  await loginVoice(page)
  for (const role of roles) await seedVoice(page.request, role)
  const draft = await voiceDraft(page.request, 'article', 'zhihu')
  await page.goto(`${voiceAiBase}/article?draft=${draft.id}`)
  const panel = page.getByTestId('voice-profile-panel')
  await expect(panel).toBeVisible()
  await panel.getByRole('button', { name: '编辑我的文风' }).click()
  const dialog = page.getByRole('dialog', { name: /我的文风/ })
  await dialog.getByRole('textbox', { name: '原文片段', exact: true }).fill('这家店不错！！！价格38元。')
  await dialog.getByRole('textbox', { name: '修改后片段', exact: true }).fill('这家店不错。价格38元。')
  const before = await data(await page.request.get(`${voiceAiBase}/api/creation-voice/consumer`))
  await dialog.getByRole('button', { name: '提取候选', exact: true }).click()
  const checkbox = dialog.getByRole('checkbox', { name: '选择候选1' })
  await expect(checkbox).toBeVisible(); await expect(checkbox).not.toBeChecked()
  expect((await data(await page.request.get(`${voiceAiBase}/api/creation-voice/consumer`))).revision).toBe(before.revision)
  await checkbox.check(); await dialog.getByRole('button', { name: '加入待保存规则' }).click()
  await dialog.getByRole('button', { name: '保存文风', exact: true }).click(); await expect(dialog).not.toBeVisible()
  const saved = await data(await page.request.get(`${voiceAiBase}/api/creation-voice/consumer`))
  expect(saved.rules).toContain('少用感叹号，保持明确判断')
  await panel.getByRole('checkbox', { name: '本次使用我的文风' }).check()
  await expect.poll(async () => (await data(await page.request.get(`${voiceAiBase}/api/creation-drafts/${draft.id}`))).workspace.inputs.brief.voice.mode).toBe('profile')
  await page.reload(); await expect(panel.getByRole('checkbox', { name: '本次使用我的文风' })).toBeChecked()
  const other = await browser.newContext()
  try {
    const login = await other.request.post(`${voiceAiBase}/api/auth/login`, { data: { email: process.env.E2E_SEED_ADMIN_EMAIL || 'e2e-admin@test.local', password: process.env.E2E_PASSWORD } })
    expect(login.ok()).toBe(true)
    expect((await data(await other.request.get(`${voiceAiBase}/api/creation-voice/consumer`))).revision).toBe(0)
  } finally { await other.close() }
  await page.request.put(`${voiceAiBase}/api/creation-voice/consumer`, { data: { expectedRevision: saved.revision, enabled: true, rules: ['另一页面的新规则'], samples: [] } }).then(data)
  expect((await data(await page.request.get(`${voiceAiBase}/api/creation-voice/merchant`))).rules).toEqual(['merchant：短句，保留明确判断'])
  await page.reload(); await expect(panel.getByRole('alert').first()).toContainText('版本已失效')
  await panel.getByRole('button', { name: '编辑我的文风' }).click()
  page.once('dialog', prompt => prompt.accept())
  await page.getByRole('dialog', { name: /我的文风/ }).getByRole('button', { name: '清空当前身份文风' }).click()
  await expect(page.getByRole('dialog', { name: /我的文风/ })).not.toBeVisible()
  const cleared = await data(await page.request.get(`${voiceAiBase}/api/creation-voice/consumer`))
  expect(cleared.rules).toEqual([]); expect(cleared.samples).toEqual([]); expect(cleared.enabled).toBe(false)
  await expect(panel.getByRole('checkbox', { name: '本次使用我的文风' })).not.toBeChecked()
  await mkdir(`test-artifacts/task-108/e2e/${info.project.name}`, { recursive: true })
})

test('42份合成材料经真实四平台API和模型fixture执行（非质量盲评）', async ({ page }, info) => {
  test.setTimeout(240_000)
  await loginVoice(page)
  await fundVoiceCases(page.request)
  const profiles: Record<string, { revision: number }> = {}
  for (const role of roles) profiles[role] = await seedVoice(page.request, role)
  const evidence = []
  for (const item of cases.cases) {
    const brief = { processingMode: 'create', authorRole: item.role, voice: { mode: 'profile', role: item.role, revision: profiles[item.role]!.revision }, facts: item.facts.map(statement => ({ statement, basis: 'user-confirmed' })) }
    const endpoint = item.platform === 'moments' ? 'moments-generation/generate' : item.platform === 'dianping' ? 'image-analysis/step/optimize' : 'article-generation/content'
    const payload = item.platform === 'moments' ? { topic: item.sourceText, style: 'lifestyle', brief } : item.platform === 'dianping' ? { review: item.sourceText, platform: item.platform, brief } : { topic: item.sourceText, title: '合成验收', outline: item.sourceText, platform: item.platform, answerMode: item.genre === 'answer', question: item.genre === 'answer' ? '体验如何？' : undefined, brief }
    const response = await page.request.post(`${voiceAiBase}/api/${endpoint}`, { data: payload, timeout: 30_000 })
    const output = await response.text()
    expect(response.ok(), `${item.id}: HTTP ${response.status()} ${output}`).toBe(true)
     expect(output, item.id).not.toContain('"type":"error"')
    expect(output, item.id).toMatch(/38|待核对|待确认/)
    evidence.push({ id: item.id, status: response.status(), output, quality: 'FIXTURE_ONLY' })
  }
  await mkdir(`test-artifacts/task-108/e2e/${info.project.name}`, { recursive: true })
  await writeFile(`test-artifacts/task-108/e2e/${info.project.name}/cases.json`, JSON.stringify(evidence, null, 2))
})

test('两入口三页面明暗移动与键盘焦点', async ({ page }, info) => {
  test.setTimeout(240_000)
  await loginVoice(page); await seedVoice(page.request, 'consumer'); await seedVoice(page.request, 'merchant')
  const output = `test-artifacts/task-108/e2e/${info.project.name}/screenshots`
  await mkdir(output, { recursive: true })
  for (const base of [voiceAiBase, voiceClientBase]) {
    for (const [capability, platform, role, genre] of [['article', 'zhihu', 'consumer', 'article'], ['article', 'zhihu', 'merchant', 'answer'], ['article', 'xiaohongshu', 'consumer', 'article'], ['image', 'dianping', 'consumer', 'review'], ['moments', 'moments', 'consumer', 'post']]) {
      const shotKey = `${platform}-${role}-${genre}`
      const draft = await voiceDraft(page.request, capability!, platform!, role!, { mode: 'none' }, genre!)
      await page.goto(`${base}/${capability}?draft=${draft.id}`)
      const panel = page.getByTestId('voice-profile-panel'); await expect(panel).toBeVisible()
      await expect(panel.getByRole('button', { name: '编辑我的文风' })).toBeEnabled()
      for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
        await page.setViewportSize({ width, height: width === 390 ? 844 : 800 })
        await page.evaluate(value => { document.documentElement.dataset.theme = value }, theme)
        await panel.scrollIntoViewIfNeeded()
        const overflow = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth, elements: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1).slice(0, 12).map(el => `${el.tagName}.${el.className}`) }))
        expect(overflow.scroll, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.width)
        await page.screenshot({ path: `${output}/${base === voiceAiBase ? 'ai' : 'user'}-${shotKey}-${theme}-${width}.png`, fullPage: true })
      }
      const edit = panel.getByRole('button', { name: '编辑我的文风' }); await edit.click()
      const dialog = page.getByRole('dialog', { name: /我的文风/ }); await expect(dialog).toBeVisible()
      await page.keyboard.press('Tab'); expect(await dialog.evaluate(el => el.contains(document.activeElement))).toBe(true)
      for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
        await page.setViewportSize({ width, height: width === 390 ? 844 : 800 })
        await page.evaluate(value => { document.documentElement.dataset.theme = value }, theme)
        const overflow = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth, elements: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1).slice(0, 12).map(el => `${el.tagName}.${el.className}`) }))
        expect(overflow.scroll, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.width)
        await page.screenshot({ path: `${output}/${base === voiceAiBase ? 'ai' : 'user'}-${shotKey}-editor-${theme}-${width}.png`, fullPage: true })
      }
      await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible(); await expect(edit).toBeFocused()
    }
  }
})
