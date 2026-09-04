import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { reloadOnChunkError } from '../lib/chunk-reload'

/**
 * AI 创作中心路由（ai.html 入口，任务书 #76）。
 *
 * - `/`（name: create）挂 AiCreationCenter mode="personal"——九板块 + 自由创作三来源。
 * - 七枚工具视图路由与草场侧路径保持一致（D4：共享组件双挂载，URL 路径两边一致）；
 *   工具视图的「返回创作中心」经 open-view 事件由壳映射回 create，不硬编码路由名。
 * - 板块导航（assistant/runs/…）是组件内 tab，不占路由。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('./AiAppLayout.vue'),
    children: [
      {
        path: '',
        name: 'create',
        component: () => import('../views/ai-center/AiCreationCenter.vue'),
      },
      {
        path: 'video',
        name: 'video',
        component: () => import('../views/video/VideoAnalysisView.vue'),
      },
      {
        path: 'image',
        name: 'image',
        component: () => import('../views/image/ImageAnalysisView.vue'),
      },
      {
        path: 'article',
        name: 'article',
        component: () => import('../views/article/ArticleCreationView.vue'),
      },
      {
        path: 'moments',
        name: 'moments',
        component: () => import('../views/moments/MomentsCreationView.vue'),
      },
      {
        path: 'comedy',
        name: 'comedy',
        component: () => import('../views/comedy/ComedyWritingView.vue'),
      },
      {
        path: 'video-production',
        name: 'video-production',
        component: () => import('../views/video-production/VideoProductionView.vue'),
      },
      {
        path: 'video-canvas',
        name: 'video-canvas',
        component: () => import('../views/video-canvas/VideoCanvasView.vue'),
      },
      { path: ':pathMatch(.*)*', redirect: { name: 'create' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 首登强制改密的硬闸在 edge（业务 API 一律 428）；AI 应用无改密页，不做路由拦截，
// 由壳内横幅引导回草场完成改密（AiAppLayout）。

// 发版后旧标签页的懒加载 chunk 自救（与草场/治理台入口同款）。
router.onError((error, to) => {
  reloadOnChunkError(error, to.fullPath)
})

export default router
