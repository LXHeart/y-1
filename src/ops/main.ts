import { createApp } from 'vue'
import { createPinia } from 'pinia'
import OpsApp from './OpsApp.vue'
import router from './router'
import { clearChunkRetryMarker, readChunkRetryTarget } from '../lib/chunk-reload'
import '../style.css'

// 治理台入口（ops.html）：与用户端共用样式、登录与 API 客户端，
// 但路由树独立（仅 管理后台 + 运营处置），部署在独立 origin（nginx 81 端口）。
createApp(OpsApp).use(createPinia()).use(router).mount('#app')

// chunk 自救的第二步（src/lib/chunk-reload.ts）：刷新拉到新入口后，
// 把用户送回原本要去的页面；成功才清标记，失败保留标记防止循环刷新。
const chunkRetryTarget = readChunkRetryTarget()
if (chunkRetryTarget) {
  void router.replace(chunkRetryTarget).then(clearChunkRetryMarker).catch(() => {})
}
