/**
 * 系列 AI 图卡模板常量（任务书 #54 B 决策，#43 D8 同款：前端静态常量，运营配置化为登记缺口）。
 *
 * 风格×布局×配色三维矩阵 + preset 组合，本地化改写自 baoyu-skills 的 baoyu-xhs-images
 * 技能（JimLiu，MIT：12 风格 × 8 布局 × 3 配色），仅取其分类学思路与命名参照，
 * 描述词按草场商家内容营销场景重写；服务端只收描述词文本（后端模板无关）。
 */

export interface CardSeriesStyle {
  id: string
  label: string
  /** 送入计划/生图 prompt 的视觉风格描述词（后端校验 ≤200 字） */
  prompt: string
}

export interface CardSeriesLayout {
  id: string
  label: string
  /** 送入生图 prompt 的画面布局描述词（引导画面分区与文字留白——文字由前端叠排） */
  prompt: string
  /** 前端叠字的文字排版模式 */
  textLayout: 'top-title' | 'center-title' | 'bottom-list'
}

export interface CardSeriesPalette {
  id: string
  label: string
  prompt: string
}

export const CARD_SERIES_STYLES: readonly CardSeriesStyle[] = [
  { id: 'cute-fresh', label: '可爱清新', prompt: '圆润造型、柔和高明度色调、活泼手绘插画质感，亲切不幼稚' },
  { id: 'warm-life', label: '暖调生活', prompt: '暖光氛围、生活化场景、胶片质感，真实而有人情味' },
  { id: 'bold-strike', label: '大胆吸睛', prompt: '高饱和撞色、大色块构图、强对比主体，远距离也醒目' },
  { id: 'minimal-note', label: '极简笔记', prompt: '大量留白、细线几何、克制的双色点缀，安静高级' },
  { id: 'retro-story', label: '复古讲古', prompt: '做旧纸感、暖褐与墨绿、插画叙事感，像老牌招贴' },
  { id: 'pop-clash', label: '波普碰撞', prompt: '波普网点、描边色块、夸张图形，年轻潮流' },
  { id: 'doodle', label: '手绘涂鸦', prompt: '马克笔涂鸦线条、随性图标点缀、轻松幽默' },
  { id: 'macaron', label: '清新马卡龙', prompt: '马卡龙粉蓝奶油色系、柔和渐变、甜品般轻快' },
  { id: 'study-note', label: '学习笔记', prompt: '手账排版感、便利贴与下划线元素、知识卡片气质' },
  { id: 'chalkboard', label: '黑板板书', prompt: '深色黑板底、粉笔笔触线条、手写标题感' },
  { id: 'texture-print', label: '纹理印刷', prompt: '丝网印刷质感、颗粒噪点、有限套色' },
  { id: 'sketch', label: '速写随笔', prompt: '钢笔速写线稿、局部淡彩、白描干净' },
]

export const CARD_SERIES_LAYOUTS: readonly CardSeriesLayout[] = [
  { id: 'sparse', label: '留白封面', prompt: '大面积简洁背景、主体居中偏上、下方四分之一为要点文字区', textLayout: 'bottom-list' },
  { id: 'balanced', label: '均衡图文', prompt: '上二分之一主视觉、下二分之一浅色要点文字区', textLayout: 'top-title' },
  { id: 'dense', label: '高密度信息', prompt: '满版插画、四边留出窄边距文字区', textLayout: 'center-title' },
  { id: 'list', label: '清单要点', prompt: '左侧窄条主视觉、右侧宽幅清单文字区', textLayout: 'top-title' },
  { id: 'comparison', label: '左右对比', prompt: '画面等分为左右两区，中间留窄分隔带', textLayout: 'center-title' },
  { id: 'flow', label: '流程步骤', prompt: '画面呈之字形动线、节点之间留步进间隙', textLayout: 'bottom-list' },
  { id: 'mindmap', label: '导图发散', prompt: '中心主体向四周发散分支、各分支留标签位', textLayout: 'center-title' },
  { id: 'quadrant', label: '四象限', prompt: '田字格四分区、每区独立小场景', textLayout: 'center-title' },
]

export const CARD_SERIES_PALETTES: readonly CardSeriesPalette[] = [
  { id: 'macaron', label: '马卡龙', prompt: '马卡龙色系：奶油白底、粉杏/薄荷/雾蓝点缀' },
  { id: 'warm', label: '暖调', prompt: '暖调色系：米色底、陶土橙/姜黄/砖红点缀' },
  { id: 'neon', label: '霓虹', prompt: '霓虹色系：深色底、亮紫/青柠/品蓝点缀' },
]

/** 组合 preset：风格+布局（+可选配色）一键选择。 */
export interface CardSeriesPreset {
  id: string
  label: string
  styleId: string
  layoutId: string
  paletteId?: string
}

export const CARD_SERIES_PRESETS: readonly CardSeriesPreset[] = [
  { id: 'xhs-cute-list', label: '种草清单', styleId: 'cute-fresh', layoutId: 'list', paletteId: 'macaron' },
  { id: 'foodie-warm', label: '美食暖调', styleId: 'warm-life', layoutId: 'balanced', paletteId: 'warm' },
  { id: 'promo-bold', label: '活动大促', styleId: 'bold-strike', layoutId: 'sparse', paletteId: 'neon' },
  { id: 'guide-note', label: '攻略笔记', styleId: 'study-note', layoutId: 'flow' },
  { id: 'menu-retro', label: '价目复古', styleId: 'retro-story', layoutId: 'comparison', paletteId: 'warm' },
  { id: 'brand-minimal', label: '品牌极简', styleId: 'minimal-note', layoutId: 'quadrant' },
]

export const CARD_SERIES_SIZES: ReadonlyArray<{ id: string; label: string }> = [
  { id: '1024x1792', label: '竖版 9:16（小红书/抖音/朋友圈）' },
  { id: '1024x1024', label: '方形 1:1' },
  { id: '1792x1024', label: '横版 16:9' },
]

export function findCardSeriesStyle(id: string): CardSeriesStyle | undefined {
  return CARD_SERIES_STYLES.find((item) => item.id === id)
}

export function findCardSeriesLayout(id: string): CardSeriesLayout | undefined {
  return CARD_SERIES_LAYOUTS.find((item) => item.id === id)
}

export function findCardSeriesPalette(id: string): CardSeriesPalette | undefined {
  return CARD_SERIES_PALETTES.find((item) => item.id === id)
}
