import { defineConfig } from 'vite'

/**
 * 任务书 #106 C106-06（D06）：测试专用 Vite harness 配置。
 *
 * - root 指仓库根：harness TS 经相对路径直接 import 生产模块
 *   （src/lib/account-private-cache、stores/account-session、composables/useEngagementExitFunds、
 *   composables/grassland-http），不复刻任何缓存/轮询算法。
 * - 固定 loopback + strictPort：端口被占时入口必须报错退出，不连接陌生已有服务
 *   （TC106-06-05 对照；task-106-browser.sh 在启动前先做占用探针）。
 * - 只服务测试 URL（task-106-harness.html），不改产品路由/入口。
 */
export default defineConfig({
  root: process.cwd(),
  server: {
    host: '127.0.0.1',
    port: Number(process.env.TASK106_HARNESS_PORT || 18190),
    strictPort: true,
  },
  logLevel: 'warn',
})
