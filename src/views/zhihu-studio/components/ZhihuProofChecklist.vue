<template>
  <section class="zhihu-proof-checklist" aria-label="发布前检查">
    <div class="zhihu-check-head">
      <h2>发布前检查</h2>
      <p class="zhihu-check-summary">
        <span class="badge badge-success">{{ passCount }} 项通过</span>
        <span class="badge badge-warning">{{ attentionCount }} 项建议关注</span>
      </p>
    </div>
    <p class="zhihu-check-note">以下逐项来自当前草稿与平台规范的真实计算；建议关注项不阻断发布，发布前请自行核对。</p>

    <ul class="zhihu-check-list">
      <li v-for="item in items" :key="item.key" class="zhihu-check-item" :data-test="`zhihu-check-${item.key}`">
        <div class="zhihu-check-row">
          <span class="zhihu-check-label">{{ item.label }}</span>
          <span class="zhihu-check-value">{{ item.value }}</span>
          <span class="badge" :class="badgeClass(item.state)">{{ item.badge }}</span>
        </div>
        <p v-for="(note, i) in item.notes" :key="i" class="zhihu-check-item-note">{{ note }}</p>
      </li>
    </ul>

    <div v-if="formatIssues.length" class="zhihu-check-issues" data-test="zhihu-check-issues">
      <h3>规范提示</h3>
      <ul>
        <li v-for="(issue, i) in formatIssues" :key="i">{{ issue }}</li>
      </ul>
    </div>

    <div class="zhihu-check-actions">
      <button type="button" class="gl-btn-secondary" data-test="zhihu-check-go-edit" @click="emit('goEdit')">
        返回创作修改
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 发布前检查清单（对位 xhs-studio ProofChecklist，知乎口径）：
 * - 回答模式：①问题字数（≥8，engine MIN_QUESTION_LENGTH 同值）②正文字数（契约 200-3000）
 *   ③段落长度 ④AI 辅助创作声明提醒（真实平台要求，旧视图 finish hints 同源）。
 * - 文章模式：①标题字数（契约 ≤30）②正文字数 ③话题数（契约 tagHint 3-5 个）
 *   ④段落长度 ⑤AI 声明提醒。配图非必检项（知乎可无图发布）不设断言。
 * - formatIssues 为共享 composable 真实规则结果；「优化建议/量化收益/预估指标」
 *   无数据源一律不渲染（宁缺毋假）。
 * - 数据获取二选一（F7 同款）：生产经 inject 只读自取；props 仅最小测试注入口。
 */
import { computed, inject } from 'vue'
import { ZHIHU_STUDIO_CONTEXT_KEY } from '../types'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'
import type { PlatformFormatRule } from '../../../config/platform-format-rules'

/** 与 engine MIN_QUESTION_LENGTH 同值（fetchTitles 回答模式必填判据）。 */
const MIN_QUESTION_CHARS = 8
/** 段落长度本地警示阈值（任一段 >200 字 → badge-warning；知乎长段落容忍度高于笔记）。 */
const PARAGRAPH_MAX_CHARS = 200
/** 话题数建议区间（契约 tagHint：「文章可加 3-5 个话题标签」）。 */
const TOPIC_MIN = 3
const TOPIC_MAX = 5
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
  question?: string
  answerMode?: boolean
}>()

const emit = defineEmits<{ goEdit: [] }>()

const injected = inject(ZHIHU_STUDIO_CONTEXT_KEY, null)

const selectedTitle = computed(() => props.selectedTitle ?? injected?.engine.selectedTitle.value ?? '')
const content = computed(() => props.content ?? injected?.engine.content.value ?? '')
const topics = computed(() => props.topics ?? injected?.autosave.deliveryValue.value.topics ?? [])
const question = computed(() => props.question ?? injected?.engine.question.value ?? '')
const answerMode = computed(() => props.answerMode ?? (injected?.engine.contentMode.value === 'answer'))
const formatIssues = computed(() => props.formatIssues ?? injected?.format.formatIssues.value ?? [])
const formatRule = computed<PlatformFormatRule | null>(() =>
  props.formatRule ?? injected?.format.formatRule.value ?? getPlatformFormatRule('zhihu'))

const bodyCharCount = computed(() => content.value.replace(IMAGE_MD, '').trim().length)

/** 段落切分口径：剥离图片 markdown 后按空行分段（与正文字数同源）。 */
const paragraphs = computed(() => content.value
  .replace(IMAGE_MD, '')
  .split(/\n\s*\n/)
  .map((paragraph) => paragraph.trim())
  .filter((paragraph) => paragraph !== ''))

