// @vitest-environment node
/**
 * 任务书 #106 C106-06（D06 / TC106-06-01～05）：三引擎真实双页 cache/轮询 harness。
 *
 * - 同源两个真实 Page（同一 context）：独立 sessionStorage、共享 localStorage、真实
 *   BroadcastChannel/storage 事件；禁用 BC 变体走真实 storage 回退。
 * - 只通过真实 DOM 按钮驱动生产模块（page.evaluate 仅读取计数/存储快照——观察而非替代）。
 * - 隐藏为真实页面可见性（bringToFront 切换），以 document.hidden 实测为准，不改 getter。
 * - 资金请求经真实浏览器网络，route 延迟/失败由本 spec 控制；真实时钟推进 5s 轮询节拍。
 */
import { expect, test, type Page, type Route } from '@playwright/test'

const HARNESS = '/tests/e2e/fixtures/task-106-harness.html'

interface FundsBody {
  operationId: string
  kind: string
  state: string
  amounts: { deposit_refundCents: number; bounty_releaseCents: number }
  blockedReason: string
  updatedAt: string
}

function fundsBody(state: string): FundsBody {
  return {
    operationId: `op-${state}`,
    kind: 'no_fault',
    state,
    amounts: { deposit_refundCents: 100, bounty_releaseCents: 500 },
    blockedReason: state === 'needs_review' ? 'funds_reconciliation_required' : 'funds_pending',
    updatedAt: '2026-09-22T00:00:00Z',
  }
}

async function openHarness(page: Page): Promise<void> {
  await page.goto(HARNESS)
  await page.waitForSelector('[data-testid=login]')
}

async function setInputs(page: Page, values: Record<string, string>): Promise<void> {
  for (const [testid, value] of Object.entries(values)) {
    await page.fill(`[data-testid=${testid}]`, value)
  }
}

