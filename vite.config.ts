import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // dev 走 edge-bff（默认 :8081，见 docker-compose EDGE_BFF_PORT），与生产形态一致：
      // BFF 按 RouteManifest 分流；未登记、method 不匹配或停用的路由由 Edge fail-closed 404。
      // 需本地起默认 Compose 栈：docker compose up -d
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
