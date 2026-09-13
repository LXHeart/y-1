#!/usr/bin/env node
/**
 * 任务书 #101 验收脚本：创作工作台 UI／文件核验（可重复执行）。
 *
 * 用法：node scripts/acceptance/verify-creation-studio.mjs --phase m1|m2|m3
 *
 * - phase=m1（C101-13）：图卡完整流程 UI 核验——登录态、原稿导入、计划确认、生成
 *   慢/错/unknown 交互、采用与刷新恢复。浏览器本地 fixture（不发任何真实业务请求、
 *   不自动发现或使用真实密钥）；截图写 test-artifacts/task-101/acceptance/。
 * - phase=m2（C101-18）：真实文件与图片包核验——C101-18 落地后实现。
 * - phase=m3（C101-22）：公众号草稿同步核验（隔离模拟服务）——C101-22 落地后实现。
 * 真实模型/真实渠道验收按 V-LIVE-IMAGE / V-LIVE-WECHAT 另行显式授权执行，本脚本不冒充。
 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright'

const args = process.argv.slice(2)
const phaseIndex = args.indexOf('--phase')
const phase = phaseIndex >= 0 ? args[phaseIndex + 1] : ''
if (!['m1', 'm2', 'm3'].includes(phase)) {
  console.error('用法: node scripts/acceptance/verify-creation-studio.mjs --phase m1|m2|m3')
  process.exit(2)
}
if (phase === 'm3') {
  // C101-22：公众号草稿同步 UI 核验（默认隔离模拟服务；真实渠道须显式 --live-account 授权，
  // 禁止自动选用第一个真实账号）。
  const liveAccountArg = args.indexOf('--live-account')
  const liveAccount = liveAccountArg >= 0 ? args[liveAccountArg + 1] : ''
  if (liveAccount) {
    console.log(`live 模式：获授权连接 ${liveAccount}——真实渠道验收按 V-LIVE-WECHAT 记录，本脚本不自动执行`)
  }
  const baseUrlM3 = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
  const outputM3 = resolve('test-artifacts/task-101/acceptance/m3')
  await mkdir(outputM3, { recursive: true })
  const browserM3 = await chromium.launch({ headless: true })
  const steps = []
  const account = { id: 'acct-m3', displayName: '验收公众号（模拟）', appId: 'wxaaaa0000000000e3',
    state: 'active', version: 2, verifiedAt: '2026-09-14T00:00:00Z', error: null }
  let syncState = 'succeeded'
  const syncBody = (state, version = 5) => ({ id: 'sync-m3', requestId: 'req-m3', accountId: 'acct-m3',
    draftId: 'draft-e2e-1', draftVersion: 2, state, externalDraftMediaId: state === 'succeeded' ? 'MID-M3' : null,
    payloadHash: 'h', version, createdAt: '2026-09-14T00:00:00Z',
    verifiedAt: state === 'succeeded' ? '2026-09-14T00:01:00Z' : null,
    error: state === 'unknown' ? { code: 'STUDIO_UNKNOWN_OUTCOME', message: '草稿写入结果未知，请核实草稿箱后确认' } : null })
  try {
    const page = await browserM3.newPage({ viewport: { width: 1440, height: 900 } })
    await page.route(/\/api\//, async (route) => {
      const url = new URL(route.request().url())
      const fulfill = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
        body: JSON.stringify({ success: true, data }) })
      if (url.pathname === '/api/auth/me') {
        await fulfill({ user: { id: 'acc-e2e', email: 'verify-101@test.invalid', displayName: '验收账号' } })
        return
      }
      if (url.pathname.endsWith('/exports') && route.request().method() === 'POST') {
        const request = JSON.parse(route.request().postData() || '{}')
        await fulfill({ draftId: 'draft-e2e-1', version: 2, format: request.format || 'wechat-html',
          file: { exportId: 'exp-m3', filename: '公众号验收.html', contentType: 'text/html', sha256: 'h',
            url: 'https://signed.test.invalid/creation-exports/exp-m3.html?sig=1', sizeBytes: 2048,
            expiresAt: '2999-01-01T00:00:00Z' }, missingItems: [] })
        return
      }
      if (url.pathname.endsWith('/api/creation-channels/wechat/accounts')) {
        await fulfill({ items: [account], nextCursor: null })
        return
      }
      if (url.pathname.endsWith('/draft-syncs') && route.request().method() === 'POST') {
        await fulfill(syncBody(syncState), 202)
        return
      }
      if (/\/draft-syncs\/[\w-]+$/.test(url.pathname)) {
        await fulfill(syncBody(syncState))
        return
      }
      if (url.pathname.endsWith('/candidates')) {
        await fulfill({ items: [{ externalDraftMediaId: 'MID-M3', title: '验收草稿（模拟）',
          updatedAt: '1726262400', contentMatches: true }], searchedCount: 1, hasMore: false })
        return
      }
      if (url.pathname.endsWith('/reconcile')) {
        await fulfill(syncBody('succeeded', 8))
        return
      }
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, error: 'm3 fixture 未覆盖 ' + url.pathname }) })
    })
    const run = async (name, fn) => {
      try { await fn(); steps.push({ name, status: 'PASS' }) } catch (error) { steps.push({ name, status: 'FAIL', detail: String(error).slice(0, 400) }) }
    }
    await run('预览打开：快照版本与账号选择可见', async () => {
      syncState = 'succeeded'
      await page.goto(baseUrlM3 + '/article?draft=draft-e2e-1')
      await page.getByTestId('delivery-wechat-sync').waitFor({ timeout: 30_000 })
      await page.getByTestId('delivery-wechat-sync').click()
      await page.getByTestId('wechat-draft-preview').waitFor({ timeout: 30_000 })
      const snapshot = await page.getByTestId('wechat-draft-preview-snapshot').textContent()
      assert(snapshot.includes('v'), '快照版本展示')
      const select = await page.getByTestId('wechat-preview-account').textContent()
      assert(select.includes('验收公众号'), '账号选择展示')
    })
    await run('提交 → 已存入草稿箱；无「已发布」与外链', async () => {
      await page.getByTestId('wechat-preview-open-comment').check()
      await page.getByTestId('wechat-preview-submit').click()
      await page.getByTestId('wechat-sync-state').waitFor({ timeout: 30_000 })
      const stateText = await page.getByTestId('wechat-sync-state').textContent()
      assert(stateText.includes('草稿箱'), `状态=${stateText}`)
      const panelText = await page.getByTestId('wechat-sync-panel').textContent()
      assert(!panelText.includes('已发布'), '不得出现已发布')
      assert(!/weixin\.qq\.com\/s/.test(panelText), '不得编造公开链接')
      await page.screenshot({ path: resolve(outputM3, '01-sync-succeeded.png') })
    })
    await run('unknown：只提供核实（无自动重发）→ 候选核实成功', async () => {
      syncState = 'unknown'
      await page.getByTestId('wechat-sync-candidates').waitFor({ timeout: 30_000 })
      const hint = await page.getByTestId('wechat-sync-unknown').textContent()
      assert(hint.includes('不会自动重发'), 'unknown 文案')
      await page.getByTestId('wechat-sync-candidates').click()
      await page.getByTestId('wechat-sync-candidates-list').waitFor({ timeout: 30_000 })
      const listText = await page.getByTestId('wechat-sync-candidates-list').textContent()
      assert(listText.includes('内容一致'), '服务端匹配标记')
      await page.getByTestId('wechat-sync-verify-MID-M3').click()
      await page.getByTestId('wechat-sync-state').filter({ hasText: '已存入草稿箱' }).waitFor({ timeout: 30_000 })
      await page.screenshot({ path: resolve(outputM3, '02-reconciled.png') })
    })
    await page.close()
  } finally {
    await browserM3.close()
  }
  const failedM3 = steps.filter((item) => item.status === 'FAIL')
  for (const item of steps) console.log(`[${item.status}] ${item.name}${item.detail ? ' — ' + item.detail : ''}`)
  await writeFile(resolve(outputM3, 'results.json'),
    JSON.stringify({ phase, mode: liveAccount ? 'live-authorized' : 'isolated-mock', steps }, null, 2))
  console.log(failedM3.length ? `M3 验收失败：${failedM3.length}/${steps.length} 步` : `M3 验收通过（隔离模拟；截图在 ${outputM3}）`)
  process.exit(failedM3.length ? 1 : 0)
}
if (phase === 'm2') {
  // C101-18：新格式导出 UI 核验（浏览器本地 fixture——POST exports → 轮询 → 实际下载）
  const baseUrlM2 = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
  const outputM2 = resolve('test-artifacts/task-101/acceptance/m2')
  await mkdir(outputM2, { recursive: true })
  const browserM2 = await chromium.launch({ headless: true })
  const steps = []
  try {
    const page = await browserM2.newPage({ viewport: { width: 1440, height: 900 } })
    await page.route(/\/api\//, async (route) => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/auth/me') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { user: { id: 'acc-e2e', email: 'verify-101@test.invalid', displayName: '验收账号' } } }) })
        return
      }
      if (url.pathname.endsWith('/exports') && route.request().method() === 'POST') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { draftId: 'draft-e2e-1', version: 2, format: 'bundle-zip', file: { exportId: 'exp-m2', filename: '验收导出.zip', contentType: 'application/zip', sha256: 'h', url: 'https://signed.test.invalid/creation-exports/exp-m2.zip?sig=1', sizeBytes: 4096, expiresAt: '2999-01-01T00:00:00Z' }, missingItems: [] } }) })
        return
      }
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, error: 'm2 fixture 未覆盖 ' + url.pathname }) })
    })
    const run = async (name, fn) => {
      try { await fn(); steps.push({ name, status: 'PASS' }) } catch (error) { steps.push({ name, status: 'FAIL', detail: String(error).slice(0, 400) }) }
    }
    await run('导出面板渲染（交付面板含新格式选择）', async () => {
      await page.goto(baseUrlM2 + '/article?draft=draft-e2e-1')
      await page.getByTestId('studio-export-format').waitFor({ timeout: 30_000 })
      assert((await page.getByTestId('studio-export-format').textContent()).includes('图片包 ZIP'))
    })
    await run('点「导出真实文件」→ 实际下载完成标记', async () => {
      await page.getByTestId('studio-export').click()
      await page.getByTestId('studio-export-done').waitFor({ timeout: 30_000 })
      assert((await page.getByTestId('studio-export-done').textContent()).includes('验收导出.zip'))
      await page.screenshot({ path: resolve(outputM2, '01-export-done.png') })
    })
    await page.close()
  } finally {
    await browserM2.close()
  }
  const failedM2 = steps.filter((item) => item.status === 'FAIL')
  for (const item of steps) console.log(`[${item.status}] ${item.name}${item.detail ? ' — ' + item.detail : ''}`)
  await writeFile(resolve(outputM2, 'results.json'), JSON.stringify({ phase, steps }, null, 2))
  console.log(failedM2.length ? `M2 验收失败：${failedM2.length}/${steps.length} 步` : `M2 验收通过（截图在 ${outputM2}）`)
  process.exit(failedM2.length ? 1 : 0)
}

const baseUrl = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const output = resolve('test-artifacts/task-101/acceptance/m1')
await mkdir(output, { recursive: true })

const SOURCE_TEXT = '门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。\n\n晚饭人均 45 元。\n\n加菜另算 10 元。\n\n会员再省 8 元。'
const planItems = Array.from({ length: 6 }, (_, index) => ({
  itemId: `item-${index + 1}`, cardId: `card-${index + 1}`, position: index + 1,
  role: index === 0 ? 'cover' : 'content', title: `第${index + 1}卡`,
  bullets: ['要点一'], caption: '', purpose: '', illustration: '暖光小店门头特写。',
  sourceBlockIds: ['b1'], criticalText: ['元'], layoutId: 'list', targetAspect: '3:4',
  placement: null, inputMediaRef: null,
}))
const planBody = (status) => ({
  id: 'plan-e2e-1', draftId: 'draft-e2e-1', status, revision: 1,
  confirmedRevision: status === 'ready' ? 1 : null,
  source: { id: 'source-e2e-1', contentHash: 'a'.repeat(64) },
  baseDraftVersion: 2, baseContentHash: 'b'.repeat(64), stale: false,
  document: status === 'ready' ? {
    recipe: { id: 'social-card-series', version: '1.0.0' }, strategy: 'information',
    style: { styleId: 'minimal-note', layoutId: 'list', paletteId: 'macaron' },
    items: planItems, explanation: '', uncoveredBlockIds: [],
  } : null,
  runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
})
const artifactOf = (itemId) => ({
  id: `art-${itemId}`, itemId, attemptId: `attempt-${itemId}`,
  plan: { id: 'plan-e2e-1', revision: 1 },
  originalMediaRef: { id: `media-orig-${itemId}`, refType: 'media' },
  deliveryMediaRef: { id: `media-del-${itemId}`, refType: 'media' },
  runId: `run-${itemId}`, width: 1080, height: 1440, contentHash: 'f'.repeat(64),
  anchorArtifactId: null, createdAt: '2026-09-13T00:00:00Z',
})
const jobSnapshot = (state, items) => ({
  id: 'job-e2e-1', requestId: 'req-1', draftId: 'draft-e2e-1',
  plan: { id: 'plan-e2e-1', revision: 1 }, state, version: 1, quoteId: 'quote-e2e-1',
  cancelRequested: false, createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:30Z',
  items: items.map((item) => ({
    attemptId: `attempt-${item.itemId}`, itemId: item.itemId,
    position: Number(item.itemId.split('-')[1]), state: item.state,
    runId: item.state === 'succeeded' ? `run-${item.itemId}` : null,
    error: item.state === 'failed' ? { code: 'PROVIDER_FAILED', message: '生成失败，详见错误码' } : null,
    artifact: item.state === 'succeeded' ? artifactOf(item.itemId) : null,
  })),
})
let jobQueue = [
  jobSnapshot('running', [
    { itemId: 'item-1', state: 'dispatching' }, { itemId: 'item-2', state: 'queued' },
    { itemId: 'item-3', state: 'queued' }, { itemId: 'item-4', state: 'queued' },
    { itemId: 'item-5', state: 'queued' }, { itemId: 'item-6', state: 'queued' },
  ]),
  jobSnapshot('partial', [
    { itemId: 'item-1', state: 'succeeded' }, { itemId: 'item-2', state: 'succeeded' },
    { itemId: 'item-3', state: 'failed' }, { itemId: 'item-4', state: 'succeeded' },
    { itemId: 'item-5', state: 'succeeded' }, { itemId: 'item-6', state: 'succeeded' },
  ]),
  jobSnapshot('succeeded', [
    { itemId: 'item-1', state: 'succeeded' }, { itemId: 'item-2', state: 'succeeded' },
    { itemId: 'item-3', state: 'succeeded' }, { itemId: 'item-4', state: 'succeeded' },
    { itemId: 'item-5', state: 'succeeded' }, { itemId: 'item-6', state: 'succeeded' },
  ]),
]
let lastJob = null
const adopted = { selections: [], mediaIds: [] }

function apiFixture(url, method, postData) {
  const path = new URL(url).pathname
  if (path === '/api/auth/me') {
    return { user: { id: 'acc-e2e', email: 'verify-101@test.invalid', displayName: '验收账号' } }
  }
  if (path === '/api/creation-drafts' && method === 'POST') {
    return { id: 'draft-e2e-1', title: '原稿到图卡', capability: 'article', status: 'in_progress', version: 2,
      platform: 'xiaohongshu', contentForm: 'graphic', topic: '门店三年', content: SOURCE_TEXT,
      resultAssetIds: [], runIds: [], updatedAt: '2026-09-13T00:00:00Z', workspace: { schemaVersion: 1, capability: 'article', inputs: {} } }
  }
  if (path.startsWith('/api/creation-drafts/') && method === 'PUT') {
    return { ...postData, version: (postData.expectedVersion ?? 2) + 1 }
  }
  if (path.startsWith('/api/creation-drafts/') && method === 'GET') {
    return {
      id: 'draft-e2e-1', title: '原稿到图卡', capability: 'article', status: 'in_progress', version: 9,
      platform: 'xiaohongshu', contentForm: 'graphic', topic: '门店三年', content: SOURCE_TEXT,
      resultAssetIds: [...adopted.mediaIds],
      runIds: adopted.mediaIds.map((id) => `run-${id.replace('media-del-', '')}`),
      updatedAt: '2026-09-13T00:01:00Z',
      workspace: {
        schemaVersion: 1, capability: 'article', currentStep: 'content',
        inputs: { studio: { schemaVersion: 1, recipe: { id: 'social-card-series', version: '1.0.0' },
          sourceDocumentId: 'source-e2e-1', visualPlan: { id: 'plan-e2e-1', revision: 1 },
          activeVisualJobId: 'job-e2e-1', lastProposalId: null, renderTheme: 'standard' } },
        resultRefs: adopted.mediaIds.map((id, index) => ({
          id, refType: 'media', role: index === 0 ? 'cover' : 'card', cardId: `card-${index + 1}`, position: index + 1 })),
        delivery: { version: 1, platform: 'xiaohongshu', contentForm: 'graphic',
          coverRef: adopted.mediaIds.length ? { id: adopted.mediaIds[0], refType: 'media', role: 'cover', cardId: 'card-1', position: 1 } : undefined,
          mediaRefs: adopted.mediaIds.map((id, index) => ({ id, refType: 'media', role: 'card', cardId: `card-${index + 1}`, position: index + 1 })) },
      },
    }
  }
  if (path === '/api/creation-studio/sources' && method === 'POST') {
    return { id: 'source-e2e-1', draftId: 'draft-e2e-1', schemaVersion: 1, kind: 'markdown', title: '',
      rawText: SOURCE_TEXT, normalizedMarkdown: SOURCE_TEXT, contentHash: 'a'.repeat(64),
      blocks: [], sourceRefs: [], warnings: [], createdAt: '2026-09-13T00:00:00Z' }
  }
  if (path === '/api/creation-studio/sources/source-e2e-1') {
    return apiFixture('http://fixture/api/creation-studio/sources', 'POST')
  }
  if (path === '/api/creation-studio/visual-plans' && method === 'POST') return planBody('preparing')
  if (path === '/api/creation-studio/visual-plans/plan-e2e-1' && method === 'GET') return planBody('ready')
  if (path === '/api/creation-studio/visual-plans/plan-e2e-1/confirm') return planBody('ready')
  if (path === '/api/creation-studio/visual-plans/plan-e2e-1/estimate') {
    return { id: 'quote-e2e-1', plan: { id: 'plan-e2e-1', revision: 1 },
      selectedItemIds: postData?.selectedItemIds ?? [], imageCalls: 6, consistencyMode: 'prompt-only',
      anchorArtifactId: null, userCredits: 0, platformBudgetCents: 180, billingSource: 'platform',
      pricingVersion: 'v1', configurationFingerprint: 'fixture', expiresAt: '2999-01-01T00:00:00Z', warnings: [] }
  }
  if (path === '/api/creation-studio/visual-jobs' && method === 'POST') {
    lastJob = jobQueue[0] ?? lastJob
    return lastJob
  }
  if (path === '/api/creation-studio/visual-jobs/job-e2e-1' && method === 'GET') {
    const head = jobQueue[0] ?? lastJob
    if (jobQueue.length > 1) jobQueue = jobQueue.slice(1)
    return head
  }
  if (path === '/api/creation-studio/visual-plans/plan-e2e-1/adopt') {
    for (const selection of postData.selections ?? []) {
      adopted.selections.push(selection)
      adopted.mediaIds.push(`media-del-${selection.itemId}`)
    }
    return { project: apiFixture('http://fixture/api/creation-drafts/x', 'GET'), appliedVersion: 9, alreadyApplied: false }
  }
  if (path.startsWith('/api/media/')) return { url: 'https://signed.test.invalid/delivery.jpg' }
  if (path === '/api/ai/creation/styles' || path.includes('/style-skills')) return { items: [] }
  return null
}

const browser = await chromium.launch({ headless: true })
const results = []
async function step(name, run) {
  try {
    await run()
    results.push({ name, status: 'PASS' })
  } catch (error) {
    results.push({ name, status: 'FAIL', detail: String(error).slice(0, 500) })
  }
}

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.route(/\/api\//, async (route) => {
    const method = route.request().method()
    const url = route.request().url()
    let postData
    try { postData = route.request().postDataJSON() } catch { postData = undefined }
    const data = apiFixture(url, method, postData)
    if (data != null) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
    } else {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, error: 'fixture 未覆盖 ' + new URL(url).pathname }) })
    }
  })

  await step('打开 AI 应用（本地 fixture，无真实请求）', async () => {
    await page.goto(baseUrl + '/')
    await page.waitForLoadState('domcontentloaded')
  })
  await step('小红书图文 + 从已有内容开始 → 进入正文阶段', async () => {
    await page.goto(baseUrl + '/')
    await page.getByRole('button', { name: '小红书' }).click({ timeout: 30_000 })
    await page.getByRole('button', { name: '图文', exact: true }).click()
    await page.getByRole('button', { name: '从已有内容开始' }).click()
    await page.getByRole('button', { name: '开始创作' }).click()
    await page.getByRole('textbox', { name: /原稿/ }).first().fill(SOURCE_TEXT)
    await page.getByRole('button', { name: /导入并编辑/ }).click()
    await page.getByTestId('studio-plan-launch').waitFor({ timeout: 30_000 })
  })
  await step('发起视觉策划 → 确认计划 → 费用确认 → 生成（慢态可见）', async () => {
    await page.getByTestId('studio-plan-launch').click()
    await page.getByTestId('plan-confirm').waitFor({ timeout: 30_000 })
    await page.getByTestId('plan-confirm').click()
    await page.getByTestId('visual-quote-start').click()
    await page.getByTestId('visual-cost-text').waitFor({ timeout: 30_000 })
    assert.match(await page.getByTestId('visual-cost-text').innerText(), /图片生成/)
    await page.screenshot({ path: resolve(output, '01-plan-cost.png') })
    await page.getByTestId('visual-cost-ok').click()
    await page.getByTestId('visual-item-1').waitFor({ timeout: 60_000 })
  })
  await step('部分成功：第 3 张失败、其余候选保留；重做后全成功', async () => {
    await page.getByTestId('visual-job-state').filter({ hasText: '部分成功' }).waitFor({ timeout: 60_000 })
    assert((await page.locator('[data-test^="visual-candidate-"]').count()) === 5)
    await page.screenshot({ path: resolve(output, '02-partial-redo.png') })
    await page.getByTestId('visual-redo-3').click()
    await page.getByTestId('visual-cost-ok').click({ timeout: 30_000 })
    await page.getByTestId('visual-job-state').filter({ hasText: '已成功' }).waitFor({ timeout: 60_000 })
    assert((await page.locator('[data-test^="visual-candidate-"]').count()) === 6)
  })
  await step('采用第 2 页 → 已采用标记；刷新恢复已采用媒体', async () => {
    await page.getByTestId('visual-candidate-2').getByTestId('visual-candidate-select').click()
    await page.getByTestId('visual-adopt').click()
    await page.getByTestId('visual-adopted-badge').waitFor({ timeout: 30_000 })
    assert.deepEqual(adopted.selections, [{ itemId: 'item-2', artifactId: 'art-2' }])
    await page.screenshot({ path: resolve(output, '03-adopted.png') })
    await page.reload()
    await page.getByTestId('card-series-panel').waitFor({ timeout: 60_000 })
    await page.getByTestId('visual-candidate-2').getByTestId('visual-candidate-select')
      .filter({ hasText: '已采用' }).waitFor({ timeout: 60_000 })
  })
  await page.close()
} finally {
  await browser.close()
}

const failed = results.filter((item) => item.status === 'FAIL')
for (const item of results) {
  console.log(`[${item.status}] ${item.name}${item.detail ? ' — ' + item.detail : ''}`)
}
await writeFile(resolve(output, 'results.json'), JSON.stringify({ phase, results }, null, 2))
console.log(failed.length ? `M1 验收失败：${failed.length}/${results.length} 步` : `M1 验收通过（${results.length} 步，截图在 ${output}）`)
process.exit(failed.length ? 1 : 0)
