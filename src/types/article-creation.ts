export type ArticlePlatform = 'wechat' | 'zhihu' | 'xiaohongshu'

export type ArticleCreationStage = 'topic' | 'titles' | 'outline' | 'content' | 'images'

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
}
