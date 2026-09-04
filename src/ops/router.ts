import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { reloadOnChunkError } from '../lib/chunk-reload'

/**
 * 治理台路由（独立入口 ops.html，独立 origin 部署）。
 *
 * 扁平路由：OpsApp 是挂载根（含登录态、导航与 <router-view>），
 * 不作为路由组件再嵌一层——否则整页双渲染。
 *
 * 路由级 roles 只是 UX 分层（无权限显示「无访问权限」而不是空白）；
 * 真正的门禁始终在服务端 backend_role / requireRole——治理端与用户端共用
 * 同一套 cookie session API，拆入口不改变后端授权语义。
 */
export const OPS_ROUTE_ROLES = {
  // 任务书 #72 卡C D4：客服/风控进 AdminView 查账号（页签级再收敛——cs/risk 只见「用户管理」）。
  admin: ['platform_admin', 'content_reviewer', 'customer_service', 'risk'],
  'ops-console': ['platform_admin', 'customer_service'],
} as const

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: 'admin' } },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('./admin/AdminView.vue'),
  },
  {
    path: '/ops',
    name: 'ops-console',
    component: () => import('./ops-console/OpsConsole.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 发版后旧标签页的懒加载 chunk 自救：失败即带目标路由刷新一次（src/lib/chunk-reload.ts）。
// 治理台两个视图都是懒加载——旧标签页点「运营处置」/「管理后台」加载已删除 chunk
// 时若不自救，点击毫无反应（2026-08-29 实录）。
router.onError((error, to) => {
  reloadOnChunkError(error, to.fullPath)
})

export default router
