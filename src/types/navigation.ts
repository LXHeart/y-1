/**
 * 全局视图名（主导航与跨视图跳转共用）。
 *
 * 工具视图（video/image/article/moments/comedy/video-production）不再是主导航入口
 * （PRD §4.2：AI 中心不以热点/图片/文章/视频分析作为一级入口），但路由保留——
 * 它们是 AI 中心 resolveWorkflow 解析出的「制作工作流目的地」，handoff 与深链依赖。
 */
export type AppView =
  | 'home'
  | 'ai-center'
  | 'video'
  | 'image'
  | 'article'
  | 'moments'
  | 'comedy'
  | 'video-production'
  | 'commerce'
  | 'grassland'
  | 'complaints'
