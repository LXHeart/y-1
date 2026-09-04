import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { reloadOnChunkError } from '../lib/chunk-reload'

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
        // 任务书 #66 C2/C3：画布导演台（专业模式）——?storyboard={id} 与快速模式同数据互切
        path: 'video-canvas',
        name: 'video-canvas',
        component: () => import('../views/video-canvas/VideoCanvasView.vue'),
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
        // 任务书 #74 D2：一级页签撤除后旧深链不 404——落到工作台并自动打开个人设置
        // 弹窗（弹窗第三节有兜底表单与「我的投诉」）；未登录由工作台登录引导接住。
        path: 'complaints',
        name: 'complaints',
        redirect: { path: '/grassland', query: { settings: '1' } },
      },
      {
        path: 'first-password',
        name: 'first-password',
        component: () => import('../views/home/FirstPasswordChangeView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 首登强制改密的体验层闸（任务书 #48）：登录响应/me 已带 mustChangePassword 标记，
// 命中且未解除时把业务路由拉到 /first-password。刻意不在这里发请求——硬闸在 edge
// （业务 API 一律 428），此处只消费既有状态，避免双请求与测试环境的隐性网络依赖。
router.beforeEach((to) => {
  try {
    const auth = useAuthStore()
    if (!auth.loaded || !auth.mustChangePassword || to.name === 'first-password') return true
    return { name: 'first-password' }
  } catch {
    // 无活动 Pinia（部分单测直挂视图）→ 放行，不参与导航
    return true
  }
})

// 发版后旧标签页的懒加载 chunk 自救：失败即带目标路由刷新一次（src/lib/chunk-reload.ts）。
router.onError((error, to) => {
  reloadOnChunkError(error, to.fullPath)
})

export default router
