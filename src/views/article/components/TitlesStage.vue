<script setup lang="ts">
import { computed } from 'vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import type { ArticlePlatform } from '../../../types/article-creation'
import type { SafetyReport } from '../../../types/content-safety'

/**
 * 标题/开头候选步（任务书 #91 R2 自 ArticleCreationView.vue 模板 261–346 整段迁入，纯搬运；
 * answerMode 分支对整段搬）。返回按钮的目标页签判定（answerMode ? question : topic）留在父层
 * goBack 事件里。title-list/title-counter 样式随迁。
 */
const props = defineProps<{
  answerMode: boolean
  titles: ReadonlyArray<{ title: string; hook?: string }>
  selectedTitle: string
  selectTitle: (title: string) => void
  formatRule: { maxTitleChars: number | null } | null
  formatRuleSummary: string
  titleOverLimit: boolean
  outlineLoading: boolean
  streamOutline: () => void
  safetyReport: SafetyReport | null
  platform: ArticlePlatform
  checkContentForm: string | undefined
}>()

const emit = defineEmits<{
  'update:selectedTitle': [value: string]
  'update:safetyReport': [value: SafetyReport]
  'goBack': []
}>()

const selectedTitleModel = computed({
  get: () => props.selectedTitle,
  set: (value: string) => emit('update:selectedTitle', value),
})
</script>

<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" @click="emit('goBack')">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回
        </button>
        <p class="eyebrow">第二步</p>
      </div>
      <h2 class="card-title">{{ answerMode ? '从候选开头里选一个' : '从候选标题里选一个方向' }}</h2>
      <p class="field-note">
        {{ answerMode
          ? '开头决定读者是否读完；可直接点选，也可以在下方改写成你自己的开场。'
          : '可直接点选，也可以在下方手动改写成你更想要的标题。' }}
      </p>
    </header>

    <div v-if="formatRule && !answerMode" class="format-rule-bar" :class="{ 'format-rule-bar-warn': titleOverLimit }" role="note">
      <p class="format-rule-summary">{{ formatRuleSummary }}</p>
      <p v-if="titleOverLimit" class="format-rule-warn">标题已超过 {{ formatRule.maxTitleChars }} 字建议上限，建议精简后再发布。</p>
    </div>

    <ul class="title-list">
      <li v-for="(t, i) in titles" :key="i">
        <button
          type="button"
          class="title-item"
          :class="{ 'title-selected': selectedTitle === t.title }"
          :aria-pressed="selectedTitle === t.title"
          @click="selectTitle(t.title)"
        >
          <p class="title-text" :class="{ 'title-text-opening': answerMode }">{{ t.title }}</p>
          <p v-if="t.hook" class="title-hook">{{ t.hook }}</p>
        </button>
      </li>
    </ul>

    <div class="custom-title-area">
      <label class="field-note" for="custom-title">{{ answerMode ? '自定义开头' : '自定义标题' }}</label>
      <textarea
        v-if="answerMode"
        id="custom-title"
        v-model="selectedTitleModel"
        class="stream-textarea"
        data-testid="custom-opening-input"
        placeholder="写下你自己的开场（建议 60-120 字，先亮结论或抛判断）..."
        rows="4"
      ></textarea>
      <template v-else>
        <input
          id="custom-title"
          v-model="selectedTitleModel"
          class="custom-title-input"
          type="text"
          placeholder="输入你最终想用的标题..."
        >
        <!-- 任务书 #62：知乎文章标题上限 30 字（契约 platform-format-rules）-->
        <p
          v-if="formatRule && formatRule.maxTitleChars !== null"
          class="field-note title-counter"
          :class="{ 'title-counter-over': titleOverLimit }"
          data-testid="title-char-counter"
        >{{ selectedTitle.trim().length }} / {{ formatRule.maxTitleChars }} 字</p>
      </template>
    </div>

    <div class="action-row">
      <button
        class="btn-primary gl-btn-primary"
        :disabled="outlineLoading || !selectedTitle.trim()"
        @click="streamOutline"
      >
        {{ outlineLoading ? '生成中…' : '生成大纲' }}
      </button>
    </div>
    <SafetyFindingsPanel
      v-if="safetyReport"
      :report="safetyReport"
      :text="selectedTitle || titles.map((item) => item.title).join('\n')"
      :platform="platform"
      :content-form="checkContentForm"
      @updated="emit('update:safetyReport', $event)"
    />
  </section>
</template>

<style scoped src="../stage-shared.css"></style>

<style scoped>
.title-text-opening {
  white-space: pre-wrap;
}

.title-counter {
  margin: 0;
}

.title-counter-over {
  color: var(--color-danger);
  font-weight: 600;
}

.title-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.title-item {
  width: 100%;
  display: grid;
  gap: 6px;
  padding: var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--gradient-surface);
  cursor: pointer;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.title-item:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  transform: translateY(-1px);
}

.title-item:focus-visible {
  outline: none;
  border-color: var(--color-border-accent);
  box-shadow: var(--focus-ring);
}

.title-selected {
  background: var(--color-surface-highlight);
  border-color: var(--color-border-accent);
  box-shadow: var(--focus-ring);
}

.title-text {
  margin: 0;
  color: var(--color-text);
  font-size: 0.96rem;
  font-weight: 600;
  line-height: 1.45;
}
</style>
