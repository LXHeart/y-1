import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  // 编译 .vue SFC；部署与工具契约测试继续使用 Node 环境。
  plugins: [vue()],
  // 不依赖宿主机 localhost DNS/hosts；CI 与受限开发环境都直接绑定 IPv4 loopback。
  server: { host: '127.0.0.1' },
  test: {
    // 默认 node；需要 DOM 的组件测试在文件顶部单独声明 happy-dom。
    // 需要 DOM 的组件测试在文件顶部用 `// @vitest-environment happy-dom` 单独声明，
    // 避免给几百个纯逻辑测试无谓地套一层 DOM。
    environment: 'node',
    environmentOptions: { happyDOM: { url: 'http://127.0.0.1:3000' } },
    setupFiles: ['./test/setup-env.ts'],
    include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'json-summary', 'json', 'html'],
      include: ['src/**/*.ts', 'src/**/*.vue'],
      exclude: ['**/*.test.ts'],
      thresholds: {
        // Node 后端退役后基线只统计前端源码；新增/修改代码仍要求 >=80%。
        // 2026-08-20 按实测回调（原 68/74/53/68 为初始保守值；实测 78.35/78.08/60.57/78.35，
        // 留 2-3 点 CI 波动余量）。
        statements: 76,
        branches: 76,
        functions: 58,
        lines: 76,
      },
    },
  },
})
