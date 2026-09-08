// 业务审查 2026-09-07 第一批落地冒烟 + 双主题截图（C01 归因申诉/运营纠错）：
//   用户端 /commerce：申诉表单 + 申诉进度盒 × 明暗 → test-artifacts/review-batch1/
//   治理台 /admin 订单核销页签：归因申诉队列 × 明暗
// 用法：栈起好后 `node scripts/acceptance/shot-review-batch1.mjs`。账号直插 DB（同 e2e-seed 手法）。
import { chromium } from 'playwright'
import bcrypt from 'bcryptjs'
import { execSync } from 'node:child_process'
import fs from 'node:fs'

const WEB = 'http://127.0.0.1:8080'
const OPS = 'http://127.0.0.1:8083'
const OUT = 'test-artifacts/review-batch1'
const PASSWORD = 'test-password-2026'
const results = []
const ok = (name, cond, detail = '') => {
  results.push({ name, pass: Boolean(cond), detail })
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

/** SQL 走 stdin（docker exec 引号层会吃换行/转义，-i 管道最稳）。 */
const psql = (sql) => execSync(
  "docker exec -i y-1-postgres-local-1 psql -U grassland -d grassland -tA",
  { encoding: 'utf8', input: sql }).trim()

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  // ---------- 账号与组织（幂等直插，密码同 e2e 口令） ----------
  const hash = bcrypt.hashSync(PASSWORD, 10)
  const ensureUser = (email) => psql(
    `INSERT INTO app_users(id, email, password_hash, status, role)
     VALUES (gen_random_uuid(), '${email}', '${hash}', 'active', 'user')
     ON CONFLICT (email) DO UPDATE SET status='active' RETURNING id`)
    .split('\n').map((l) => l.trim()).find((l) => /^[0-9a-f-]{36}$/.test(l))
  const consumerId = ensureUser('review-consumer@test.local')
  const recommenderId = ensureUser('review-recommender@test.local')
  // 推荐官身份（apply 端点需要 identity_profile 行）
  psql(`INSERT INTO identity_profile(id, account_id, identity_type, status)
     VALUES (gen_random_uuid(), '${recommenderId}', 'recommender', 'active')
     ON CONFLICT DO NOTHING`)
  // 商家用 #75 冒烟专号（单组织、finance_transaction、无身份漂移）
  const merchantEmail = 'e2e-merchant75@test.local'
  const merchantId = psql(`SELECT id FROM app_users WHERE email='${merchantEmail}'`)
  const orgId = psql(
    `SELECT o.id FROM organization o JOIN app_users u ON u.id=o.owner_account_id WHERE u.email='${merchantEmail}'`)
  const adminEmail = 'e2e-admin@test.local'
  if (!merchantId || !orgId) throw new Error(`seed 缺失 merchant=${merchantId} org=${orgId}，先跑 npm run e2e:seed`)
  console.log(`consumer=${consumerId} recommender=${recommenderId} org=${orgId}`)
  // 该组织若无门店则补一个（任务创建校验门店存在），并给商家门店成员行（accept 走门店级授权）
  psql(`INSERT INTO store(id, organization_id, name, status)
     SELECT gen_random_uuid(), '${orgId}', '评审冒烟门店', 'active'
     WHERE NOT EXISTS (SELECT 1 FROM store WHERE organization_id='${orgId}' AND deleted_at IS NULL)`)
  psql(`INSERT INTO store_membership(id, store_id, account_id, role)
     SELECT gen_random_uuid(), s.id, '${merchantId}', 'owner'
     FROM store s
     WHERE s.organization_id='${orgId}' AND s.deleted_at IS NULL
       AND NOT EXISTS (SELECT 1 FROM store_membership m WHERE m.store_id = s.id AND m.account_id = '${merchantId}')`)
  // 清掉本冒烟消费者旧订单（保证 UI 单卡片、按最新订单截图）
  psql(`DELETE FROM consumer_order_attribution_appeal WHERE order_id IN
     (SELECT id FROM consumer_order WHERE consumer_account_id='${consumerId}')`)
  psql(`DELETE FROM consumer_order WHERE consumer_account_id='${consumerId}'`)

  const browser = await chromium.launch()
  const newPage = async (base, theme, path) => {
    const context = await browser.newContext({ baseURL: base, viewport: { width: 1480, height: 950 } })
    await context.addInitScript((mode) => localStorage.setItem('theme-preference', mode), theme)
    const page = await context.newPage()
    await page.goto(path, { waitUntil: 'domcontentloaded' })
    return { context, page }
  }
  const login = async (page, email, identityType) => {
    await page.evaluate(async ([mail, pass, type]) => {
      await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: mail, password: pass }),
      })
      if (type) {
        await fetch('/api/me/active-identity', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type }),
        })
      }
    }, [email, PASSWORD, identityType])
    await page.reload({ waitUntil: 'domcontentloaded' })
  }
  const api = async (page, method, path, body) => page.evaluate(async ([m, p, b]) => {
    const res = await fetch(p, {
      method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined,
    })
    return { status: res.status, json: await res.json().catch(() => null) }
  }, [method, path, body])

  // ---------- 业务流：固定佣套餐 → 推广任务 → 接单 → 下单 → 申诉 ----------
  let packageId, taskId, orderId
  {
    const { context, page } = await newPage(WEB, 'light', '/')
    await login(page, merchantEmail, 'merchant')
    const offerBody = {
      organizationId: orgId, title: '评审冒烟双人套餐', description: 'C01 申诉冒烟',
      priceCents: 2000, totalStock: 5, validDaysAfterPurchase: 30, platformFeeBps: 500,
      recommenderFixedCents: 500, recommenderShareBps: 0, policyVersion: 'commerce-v1',
    }
    const created = await api(page, 'POST', '/api/v2/merchant/packages', offerBody)
    packageId = created.json?.data?.id
    ok('套餐创建（固定佣 500）', created.status === 201 && !!packageId, JSON.stringify(created.json)?.slice(0, 120))
    await api(page, 'POST', `/api/v2/merchant/packages/${packageId}/publish`)

    const task = await api(page, 'POST', '/api/tasks', {
      organizationId: orgId, title: '评审冒烟推广任务', platform: 'xiaohongshu',
      storeId: psql(`SELECT id FROM store WHERE organization_id='${orgId}' LIMIT 1`)
        || '00000000-0000-0000-0000-000000000001',
      applicationDeadline: new Date(Date.now() + 3600_000).toISOString(),
      commercePackageId: packageId,
    })
    taskId = task.json?.data?.id
    ok('推广任务创建', task.status === 201 && !!taskId, JSON.stringify(task.json)?.slice(0, 160))
    await context.close()
  }
  {
    const { context, page } = await newPage(WEB, 'light', '/')
    await login(page, adminEmail)
    const status = psql(`SELECT status FROM task WHERE id='${taskId}'`)
    if (status === 'pending_review') {
      const version = psql(`SELECT version FROM task WHERE id='${taskId}'`)
      const approved = await api(page, 'POST', `/api/admin/tasks/${taskId}/review/approve`, { expectedVersion: Number(version) })
      ok('任务审核通过', approved.status === 200, JSON.stringify(approved.json)?.slice(0, 120))
    } else {
      ok('任务免审直发（发布历史豁免）', status === 'published', `status=${status}`)
    }
    await context.close()
  }
  {
    const { context, page } = await newPage(WEB, 'light', '/')
    await login(page, 'review-recommender@test.local', 'recommender')
    const applied = await api(page, 'POST', `/api/tasks/${taskId}/applications`, { note: '评审冒烟带客' })
    const appId = applied.json?.data?.id
    ok('推荐官报名', applied.status === 201 && !!appId, JSON.stringify(applied.json)?.slice(0, 160))
    await context.close()

    const merchantCtx = await newPage(WEB, 'light', '/')
    await login(merchantCtx.page, merchantEmail, 'merchant')
    const accepted = await api(merchantCtx.page, 'POST', `/api/tasks/${taskId}/applications/${appId}/accept`)
    ok('商家接受报名', accepted.status === 200, JSON.stringify(accepted.json)?.slice(0, 160))
    await merchantCtx.context.close()
  }
  {
    const { context, page } = await newPage(WEB, 'light', '/')
    await login(page, 'review-consumer@test.local')
    const order = await api(page, 'POST', '/api/v2/orders', { packageId, recommenderAccountId: recommenderId })
    orderId = order.json?.data?.id
    ok('消费者经推荐官链接下单（固定佣 500）',
      order.status === 201 && order.json?.data?.recommenderAmountCents === 500,
      `amount=${order.json?.data?.recommenderAmountCents}`)
    // 旧通道必须已死：POST /attribution → 405/404。
    const dead = await api(page, 'POST', `/api/v2/orders/${orderId}/attribution`,
      { recommenderAccountId: recommenderId, recommenderShareBps: 9000 })
    ok('买家直改分成端点已封死', dead.status === 405 || dead.status === 404, `status=${dead.status}`)
    // 申诉主张未接任务者 → 409（统一资格闸）。
    const junk = await api(page, 'POST', `/api/v2/orders/${orderId}/attribution-appeals`,
      { claimedRecommenderAccountId: '00000000-0000-0000-0000-00000000dead', reason: '随便指一个人拿佣金' })
    ok('申诉资格闸拦截未接任务者', junk.status === 409, JSON.stringify(junk.json)?.slice(0, 120))
    await context.close()
  }

  // ---------- 用户端截图：申诉表单 + 提交后的进度盒 × 明暗 ----------
  for (const theme of ['light', 'dark']) {
    const { context, page } = await newPage(WEB, theme, '/commerce')
    await login(page, 'review-consumer@test.local')
    await page.goto('/commerce', { waitUntil: 'domcontentloaded' })
    let cards = 0
    for (let i = 0; i < 10 && cards === 0; i++) {
      await page.waitForTimeout(1000)
      cards = await page.locator('.order-card').count()
    }
    if (cards === 0) throw new Error('consumer /commerce 未渲染订单卡')
    // 首次进主题循环时提交申诉（light 先行）；另一主题只读进度。
    if (theme === 'light') {
      await page.locator('.attribution-line button').first().click()
      await page.locator('.subform input').first().fill(recommenderId)
      await page.locator('.subform textarea').first().fill('截图冒烟：实际经该推荐官链接购买，请平台复核改绑')
      await page.screenshot({ path: `${OUT}/consumer-appeal-form-${theme}.png` })
      await page.locator('.subform button').first().click()
      await page.waitForSelector('.appeal-box', { timeout: 10_000 })
    } else {
      await page.waitForSelector('.appeal-box', { timeout: 10_000 })
    }
    const box = await page.locator('.appeal-box').first().textContent()
    ok(`${theme} 申诉进度盒`, (box || '').includes('待平台审核'), String(box).slice(0, 80))
    await page.screenshot({ path: `${OUT}/consumer-appeal-box-${theme}.png`, fullPage: false })
    await context.close()
  }

  // ---------- 治理台截图：归因申诉队列（含处置操作）× 明暗 ----------
  for (const theme of ['light', 'dark']) {
    const { context, page } = await newPage(OPS, theme, '/admin')
    await login(page, adminEmail)
    await page.getByTestId('admin-group-finance').click()
    await page.getByTestId('admin-tab-commerce').click()
    await page.locator('.commerce-admin .section-head', { hasText: '归因申诉队列' })
      .waitFor({ timeout: 20_000 })
    if (theme === 'dark') {
      // 亮色轮已处置 → 队列切「已改绑」再看 applied 行。
      await page.locator('.commerce-admin select').last().selectOption('applied')
      await page.waitForTimeout(1200)
    }
    const row = page.locator('.commerce-admin tr', { hasText: '截图冒烟' }).first()
    await row.waitFor({ timeout: 10_000 })
    if (theme === 'light') {
      // 运营纠错：目标=申诉主张的推荐官，金额由服务端按冻结固定佣重算。
      await row.locator('td input').fill('评审冒烟：证据核实，按冻结规则改绑')
      await page.screenshot({ path: `${OUT}/ops-appeal-queue-${theme}.png` })
      await row.getByRole('button', { name: '按冻结规则改绑' }).click()
      await page.waitForTimeout(1500)
      const orderRow = await page.evaluate(async (oid) => {
        const res = await fetch(`/api/admin/commerce/orders?limit=50`)
        const json = await res.json()
        const hit = json.data.items.find((o) => o.id === oid)
        return hit ? { recommender: hit.recommenderAccountId, amount: hit.recommenderAmountCents, merchant: hit.merchantAmountCents } : null
      }, orderId)
      ok('运营纠错按冻结规则重算（固定佣 500 保持）',
        orderRow && orderRow.recommender === recommenderId && orderRow.amount === 500 && orderRow.merchant === 1400,
        JSON.stringify(orderRow))
    } else {
      const appliedText = await page.locator('.commerce-admin table').last().textContent()
      ok('dark 申诉已 applied', (appliedText || '').includes('已改绑'))
      await page.screenshot({ path: `${OUT}/ops-appeal-applied-${theme}.png` })
    }
    await context.close()
  }

  // ---------- 消费者最终态：applied 回显 ----------
  {
    const { context, page } = await newPage(WEB, 'light', '/commerce')
    await login(page, 'review-consumer@test.local')
    await page.waitForSelector('.order-card', { timeout: 20_000 })
    await page.waitForSelector('.appeal-box.applied', { timeout: 10_000 })
    const box = await page.locator('.appeal-box.applied').textContent()
    ok('消费者侧 applied 回显', (box || '').includes('已改绑'))
    await page.screenshot({ path: `${OUT}/consumer-appeal-applied-light.png` })
    await context.close()
  }

  await browser.close()
  fs.writeFileSync(`${OUT}/result.json`, JSON.stringify(results, null, 2))
  const failed = results.filter((r) => !r.pass)
  console.log(`\n${results.length - failed.length}/${results.length} PASS`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((error) => { console.error(error); process.exit(1) })
