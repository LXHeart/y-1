import rules from '../../contracts/task-contract.v1.json'
import { formatYuan } from './money'
import type { TaskPreview } from '../types/grassland'

export type ContractSnapshot = Record<string, unknown>
export const contractFields = rules.fields
export const requirementFields = rules.requirementFields
type Formatter = (value: unknown, preview?: TaskPreview) => string[]
const object = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
const text = (value: unknown) => value == null ? '未约定' : String(value)
const platforms: Record<string, string> = {
  douyin: '抖音', xiaohongshu: '小红书', kuaishou: '快手', zhihu: '知乎', bilibili: 'B站',
  'wechat-channels': '视频号', 'wechat-official': '公众号', 'wechat-moments': '朋友圈', dianping: '大众点评',
}
const forms: Record<string, string> = { image: '图文', video: '视频', article: '文章', interaction: '点赞互动' }

export const contractFormatters: Record<string, Formatter> = {
  text: value => [text(value)],
  list: value => Array.isArray(value) && value.length ? value.map(text) : ['未约定'],
  money: value => [typeof value === 'number' ? formatYuan(value) : '未约定'],
  platform: value => [platforms[String(value)] ?? text(value)],
  contentForm: value => [forms[String(value)] ?? text(value)],
  days: (value, preview) => [preview
    ? `接受后 ${preview.delivery.deliveryDeadlineDays} 天内${preview.delivery.source === 'default' ? '（平台默认）' : ''}`
    : value == null ? '采用平台默认期限；本快照未记录具体天数' : `接受后 ${value} 天内`],
  review: (value, preview) => [value !== true ? '无需发布前审稿' : preview
    ? `${preview.review.reviewWindowHours} 小时内审稿，最多退改 ${preview.review.reviseCap} 次，退回后 ${preview.review.resubmitHours} 小时内补交。${preview.review.timeoutPolicy}。`
    : '须经商家审稿批准后发布'],
  cancel: (value, preview) => {
    if (preview) {
      const p = preview.cancel
      return [(['bounty', 'ladder'].includes(preview.payout.mode)
        ? `已确认脚本 ${p.scriptBps / 100}% / 合格成品 ${p.deliverableBps / 100}% / 按约发布 ${p.publishedBps / 100}%。` : '') + p.cap]
    }
    const policy = object(value)
    return ['script', 'deliverable', 'published'].map((key, i) =>
      `${['已确认脚本', '合格成品', '按约发布'][i]}：${typeof policy[key] === 'number' ? `${policy[key] / 100}%` : '平台默认（本快照未记录比例）'}`)
  },
  ladder: value => {
    if (value == null) return ['未约定']
    const ladder = object(value)
    return [`指标：${text(ladder.metricKey)}；按已达最高档结算，不累加`,
      ...(Array.isArray(ladder.tiers) ? ladder.tiers.map(tier => {
        const row = object(tier)
        return `达到 ${text(row.threshold)}：${typeof row.payoutCents === 'number' ? formatYuan(row.payoutCents) : '金额未记录'}`
      }) : [])]
  },
  interaction: value => value == null ? ['未约定'] :
    [`目标：${text(object(value).targetUrl)}`, `动作：${text(object(value).actionType)}`],
  requirements: value => requirementFields.flatMap(field => {
    const data = object(value)
    if (!Object.prototype.hasOwnProperty.call(data, field.key)) return [`${field.label}：本快照未记录`]
    return formatContractValue(field.format, data[field.key]).map(line => `${field.label}：${line}`)
  }),
}

function formatContractValue(format: string, value: unknown, preview?: TaskPreview): string[] {
  const formatter = contractFormatters[format]
  if (!formatter) throw new Error(`Missing contract formatter: ${format}`)
  return formatter(value, preview)
}

/** Missing historical fields must never be filled from today's mutable task/defaults. */
export function contractDisplayRows(terms: ContractSnapshot, preview?: TaskPreview) {
  return contractFields.map(field => ({
    key: field.key, label: field.label,
    lines: Object.prototype.hasOwnProperty.call(terms, field.key)
      ? formatContractValue(field.format, terms[field.key], preview)
      : ['本快照未记录'],
  }))
}
