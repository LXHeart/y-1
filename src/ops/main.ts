import { createApp } from 'vue'
import { createPinia } from 'pinia'
import OpsApp from './OpsApp.vue'
import router from './router'
import '../style.css'

// 治理台入口（ops.html）：与用户端共用样式、登录与 API 客户端，
// 但路由树独立（仅 管理后台 + 运营处置），部署在独立 origin（nginx 81 端口）。
createApp(OpsApp).use(createPinia()).use(router).mount('#app')