const items = computed<CheckItem[]>(() => {
  const rule = formatRule.value
  const list: CheckItem[] = []

  // ① 回答：问题字数（≥8）；文章：标题字数（契约 ≤30）。
  if (answerMode.value) {
    const questionLength = question.value.trim().length
    if (questionLength >= MIN_QUESTION_CHARS) {
      list.push({ key: 'question', label: '问题字数', value: `${questionLength} 字`, state: 'pass', badge: '通过', notes: [] })
    } else {
      list.push({ key: 'question', label: '问题字数', value: `${questionLength} 字（至少 ${MIN_QUESTION_CHARS} 字）`, state: 'attention', badge: '建议关注',
        notes: ['问题过短，无法定位目标问题；请回选题步补全。'] })
    }
  } else {
    const maxTitle = rule?.maxTitleChars ?? 30
    const titleLength = selectedTitle.value.trim().length
    if (titleLength === 0) {
      list.push({ key: 'title', label: '标题字数', value: `0/${maxTitle} 字`, state: 'attention', badge: '建议关注',
        notes: ['尚未选择标题，请回创作步选定。'] })
    } else if (titleLength > maxTitle) {
      list.push({ key: 'title', label: '标题字数', value: `${titleLength}/${maxTitle} 字`, state: 'attention', badge: '建议关注',
        notes: [`超过建议上限 ${maxTitle} 字，发布时可能被截断。`] })
    } else {
      list.push({ key: 'title', label: '标题字数', value: `${titleLength}/${maxTitle} 字`, state: 'pass', badge: '通过', notes: [] })
    }
  }

  // ② 正文字数 vs 契约 min-max（知乎 200-3000）。
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
      notes: [`低于建议下限 ${minChars} 字，建议补充论据或适用边界。`] })
  } else if (bodyLength > maxChars) {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字（建议 ${minChars}-${maxChars} 字）`, state: 'attention', badge: '建议关注',
      notes: [`超过建议上限 ${maxChars} 字，发布时可能被截断或影响传播。`] })
  } else {
    list.push({ key: 'body', label: '正文字数', value: `${bodyLength} 字（建议 ${minChars}-${maxChars} 字）`, state: 'pass', badge: '通过', notes: [] })
  }

  // ③ 话题数（仅文章模式；回答绑定问题无话题）。
  if (!answerMode.value) {
    const topicCount = topics.value.filter((topic) => topic.trim() !== '').length
    if (topicCount >= TOPIC_MIN && topicCount <= TOPIC_MAX) {
      list.push({ key: 'topics', label: '话题数量', value: `${topicCount} 个（建议 ${TOPIC_MIN}-${TOPIC_MAX} 个）`, state: 'pass', badge: '通过', notes: [] })
    } else {
      list.push({ key: 'topics', label: '话题数量', value: `${topicCount} 个（建议 ${TOPIC_MIN}-${TOPIC_MAX} 个）`, state: 'attention', badge: '建议关注',
        notes: [topicCount === 0 ? '尚未添加话题，可在创作步的话题标签区补充。' : `建议 ${TOPIC_MIN}-${TOPIC_MAX} 个话题，与选题领域对齐。`] })
    }
  }

  // ④ 段落长度本地警示（任一段 >200 字）。
  const longIndexes = paragraphs.value
    .map((paragraph, index) => ({ length: paragraph.length, index }))
    .filter((item) => item.length > PARAGRAPH_MAX_CHARS)
  if (paragraphs.value.length === 0) {
    list.push({ key: 'paragraphs', label: '段落长度', value: '—', state: 'info', badge: '参考', notes: ['正文为空，暂无段落。'] })
  } else if (longIndexes.length > 0) {
    const longest = Math.max(...paragraphs.value.map((paragraph) => paragraph.length))
    list.push({ key: 'paragraphs', label: '段落长度', value: `最长段落 ${longest} 字`, state: 'attention', badge: '建议关注',
      notes: [`第 ${longIndexes.map((item) => item.index + 1).join('、')} 段超过 ${PARAGRAPH_MAX_CHARS} 字，建议拆短或加小标题分层。`] })
  } else {
    const longest = paragraphs.value.length
      ? Math.max(...paragraphs.value.map((paragraph) => paragraph.length)) : 0
    list.push({ key: 'paragraphs', label: '段落长度', value: `最长段落 ${longest} 字`, state: 'pass', badge: '通过', notes: [] })
  }

  // ⑤ AI 辅助创作声明（真实平台要求，旧视图 finish hints 同源文案）。
  list.push({
    key: 'ai-declaration', label: 'AI 辅助声明', value: '发布时勾选', state: 'info', badge: '必读',
    notes: ['知乎要求 AI 辅助创作须声明，发布时请勾选「AI 生成」声明。'],
  })

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
.zhihu-proof-checklist {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.zhihu-check-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-check-summary {
  margin: 0;
  display: flex;
  gap: var(--space-xs);
}

.zhihu-check-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.zhihu-check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
}

.zhihu-check-item {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-sm) 0;
  border-bottom: var(--border-width) solid var(--color-border);
}

.zhihu-check-item:last-child {
  border-bottom: 0;
}

.zhihu-check-row {
  display: grid;
  grid-template-columns: minmax(72px, auto) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-sm);
}

.zhihu-check-label {
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
}

.zhihu-check-value {
  color: var(--color-text);
  font-size: var(--type-body-sm);
  overflow-wrap: anywhere;
}

.zhihu-check-item-note {
  margin: 0;
  padding-left: calc(72px + var(--space-sm));
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.zhihu-check-issues {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border: var(--border-width) solid var(--surface-warning);
  border-radius: var(--radius-md);
  background: var(--surface-warning);
}

.zhihu-check-issues h3 {
  margin: 0;
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
  color: var(--color-warning);
}

.zhihu-check-issues ul {
  margin: 0;
  padding-left: 1.2em;
  display: grid;
  gap: var(--space-xxs);
}

.zhihu-check-issues li {
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.zhihu-check-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
