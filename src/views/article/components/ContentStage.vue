<script setup lang="ts">
import { computed } from 'vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import type { ArticlePlatform } from '../../../types/article-creation'
import type { SafetyReport } from '../../../types/content-safety'

/**
 * 正文步（任务书 #91 R3 自 ArticleCreationView.vue 模板 412–491 整段迁入，纯搬运；
 * stream/format 样式在 stage-shared.css）。
 */
const props = defineProps<{
  content: string
  contentLoading: boolean
  goToOutline: () => void
  copyContent: () => void
  copied: boolean
  formatRule: { tagHint?: string | null } | null
  formatRuleSummary: string
  formatIssues: ReadonlyArray<string>
  cancel: () => void
  noteMode: boolean
  resetWorkflow: () => void
  enterCheck: () => void
  safetyReport: SafetyReport | null
  platform: ArticlePlatform
  checkContentForm: string | undefined
  /** 任务书 #101 C101-17：排版会话（原稿直达正文——返回大纲/生成文案不适用）。 */
  formatMode?: boolean
}>()

const emit = defineEmits<{
  'update:content': [value: string]
  'update:safetyReport': [value: SafetyReport]
  /** C101-17：显式排版入口（滚到/展开下方排版预览面板）。 */
  'open-format': []
}>()

const contentModel = computed({
  get: () => props.content,
  set: (value: string) => emit('update:content', value),
})
</script>

<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" @click="goToOutline">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回
        </button>
        <p class="eyebrow">第四步</p>
      </div>
      <div class="card-head-row card-head-row-wrap">
        <div>
          <h2 class="card-title">文章正文</h2>
          <!-- C101-17：format 会话正文来自原稿（不「生成」）——固定步骤文案按模式适配 -->
          <p class="field-note" data-test="content-stage-note">
            {{ formatMode ? '原稿已导入正文，可直接编辑或到下方排版预览。' : '正文支持边生成边查看，完成后可继续人工润色。' }}
          </p>
        </div>
        <div class="head-actions">
          <button v-if="formatMode" class="btn-secondary btn-sm" data-test="open-format" @click="emit('open-format')">
            排版预览
          </button>
          <button class="btn-secondary btn-sm" @click="copyContent">
            {{ copied ? '已复制' : '复制正文' }}
          </button>
        </div>
      </div>
    </header>

    <div v-if="formatRule" class="format-rule-bar" :class="{ 'format-rule-bar-warn': formatIssues.length > 0 }" role="note">
      <p class="format-rule-summary">{{ formatRuleSummary }}</p>
      <p v-if="formatRule.tagHint" class="format-rule-hint">{{ formatRule.tagHint }}</p>
      <ul v-if="formatIssues.length > 0" class="format-rule-warnings">
        <li v-for="issue in formatIssues" :key="issue">{{ issue }}</li>
      </ul>
    </div>

    <div class="stream-area stream-area-large">
      <textarea
        v-model="contentModel"
        class="stream-textarea"
        :class="{ 'stream-loading': contentLoading }"
        placeholder="正文会在这里实时生成..."
        rows="20"
      ></textarea>
      <div v-if="contentLoading" class="stream-badge">
        <span class="stream-dot"></span>
        生成中
      </div>
    </div>

    <p v-if="noteMode" class="field-note" data-test="note-mode-hint">
      小红书图文正文不配图：视觉素材用下方「拆成小红书图卡」生成；结尾话题标签已默认生成，可直接在正文末尾修改。
    </p>
    <p v-if="formatMode" class="field-note" data-test="format-mode-hint">
      排版模式不改写原稿：标题／摘要等元数据在交付面板单独编辑，摘要建议由「生成摘要建议」独立发起。
    </p>

    <div class="action-row">
      <button class="btn-secondary" @click="resetWorkflow">
        重新开始
      </button>
      <button
        v-if="contentLoading"
        class="btn-secondary"
        @click="cancel"
      >
        取消
      </button>
      <!-- 任务书 #63 卡5：正文步的收口统一走检查步（完成/软确认都在检查步），不在正文步直接完成 -->
      <button
        v-if="!contentLoading && content.trim()"
        class="btn-primary gl-btn-primary"
        data-test="go-check"
        @click="enterCheck"
      >
        去检查
      </button>
    </div>

    <SafetyFindingsPanel
      v-if="safetyReport"
      :report="safetyReport"
      :text="content"
      :platform="platform"
      :content-form="checkContentForm"
      @updated="emit('update:safetyReport', $event)"
    />
  </section>
</template>

<style scoped src="../stage-shared.css"></style>

<style scoped>
.head-actions { display: flex; gap: 8px; align-items: center; }
</style>
