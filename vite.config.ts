import { resolve } from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue(), {
    name: 'ops-dev-entry',
    apply: 'serve',
    configureServer(server) {
      if (mode !== 'ops') return
      server.middlewares.use((req, _res, next) => {
        if (req.method === 'GET' && req.headers.accept?.includes('text/html')) {
          const url = new URL(req.url || '/', 'http://localhost')
          if (['/', '/admin', '/ops'].includes(url.pathname)) req.url = `/ops.html${url.search}`
        }
        next()
      })
    },
  }, {
    // AI 应用 dev 入口（107-4 C22/C23 视觉验收）：生产由 nginx 独立 origin 做
    // history 路由映射（nginx.conf ai server try_files → /ai.html），dev 用
    // `vite --mode ai` 提供同等行为。
    name: 'ai-dev-entry',
    apply: 'serve',
    configureServer(server) {
      if (mode !== 'ai') return
      server.middlewares.use((req, _res, next) => {
        if (req.method === 'GET' && req.headers.accept?.includes('text/html')) {
          const url = new URL(req.url || '/', 'http://localhost')
          const known = ['/', '/index.html', '/ops.html', '/ops', '/admin', '/ai.html', '/video', '/image', '/video-canvas', '/digital-human', '/video-clone', '/hypit']
          if (!known.some((p) => url.pathname === p || url.pathname.startsWith(`${p}/`))) return next()
          if (url.pathname.startsWith('/api/')) return next()
          req.url = `/ai.html${url.search}`
        }
        next()
      })
    },
  }],
  build: {
    rollupOptions: {
      // 三页入口：index.html = 用户端（商家/推荐官/消费者），ops.html = 治理台（运营处置 +
      // 管理后台），ai.html = AI 创作中心独立应用（任务书 #76）——后两者独立 origin 部署，
      // 分别见 nginx.conf 81 / 82 端口 server。
      input: {
        main: resolve(__dirname, 'index.html'),
        ops: resolve(__dirname, 'ops.html'),
        ai: resolve(__dirname, 'ai.html'),
      },
      output: {
        // 框架运行时 + 重型三方库单独成块：业务代码发版时浏览器仍命中缓存的 vendor chunk。
        // 内容哈希文件名由 nginx 的 immutable 一年缓存策略承接（见 nginx.conf）。
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          markdown: ['marked'],
          sanitizer: ['dompurify'],
          qrcode: ['qrcode'],
        },
      },
    },
  },
  server: {
    // Hypit 的生成发行版与运行时状态由独立脚本/测试管理，不属于 y-1 前端源码。
    // 忽略可避免引擎重放/隔离测试写槽时触发 Vite 反复清缓存和整页 reload。
    watch: {
      ignored: ['**/platform-hypit/.generated/**', '**/data/hypit/**'],
    },
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
}))
