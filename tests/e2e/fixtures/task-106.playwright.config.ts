import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

/**
 * 任务书 #106 C106-06（D06 / V05）：三引擎真实双页 cache/轮询 harness 专用 Playwright 配置。
 *
 * - webServer 自管测试 Vite（task-106.vite.config.ts，strictPort）；reuseExistingServer:false——
 *   端口被占直接失败，不连接陌生已有服务；测试结束自动关自身 server。
 * - 默认三引擎（chromium/firefox/webkit）；E2E_ENGINES 单引擎结果只能 PARTIAL（wrapper 提示）。
 * - 结果 JSON 按 E2E_TASK106_RESULTS 分引擎输出，后引擎不覆盖前引擎。
 */
const port = Number(process.env.TASK106_HARNESS_PORT || 18190)
const baseURL = process.env.TASK106_BASE_URL || `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: '../../',
  // 只挑本 harness spec；其他 spec 属于完整隔离栈（scripts/ci-e2e.sh 路径），与本入口无关。
  testMatch: /task106-cache-polling\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: path.resolve(process.env.E2E_TASK106_RESULTS || 'test-artifacts/task-106/harness/results.json') }],
  ],
  outputDir: fileURLToPath(new URL('../../../test-artifacts/task-106/harness/playwright', import.meta.url)),
  use: {
    baseURL,
    trace: 'off',
  },
  webServer: {
    command: `npx vite --config tests/e2e/fixtures/task-106.vite.config.ts --port ${port}`,
    cwd: fileURLToPath(new URL('../../..', import.meta.url)),
    url: `${baseURL}/tests/e2e/fixtures/task-106-harness.html`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
})
