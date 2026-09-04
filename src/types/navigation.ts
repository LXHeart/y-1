/**
 * 全局视图名（主导航与跨视图跳转共用）。
 *
 * 工具视图（video/image/article/moments/comedy/video-production）不再是主导航入口
 * （PRD §4.2：AI 中心不以热点/图片/文章/视频分析作为一级入口），但路由保留——
 * 它们是 AI 中心 resolveWorkflow 解析出的「制作工作流目的地」，handoff 与深链依赖。
 *
 * 任务书 #76：AI 内容创作中心独立成应用（ai.html/独立 origin）。`creation` 是草场内嵌
 * 创作面（任务锁定创作 + 素材库）；`ai-center` 保留为旧深链兼容（路由改道外跳 AI 应用）。
 */
export type AppView =
  | 'home'
  | 'creation'
  | 'ai-center'
  | 'video'
  | 'image'
  | 'article'
  | 'moments'
  | 'comedy'
  | 'video-production'
  | 'commerce'
  | 'grassland'
  | 'precedents'
  | 'complaints'
