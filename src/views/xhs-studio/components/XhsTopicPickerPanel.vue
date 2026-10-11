<template>
  <section class="xhs-topic-picker">
    <header class="xhs-block-head">
      <div>
        <h2>选题灵感</h2>
        <p class="gl-hint">自定义选题，或从热榜选一条拆解成小红书笔记选题。</p>
      </div>
      <button
        v-if="hasDraftWork"
        type="button"
        class="xhs-topic-restart"
        :disabled="generating"
        @click="void ctx.resetSession()"
      >重新选题</button>
    </header>

    <div class="gl-form-grid">
      <div class="gl-form-field xhs-topic-field">
        <label for="xhs-topic-title">选题题目<span class="xhs-field-limit">{{ fields.title.length }}/{{ XHS_TOPIC_TITLE_MAX }} 字 · 必填</span></label>
        <input
          id="xhs-topic-title"
          ref="titleRef"
          v-model="fields.title"
          type="text"
          placeholder="例如：通勤穿搭一周不重样"
          @input="onTextInput('title', $event)"
        >
      </div>
      <div class="gl-form-field xhs-topic-field">
        <label for="xhs-topic-angle">切入角度<span class="xhs-field-limit">{{ fields.angle.length }}/{{ XHS_TOPIC_ANGLE_MAX }} 字 · 选填</span></label>
        <input
          id="xhs-topic-angle"
          v-model="fields.angle"
          type="text"
          placeholder="例如：打工人低成本胶囊衣橱"
          @input="onTextInput('angle', $event)"
        >
      </div>
      <div class="gl-form-field xhs-topic-summary">
        <label for="xhs-topic-summary">内容概要<span class="xhs-field-limit">≥ {{ XHS_TOPIC_SUMMARY_MIN }} 字 · 必填</span></label>
        <textarea
          id="xhs-topic-summary"
          ref="summaryRef"
          v-model="fields.summary"
          rows="3"
          placeholder="想覆盖的要点、想突出的人设与目标读者…"
        ></textarea>
      </div>
    </div>

    <p v-if="formError" class="xhs-topic-error" role="alert">{{ formError }}</p>

    <div class="xhs-topic-actions">
      <button
        type="button"
        class="gl-btn-primary"
        :disabled="generating"
        @click="onGenerate"
      >{{ generating ? '生成中…' : '✦ 用此选题生成' }}</button>
    </div>

    <div class="xhs-topic-hot">
      <h3>热榜选题</h3>
      <p class="gl-hint">来自全网热榜（实时抓取），按平台分组；「选为选题」预填题目，可再 AI 拆解为结构化选题。</p>
      <HotTopicPicker
        :items="hotItems"
        :groups="hotGroups"
        :provider="hotProvider"
        :fetched-at="hotFetchedAt"
        :taxonomy="hotTaxonomy"
        :filters="hotFilters"
        :loading="hotLoading"
        :error="hotError"
        :selected-title="fields.title"
        :picked-title="pickedTitle"
        :resolving-topic="resolvingTopic"
        :topic-error="topicError"
        :structured-topic="structuredTopic"
        @refresh="onRefreshHot"
        @filter="onFilterHot"
        @pick="onPickHot"
        @refine="onRefineHot"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * pick 步主面板（方案 §4.2 工程师 A）：自定义选题表单 + 热榜选题（真实源整块复用）。
 * 提交=本地校验（题目必填/概要 ≥10 字，失败提示并聚焦）→ 写 engine.topic 与
 * brief.extraInstructions → fetchTitles → titles 非空进 generate 步，失败 notify 引擎 error。
 * 已有生成物时先 reset({keepPlatform:true}) 清生成物（不走 setTopic 的无参 reset——会退平台）；
 * 热榜 pick 只预填不生成；refine 的 epoch 守卫在 useXhsHotList；「重新选题」直调 ctx.resetSession。
 */
import { computed, ref } from 'vue'
import HotTopicPicker from '../../ai-center/components/HotTopicPicker.vue'
import { useAuth } from '../../../composables/useAuth'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'
import {
  useXhsTopicForm,
  XHS_TOPIC_TITLE_MAX, XHS_TOPIC_ANGLE_MAX, XHS_TOPIC_SUMMARY_MIN,
} from '../composables/useXhsTopicForm'
import { useXhsHotList } from '../composables/useXhsHotList'
import type { HomepageHotFilters } from '../../../types/homepage-hot'
import type { XhsTopicFormFields } from '../composables/useXhsTopicForm'

const emit = defineEmits<{
  generate: []; 'pick-hot': [title: string]; refine: []
  'refresh-hot': []; 'filter-hot': [filters: HomepageHotFilters]; 'request-login': []
}>()

