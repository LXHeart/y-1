export type ArticlePlatform = 'wechat' | 'zhihu' | 'xiaohongshu'

export type ArticleCreationStage = 'topic' | 'titles' | 'outline' | 'content' | 'images'

/** 创作 style skill 分类（任务书 #57）：与后端 creation_style_skill.category 对齐。 */
export type CreationStyleSkillCategory = 'TITLE_FORMULA' | 'GENRE' | 'STYLE'

/** 用户侧目录项（GET /api/creation-style-skills 下发，不含 promptContent）。 */
export interface CreationStyleSkillOption {
  category: CreationStyleSkillCategory
  code: string
  name: string
  description: string
  sortOrder: number
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
