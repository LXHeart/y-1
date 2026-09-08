import { chromium } from 'playwright'
const PASSWORD = 'test-password-2026'
const browser = await chromium.launch()
const ctx = await browser.newContext({ baseURL: 'http://127.0.0.1:8080' })
const page = await ctx.newPage()
await page.goto('/', { waitUntil: 'domcontentloaded' })
const out = await page.evaluate(async ([mail, pass]) => {
  const lines = []
  const j = async (label, res) => lines.push(label + ' ' + res.status + ' ' + (await res.text()).slice(0, 300))
  await j('login', await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: mail, password: pass }) }))
  await j('activate', await fetch('/api/me/active-identity', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'merchant' }) }))
  await j('active', await fetch('/api/me/active-identity'))
  await j('apps', await fetch('/api/tasks/f0661310-2aa1-45b5-878f-1889577aee9e/applications'))
  await j('accept', await fetch('/api/tasks/f0661310-2aa1-45b5-878f-1889577aee9e/applications/f27da72d-18ea-4454-ad1b-82ff43bcc2d6/accept', { method: 'POST' }))
  return lines.join('\n')
}, ['e2e-merchant75@test.local', PASSWORD])
console.log(out)
await browser.close()
