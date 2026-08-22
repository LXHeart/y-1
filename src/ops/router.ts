import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

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
  admin: ['platform_admin', 'content_reviewer'],
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

export default router
