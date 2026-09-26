import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { reloadOnChunkError } from '../lib/chunk-reload'

// 任务书 #92：工作区能力/来源上下文的解析逻辑收敛在 lib/creation-workspace.ts（前端工作区域，
// C-03 起与最近项目共用）；此处 re-export 维持 C-01 起的导入面（router.ts 为深链解析的首个锚点）。
export {
  CREATION_CAPABILITIES,
  parseCreationSourceQuery,
} from '../lib/creation-workspace'
export type { CreationCapability, CreationSourceContext } from '../lib/creation-workspace'

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
      {
        // 数字人工作台（任务书 #105E，K11）：独立工作区路由，不扩 CreationCapability 枚举。
        path: 'digital-human',
        name: 'digital-human',
        component: () => import('../views/digital-human/DigitalHumanWorkbench.vue'),
      },
      {
        // 视频克隆工作台（任务书 #107-3 C107-22）：/video-clone 列表、/:projectId 深链，
        // 同一模块挂载；不扩 CreationCapability 枚举（跨路由导航，不是创作能力卡）。
        path: 'video-clone',
        name: 'video-clone',
        component: () => import('../views/video-clone/VideoCloneWorkbench.vue'),
      },
      {
        path: 'video-clone/:projectId',
        name: 'video-clone-project',
        component: () => import('../views/video-clone/VideoCloneWorkbench.vue'),
      },
      {
        // 旧 /hypit 深链兼容重定向：首段形如工程 id 则映射深链，其余回列表；
        // query 只透传（目标侧 safeId/safeStep 过滤，URL 不是权限来源）。
        path: 'hypit/:pathMatch(.*)*',
        redirect: (to) => {
          const raw = to.params.pathMatch;
          const segments = Array.isArray(raw) ? raw.map(String) : raw ? [String(raw)] : [];
          const first = segments[0] ?? '';
          if (/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/u.test(first)) {
            return { name: 'video-clone-project', params: { projectId: first }, query: to.query };
          }
          return { name: 'video-clone', query: to.query };
        },
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
