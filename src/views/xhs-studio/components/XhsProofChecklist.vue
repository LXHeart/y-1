<template>
  <section class="xhs-proof-checklist" aria-label="发布前检查">
    <div class="xhs-check-head">
      <h2>发布前检查</h2>
      <p class="xhs-check-summary">
        <span class="badge badge-success">{{ passCount }} 项通过</span>
        <span class="badge badge-warning">{{ attentionCount }} 项建议关注</span>
      </p>
    </div>
    <p class="xhs-check-note">以下逐项来自当前草稿与平台规范的真实计算；建议关注项不阻断发布，发布前请自行核对。</p>

    <ul class="xhs-check-list">
      <li v-for="item in items" :key="item.key" class="xhs-check-item" :data-test="`xhs-check-${item.key}`">
        <div class="xhs-check-row">
          <span class="xhs-check-label">{{ item.label }}</span>
          <span class="xhs-check-value">{{ item.value }}</span>
          <span class="badge" :class="badgeClass(item.state)">{{ item.badge }}</span>
        </div>
        <p v-for="(note, i) in item.notes" :key="i" class="xhs-check-item-note">{{ note }}</p>
      </li>
    </ul>

    <div v-if="formatIssues.length" class="xhs-check-issues" data-test="xhs-check-issues">
      <h3>规范提示</h3>
      <ul>
        <li v-for="(issue, i) in formatIssues" :key="i">{{ issue }}</li>
      </ul>
    </div>

    <div class="xhs-check-actions">
      <button type="button" class="gl-btn-secondary" data-test="xhs-check-go-edit" @click="emit('goEdit')">
        返回创作修改
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 发布前检查清单（方案 §3 proof-checklist / §4.4【F4/F7】）：全部条目映射真实可算值——
 * 标题/正文字数对平台契约阈值、话题数对 tagHint 建议区间、配图为成功卡数、配图规格
 * 两行分列（生成尺寸=cards.size 实际值 + 发布建议=契约 imageSpec.note，不做画幅核验
 * 断言）、段落长度为本地计算；formatIssues 为共享 composable 的真实规则结果。
 * 「优化建议/量化收益」类原型能力无数据源，一律不渲染（宁缺毋假）。
 *
 * 数据获取二选一（F7）：生产经 inject 只读自取；props 仅作最小测试注入口（缺省回落
 * context，再缺省回落小红书契约常量）。
 */
import { computed, inject } from 'vue'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'
import type { PlatformFormatRule } from '../../../config/platform-format-rules'

/** 段落长度本地警示阈值（方案 §4.4：任一段 >40 字 → badge-warning）。 */
const PARAGRAPH_MAX_CHARS = 40
/** 话题数建议区间（契约 tagHint：「正文末尾添加 3-8 个话题标签…」）。 */
const TOPIC_MIN = 3
const TOPIC_MAX = 8
/** 正文计数口径与 useArticleFormatRule 一致：剥离图片 markdown 后去空白长度。 */
const IMAGE_MD = /!\[[^\]]*\]\([^)]*\)/g

type CheckState = 'pass' | 'attention' | 'info'

interface CheckItem {
  key: string
  label: string
  value: string
  state: CheckState
  badge: string
  notes: string[]
}

const props = defineProps<{
  formatIssues?: string[]
  formatRule?: PlatformFormatRule | null
  selectedTitle?: string
  content?: string
  topics?: string[]
  successCardCount?: number
  cardSize?: string
}>()

const emit = defineEmits<{ goEdit: [] }>()

const injected = inject(XHS_STUDIO_CONTEXT_KEY, null)

const selectedTitle = computed(() => props.selectedTitle ?? injected?.engine.selectedTitle.value ?? '')
const content = computed(() => props.content ?? injected?.engine.content.value ?? '')
const topics = computed(() => props.topics ?? injected?.autosave.deliveryValue.value.topics ?? [])
const successCardCount = computed(() =>
  props.successCardCount ?? injected?.cards.results.value.filter((result) => result.ok).length ?? 0)
