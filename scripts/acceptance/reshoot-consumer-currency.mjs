// ¥ 符号修复复验：消费订单页明暗两主题截图 + 断言 DOM 无「¥¥」双符号。
// 前置：scripts/acceptance/shot-review-batch1.mjs 已跑过（review-consumer 账号与其订单已存在）、栈已起。
import { chromium } from 'playwright'

const WEB = 'http://127.0.0.1:8080'
const OUT = 'test-artifacts/review-batch1'
const PASSWORD = 'test-password-2026'

const browser = await chromium.launch()
let fail = 0
for (const theme of ['light', 'dark']) {
  const context = await browser.newContext({ baseURL: WEB, viewport: { width: 1480, height: 950 } })
  await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
  const page = await context.newPage()
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await page.evaluate(async ([mail, pass]) => {
    await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: mail, password: pass }),
    })
  }, ['review-consumer@test.local', PASSWORD])
  await page.goto('/commerce', { waitUntil: 'domcontentloaded' })
  const card = page.locator('.order-card').first()
  await card.waitFor({ timeout: 15_000 })
  const bodyText = await page.locator('body').innerText()
  const doubled = bodyText.includes('¥¥')
  const hasAmount = /¥\d+\.\d{2}/.test(bodyText)
  console.log(`${doubled || !hasAmount ? 'FAIL' : 'PASS'}  ${theme} 无¥¥=${!doubled} 单符号金额存在=${hasAmount}`)
  if (doubled || !hasAmount) fail++
  await page.screenshot({ path: `${OUT}/consumer-currency-fixed-${theme}.png`, fullPage: true })
  await context.close()
}
await browser.close()
process.exit(fail ? 1 : 0)
