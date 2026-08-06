/**
 * PRD §4.7 平台适配与规范检查：按平台给出只读的内容规范提示。
 *
 * 这些规则仅用于前端生成与编辑时的提示和轻校验，不作为服务端权威校验。
 * 数值为建议值，可由运营配置演进；规则版本化，更新不改变历史任务与历史记录。
 */
import type { AiPlatformId } from '../types/ai-creation'

export const PLATFORM_FORMAT_RULES_VERSION = '2026-08-06'

export interface PlatformFormatRule {
  platformId: AiPlatformId
  platformLabel: string
  /** 正文字数建议下限。 */
  minChars: number
  /** 正文字数建议上限（建议值，可由运营配置演进）。 */
  maxChars: number
  /** 标题长度上限；null 表示该平台没有独立标题，以正文/文案开头承担标题作用。 */
  maxTitleChars: number | null
  /** 标签使用提示。 */
  tagHint: string
  /** Emoji 使用提示。 */
  emojiHint: string
  /** 内容结构要点，对应 PRD §4.7 表格“主要适配内容”。 */
  structureHints: readonly string[]
}

const PLATFORM_FORMAT_RULES: readonly PlatformFormatRule[] = Object.freeze([
  Object.freeze({
    platformId: 'xiaohongshu',
    platformLabel: '小红书',
    minChars: 50,
    maxChars: 1000, // 建议值，可由运营配置演进
    maxTitleChars: 20,
    tagHint: '正文末尾添加 3-8 个话题标签，优先覆盖品类、场景与城市词。',
    emojiHint: '适度使用 Emoji 分隔段落、突出卖点，避免整篇堆砌。',
    structureHints: ['种草标题先行，突出利益点', '正文分段，首段给出核心结论', '标签与 Emoji 配合竖版图文排版'],
  }),
  Object.freeze({
    platformId: 'douyin',
    platformLabel: '抖音',
    minChars: 15,
    maxChars: 300, // 建议值，可由运营配置演进
    maxTitleChars: 55,
    tagHint: '使用 2-5 个热门话题标签，结合互动引导语。',
    emojiHint: '少量 Emoji 用于发布文案点缀，短视频脚本内以口播表达为主。',
    structureHints: ['短视频脚本突出前 3 秒钩子', '发布文案精简并带话题', '结尾设计互动引导（评论/收藏/关注）'],
  }),
  Object.freeze({
    platformId: 'dianping',
    platformLabel: '大众点评',
    minChars: 50,
    maxChars: 1000, // 建议值，可由运营配置演进
    maxTitleChars: 20, // 建议值，可由运营配置演进
    tagHint: '少用话题标签，重点保证真实体验描述与门店信息完整。',
    emojiHint: '少量或不用 Emoji，保持评价口吻真实可信。',
    structureHints: ['以真实体验为主线，避免夸大', '给出评分建议与推荐理由', '补充图片说明与门店/价格信息'],
  }),
  Object.freeze({
    platformId: 'kuaishou',
    platformLabel: '快手',
    minChars: 10,
    maxChars: 200, // 建议值，可由运营配置演进
    maxTitleChars: null,
    tagHint: '使用 2-4 个生活化话题标签。',
    emojiHint: '少量 Emoji，保持接地气的表达。',
    structureHints: ['生活化、口语化表达优先', '短视频脚本直入主题', '话题与互动引导贴合日常场景'],
  }),
  Object.freeze({
    platformId: 'wechat-channels',
    platformLabel: '视频号',
    minChars: 10,
    maxChars: 300, // 建议值，可由运营配置演进
    maxTitleChars: null,
    tagHint: '少量话题标签，重点靠社交关系传播。',
    emojiHint: '少量 Emoji，避免过度营销感。',
    structureHints: ['竖版视频优先', '文案面向熟人社交关系表达', '附分享文案便于转发传播'],
  }),
  Object.freeze({
    platformId: 'bilibili',
    platformLabel: 'Bilibili',
    minChars: 30,
    maxChars: 2000, // 建议值，可由运营配置演进（简介区）
    maxTitleChars: 80,
    tagHint: '填写 5-10 个内容标签，覆盖分区与关键词。',
    emojiHint: '标题可少量使用 Emoji，简介区以文字为主。',
    structureHints: ['中长视频需设计清晰结构（开场/主体/结尾）', '简介补充章节与时间轴', '结尾给出三连等互动建议'],
  }),
  Object.freeze({
    platformId: 'wechat-official',
    platformLabel: '公众号',
    minChars: 300,
    maxChars: 3000, // 建议值，可由运营配置演进
    maxTitleChars: 64,
    tagHint: '一般不使用话题标签，可配摘要与导语。',
    emojiHint: '克制使用 Emoji，标题慎用，正文小标题可少量点缀。',
    structureHints: ['长图文结构完整，段落短小', '配备摘要、导语与推广语', '段落间配图，注意排版留白'],
  }),
  Object.freeze({
    platformId: 'zhihu',
    platformLabel: '知乎',
    minChars: 200,
    maxChars: 3000, // 建议值，可由运营配置演进
    maxTitleChars: 100,
    tagHint: '回答绑定问题，文章可加 3-5 个话题标签。',
    emojiHint: '基本不使用 Emoji，以理性表达为主。',
    structureHints: ['优先匹配目标问题，回答直切要点', '论点结构清晰，先结论后论据', '区分回答与文章两种格式'],
  }),
  Object.freeze({
    platformId: 'moments',
    platformLabel: '朋友圈',
    minChars: 10,
    maxChars: 200, // 建议值，可由运营配置演进（超出折叠行数影响传播）
    maxTitleChars: null,
    tagHint: '不使用话题标签，靠真实分享语气传播。',
    emojiHint: '少量 Emoji 增强生活感，避免营销腔。',
    structureHints: ['文案精简，一两句话讲清重点', '九宫格或多图给出建议顺序', '结尾自然带互动表达（约起/点赞）'],
  }),
])

export { PLATFORM_FORMAT_RULES }

export function getPlatformFormatRule(platformId: AiPlatformId | string): PlatformFormatRule | null {
  return PLATFORM_FORMAT_RULES.find((rule) => rule.platformId === platformId) ?? null
}
