export type VideoProductionStage = 'upload' | 'script' | 'generate'

export type IndustryType = '餐饮' | '零售' | '美业' | '健身' | '教育培训' | '其他'

export type VideoStyle = '烟火纪实' | '治愈清新' | '高级暗调' | '数字人口播' | '复古胶片'

export interface VideoProductionImage {
  id: string
  dataUrl: string
  name: string
}

export interface VideoProductionForm {
  shopName: string
  industryType: IndustryType
  shopAddress: string
  shopDescription: string
  videoStyle: VideoStyle
  customPrompt: string
}
