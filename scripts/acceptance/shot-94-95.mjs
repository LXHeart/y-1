// 任务书 #94/#95 双主题截图自查（§8）：
//   #94 调整弹窗（默认/校验错误/402）× 明暗；运营队列（默认+分页/高危筛选）× 明暗 → test-artifacts/task-94/
//   #95 争议页签（列表/详情抽屉）× 明暗；finance 账号侧栏可见集 × 明暗 → test-artifacts/task-95/
// 用法：栈起好后 `node scripts/acceptance/shot-94-95.mjs`。登录走 /api/auth/login（cookie 会话），主题走 localStorage theme-preference。
import { chromium } from 'playwright'
import fs from 'node:fs'

const OPS = process.env.GRASS_OPS || 'http://127.0.0.1:8083'
const OUT94 = 'test-artifacts/task-94'
const OUT95 = 'test-artifacts/task-95'
const PASSWORD = 'test-password-2026'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

async function login(page, email) {
  await page.evaluate(async ([mail, pass]) => {
    await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: mail, password: pass }),
    })
  }, [email, PASSWORD])
  await page.reload({ waitUntil: 'domcontentloaded' })
}

async function newPage(browser, theme, path) {
  const context = await browser.newContext({ baseURL: OPS, viewport: { width: 1480, height: 950 } })
  await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
  const page = await context.newPage()
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  return { context, page }
}

async function main() {
  fs.mkdirSync(OUT94, { recursive: true })
  fs.mkdirSync(OUT95, { recursive: true })
  const browser = await chromium.launch()

  for (const theme of ['light', 'dark']) {
    // ---------- #94 调整弹窗 ----------
    {
      const { context, page } = await newPage(browser, theme, '/admin')
      await login(page, 'e2e-admin@test.local')
      await page.getByTestId('admin-group-users-org').click()
      await page.getByTestId('admin-tab-users').click()
      await page.locator('.adjust-btn').first().waitFor({ timeout: 20_000 })
      await page.locator('.adjust-btn').first().click()
      await page.locator('.modal-overlay').waitFor({ timeout: 10_000 })
      await page.screenshot({ path: `${OUT94}/ops-admin-adjust-default-${theme}.png` })

      // 校验错误：0 不发请求
      await page.locator('.modal-overlay input[type="number"]').fill('0')
      await page.locator('.modal-overlay .btn-confirm').click()
      const validation = await page.locator('.modal-overlay .error-msg').textContent().catch(() => null)
      ok(`#94 ${theme} 校验文案`, validation === '数量不能为 0', String(validation))
      await page.screenshot({ path: `${OUT94}/ops-admin-adjust-validation-${theme}.png` })

      // 402：0 余额扣巨额 → 透传文案
      await page.locator('.modal-overlay input[type="number"]').fill('-1000000')
      await page.locator('.modal-overlay input[type="text"]').first().fill('截图扣减演示')
      await page.locator('.modal-overlay .btn-confirm').click()
      await page.waitForTimeout(1500)
      const err402 = await page.locator('.modal-overlay .error-msg').textContent().catch(() => null)
      ok(`#94 ${theme} 402 文案`, err402 === '积分余额不足，无法扣减', String(err402))
      await page.screenshot({ path: `${OUT94}/ops-admin-adjust-402-${theme}.png` })
      await context.close()
    }

    // ---------- #94 运营队列（默认+分页 / 高危筛选） ----------
    {
      const { context, page } = await newPage(browser, theme, '/ops')
      await login(page, 'e2e-cs@test.local')
      await page.getByRole('tab', { name: '处置单' }).click()
      await page.locator('.ops-pager').waitFor({ timeout: 20_000 })
      const pagerText = await page.locator('.ops-pager').textContent()
      ok(`#94 ${theme} 分页条总量展示`, /共\s*\d+\s*条/.test(pagerText || ''), String(pagerText))
      await page.screenshot({ path: `${OUT94}/ops-queue-paged-${theme}.png` })

      await page.locator('.ops-check input[type="checkbox"]').check()
      await page.waitForTimeout(1200)
      const highUrl = await page.evaluate(() => performance.getEntriesByType('resource')
        .some((e) => e.name.includes('severity=high')))
      ok(`#94 ${theme} 高危筛选服务端下推`, highUrl)
      await page.screenshot({ path: `${OUT94}/ops-queue-highrisk-${theme}.png` })
      await context.close()
    }

    // ---------- #95 争议页签（列表 + 详情抽屉） ----------
    {
      const { context, page } = await newPage(browser, theme, '/ops')
      await login(page, 'e2e-cs@test.local')
      await page.getByRole('tab', { name: '争议队列' }).click()
      await page.locator('.ops-table').first().waitFor({ timeout: 20_000 })
      // v-show 常驻子面板使 .ops-panel 命中多个——取可见的那个（争议面板）
      const listText = await page.locator('.ops-panel:visible').first().textContent()
      ok(`#95 ${theme} 争议列表渲染（premium 徽标）`, (listText || '').includes('premium'))
      await page.screenshot({ path: `${OUT95}/ops-disputes-list-${theme}.png` })

      await page.locator('.ops-table .ops-quiet').first().click()
      await page.locator('[aria-label="争议详情"]').waitFor({ timeout: 10_000 })
      const drawerText = await page.locator('[aria-label="争议详情"]').textContent()
      ok(`#95 ${theme} 详情抽屉脱敏`, (drawerText || '').includes('participant-')
        && !(drawerText || '').includes('13812345678'))
      await page.screenshot({ path: `${OUT95}/ops-disputes-detail-${theme}.png` })
      await context.close()
    }

    // ---------- #95 角色矩阵（finance 账号侧栏） ----------
    {
      const { context, page } = await newPage(browser, theme, '/admin')
      await login(page, 'finance-shot@test.local')
      await page.locator('[data-testid^="admin-group-"]').first().waitFor({ timeout: 20_000 })
      const groups = await page.locator('[data-testid^="admin-group-"]').allTextContents()
      const tabs = await page.locator('[data-testid^="admin-tab-"]').allTextContents()
      ok(`#95 ${theme} finance 仅见交易与财务组`,
        groups.length === 1 && groups[0].includes('交易与财务'), JSON.stringify(groups))
      ok(`#95 ${theme} finance 仅见财务对账页签`,
        tabs.length === 1 && tabs[0].includes('财务对账'), JSON.stringify(tabs))
      await page.screenshot({ path: `${OUT95}/role-finance-sidebar-${theme}.png` })
      await context.close()
    }
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
