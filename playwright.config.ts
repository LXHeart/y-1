import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-artifacts/playwright-results.xml' }],
  ],
  outputDir: 'test-artifacts/playwright',
  use: {
    baseURL: process.env.BASE_URL || 'http://127.0.0.1:18080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // 浏览器矩阵（第八批工程项）：同一套 spec 三引擎跑——WebKit/Firefox 对 cookie 属性、
    // CORS 预检、SVG/字体渲染的差异在此暴露。单引擎调试用 --project=<name>。
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
})