const cardSize = computed(() => props.cardSize ?? injected?.cards.size.value ?? '')
const formatIssues = computed(() => props.formatIssues ?? injected?.format.formatIssues.value ?? [])
const formatRule = computed<PlatformFormatRule | null>(() =>
  props.formatRule ?? injected?.format.formatRule.value ?? getPlatformFormatRule('xiaohongshu'))

const bodyCharCount = computed(() => content.value.replace(IMAGE_MD, '').trim().length)

/** 段落切分口径：剥离图片 markdown 后按空行分段（与正文字数同源）。 */
const paragraphs = computed(() => content.value
  .replace(IMAGE_MD, '')
  .split(/\n\n+/)
  .map((paragraph) => paragraph.trim())
  .filter((paragraph) => paragraph !== ''))

const items = computed<CheckItem[]>(() => {
  const rule = formatRule.value
  const list: CheckItem[] = []

  // ① 标题字数 vs 契约 maxTitleChars（小红书 20）。
  const maxTitle = rule?.maxTitleChars ?? null
  const titleLength = selectedTitle.value.trim().length
  if (maxTitle === null) {
    list.push({ key: 'title', label: '标题字数', value: `${titleLength} 字`, state: 'info', badge: '参考', notes: [] })
  } else if (titleLength === 0) {
    list.push({ key: 'title', label: '标题字数', value: `0/${maxTitle} 字`, state: 'attention', badge: '建议关注',
      notes: ['尚未选择标题，请回创作步选定。'] })
  } else if (titleLength > maxTitle) {
    list.push({ key: 'title', label: '标题字数', value: `${titleLength}/${maxTitle} 字`, state: 'attention', badge: '建议关注',
      notes: [`超过建议上限 ${maxTitle} 字，发布时可能被截断。`] })
  } else {
    list.push({ key: 'title', label: '标题字数', value: `${titleLength}/${maxTitle} 字`, state: 'pass', badge: '通过', notes: [] })
  }

  // ② 正文字数 vs 契约 min-max（小红书 50-1000）。
  const minChars = rule?.minChars
  const maxChars = rule?.maxChars
  const bodyLength = bodyCharCount.value
  if (minChars === undefined || maxChars === undefined) {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字`, state: 'info', badge: '参考', notes: [] })
  } else if (bodyLength === 0) {
    list.push({ key: 'body', label: '正文字数', value: `0 字（建议 ${minChars}-${maxChars} 字）`, state: 'attention', badge: '建议关注',
      notes: ['正文为空。'] })
  } else if (bodyLength < minChars) {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字（建议 ${minChars}-${maxChars} 字）`, state: 'attention', badge: '建议关注',
      notes: [`低于建议下限 ${minChars} 字，建议补充核心信息。`] })
  } else if (bodyLength > maxChars) {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字（建议 ${minChars}-${maxChars} 字）`, state: 'attention', badge: '建议关注',
      notes: [`超过建议上限 ${maxChars} 字，发布时可能被截断或影响传播。`] })
  } else {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字（建议 ${minChars}-${maxChars} 字）`, state: 'pass', badge: '通过', notes: [] })
  }

  // ③ 话题数 vs tagHint 建议区间（3-8）。
  const topicCount = topics.value.filter((topic) => topic.trim() !== '').length
  if (topicCount >= TOPIC_MIN && topicCount <= TOPIC_MAX) {
    list.push({ key: 'topics', label: '话题数量', value: `${topicCount} 个（建议 ${TOPIC_MIN}-${TOPIC_MAX} 个）`, state: 'pass', badge: '通过', notes: [] })
  } else {
    list.push({ key: 'topics', label: '话题数量', value: `${topicCount} 个（建议 ${TOPIC_MIN}-${TOPIC_MAX} 个）`, state: 'attention', badge: '建议关注',
      notes: [topicCount === 0 ? '尚未添加话题，可在创作步的话题标签区补充。' : `建议 ${TOPIC_MIN}-${TOPIC_MAX} 个话题，优先覆盖品类、场景与城市词。`] })
  }

  // ④ 配图张数 = 成功卡数（真实计数，无阈值断言）。
  if (successCardCount.value > 0) {
    list.push({ key: 'images', label: '配图张数', value: `${successCardCount.value} 张成功图卡`, state: 'pass', badge: '通过', notes: [] })
  } else {
    list.push({ key: 'images', label: '配图张数', value: '0 张成功图卡', state: 'attention', badge: '建议关注',
      notes: ['尚无成功配图，可在创作步按正文拆卡生成。'] })
  }

  // ⑤【F4】配图规格两行分列：生成尺寸=cards.size 实际值；发布建议=契约 imageSpec.note。
  //   不做画幅核验断言（前端无从核验产物画幅），仅作参考信息展示。
  list.push({
    key: 'spec', label: '配图规格', value: cardSize.value ? `生成尺寸 ${cardSize.value}` : '生成尺寸 —',
    state: 'info', badge: '参考',
    notes: [rule?.imageSpec?.note ? `发布建议：${rule.imageSpec.note}（平台规范）。` : '生成尺寸可在创作步的图卡面板调整。'],
  })

  // ⑥ 段落长度本地警示（任一段 >40 字）。
  const longIndexes = paragraphs.value
    .map((paragraph, index) => ({ length: paragraph.length, index }))
    .filter((item) => item.length > PARAGRAPH_MAX_CHARS)
  if (paragraphs.value.length === 0) {
    list.push({ key: 'paragraphs', label: '段落长度', value: '—', state: 'info', badge: '参考', notes: ['正文为空，暂无段落。'] })
  } else if (longIndexes.length > 0) {
    const longest = Math.max(...paragraphs.value.map((paragraph) => paragraph.length))
    list.push({ key: 'paragraphs', label: '段落长度', value: `最长段落 ${longest} 字`, state: 'attention', badge: '建议关注',
      notes: [`第 ${longIndexes.map((item) => item.index + 1).join('、')} 段超过 ${PARAGRAPH_MAX_CHARS} 字，建议拆短段落。`] })
  } else {
    const longest = paragraphs.value.length
      ? Math.max(...paragraphs.value.map((paragraph) => paragraph.length)) : 0
    list.push({ key: 'paragraphs', label: '段落长度', value: `最长段落 ${longest} 字`, state: 'pass', badge: '通过', notes: [] })
  }

  return list
})

const passCount = computed(() => items.value.filter((item) => item.state === 'pass').length)
const attentionCount = computed(() => items.value.filter((item) => item.state === 'attention').length)

function badgeClass(state: CheckState): string {
  if (state === 'pass') return 'badge-success'
  if (state === 'attention') return 'badge-warning'
  return 'badge-neutral'
}
</script>

<style scoped>
.xhs-proof-checklist {
  display: grid;
  gap: var(--space-sm);
}

.xhs-check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.xhs-check-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-check-summary {
  margin: 0;
  display: flex;
  gap: var(--space-xxs);
  flex-wrap: wrap;
}

.xhs-check-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xs);
}

.xhs-check-item {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-xs) var(--space-sm);
  border: var(--border-width) solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.xhs-check-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.xhs-check-label {
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
  font-weight: var(--weight-label);
}

.xhs-check-value {
  color: var(--color-text);
  font-size: var(--type-body-sm);
}

.xhs-check-item-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-check-issues {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-xs) var(--space-sm);
  border: var(--border-width) solid var(--surface-warning);
  border-radius: var(--radius-md);
}

.xhs-check-issues h3 {
  margin: 0;
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
  color: var(--color-warning);
}

.xhs-check-issues ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xxs);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-check-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
