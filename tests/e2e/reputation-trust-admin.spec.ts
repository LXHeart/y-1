import { expect, test } from '@playwright/test'
import type { APIResponse, Page } from '@playwright/test'

const adminEmail = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'
const adminPassword = process.env.E2E_ADMIN_PASSWORD

interface Envelope<T> {
  success: boolean
  data: T
}

interface AdminJudge {
  accountId: string
  active: boolean
  opsAdmitted: boolean
  version: number
}

interface AdminJudgePage {
  items: AdminJudge[]
  nextCursor: string | null
  hasMore: boolean
}

async function data<T>(response: APIResponse, expectedStatus = 200): Promise<T> {
  expect(response.status(), await response.text()).toBe(expectedStatus)
  const body = await response.json() as Envelope<T>
  expect(body.success).toBe(true)
  return body.data
}

async function loginBrowser(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录后管理/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  const response = page.waitForResponse((candidate) =>
    candidate.request().method() === 'POST' && candidate.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await response).status()).toBe(200)
  await expect(page.getByText('已登录', { exact: true })).toBeVisible()
}

test.describe('等级权益与审判官运营后台', () => {
  test('读取五级策略并完成审判官撤销与恢复', async ({ page }) => {
    test.setTimeout(90_000)
    test.skip(!adminPassword, 'E2E_ADMIN_PASSWORD is required for the seeded platform admin')
    await loginBrowser(page, adminEmail, adminPassword as string)

    await page.getByRole('button', { name: '管理', exact: true }).click()
    const policyResponse = page.waitForResponse((response) =>
      response.request().method() === 'GET'
      && response.url().endsWith('/api/admin/reputation-config'))
    await page.getByRole('tab', { name: '等级与权益' }).click()
    expect((await policyResponse).status()).toBe(200)
    await expect(page.getByText(/策略版本 \d+/)).toBeVisible()
    await expect(page.locator('.reputation-level-row')).toHaveCount(5)

    const judgesResponse = page.waitForResponse((response) =>
      response.request().method() === 'GET'
      && response.url().includes('/api/admin/trust/judges?'))
    await page.getByRole('tab', { name: '审判官准入' }).click()
    const judges = await data<AdminJudgePage>(await judgesResponse)
    const target = judges.items.find((judge) => judge.active)
    expect(target).toBeTruthy()
    if (!target) return

    const originalAdmission = target.opsAdmitted
    const row = page.getByRole('row').filter({ hasText: target.accountId })
    await expect(row).toBeVisible()
    try {
      if (originalAdmission) {
        await row.getByPlaceholder('必填，1-500 字').fill('E2E 撤销复核')
        const revoke = page.waitForResponse((response) =>
          response.request().method() === 'PUT' && response.url().endsWith('/admission'))
        await row.getByRole('button', { name: '撤销', exact: true }).click()
        expect((await revoke).status()).toBe(200)
        await expect(row.getByText('待准入', { exact: true })).toBeVisible()
      }

      await row.getByPlaceholder('必填，1-500 字').fill('E2E 恢复准入')
      const grant = page.waitForResponse((response) =>
        response.request().method() === 'PUT' && response.url().endsWith('/admission'))
      await row.getByRole('button', { name: '准入', exact: true }).click()
      expect((await grant).status()).toBe(200)
      await expect(row.getByText('已准入', { exact: true })).toBeVisible()

      await row.getByRole('button', { name: '记录', exact: true }).click()
      await expect(page.getByRole('heading', { name: '准入记录' })).toBeVisible()
      await expect(page.getByText('E2E 恢复准入', { exact: true })).toBeVisible()
    } finally {
      const current = await data<AdminJudge>(await page.request.get(
        `/api/admin/trust/judges/${target.accountId}`))
      if (current.opsAdmitted !== originalAdmission) {
        await data(await page.request.put(
          `/api/admin/trust/judges/${target.accountId}/admission`,
          {
            headers: { Origin: new URL(page.url()).origin },
            data: {
              admitted: originalAdmission,
              expectedVersion: current.version,
              reason: 'E2E 恢复原始状态',
            },
          },
        ))
      }
    }
  })
})
