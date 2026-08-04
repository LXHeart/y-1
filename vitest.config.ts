import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  // 编译 .vue SFC，组件测试需要（server 端测试不受影响）。
  plugins: [vue()],
  test: {
    // 默认 node：server/ 下的测试跑在 node 环境。
    // 需要 DOM 的组件测试在文件顶部用 `// @vitest-environment happy-dom` 单独声明，
    // 避免给几百个纯逻辑测试无谓地套一层 DOM。
    environment: 'node',
    setupFiles: ['./test/setup-env.ts'],
    include: ['server/src/**/*.test.ts', 'src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'json-summary', 'json', 'html'],
      include: ['server/src/**/*.ts', 'src/**/*.ts', 'src/**/*.vue'],
      exclude: ['server/src/types/**', 'server/src/scripts/**', '**/*.test.ts'],
      thresholds: {
        // 当前 ratchet 低于实测全源覆盖率；新增/修改代码仍要求 >=80%。
        statements: 46,
        branches: 74,
        functions: 66,
        lines: 46,
      },
    },
  },
})
