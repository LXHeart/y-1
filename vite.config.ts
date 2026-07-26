import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // dev 走 edge-bff（默认 :8081，见 docker-compose EDGE_BFF_PORT），与生产形态一致：
      // BFF 按 RouteManifest 分流——已迁移路由 → Java 服务，其余 default-upstream=legacy 透传回 Express:3000。
      // 需本地起 Java 栈：docker compose --profile java-edge up -d
      // 只跑旧栈时设 VITE_API_TARGET=http://localhost:3000 回退。
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
