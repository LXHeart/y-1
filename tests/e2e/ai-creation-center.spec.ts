import { expect, test } from '@playwright/test'

test('platform-first creation entry hands an independent topic to article workflow', async ({ page }) => {
  await page.goto('/')

  await page.getByRole('button', { name: '公众号' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '独立创作' }).click()
  await page.getByRole('textbox', { name: '创作主题' }).fill('秋季新品发布')
  await page.getByRole('button', { name: '开始创作' }).click()

  await expect(page.getByRole('heading', { name: '先确定主题和发布平台' })).toBeVisible()
  await expect(page.getByPlaceholder(/输入你想创作的主题/)).toHaveValue('秋季新品发布')
  await expect(page.getByRole('button', { name: '微信公众号' })).toHaveClass(/platform-btn-active/)
})

test('unauthenticated task source opens login without loading task data', async ({ page }) => {
  const taskRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/tasks')) taskRequests.push(request.url())
  })
  await page.goto('/')

  await page.getByRole('button', { name: '小红书' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '从任务创作' }).click()

  await expect(page.getByRole('dialog')).toBeVisible()
  expect(taskRequests).toEqual([])
})
