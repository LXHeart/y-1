import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 默认布局下的子路由 —— 所有功能页面共享顶部导航与全局弹窗。
 *
 * 首屏 AiCreationCenter 保留同步 import（见 DefaultLayout）避免首屏延迟；
 * 其余视图均通过 `() => import(...)` 做代码分割。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('../layouts/DefaultLayout.vue'),
    children: [
      { path: '', redirect: '/ai-center' },
      {
        path: 'ai-center',
        name: 'ai-center',
        component: () => import('../views/ai-center/AiCreationCenter.vue'),
      },
      {
        path: 'home',
        name: 'home',
        component: () => import('../views/home/HomeView.vue'),
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
        path: 'image-gen',
        name: 'image-gen',
        component: () => import('../views/image-gen/ImageGenerationView.vue'),
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
      {
        path: 'ops',
        name: 'ops',
        component: () => import('../views/ops/OpsConsole.vue'),
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('../views/admin/AdminView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
