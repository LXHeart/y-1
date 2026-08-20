import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // 框架运行时单独成块：业务代码发版时浏览器仍命中缓存的 vendor chunk。
        // 内容哈希文件名由 nginx 的 immutable 一年缓存策略承接（见 nginx.conf）。
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
        },
      },
    },
  },
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
