/**
 * 账号设置域类型（2026-08-26 最终收敛）：
 * - 顶部「设置」弹窗只剩首页热点数据源（HomepageSettings）。
 * - 分析设置（AnalysisSettings）仅剩飞书导出凭据——由图片评价视图内联维护；
 *   视频分析等用户级模型配置已下线，生成模型统一走「模型密钥」开关 + 管理后台平台模型配置。
 */
export interface FeishuIntegration {
  appId?: string
  appSecret?: string
  folderToken?: string
}

export interface AnalysisSettings {
  integrations?: {
    feishu?: FeishuIntegration
  }
}

export interface AnalysisSettingsApiResponse {
  success: boolean
  data?: AnalysisSettings
  error?: string
}

export type HotItemsProvider = '60s' | 'alapi'

export interface HomepageSettings {
  hotItems: {
    provider: HotItemsProvider
    alapiToken?: string
  }
}

export interface HomepageSettingsApiResponse {
  success: boolean
  data?: HomepageSettings
  error?: string
}