test.describe('TC106-06 · cache 双页', () => {
  test('TC106-06-01：各引擎同源两真实 Page——A 页清理、B 页一次失效、他账号/主题保留', async ({ browser }) => {
    const context = await browser.newContext()
    const pageA = await context.newPage()
    const pageB = await context.newPage()
    await openHarness(pageA)
    await openHarness(pageB)

    // B 页提前激活（早期订阅，清理前无需先清一次）。
    await setInputs(pageB, { 'account-input': 'acct-a' })
    await pageB.click('[data-testid=activate]')
    // 两页各写各的 session 私有值并登记。
    await setInputs(pageA, { 'account-input': 'acct-a', 'key-input': 'video-canvas-bind:acct-a:1:s1:d1', 'value-input': 'a1' })
    await pageA.click('[data-testid=write]')
    await pageA.click('[data-testid=register]')
    await setInputs(pageB, { 'key-input': 'video-canvas-bind:acct-a:2:s2:d2', 'value-input': 'b2' })
    await pageB.click('[data-testid=write]')
    await pageB.click('[data-testid=register]')

    await pageA.selectOption('[data-testid=area-select]', 'local')
    await setInputs(pageA, { 'key-input': 'subtitle-cues-acct-a:shared', 'value-input': 'shared-private' })
    await pageA.click('[data-testid=write]')
    await pageA.click('[data-testid=register]')
    expect(await pageB.evaluate(() => localStorage.getItem('subtitle-cues-acct-a:shared'))).toBe('shared-private')

    // A 页主动清理：真实 BC 广播到 B 页。
    await pageA.click('[data-testid=clear]')
    await expect(pageA.locator('[data-testid=last-cleared]')).toHaveText('lastCleared=2')

    // 两页私有值都清（B 页经真实通知异步清理）。
    await expect.poll(() => pageA.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-a:1:s1:d1'))).toBeNull()
    await expect.poll(() => pageB.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-a:2:s2:d2'))).toBeNull()
    await expect.poll(() => pageB.evaluate(() => localStorage.getItem('subtitle-cues-acct-a:shared'))).toBeNull()
    // B 页失效回调恰好一次。
    await expect.poll(() => pageB.locator('[data-testid=invalidations]').textContent()).toContain('invalidations=1')
    // 其他账号值与主题保留（清理不外溢）。
    expect(await pageA.evaluate(() => localStorage.getItem('theme'))).toBe('light')
    expect(await pageA.evaluate(() => localStorage.getItem('subtitle-cues-acct-other:seed'))).toBe('[]')
    await context.close()
  })

  test('TC106-06-02：禁用 BroadcastChannel——真实 storage 事件回退同结果，重复清理只失效一次', async ({ browser }) => {
    const context = await browser.newContext()
    await context.addInitScript(() => {
      Object.defineProperty(window, 'BroadcastChannel', { configurable: true, value: undefined })
    })
    const pageA = await context.newPage()
    const pageB = await context.newPage()
    await openHarness(pageA)
    await openHarness(pageB)

    await setInputs(pageB, { 'account-input': 'acct-a' })
    await pageB.click('[data-testid=activate]')
    await setInputs(pageB, { 'key-input': 'video-canvas-bind:acct-a:5:s5:d5', 'value-input': 'fallback' })
    await pageB.click('[data-testid=write]')
    await pageB.click('[data-testid=register]')

    // A 页两次主动清理（不同操作）：B 页经真实 storage 事件清理；会话已失效后不再二次回调。
    await setInputs(pageA, { 'account-input': 'acct-a' })
    await pageA.click('[data-testid=clear]')
    await expect.poll(() => pageB.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-a:5:s5:d5'))).toBeNull()
    await expect.poll(() => pageB.locator('[data-testid=invalidations]').textContent()).toContain('invalidations=1')
    await pageA.click('[data-testid=clear]')
    await pageB.waitForTimeout(500)
    expect(await pageB.textContent('[data-testid=invalidations]')).toContain('invalidations=1')
    expect(await pageA.evaluate(() => localStorage.getItem('subtitle-cues-acct-other:seed'))).toBe('[]')
    await context.close()
  })

  test('TC106-06-03：换号迟到写回收、当前代次旧通知不失效、1001 键真实计数', async ({ browser }) => {
    const context = await browser.newContext()
    const pageA = await context.newPage()
    await openHarness(pageA)

    // 1001 个 session 键登记与清理（真实计数，不截断）。
    await setInputs(pageA, { 'account-input': 'acct-a' })
    await pageA.click('[data-testid=activate]')
    await setInputs(pageA, { 'bulk-input': '1001' })
    await pageA.click('[data-testid=bulk-register]')
    await pageA.click('[data-testid=clear]')
    await expect(pageA.locator('[data-testid=last-cleared]')).toHaveText('lastCleared=1001')
    const tombstone = await pageA.evaluate(account => localStorage.getItem(`grassland:apc:gen:${JSON.stringify([account])}`), 'acct-a')
    expect(tombstone).toBeTruthy()

    // 换号后 A 迟到写+登记：值回收，B 会话不被夺。
    await setInputs(pageA, { 'account-input': 'acct-b' })
    await pageA.click('[data-testid=activate]')
    await setInputs(pageA, { 'key-input': 'video-canvas-bind:acct-a:9:s9:d9', 'value-input': 'late-a', 'other-account-input': 'acct-a' })
    await pageA.click('[data-testid=write]')
    await pageA.click('[data-testid=register-as]')
    expect(await pageA.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-a:9:s9:d9'))).toBeNull()
    // B 的值正常登记。
    await setInputs(pageA, { 'key-input': 'video-canvas-bind:acct-b:1:s1:d1', 'value-input': 'b1' })
    await pageA.click('[data-testid=write]')
    await pageA.click('[data-testid=register]')
    expect(await pageA.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-b:1:s1:d1'))).toBe('b1')

    // 重新激活 A（捕获当前墓碑）+ 当前代次新值；经真实 BC 投递不同的旧 operationId（迟到乱序）：
    // 当前代次会话不失效、新值保留。
    await setInputs(pageA, { 'account-input': 'acct-a', 'other-account-input': 'acct-a' })
    await pageA.click('[data-testid=activate]')
    await setInputs(pageA, { 'key-input': 'video-canvas-bind:acct-a:2:s2:d2', 'value-input': 'fresh-a' })
    await pageA.click('[data-testid=write]')
    await pageA.click('[data-testid=register]')
    const invalidationsBefore = await pageA.textContent('[data-testid=invalidations]')
    await setInputs(pageA, { 'op-input': 'older-than-' + tombstone! })
    await pageA.click('[data-testid=bc-post]')
    await pageA.waitForTimeout(800)
    expect(await pageA.textContent('[data-testid=invalidations]')).toBe(invalidationsBefore)
    expect(await pageA.evaluate(() => sessionStorage.getItem('video-canvas-bind:acct-a:2:s2:d2'))).toBe('fresh-a')
    await context.close()
  })
})

test.describe('TC106-06 · 资金轮询真实组件', () => {
  test('TC106-06-04：KeepAlive 失活/卸载/晚回包/恢复全真实', async ({ browser }) => {
    const context = await browser.newContext()
    const pendingRoutes: Array<{ url: string; route: Route }> = []
    await context.route('**/api/tasks/*/applications/*/exit-funds', async (route) => {
      pendingRoutes.push({ url: route.request().url(), route })
    })
    const fulfill = async (index: number, state: string): Promise<void> => {
      const entry = pendingRoutes[index]
      if (!entry) throw new Error(`route ${index} 不存在`)
      try {
        await entry.route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: fundsBody(state) }) })
      } catch {
        // 目标已切换时该请求已被 abort：放行无处可去，忽略。
      }
    }
    const fundsRequests = (page: Page): Promise<number> =>
      page.evaluate(() => (window as unknown as { __task106: { state: { fundsRequests: number } } }).__task106.state.fundsRequests)

    const pageA = await context.newPage()
    await openHarness(pageA)
    await setInputs(pageA, { 'account-input': 'acct-a' })
    await pageA.click('[data-testid=login]')

    // task-1 首查：请求挂起（route 不放行）。
    await setInputs(pageA, { 'funds-task-input': 'task-1', 'funds-app-input': 'app-1' })
    await pageA.click('[data-testid=funds-target]')
    await expect.poll(() => fundsRequests(pageA)).toBe(1)

    // ---- KeepAlive 失活（真实组件路径，与 hidden 共用同一生产门闸 activity.isActive）----
    await setInputs(pageA, { 'funds-task-input': 'task-3', 'funds-app-input': 'app-3' })
    await pageA.click('[data-testid=funds-toggle-show]')  // 失活：先切目标再断言 0 请求
    const requestsBeforeDeactivated = await fundsRequests(pageA)
    await pageA.click('[data-testid=funds-target]')
    expect(await fundsRequests(pageA)).toBe(requestsBeforeDeactivated)
    await pageA.click('[data-testid=funds-toggle-show]')  // 重新激活：立即只查最新一次
    await expect.poll(() => fundsRequests(pageA)).toBe(requestsBeforeDeactivated + 1)
    const urlsAfterReactivate = await pageA.evaluate(() => (window as unknown as { __task106: { state: { fundsUrls: string[] } } }).__task106.state.fundsUrls)
    expect(urlsAfterReactivate[urlsAfterReactivate.length - 1]).toContain('task-3')

    // ---- 晚回包：更早目标的挂起响应此刻才放行——不回写旧目标状态。----
    await fulfill(0, 'succeeded')
    await pageA.waitForTimeout(400)
    expect(await pageA.textContent('[data-testid=funds-state]')).toBe('state=null')
    // 最新请求落地 pending，进入 5s 轮询节拍。
    const latestIndex = (await fundsRequests(pageA)) - 1
    await fulfill(latestIndex, 'pending')
    await expect(pageA.locator('[data-testid=funds-state]')).toHaveText('state=pending')

    // ---- 卸载（KeepAlive 外层整体移除）：推进真实时钟越过 5s——不重建、不再请求。----
    await pageA.click('[data-testid=funds-toggle-mount]')
    await expect(pageA.locator('[data-testid=funds-unmounted]')).toBeVisible()
    const beforeUnmount = await fundsRequests(pageA)
    await pageA.waitForTimeout(6_000)
    expect(await fundsRequests(pageA)).toBe(beforeUnmount)
    await context.close()
  })

  test('TC106-06-04-hidden：真实 document.hidden 必须成立，工具不支持也不能假绿', async ({ browser }, testInfo) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    const foreground = await context.newPage()
    await context.route('**/api/tasks/*/applications/*/exit-funds', route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: fundsBody('pending') }),
    }))
    try {
      await openHarness(page)
      await openHarness(foreground)
      await page.click('[data-testid=login]')
      await page.click('[data-testid=funds-target]')
      await expect(page.locator('[data-testid=funds-state]')).toHaveText('state=pending')
      await foreground.bringToFront()
      await expect.poll(() => page.evaluate(() => document.hidden), {
        timeout: 3000,
        message: '真实隐藏不可达时必须失败并保留 PARTIAL，不能用注解冒充通过',
      }).toBe(true)
      const requests = () => page.evaluate(() =>
        (window as unknown as { __task106: { state: { fundsRequests: number } } }).__task106.state.fundsRequests)
      const before = await requests()
      // DOM 事件调用已装配的真实按钮；避免自动化 click 把后台页重新置前。
      await page.evaluate(() => {
        const input = document.querySelector<HTMLInputElement>('[data-testid=funds-task-input]')!
        input.value = 'hidden-latest'
        input.dispatchEvent(new Event('input', { bubbles: true }))
        document.querySelector<HTMLButtonElement>('[data-testid=funds-target]')!.click()
        document.querySelector<HTMLButtonElement>('[data-testid=funds-refresh]')!.click()
      })
      expect(await page.evaluate(() => document.hidden)).toBe(true)
      await page.waitForTimeout(5500)
      expect(await requests()).toBe(before)
      await page.bringToFront()
      await expect.poll(() => page.evaluate(() => document.hidden)).toBe(false)
      await expect.poll(requests).toBe(before + 1)
    } finally {
      await testInfo.attach('visibility-observation', { body: JSON.stringify({
        engine: testInfo.project.name, hidden: await page.evaluate(() => document.hidden),
      }), contentType: 'application/json' })
      await context.close()
    }
  })

  test('TC106-06-05：pending 后手动查询失败撤销续排（真实时钟 5s）；恢复后重新续排', async ({ browser }) => {
    const context = await browser.newContext()
    let requestIndex = 0
    await context.route('**/api/tasks/*/applications/*/exit-funds', async (route) => {
      const index = requestIndex
      requestIndex += 1
      if (index === 0) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: fundsBody('pending') }) })
      } else if (index === 1) {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: fundsBody('processing') }) })
      }
    })
    const page = await context.newPage()
    await openHarness(page)
    await setInputs(page, { 'account-input': 'acct-a' })
    await page.click('[data-testid=login]')
    await page.click('[data-testid=funds-target]')
    await expect(page.locator('[data-testid=funds-state]')).toHaveText('state=pending')

    // 5 秒内手动 refresh（请求 2 失败）：旧续排 timer 必须撤销。
    await page.click('[data-testid=funds-refresh]')
    const fundsRequests = () => page.evaluate(() => (window as unknown as { __task106: { state: { fundsRequests: number } } }).__task106.state.fundsRequests)
    expect(await fundsRequests()).toBe(2)
    await expect(page.locator('[data-testid=funds-error]')).not.toHaveText('error=')
    // 真实时钟越过 5s：失败后无第三次自动请求。
    await page.waitForTimeout(5_600)
    expect(await fundsRequests()).toBe(2)

    // 手动恢复：再次查询成功并恢复续排。
    await page.click('[data-testid=funds-refresh]')
    await expect(page.locator('[data-testid=funds-state]')).toHaveText('state=processing')
    await expect.poll(() => fundsRequests(), { timeout: 8_000 }).toBeGreaterThanOrEqual(4)
    await context.close()
  })
})
