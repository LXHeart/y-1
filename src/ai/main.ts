import { createApp } from 'vue'
import { createPinia } from 'pinia'
import AiApp from './AiApp.vue'
import router from './router'
import { clearChunkRetryMarker, readChunkRetryTarget } from '../lib/chunk-reload'
import '../style.css'

// AI 创作中心入口（ai.html，任务书 #76）：独立应用、独立 origin（nginx 82 端口 server）。
// 与草场共用样式、登录、API 客户端与创作组件；无商家/推荐官身份概念，任何注册账号登录即用，
// 游客可试用。跨应用会话经一次性 token（useCrossAppToken）建立。
createApp(AiApp).use(createPinia()).use(router).mount('#app')

// chunk 自救的第二步（src/lib/chunk-reload.ts）：刷新拉到新入口后送回原目标页。
const chunkRetryTarget = readChunkRetryTarget()
if (chunkRetryTarget) {
  void router.replace(chunkRetryTarget).then(clearChunkRetryMarker).catch(() => {})
}
