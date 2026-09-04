// 跨应用 origin 运行时配置（任务书 #76）。
// dev 缺省空值 = 同源推导（vite 单服务器：/ai.html 即 AI 应用入口，见 src/lib/app-config.ts）。
// 生产由 nginx envsubst 注入部署变量覆盖（nginx.conf 各入口 server 的 location = /app-config.js，
// exact 精确匹配优先于本文件），改 origin 无需重打前端镜像。
window.__GRASSLAND_APP_CONFIG__ = { aiAppOrigin: '', grasslandOrigin: '' };
