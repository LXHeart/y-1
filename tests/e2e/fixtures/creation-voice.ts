import { expect, request as playwrightRequest, type Page, type APIRequestContext } from '@playwright/test'
export const voiceAiBase = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
export const voiceClientBase = process.env.BASE_URL || 'http://127.0.0.1:18080'
export const roles = ['consumer', 'merchant', 'commercial-creator', 'researcher'] as const
export async function loginVoice(page: Page) {
  await page.goto(voiceAiBase + '/')
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  await page.locator('#login-email').fill(process.env.E2E_EMAIL || 'e2e-ci@test.local')
  await page.locator('#login-password').fill(process.env.E2E_PASSWORD || '')
  await page.getByRole('dialog').locator('button[type=submit]').click()
  await expect(page.getByTestId('auth-pill')).toBeVisible()
}
export async function data(response: Awaited<ReturnType<APIRequestContext['get']>>) {
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}`).toBe(true)
  return (await response.json()).data
}
export async function seedVoice(api: APIRequestContext, role: string) {
  const current = await data(await api.get(`${voiceAiBase}/api/creation-voice/${role}`))
  return data(await api.put(`${voiceAiBase}/api/creation-voice/${role}`, { data: { expectedRevision: current.revision, enabled: true, rules: [`${role}：短句，保留明确判断`], samples: [] } }))
}
export async function voiceDraft(api: APIRequestContext, capability: string, platform: string, role = 'consumer', voice = { mode: 'none' }, genre = 'article') {
  return data(await api.post(`${voiceAiBase}/api/creation-drafts`, { data: { sourceType: 'independent', title: '合成文风验收', topic: '合成门店', platform, contentForm: 'graphic', capability, contentMode: genre === 'answer' ? 'answer' : 'article', questionText: genre === 'answer' ? '合成门店体验如何？' : undefined,
    content: '合成门店，人均38元；排队较久。', workspace: { capability, currentStep: capability === 'article' ? (genre === 'answer' ? 'question' : 'topic') : 'compose', inputs: { brief: { processingMode: 'create', authorRole: role, voice, facts: [{ statement: '人均38元，排队较久', basis: 'user-confirmed' }] } } } } }))
}

/** 仅 fresh 验收栈的合成账号，经真实治理 API 补足批量生成额度。 */
export async function fundVoiceCases(api: APIRequestContext) {
  if (process.env.VOICE_E2E !== '1' || process.env.COMPOSE_PROJECT_NAME !== 'y1-task108-e2e') throw new Error('voice credits fixture requires isolated stack')
  const { user } = await data(await api.get(`${voiceAiBase}/api/auth/me`))
  const admin = await playwrightRequest.newContext()
  try {
    await data(await admin.post(`${voiceAiBase}/api/auth/login`, { data: { email: process.env.E2E_SEED_ADMIN_EMAIL, password: process.env.E2E_PASSWORD } }))
    await data(await admin.post(`${voiceAiBase}/api/admin/adjust-credits`, { data: { userId: user.id, amount: 10000, note: '任务108合成案例验收额度', operationId: `admin_adjust:voice108:${user.id}` } }))
  } finally { await admin.dispose() }
}
