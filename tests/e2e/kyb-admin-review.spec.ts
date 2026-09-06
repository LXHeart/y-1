import { expect, request as playwrightRequest, test } from '@playwright/test'
import type { APIRequestContext, APIResponse, Page } from '@playwright/test'
import { Pool } from 'pg'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
// 治理台独立入口（ops.html 双页应用的第二 origin；CI 由 ci-e2e.sh 注入 OPS_BASE_URL）
const opsBaseURL = process.env.OPS_BASE_URL || 'http://127.0.0.1:18081'
const merchantEmail = process.env.E2E_EMAIL || 'e2e-ci@test.local'
const merchantPassword = process.env.E2E_PASSWORD
const adminEmail = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'
const adminPassword = process.env.E2E_ADMIN_PASSWORD
const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

interface Envelope<T> {
  success: boolean
  data: T
}

interface Organization {
  id: string
}

interface Store {
  id: string
}

interface UploadTicket {
  id: string
  uploadUrl: string
  headers: Record<string, string>
}

interface Attachment {
  id: string
  mimeType: string
  sizeBytes: number
}

interface MerchantProfile {
  status: string
}

interface StoreProfile {
  status: string
  reviewNote: string
  reviewerAccountId: string
}

async function data<T>(response: APIResponse, expectedStatus = 200): Promise<T> {
  expect(response.status(), await response.text()).toBe(expectedStatus)
  const body = await response.json() as Envelope<T>
  expect(body.success).toBe(true)
  return body.data
}

async function loginApi(email: string, password: string): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL,
    extraHTTPHeaders: { Origin: baseURL },
  })
  await data(await context.post('/api/auth/login', { data: { email, password } }))
  return context
}

/**
 * 一账号一商家主体（#48/#49 单一成员模型）后不可再建第二主体——复跑/重试时复用既有主体。
 * （2026-09-06 前 e2e 同账号连建两主体，规则收紧后 409。）
 */
async function ensureOrganization(context: APIRequestContext, label: string): Promise<Organization> {
  const created = await context.post('/api/organizations', {
    data: { name: `KYB E2E ${label}`, industry: 'catering' },
  })
  if (created.status() === 201) return data<Organization>(created, 201)
  const existing = await data<Organization[]>(await context.get('/api/organizations'))
  if (existing.length > 0) return existing[0]
  throw new Error(`ensureOrganization: create=${created.status()} and no existing org (${await created.text()})`)
}

async function uploadKybMedia(
  merchant: APIRequestContext,
  storage: APIRequestContext,
  organizationId: string,
): Promise<string> {
  const ticket = await data<UploadTicket>(await merchant.post(
    `/api/organizations/${organizationId}/merchant-attachments/upload-ticket`,
    { data: { contentType: 'image/png', sizeBytes: png.length } },
  ))
  const upload = await storage.put(ticket.uploadUrl, { headers: ticket.headers, data: png })
  expect(upload.status()).toBe(200)
  await data(await merchant.post(`/api/media/${ticket.id}/confirm`))
  return ticket.id
}

async function attach(
  merchant: APIRequestContext,
  organizationId: string,
  mediaReferenceId: string,
  attachmentType: string,
): Promise<Attachment> {
  return data<Attachment>(await merchant.post(
    `/api/organizations/${organizationId}/merchant-attachments`,
    {
      data: {
        attachmentType,
        mediaReferenceId,
        mimeType: 'application/pdf',
        sizeBytes: 1,
      },
    },
  ), 201)
}

