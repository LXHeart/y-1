import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright'

// Browser-local fixtures only: no request is sent to a live business service.
const baseUrl = process.env.OPS_BASE_URL || 'http://127.0.0.1:5174'
const output = resolve('test-artifacts/ops-layout')
const users = Array.from({ length: 8 }, (_, index) => ({
  id: `sample-account-${index + 1}`, email: `member${index + 1}@example.com`,
  displayName: ['林晓', '陈嘉', '周乐', '许宁', '苏禾', '赵悦', '沈青', '江远'][index],
  role: 'user', roles: index === 1 ? ['content_reviewer'] : [],
  status: index === 3 ? 'suspended' : 'active', createdAt: '2026-09-04T08:30:00Z',
  balance: 1200 - index * 100, totalEarned: 1600, totalSpent: 400 + index * 100,
  identities: { merchant: index % 2 === 0, recommender: index % 2 === 1, member: false,
    ownedOrgNames: index % 2 === 0 ? '青禾餐饮' : null, ownedOrgs: [] },
}))
const kyb = Array.from({ length: 6 }, (_, index) => ({
  id: `sample-request-${index}`, organizationId: `org-qinghe-${index + 1}`, requesterAccountId: 'sample-owner',
  verificationType: ['merchant_profile', 'store_profile', 'withdrawal_account'][index % 3],
  targetId: `subject-${index + 1}`, status: 'pending',
  createdAt: '2026-09-07T01:30:00Z', reviewDeadline: '2026-09-08T01:30:00Z',
}))
const tasks = Array.from({ length: 5 }, (_, index) => ({
  id: `sample-task-${index}`, title: ['青禾门店秋季新品探店', '午间双人套餐推广', '周末咖啡体验招募', '新品体验内容征集', '门店开业内容推广'][index],
  platform: index % 2 ? 'douyin' : 'xhs', bountyCents: 15000 + index * 5000,
  organizationId: `org-qinghe-${index + 1}`, status: 'pending_review', version: 1,
}))
function paged(items, url) {
  return { items, total: items.length, limit: Number(url.searchParams.get('limit') || 10), offset: 0 }
}
function fixture(url) {
  const path = url.pathname
  if (path === '/api/auth/me') return { user: { id: 'sample-admin', email: 'ops@example.com', displayName: '平台运营', role: 'admin', roles: ['platform_admin'] } }
  if (path === '/api/admin/users') return paged(users, url)
  if (path === '/api/admin/kyb-requests') return paged(kyb, url)
  if (path === '/api/admin/tasks/review/stats') return { pending: 5, overdue: 1, approvedLast24Hours: 24, rejectedLast24Hours: 3 }
  if (path === '/api/admin/tasks/review') return paged(tasks, url)
  if (path === '/api/admin/recommender-requests') return paged(users.slice(0, 4).map((user) => ({
    id: user.id, accountId: user.id, materials: '美食内容创作者，已提交主页与作品记录', status: 'pending',
    createdAt: '2026-09-07T02:00:00Z', reviewDeadline: '2026-09-09T02:00:00Z',
  })), url)
  if (path.endsWith('/journals')) return paged(tasks.map((task, index) => ({
    id: task.id, type: ['DEPOSIT', 'RESERVE', 'CAPTURE', 'CONSUMER_PAYMENT', 'WITHDRAW'][index],
    organizationId: task.organizationId, engagementRef: `engagement-${index}`, currency: 'CNY',
    operationId: `sample-operation-${index}`, memo: '门店业务流水', createdAt: '2026-09-07T03:20:00Z',
  })), url)
  if (path === '/api/ops/cases') return tasks.slice(0, 3).map((task, index) => ({
    id: task.id, sourceKind: index === 0 ? 'settlement_blocked' : 'settlement_held', sourceRef: `event-${index}`,
    organizationId: task.organizationId, reason: index === 0 ? 'finance_blocked' : 'open_dispute',
    severity: index === 0 ? 'high' : 'normal', status: ['open', 'in_review', 'approved'][index],
    version: 1, createdAt: '2026-09-07T02:00:00Z',
  }))
  throw new Error(`Missing fixture for ${path}`)
}

await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const results = []
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
    for (const theme of ['light', 'dark']) {
      const context = await browser.newContext({ viewport, colorScheme: theme })
      await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', (error) => errors.push(error.message))
      await page.route('**/api/**', async (route) => {
        assert.equal(route.request().method(), 'GET', 'Layout checks must not submit business actions')
        try {
          await route.fulfill({ json: { success: true, data: fixture(new URL(route.request().url())) } })
        } catch (error) {
          errors.push(error.message)
          await route.fulfill({ status: 500, json: { success: false, error: error.message } })
        }
      })
      for (const section of ['kyb', 'users', 'tasks', 'recommenders', 'finance', 'ops']) {
        await page.goto(`${baseUrl}/${section === 'ops' ? 'ops' : `admin?section=${section}`}`)
        await page.locator(section === 'ops' ? '.ops-table tbody tr' : '.user-table tbody tr').first().waitFor()
        await page.evaluate(() => document.fonts.ready)
        const overflow = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }))
        assert.ok(overflow.scroll <= overflow.width + 1, `${section} overflows: ${JSON.stringify(overflow)}`)
        assert.deepEqual(errors, [], `${section} browser errors`)
        const filename = `${section}-${theme}-${viewport.width}.png`
        await page.screenshot({ path: resolve(output, filename), fullPage: true })
        if (viewport.width < 768 && section === 'kyb') {
          await page.getByRole('button', { name: '展开管理导航' }).click()
          await page.getByRole('dialog', { name: '管理导航' }).waitFor()
          await page.screenshot({ path: resolve(output, `sidebar-${theme}-${viewport.width}.png`) })
          await page.keyboard.press('Escape')
          assert.equal(await page.getByRole('dialog', { name: '管理导航' }).count(), 0)
        }
        results.push({ section, theme, viewport: viewport.width, filename, overflow: false })
        console.log(`PASS ${filename}`)
      }
      await context.close()
    }
  }
  await writeFile(resolve(output, 'results.json'), JSON.stringify(results, null, 2))
} finally {
  await browser.close()
}
