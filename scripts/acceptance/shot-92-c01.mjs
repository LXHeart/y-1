// 任务书 #92 C-01 双主题截图：AI 创作中心来源胶囊条（能力 chip + 门店/任务来源 + 清除按钮）。
// 用法：npm run dev 后 `node scripts/acceptance/shot-92-c01.mjs`。截图写 docs/任务书/evidence/92/（§8.8）。
// dev 无后端：会话/门店档案请求失败属预期——胶囊回退 ID 截断态正是本卡边界行为。
import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = process.env.GRASS_BASE || 'http://localhost:5173'
const OUT = 'docs/任务书/evidence/92'

const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } })
    // 主题靠 localStorage theme-preference（直接改 DOM 属性会被主题 store 竞态翻回）
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    const page = await context.newPage()

    // 深链：能力=video + 任务来源（fixture ID，无后端 → ID 截断态展示）
    await page.goto(`/ai.html?capability=video&taskId=demo-task-92`)
    const chip = page.locator('[data-testid="capability-chip"]')
    await chip.waitFor({ timeout: 15_000 })
    ok(`C-01 ${theme}：能力 chip`, (await chip.textContent()) === '视频')
    const pill = page.locator('[data-testid="source-pill"]')
    ok(`C-01 ${theme}：来源胶囊`, (await pill.textContent()).includes('任务：demo-task-92'))
    ok(`C-01 ${theme}：清除按钮可聚焦`, await page.locator('[data-testid="source-clear"]').isVisible())
    await page.screenshot({ path: `${OUT}/c01-ai-center-${theme}.png`, fullPage: false })

    // 清除后：来源消失、能力保留（截图留存交互后状态）
    await page.locator('[data-testid="source-clear"]').click()
    await page.locator('[data-testid="source-pill"]').waitFor({ state: 'detached', timeout: 5_000 })
    ok(`C-01 ${theme}：清除后仅移除来源`, (await chip.textContent()) === '视频')
    await page.screenshot({ path: `${OUT}/c01-ai-center-cleared-${theme}.png`, fullPage: false })
    await context.close()
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