async function loginBrowser(page: Page, email: string, password: string): Promise<void> {
  // 管理员会话建立在治理台 origin 上（与用户端分离部署，cookie 相互独立）
  await page.goto(opsBaseURL + '/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  const loginResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await loginResponse).status()).toBe(200)
  await expect(page.locator('.ops-user')).toBeVisible()
}

async function openKybAdmin(page: Page): Promise<void> {
  // 登录后治理台直接落管理后台（无需再点主导航「管理」——治理台就是它的唯一宿主）
  await page.getByRole('tab', { name: /KYB 审核/ }).click()
  await expect(page.getByRole('heading', { name: '待审核申请' })).toBeVisible()
}

test.describe('administrator KYB review through the public Edge entrypoint', () => {
  test('reviews authoritative merchant evidence, controlled download, and approval', async ({ page }) => {
    test.setTimeout(120_000)
    expect(merchantPassword).toBeTruthy()
    expect(adminPassword).toBeTruthy()
    const merchant = await loginApi(merchantEmail, merchantPassword as string)
    const storage = await playwrightRequest.newContext()
    let merchantContext2: APIRequestContext | null = null
    try {
      const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
      const organization = await ensureOrganization(merchant, `merchant-${suffix}`)
      // 一账号一主体后「另一主体」改用种子商家账号（e2e-merchant）的既有主体；
      // 跨主体写入由此同时命中所有权与媒体归属两道守卫，不再钉死 400。
      merchantContext2 = await loginApi('e2e-merchant@test.local', merchantPassword as string)
      const otherOrganization = (await data<Organization[]>(
        await merchantContext2.get('/api/organizations')))[0]
      expect(otherOrganization?.id).toBeTruthy()
      await data(await merchant.post(`/api/organizations/${organization.id}/merchant-profile`, {
        data: {
          legalName: `草场 E2E 商户 ${suffix}`,
          unifiedSocialCreditCode: `91310000${suffix.replace(/\D/g, '').slice(-10).padStart(10, '0')}`,
          businessType: 'company',
          legalPersonName: '张三',
          legalPersonIdNumber: '310101199001015673',
          registeredCapitalCents: 1_000_000,
          establishmentDate: '2020-01-02',
          businessAddress: {
            province: '上海市', city: '上海市', district: '静安区', address: '南京西路 8 号',
          },
          contactPhone: '13800000000',
          contactEmail: merchantEmail,
        },
      }))

      const attachmentTypes = [
        'business_license', 'legal_person_id_front', 'legal_person_id_back',
      ] as const
      for (const [index, attachmentType] of attachmentTypes.entries()) {
        const mediaId = await uploadKybMedia(merchant, storage, organization.id)
        if (index === 0) {
          const crossOrganization = await merchant.post(
            `/api/organizations/${otherOrganization.id}/merchant-attachments`,
            { data: { attachmentType, mediaReferenceId: mediaId } },
          )
          expect(crossOrganization.status()).toBeGreaterThanOrEqual(400)
        }
        const saved = await attach(merchant, organization.id, mediaId, attachmentType)
        expect(saved.mimeType).toBe('image/png')
        expect(saved.sizeBytes).toBe(png.length)
      }
      await data<MerchantProfile>(await merchant.post(
        `/api/organizations/${organization.id}/merchant-profile/submit`,
      ), 201)

      await loginBrowser(page, adminEmail, adminPassword as string)
      await openKybAdmin(page)
      const row = page.getByRole('row').filter({ hasText: organization.id })
      await expect(row).toBeVisible()
      const detailResponse = page.waitForResponse((response) =>
        response.request().method() === 'GET'
        && /\/api\/admin\/kyb-requests\/[^/]+$/.test(response.url()))
      await row.getByRole('button', { name: '通过' }).click()
      const detail = await detailResponse
      expect(detail.status()).toBe(200)
      const detailBody = await detail.json() as Envelope<{ attachments: unknown[] }>
      expect(detailBody.data.attachments).toHaveLength(3)
      expect(JSON.stringify(detailBody.data.attachments)).not.toContain('mediaReferenceId')

      const dialog = page.getByRole('dialog', { name: '通过商户资料' })
      await expect(dialog).toContainText(`草场 E2E 商户 ${suffix}`)
      await expect(dialog).toContainText('****5673')
      await expect(dialog).toContainText('南京西路 8 号')
      await expect(dialog.locator('.material-row')).toHaveCount(3)

      const downloadResponse = page.waitForResponse((response) =>
        response.request().method() === 'GET' && response.url().includes('/download-url'))
      await dialog.getByRole('button', { name: '查看' }).first().click()
      const download = await downloadResponse
      expect(download.status()).toBe(200)
      const downloadBody = await download.json() as Envelope<{ downloadUrl: string }>
      const downloaded = await storage.get(downloadBody.data.downloadUrl)
      expect(downloaded.status()).toBe(200)
      expect(downloaded.headers()['content-type']).toContain('image/png')
      expect(await downloaded.body()).toEqual(png)

      await dialog.getByLabel('审核备注').fill('E2E 材料核验通过')
      const approveResponse = page.waitForResponse((response) =>
        response.request().method() === 'POST' && response.url().endsWith('/approve'))
      await dialog.getByRole('button', { name: '确认' }).click()
      expect((await approveResponse).status()).toBe(200)
      await expect(row).toHaveCount(0)

      const profile = await data<MerchantProfile>(await merchant.get(
        `/api/organizations/${organization.id}/merchant-profile`,
      ))
      expect(profile.status).toBe('approved')
    } finally {
      await merchantContext2?.dispose()
      await merchant.dispose()
      await storage.dispose()
    }
  })

  test('rejects a store profile with a required reason and emits both lifecycle events', async ({ page }) => {
    test.setTimeout(90_000)
    expect(merchantPassword).toBeTruthy()
    expect(adminPassword).toBeTruthy()
    const merchant = await loginApi(merchantEmail, merchantPassword as string)
    try {
      const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
      const organization = await ensureOrganization(merchant, `store-${suffix}`)
      const store = await data<Store>(await merchant.post(
        `/api/organizations/${organization.id}/stores`,
        { data: { name: `审核门店 ${suffix}` } },
      ), 201)
      const profilePath = `/api/organizations/${organization.id}/stores/${store.id}/profile`
      await data(await merchant.post(profilePath, {
        data: {
          address: JSON.stringify({
            province: '上海市', city: '上海市', district: '静安区', address: '南京西路 18 号',
          }),
          phone: '13800000001',
          businessHours: JSON.stringify([{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }]),
          description: 'KYB E2E 门店',
        },
      }))
      await data(await merchant.post(`${profilePath}/submit`), 201)

      await loginBrowser(page, adminEmail, adminPassword as string)
      await openKybAdmin(page)
      const row = page.getByRole('row').filter({ hasText: store.id })
      await expect(row).toBeVisible()
      await row.getByRole('button', { name: '拒绝' }).click()
      const dialog = page.getByRole('dialog', { name: '拒绝门店资料' })
      await expect(dialog).toContainText('南京西路 18 号')
      await dialog.getByRole('button', { name: '确认' }).click()
      await expect(dialog.getByRole('alert')).toHaveText('请填写拒绝原因')

      await dialog.getByLabel('审核备注').fill('地址无法核验')
      const rejectResponse = page.waitForResponse((response) =>
        response.request().method() === 'POST' && response.url().endsWith('/reject'))
      await dialog.getByRole('button', { name: '确认' }).click()
      expect((await rejectResponse).status()).toBe(200)
      await expect(row).toHaveCount(0)

      const profile = await data<StoreProfile>(await merchant.get(profilePath))
      expect(profile.status).toBe('rejected')
      expect(profile.reviewNote).toBe('地址无法核验')
      expect(profile.reviewerAccountId).toBeTruthy()

      const databaseUrl = process.env.E2E_DATABASE_URL
      expect(databaseUrl).toBeTruthy()
      const pool = new Pool({ connectionString: databaseUrl })
      try {
        const events = await pool.query<{ event_type: string }>(
          `SELECT event_type FROM outbox
           WHERE aggregate_id = $1
             AND event_type IN ('StoreProfileSubmitted', 'StoreProfileRejected')
           ORDER BY created_at`,
          [store.id],
        )
        expect(events.rows.map((row) => row.event_type)).toEqual([
          'StoreProfileSubmitted', 'StoreProfileRejected',
        ])
      } finally {
        await pool.end()
      }
    } finally {
      await merchant.dispose()
    }
  })
})
