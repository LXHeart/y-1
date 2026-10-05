/**
 * 私有文风档案共享类型（任务书 #108 / W19、C-03）。
 *
 * 对齐 intelligence 后端契约（§6）：
 * - role 固定四值（RULE-004），platform 固定四平台、genre 固定四体裁（RULE-005）。
 * - brief.voice 为判别联合：none 不附 role/revision；profile 必须带 role+revision（revision≥1）；
 *   整体缺省仅为旧客户端兼容（RULE-004）。
 * - Profile/PUT 结构对齐 API-001/002 信封；缺槽 GET 返回 revision=0、enabled=false、空数组。
 */

/** 文风身份（写作视角，不是系统授权角色）。固定四槽，不自由增角色。 */
export type CreationVoiceRole = 'consumer' | 'merchant' | 'commercial-creator' | 'researcher'

/** 支持私有文风的四个平台（RULE-005）。 */
export type CreationVoicePlatform = 'zhihu' | 'xiaohongshu' | 'dianping' | 'moments'

/** 体裁固定四值；与平台的映射由服务端决定（文章 answer/article、图片评价 note、朋友圈 short-post）。 */
export type CreationVoiceGenre = 'article' | 'answer' | 'note' | 'short-post'

/**
 * 创作简报中的文风选择（§6.6）：
 * - none：本次不使用我的文风（不回落旧偏好）；
 * - profile：使用指定身份槽位按提交 revision 精确读取（服务端校验 role 与 brief.authorRole 一致）。
 * 缺省 voice 仅旧客户端兼容路径；新 UI 首次写入时显式给 none。
 */
export type CreationBriefVoice = { mode: 'none' } | { mode: 'profile'; role: CreationVoiceRole; revision: number }

/** 范文条目：id 为客户端 UUID（单档案内唯一），consent 必须为 true（本人作品/获准使用）。 */
export interface CreationVoiceSample {
  id: string
  platform: CreationVoicePlatform
  genre: CreationVoiceGenre
  text: string
  consent: true
}

/** 档案（API-001 GET / API-002 PUT 的 data）：缺槽返回 revision=0、enabled=false、空数组、updatedAt=null。 */
export interface CreationVoiceProfile {
  role: CreationVoiceRole
  revision: number
  enabled: boolean
  rules: string[]
  samples: CreationVoiceSample[]
  updatedAt: string | null
}

/** API-002 PUT 请求体：全部必传；CAS 以 expectedRevision 比对（首次建槽为 0）。 */
export interface PutCreationVoiceProfileRequest {
  expectedRevision: number
  enabled: boolean
  rules: string[]
  samples: CreationVoiceSample[]
}