const ctx = useXhsStudioContext()
const auth = useAuth()

const form = useXhsTopicForm(ctx.engine.topic)
const { fields } = form
const hotList = useXhsHotList({
  form,
  isAuthenticated: () => auth.isAuthenticated.value,
  requestLogin: () => emit('request-login'),
})
// 挂载即拉热榜一次（失败不标记 loaded，刷新/重进自动重试）。
void hotList.ensureLoaded()

const {
  items: hotItems, groups: hotGroups, provider: hotProvider, fetchedAt: hotFetchedAt,
  taxonomy: hotTaxonomy, filters: hotFilters, loading: hotLoading, error: hotError,
} = hotList.hot
const { structuredTopic, resolvingTopic, topicError } = hotList.assistant
const { pickedTitle } = hotList

const titleRef = ref<HTMLInputElement | null>(null)
const summaryRef = ref<HTMLTextAreaElement | null>(null)
const formError = ref('')

const generating = computed(() => ctx.engine.titlesLoading.value)
/** 已有生成物（titles/content）时提供「重新选题」显式确认入口。 */
const hasDraftWork = computed(() => ctx.steps.reached.value !== 'pick')

/** 题目/角度超长本地截断（v-model 先落值再截，避免输入法组字冲突）。 */
function onTextInput(field: 'title' | 'angle', event: Event): void {
  const input = event.target as HTMLInputElement
  const capped = input.value.slice(0, field === 'title' ? XHS_TOPIC_TITLE_MAX : XHS_TOPIC_ANGLE_MAX)
  if (capped !== input.value) { input.value = capped; fields[field] = capped }
}

function focusField(field: keyof XhsTopicFormFields): void {
  if (field === 'title') titleRef.value?.focus()
  else if (field === 'summary') summaryRef.value?.focus()
}

async function onGenerate(): Promise<void> {
  if (generating.value) return
  if (!auth.isAuthenticated.value) {
    emit('request-login')
    return
  }
  const error = form.validate()
  if (error) {
    formError.value = error.message
    focusField(error.field)
    return
  }
  formError.value = ''
  const inputs = form.toInputs()
  // 已有生成物时清掉再换选题（保留平台/风格三选/人群；无参 reset 会退平台，禁用）。
  // 图卡与交付草稿同口径清理（F-02）：旧选题的图卡/persistedMediaIds/已编辑话题标签
  // 不得带进新选题的草稿、交付 mediaRefs 与发布顺序（对照 resetSession 的清理面）。
  if (ctx.engine.stage.value !== 'topic') {
    ctx.engine.reset({ keepPlatform: true })
    ctx.autosave.resetCards()
    ctx.autosave.deliveryDraft.value = {}
  }
  ctx.engine.topic.value = inputs.topic
  const brief = ctx.engine.brief.value ?? { processingMode: 'create' as const, voice: { mode: 'none' as const } }
  ctx.engine.brief.value = { ...brief, extraInstructions: inputs.extraInstructions || undefined }
  emit('generate')
  await ctx.engine.fetchTitles()
  if (ctx.engine.titles.value.length > 0) ctx.steps.go('generate')
  else if (ctx.engine.error.value) ctx.notify(ctx.engine.error.value)
}

function onPickHot(title: string): void {
  hotList.pick(title)
  emit('pick-hot', title)
}

function onRefineHot(): void {
  emit('refine')
  void hotList.refine(fields.angle)
}

async function onRefreshHot(): Promise<void> {
  emit('refresh-hot')
  await hotList.refresh()
}

async function onFilterHot(filters: HomepageHotFilters): Promise<void> {
  emit('filter-hot', filters)
  await hotList.applyFilters(filters)
}
</script>

<style scoped>
.xhs-topic-picker { display: grid; gap: var(--space-md); }
.xhs-block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-sm); }
.xhs-block-head h2 {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); line-height: var(--leading-card-title);
}
.xhs-topic-restart { flex: 0 0 auto; }
.xhs-topic-field label, .xhs-topic-summary label {
  display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-xs);
}
.xhs-field-limit { font-size: var(--type-caption); color: var(--color-text-muted); font-variant-numeric: tabular-nums; }
.xhs-topic-summary { grid-column: 1 / -1; }
.xhs-topic-error { margin: 0; color: var(--color-danger); font-size: var(--type-caption); }
.xhs-topic-actions { display: flex; justify-content: flex-end; }
.xhs-topic-hot {
  display: grid; gap: var(--space-xs); padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border);
}
.xhs-topic-hot h3 { margin: 0; font-size: var(--type-body); font-weight: var(--weight-heading); }
.xhs-topic-hot .gl-hint { margin: 0; }
</style>
