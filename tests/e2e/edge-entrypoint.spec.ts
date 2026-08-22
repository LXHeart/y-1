import { expect, test } from '@playwright/test'

const email = process.env.E2E_EMAIL || 'e2e-ci@test.local'
const displayName = process.env.E2E_DISPLAY_NAME || 'CI E2E User'

test.describe('unified Edge public entrypoint', () => {
  test('keeps internal paths private and rejects cross-origin writes', async ({ request }) => {
    for (const path of ['/internal', '/internal/credits', '/api/internal', '/api/internal/credits']) {
      await expect((await request.get(path)).status()).toBe(404)
    }

    const captcha = await request.get('/api/auth/captcha')
    expect(captcha.status()).toBe(200)
    expect(captcha.headers()['content-type']).toContain('image/svg+xml')

    const crossOriginWrite = await request.post('/api/auth/login', {
      headers: { Origin: 'https://evil.example' },
      data: { email, password: 'invalid-password' }, // secret-scan: allow - test fixture
    })
    expect(crossOriginWrite.status()).toBe(403)
  })

  test('logs in through Nginx, Edge, Identity, and the shared session cookie', async ({ page }) => {
    const password = process.env.E2E_PASSWORD
    test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')

    await page.goto('/')
    await page.getByRole('button', { name: '登录', exact: true }).click()

    const dialog = page.getByRole('dialog', { name: /登录草场/ })
    await dialog.locator('#login-email').fill(email)
    await dialog.locator('#login-password').fill(password as string)

    const loginResponse = page.waitForResponse((response) =>
      response.request().method() === 'POST' && response.url().endsWith('/api/auth/login'))
    await dialog.locator('button[type="submit"]').click()
    expect((await loginResponse).status()).toBe(200)

    await expect(page.getByText('已登录', { exact: true })).toBeVisible()
    await expect(page.getByText(displayName, { exact: true })).toBeVisible()

    const me = await page.request.get('/api/auth/me')
    expect(me.status()).toBe(200)
    expect((await me.json()).data.user.email).toBe(email)

    const taskFeed = await page.request.get('/api/tasks/feed')
    expect(taskFeed.status()).toBe(200)
    expect((await taskFeed.json()).data.items).toEqual([])

    const wallet = await page.request.get('/api/finance/wallets/me')
    expect(wallet.status()).toBe(200)
    expect((await wallet.json()).data.balanceCents).toBe(0)

    const dispute = await page.request.post('/api/trust/disputes', {
      data: {
        engagementRef: '00000000-0000-0000-0000-000000000001',
        reason: 'E2E route ownership probe',
      },
    })
    expect(dispute.status()).toBe(403)
    expect((await dispute.json()).success).toBe(false)

    const media = await page.request.get('/api/media/media')
    expect(media.status()).toBe(404)
    const mediaBody = await media.json()
    expect(mediaBody.success).toBe(false)
    expect(mediaBody.error).toBe('媒体不存在')
  })

  test('accepts mobile access tokens and rechecks refresh-token revocation at Edge', async ({ request }) => {
    const password = process.env.E2E_PASSWORD
    test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')

    const deviceHeaders = {
      Origin: process.env.BASE_URL || 'http://127.0.0.1:18080',
      'X-Device-Info': `playwright-edge-${Date.now()}`,
      'X-Device-Name': 'Playwright Edge',
    }
    const login = await request.post('/api/auth/login', {
      headers: deviceHeaders,
      data: { email, password },
    })
    expect(login.status(), await login.text()).toBe(200)
    const loginBody = await login.json() as {
      success: boolean
      data: { tokens: { access_token: string; refresh_token: string } }
    }
    expect(loginBody.success).toBe(true)
    const accessToken = loginBody.data.tokens.access_token
    const refreshToken = loginBody.data.tokens.refresh_token
    expect(accessToken).toBeTruthy()
    expect(refreshToken).toBeTruthy()

    const authenticated = await request.get('/api/tasks/feed', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    expect(authenticated.status(), await authenticated.text()).toBe(200)

    const refreshed = await request.post('/api/auth/refresh', {
      headers: { ...deviceHeaders, Authorization: `Bearer ${refreshToken}` },
    })
    expect(refreshed.status(), await refreshed.text()).toBe(200)
    const refreshedBody = await refreshed.json() as {
      success: boolean
      data: { access_token: string; expires_in: number }
    }
    expect(refreshedBody.success).toBe(true)
    const refreshedAccessToken = refreshedBody.data.access_token
    expect(refreshedAccessToken).toBeTruthy()

    const authenticatedAfterRefresh = await request.get('/api/tasks/feed', {
      headers: { Authorization: `Bearer ${refreshedAccessToken}` },
    })
    expect(authenticatedAfterRefresh.status(), await authenticatedAfterRefresh.text()).toBe(200)

    const revoked = await request.post('/api/auth/revoke', {
      headers: deviceHeaders,
      data: { refresh_token: refreshToken, all_devices: false },
    })
    expect(revoked.status(), await revoked.text()).toBe(200)

    const afterRevoke = await request.get('/api/tasks/feed', {
      headers: { Authorization: `Bearer ${refreshedAccessToken}` },
    })
    expect(afterRevoke.status(), await afterRevoke.text()).toBe(401)
  })
})
