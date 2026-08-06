/**
 * PRD §4.6 风格化脚本模板：六种抽象表达风格。
 *
 * 合规约束：模板只描述抽象的表达特征，不含任何在世创作者的个人风格，
 * 也不以模仿具体创作者作为产品默认能力。
 */

export const STYLE_TEMPLATE_VERSION = '2026-08-06'

export type StyleTemplateId =
  | 'light-comedy'
  | 'reversal-opening'
  | 'observational-humor'
  | 'professional-review'
  | 'emotional-story'
  | 'checklist-dense'

export interface StyleTemplate {
  id: StyleTemplateId
  label: string
  /** 抽象表达特征描述，供脚本生成提示词与脱口秀页消费。 */
  description: string
}

const STYLE_TEMPLATES: readonly StyleTemplate[] = Object.freeze([
  Object.freeze({
    id: 'light-comedy',
    label: '轻喜剧/段子式表达',
    description: '以短句和包袱推进节奏，用夸张类比和生活化吐槽制造笑点，结尾回扣主题卖点。',
  }),
  Object.freeze({
    id: 'reversal-opening',
    label: '反转式开场',
    description: '开场先抛出与预期相反的结论或场景制造悬念，中段揭示真实意图，形成记忆点后再展开核心信息。',
  }),
  Object.freeze({
    id: 'observational-humor',
    label: '观察式生活幽默',
    description: '从日常细节中提取普遍共鸣的场景，用冷静克制的叙述放大反差，让读者产生“就是这样”的会心一笑。',
  }),
  Object.freeze({
    id: 'professional-review',
    label: '专业测评',
    description: '以维度化对比和可验证的事实展开，先结论后论据，标注优缺点与适用场景，保持中立口吻。',
  }),
  Object.freeze({
    id: 'emotional-story',
    label: '情绪故事',
    description: '以第一人称经历串联起因、转折与感受，用具体细节而非形容词渲染情绪，结尾自然落到主题价值。',
  }),
  Object.freeze({
    id: 'checklist-dense',
    label: '清单式高密度信息',
    description: '用编号条目组织关键信息，每条一句、信息密度高，优先给出可执行的要点、价格与注意事项。',
  }),
])

export { STYLE_TEMPLATES }

export function getStyleTemplate(id: StyleTemplateId | string): StyleTemplate | null {
  return STYLE_TEMPLATES.find((template) => template.id === id) ?? null
}
