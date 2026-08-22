import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 用户端路由（index.html 入口）。
 *
 * 路由结构对齐 PRD §11.2 分层：草场是平台，AI 内容创作中心是内置共享能力。
 *
 * - `/` 草场主页：角色感知的落地页（未登录平台介绍 / 商家 / 推荐官）。
 * - 工具视图（video/image/article/moments/comedy/video-production）保留路由但不在
 *   主导航露出——它们是 AI 中心工作流的落地目的地（见 types/navigation.ts 注释）。
 * - `/home`、`/image-gen` 是旧入口的兜底重定向，外发过的链接不作废。
 * - 运营处置与管理后台在独立治理台入口（ops.html / src/ops，独立 origin 部署）。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('../layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('../views/home/GrasslandHomeView.vue'),
      },
      { path: 'home', redirect: '/' },
      {
        path: 'ai-center',
        name: 'ai-center',
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
      { path: 'image-gen', redirect: { name: 'ai-center' } },
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
        path: 'commerce',
        name: 'commerce',
        component: () => import('../views/commerce/ConsumerCommerceView.vue'),
      },
      {
        path: 'grassland',
        name: 'grassland',
        component: () => import('../views/grassland/GrasslandWorkbench.vue'),
      },
      {
        path: 'complaints',
        name: 'complaints',
        component: () => import('../views/complaints/ComplaintsView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
