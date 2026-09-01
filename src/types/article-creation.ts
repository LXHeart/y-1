export type ArticlePlatform = 'wechat' | 'zhihu' | 'xiaohongshu'

/**
 * 任务书 #63：`check` 为正文之后的独立检查步（软确认放行）——
 * 有配图流 …正文 → 检查 → 配图；noteMode 检查为收尾步。
 */
export type ArticleCreationStage =
  | 'question' | 'topic' | 'titles' | 'outline' | 'content' | 'check' | 'images'

/**
 * 内容模式（任务书 #62）：article=独立文章（默认，现状）；answer=挂在已有问题下的知乎回答。
 * 仅知乎分叉——其余平台恒为 article，platform 值不拆（`zhihu` 是公开契约的一部分）。
 */
export type ArticleContentMode = 'article' | 'answer'

/** 创作 style skill 分类（任务书 #57）：与后端 creation_style_skill.category 对齐。 */
export type CreationStyleSkillCategory = 'TITLE_FORMULA' | 'GENRE' | 'STYLE'

/** 用户侧目录项（GET /api/creation-style-skills 下发，不含 promptContent）。 */
export interface CreationStyleSkillOption {
  category: CreationStyleSkillCategory
  code: string
  name: string
  description: string
  sortOrder: number
  /** 适用平台（任务书 #62）：空数组=全平台通用；否则只在列出的平台可选。 */
  applicablePlatforms?: string[]
}

export interface ArticleTitleOption {
  title: string
  hook: string
}

export interface ImagePlacement {
  position: string
  description: string
  searchKeywords: string
  prompt: string
}

export interface ImageRecommendation {
  recommendedCount: number
  placements: ImagePlacement[]
}

export interface ImageSearchResult {
  url: string
  thumbnailUrl: string
  sourceUrl?: string
  description?: string
  width?: number
  height?: number
}

export interface GeneratedImage {
  imageUrl: string
  revisedPrompt?: string
}

export type ImageSlotMode = 'none' | 'search' | 'generate'

export interface ArticleImageSlot {
  placement: ImagePlacement
  mode: ImageSlotMode
  searchResults: ImageSearchResult[]
  selectedImage: ImageSearchResult | GeneratedImage | null
  generating: boolean
  searching: boolean
  skipped: boolean
}
