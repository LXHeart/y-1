<template>
  <div class="zhihu-side-config">
    <section class="glass-card zhihu-config-card" aria-label="选题台">
      <h2>选题台</h2>
      <p class="zhihu-config-note">{{ answerMode ? '问题与补充说明在生成时生效，随草稿自动保存。' : '选题方向与配置在生成时生效，随草稿自动保存。' }}</p>

      <!-- 回答：目标问题（手输为准，链接本地提取 questionId 仅溯源） -->
      <div v-if="answerMode" class="gl-form-field">
        <label class="field-label" for="zhihu-question-input">目标问题</label>
        <input
          id="zhihu-question-input"
          class="gl-input"
          type="text"
          data-test="zhihu-question-input"
          placeholder="粘贴知乎问题链接，或直接输入问题原文"
          :value="question"
          :disabled="questionLocked"
          @input="emit('update:question', ($event.target as HTMLInputElement).value)"
        >
        <p v-if="questionRef" class="zhihu-config-hint" data-test="zhihu-question-ref">
          已识别问题链接 · questionId {{ questionRef }}（仅本地溯源存档，不发起抓取）
        </p>
        <p v-else class="zhihu-config-hint">至少 {{ minQuestionChars }} 字；问题本身即标题，回答无独立标题。</p>
        <p v-if="questionLocked" class="zhihu-config-hint">任务指定问题，不可修改。</p>
      </div>

      <!-- 文章：选题方向 -->
      <div v-else class="gl-form-field">
        <label class="field-label" for="zhihu-topic-input">选题方向</label>
        <input
          id="zhihu-topic-input"
          class="gl-input"
          type="text"
          data-test="zhihu-topic-input"
          placeholder="想写什么？一句话描述（最多 200 字）"
          :value="topic"
          @input="emit('update:topic', ($event.target as HTMLInputElement).value)"
        >
      </div>

      <!-- 回答：补充说明（engine.topic 在回答模式的后端语义=可选补充） -->
      <div v-if="answerMode" class="gl-form-field">
        <label class="field-label" for="zhihu-supplement-input">补充说明（可选）</label>
        <textarea
          id="zhihu-supplement-input"
          class="gl-input zhihu-config-textarea"
          data-test="zhihu-supplement-input"
          rows="3"
          placeholder="切入角度、需要覆盖的要点等，随生成请求发出"
          :value="topic"
          @input="emit('update:topic', ($event.target as HTMLTextAreaElement).value)"
        ></textarea>
      </div>

      <div class="gl-form-field">
        <span class="field-label">领域</span>
        <div class="gl-chips" role="radiogroup" aria-label="领域">
          <button
            v-for="option in genreOptions"
            :key="option.code"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': genre === option.code }"
            role="radio"
            :aria-checked="genre === option.code"
            :title="option.description"
            :data-test="`zhihu-genre-${option.code}`"
            @click="emit('update:genre', option.code)"
          >{{ option.name }}</button>
        </div>
        <p v-if="genreOptions.length === 0 && !loading" class="zhihu-config-hint">暂无可选领域（服务端目录为空）。</p>
      </div>

      <div class="gl-form-field">
        <span class="field-label">语气</span>
        <div class="gl-chips" role="radiogroup" aria-label="语气">
          <button
            v-for="option in styleOptions"
            :key="option.code"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': style === option.code }"
            role="radio"
            :aria-checked="style === option.code"
            :title="option.description"
            :data-test="`zhihu-style-${option.code}`"
            @click="emit('update:style', option.code)"
          >{{ option.name }}</button>
        </div>
      </div>

      <div class="gl-form-field">
        <span class="field-label">目标读者</span>
        <div class="gl-chips" role="radiogroup" aria-label="目标读者">
          <button
            v-for="option in audienceOptions"
            :key="option"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': audience === option }"
            role="radio"
            :aria-checked="audience === option"
            @click="emit('update:audience', audience === option ? '' : option)"
          >{{ option }}</button>
        </div>
        <p class="zhihu-config-hint">可不选；选择后写入创作简报，随生成请求生效。</p>
      </div>

      <p v-if="loading" class="zhihu-config-hint">风格目录加载中…</p>
      <p v-else-if="error" class="zhihu-config-error" role="alert">
        {{ error }}
        <button type="button" class="zhihu-config-retry" @click="emit('retry')">重试</button>
      </p>

      <button
        type="button"
        class="gl-btn-primary zhihu-config-generate"
        data-test="zhihu-generate"
        :disabled="generating || !canGenerate"
        @click="onGenerate"
      >{{ generating ? '生成中…' : generateLabel }}</button>

      <p class="zhihu-config-credits">
        积分余额：
        <span class="gl-num">{{ balance === null ? (balanceError ? '获取失败' : '…') : `${balance} 次` }}</span>
      </p>
    </section>

    <section class="glass-card zhihu-config-card" aria-label="平台规范">
      <h2>平台规范</h2>
      <p class="zhihu-config-summary" data-test="zhihu-format-summary">{{ formatSummary }}</p>
      <p class="zhihu-config-hint">{{ tipText }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 左栏选题台（原型「选题台 · 回答/文章」的真身）：形态分叉输入（回答=目标问题 +
 * 补充说明；文章=选题方向）+ 领域/语气 chips（服务端风格目录，#62 向知乎开放）+
 * 目标读者（brief.audience 真实端到端字段）+ 生成主按钮 + 积分余额。
 *
 * - 问题输入经 engine.setQuestion：原文照存，链接本地正则提取 questionId 仅溯源
 *   （zhihu-question.ts 零网络铁律）；任务锁定问题时只读。
 * - 目标字数滑杆/所属专栏/封面风格不渲染：无生成参数通路或数据源（defer，宁缺毋假）。
 * - 创作提示=契约 structureHints 真实文案，非编造运营话术。
 */
import { computed } from 'vue'
import { useAuth } from '../../../composables/useAuth'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

const props = defineProps<{
  answerMode: boolean
  /** 回答：目标问题（engine.question）；文章：选题方向/回答补充说明（engine.topic）。 */
  question: string
  questionRef: string
  questionLocked?: boolean
  topic: string
  genreOptions: CreationStyleSkillOption[]
  styleOptions: CreationStyleSkillOption[]
  genre: string
  style: string
  loading: boolean
  error: string
  audience: string
  /** null=未加载/失败（与成功 0 区分）；错误经 balanceError 区分文案。 */
  balance: number | null
  balanceError: string
  formatSummary: string
  canGenerate: boolean
  generating: boolean
  generateLabel: string
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'update:topic': [value: string]
  'update:genre': [code: string]
  'update:style': [code: string]
  'update:audience': [value: string]
  retry: []
  generate: []
  /** 未登录点生成：上抛壳层拉起登录（AiAppLayout 已接同款事件）。 */
  'request-login': []
}>()

/** 与 engine MIN_QUESTION_LENGTH 同值（fetchTitles 的回答模式必填判据）。 */
const minQuestionChars = 8
const audienceOptions = computed(() => ['泛读者', '行业从业者', '学生 / 求职者'])
const auth = useAuth()

/** 契约 structureHints 摘要（真实平台规范文案，按形态分叉）。 */
const tipText = computed(() => props.answerMode
  ? '回答直切要点，说明依据与适用边界；先结论后论据。'
  : '论点结构清晰，先结论后论据；文章可加 3-5 个话题标签。')

function onGenerate(): void {
  if (props.generating || !props.canGenerate) return
  if (!auth.isAuthenticated.value) {
    emit('request-login')
    return
  }
  emit('generate')
}
</script>

<style scoped>
.zhihu-side-config {
  display: grid;
  gap: var(--space-md);
}

.zhihu-config-card {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-config-card h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-config-card > p {
  margin: 0;
}

.zhihu-config-note,
.zhihu-config-hint {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.zhihu-config-summary {
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
  line-height: var(--leading-body-sm);
}

.zhihu-config-textarea {
  resize: vertical;
  min-height: 72px;
  font-family: inherit;
}

.zhihu-config-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-danger);
  font-size: var(--type-caption);
}

.zhihu-config-retry {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-danger);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.zhihu-config-generate {
  min-height: var(--control-height);
}

.zhihu-config-credits {
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}
</style>
