import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAccountSessionStore } from './stores/account-session'
import { clearChunkRetryMarker, readChunkRetryTarget } from './lib/chunk-reload'
import './style.css'

// 任务书 #79 C79-01：同一 Pinia 实例，mount 前启动账号会话 store——先于任何组件建立
// auth→session 的 sync watch，组件初始化时 epoch 已随当前账号就位。
const pinia = createPinia()
const app = createApp(App)
app.use(pinia)
app.use(router)
useAccountSessionStore(pinia)
app.mount('#app')

// chunk 自救的第二步（src/lib/chunk-reload.ts）：刷新拉到新入口后，
// 把用户送回原本要去的页面；成功才清标记，失败保留标记防止循环刷新。
const chunkRetryTarget = readChunkRetryTarget()
if (chunkRetryTarget) {
  void router.replace(chunkRetryTarget).then(clearChunkRetryMarker).catch(() => {})
}
