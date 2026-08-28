import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { clearChunkRetryMarker, readChunkRetryTarget } from './lib/chunk-reload'
import './style.css'

createApp(App).use(createPinia()).use(router).mount('#app')

// chunk 自救的第二步（src/lib/chunk-reload.ts）：刷新拉到新入口后，
// 把用户送回原本要去的页面；成功才清标记，失败保留标记防止循环刷新。
const chunkRetryTarget = readChunkRetryTarget()
if (chunkRetryTarget) {
  void router.replace(chunkRetryTarget).then(clearChunkRetryMarker).catch(() => {})
}
