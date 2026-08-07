<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useCreationAssistant } from '../composables/useCreationAssistant'
import { useCreationDraft } from '../composables/useCreationDraft'
import type { CreationDraft } from '../types/creation-assistant'
import type { CreationSource } from '../types/ai-creation'

/**
 * 智能创作助手面板（PRD §4.9 / Slice 15 Stage 5）。
 *
 * 四块能力合在一个面板，因为它们共用同一份「当前草稿 + 内容」上下文：
 * - 草稿（§4.9.7）：列表 / 新建 / 打开，正文编辑走 debounce 自动保存（乐观锁，409 给合并入口）。
 * - 引导（§4.9.1/§4.9.2）：chat 形式问答，AI 回追问或创作简报；简报里被推测的字段打标记。
 * - 评分 + 建议（§4.9.4/§4.9.6）：对当前正文评分（五维度）并给优化建议。
 * - 任务覆盖（§4.9.3）：仅任务来源草稿可用，任务要求由调用方从草场快照传入。
 *
 * 热点→结构化选题（§4.9.5）不在这里——那是创作中心选题阶段的事，见 AiCreationCenter。
 */

const props = defineProps<{
  authenticated: boolean
  platform?: string
  contentForm?: string
  source?: CreationSource
  topic?: string
  /** 任务来源时的任务要求快照（intelligence 不跨服务读 marketplace，必须由前端带入）。 */
  taskRequirements?: string
}>()

const emit = defineEmits<{ 'request-login': [] }>()

const assistant = useCreationAssistant()
const draftStore = useCreationDraft()

type AssistantTab = 'draft' | 'guide' | 'score'
const activeTab = ref<AssistantTab>('draft')
const newDraftTitle = ref('')
const chatInput = ref('')
const contentDraft = ref('')

const MIN_SCORE_LENGTH = 10

const current = computed(() => draftStore.draft.value)
const canScore = computed(() => contentDraft.value.trim().length >= MIN_SCORE_LENGTH)
const canCheckCoverage = computed(() =>
  canScore.value && Boolean(props.taskRequirements && props.taskRequirements.trim()))

const autosaveLabel = computed(() => {
  switch (draftStore.autosaveState.value) {
    case 'pending': return '待保存…'
    case 'saving': return '保存中…'
    case 'saved': return '已保存'
    case 'conflict': return '版本冲突'
    case 'error': return '保存失败'
    default: return ''
  }
})

/** 打开草稿时把正文灌进编辑框；切换草稿要跟着换，否则会把上一份的正文存到新草稿上。 */
watch(current, (draft, previous) => {
  contentDraft.value = draft?.content ?? ''
  if (draft?.id !== previous?.id) assistant.resetAssessments()
})

function onContentInput(): void {
  if (!current.value) return
  assistant.resetAssessments()
  draftStore.queueSave({ content: contentDraft.value })
}

async function reloadForConflict(): Promise<void> {
  const reloaded = await draftStore.reloadForConflict()
  if (reloaded) assistant.resetAssessments()
}

async function createDraft(): Promise<void> {
  if (!props.authenticated) {
    emit('request-login')
    return
  }
  const source = props.source
  await draftStore.createDraft({
    title: newDraftTitle.value.trim() || undefined,
    sourceType: source?.type ?? 'independent',
    taskId: source?.type === 'task' ? source.taskId : undefined,
    taskVersion: source?.type === 'task' ? source.taskVersion : undefined,
    storeId: source?.type === 'store' ? source.storeId : undefined,
    platform: props.platform,
    contentForm: props.contentForm,
    topic: props.topic?.trim()
      || (source?.type === 'hot-topic' ? source.title.trim() : undefined),
  })
  newDraftTitle.value = ''
}

async function openDraft(draft: CreationDraft): Promise<void> {
  await draftStore.openDraft(draft.id)
}

async function sendMessage(): Promise<void> {
  if (!props.authenticated) {
    emit('request-login')
    return
  }
  const text = chatInput.value.trim()
  if (!text || assistant.guiding.value) return
  chatInput.value = ''
  await assistant.sendGuideMessage(text, props.platform)
}

/** 把简报落进当前草稿的大纲，接上后续「大纲→正文」链路。 */
function applyBriefToDraft(): void {
  const brief = assistant.brief.value
  if (!brief || !current.value) return
  const outline = [
    `角度：${brief.angle}`,
    `受众：${brief.audience}`,
    `结构：${brief.structure}`,
  ].join('\n')
  draftStore.queueSave({ outline })
}

function isInferred(field: string): boolean {
  return assistant.brief.value?.inferredFields.includes(field) ?? false
}

async function runScore(): Promise<void> {
  if (!props.authenticated) {
    emit('request-login')
    return
  }
  await assistant.runScore(contentDraft.value.trim(), props.platform, current.value?.articleTitle)
}

async function runSuggest(): Promise<void> {
  if (!props.authenticated) {
    emit('request-login')
    return
  }
  await assistant.runSuggest(contentDraft.value.trim(), props.platform, current.value?.articleTitle)
}

async function runCoverage(): Promise<void> {
  if (!props.taskRequirements) return
  await assistant.checkTaskCoverage(
    contentDraft.value.trim(), props.taskRequirements, props.platform)
}

onMounted(() => {
  if (props.authenticated) void draftStore.loadDrafts()
})

// 组件卸载时草稿要落盘（用户切 tab 就走，debounce 还没到点）+ 收掉所有在飞的流。
onBeforeUnmount(() => {
  void draftStore.flush()
  assistant.cancelAll()
})
</script>

<template>
  <section class="assistant">
    <nav class="as-tabs">
      <button
        v-for="tab in ([
          { id: 'draft', label: '草稿' },
          { id: 'guide', label: '问答引导' },
          { id: 'score', label: '评分与建议' },
        ] as ReadonlyArray<{ id: AssistantTab; label: string }>)"
        :key="tab.id"
        type="button"
        :class="['as-tab', { active: activeTab === tab.id }]"
        @click="activeTab = tab.id"
      >{{ tab.label }}</button>
    </nav>

    <p v-if="!props.authenticated" class="as-alert">
      登录后可使用创作助手（草稿会按账号保存）。
      <button type="button" class="as-link" @click="emit('request-login')">去登录</button>
    </p>

    <p v-if="draftStore.error.value" class="as-err">{{ draftStore.error.value }}</p>

    <!-- 草稿 -->
    <div v-if="activeTab === 'draft'" class="as-body">
      <div class="as-row">
        <input
          v-model="newDraftTitle"
          class="as-input"
          type="text"
          placeholder="草稿标题（可留空）"
          :disabled="!props.authenticated"
        >
        <button
          type="button"
          class="as-btn"
          :disabled="!props.authenticated || draftStore.loading.value"
          @click="createDraft"
        >新建草稿</button>
      </div>

      <ul v-if="draftStore.drafts.value.length" class="as-list">
        <li
          v-for="item in draftStore.drafts.value"
          :key="item.id"
          :class="['as-item', { active: current?.id === item.id }]"
        >
          <button type="button" class="as-item-open" @click="openDraft(item)">
            <span class="as-item-title">{{ item.title }}</span>
            <span class="as-item-meta">v{{ item.version }} · {{ item.status }}</span>
          </button>
          <button type="button" class="as-item-del" @click="draftStore.removeDraft(item.id)">删除</button>
        </li>
      </ul>
      <p v-else-if="props.authenticated && !draftStore.loading.value" class="as-empty">
        还没有草稿，新建一个开始创作。
      </p>

      <div v-if="current" class="as-editor">
        <header class="as-editor-head">
          <span class="as-editor-title">{{ current.title }}</span>
          <span :class="['as-save', draftStore.autosaveState.value]">{{ autosaveLabel }}</span>
        </header>

        <p v-if="draftStore.autosaveState.value === 'conflict'" class="as-conflict">
          这份草稿在别处被修改过。重新载入会丢弃本地未保存的改动。
          <button type="button" class="as-link" @click="reloadForConflict">重新载入</button>
        </p>

        <textarea
          v-model="contentDraft"
          class="as-textarea"
          rows="10"
          placeholder="在这里写正文，停止输入后自动保存"
          :disabled="draftStore.loading.value"
          @input="onContentInput"
        />
      </div>
    </div>

    <!-- 问答引导 -->
    <div v-else-if="activeTab === 'guide'" class="as-body">
      <div class="as-chat">
        <p v-if="!assistant.messages.value.length" class="as-empty">
          说说你想写什么，助手会一步步问清楚，最后给出创作简报。
        </p>
        <div
          v-for="(msg, index) in assistant.messages.value"
          :key="index"
          :class="['as-msg', msg.role]"
        >
          <p class="as-msg-text">{{ msg.text }}</p>
          <dl v-if="msg.brief" class="as-brief">
            <div class="as-brief-row">
              <dt>角度</dt>
              <dd>
                {{ msg.brief.angle }}
                <span v-if="isInferred('angle')" class="as-inferred">AI 推测</span>
              </dd>
            </div>
            <div class="as-brief-row">
              <dt>受众</dt>
              <dd>
                {{ msg.brief.audience }}
                <span v-if="isInferred('audience')" class="as-inferred">AI 推测</span>
              </dd>
            </div>
            <div class="as-brief-row">
              <dt>结构</dt>
              <dd>
                {{ msg.brief.structure }}
                <span v-if="isInferred('structure')" class="as-inferred">AI 推测</span>
              </dd>
            </div>
          </dl>
        </div>
        <p v-if="assistant.guiding.value" class="as-typing">助手正在思考…</p>
        <p v-if="assistant.guideError.value" class="as-err">{{ assistant.guideError.value }}</p>
      </div>

      <div class="as-row">
        <input
          v-model="chatInput"
          class="as-input"
          type="text"
          placeholder="描述你的想法，回车发送"
          :disabled="assistant.guiding.value"
          @keyup.enter="sendMessage"
        >
        <button
          type="button"
          class="as-btn"
          :disabled="assistant.guiding.value || !chatInput.trim()"
          @click="sendMessage"
        >发送</button>
      </div>
      <div class="as-row">
        <button
          v-if="assistant.brief.value && current"
          type="button"
          class="as-btn ghost"
          @click="applyBriefToDraft"
        >把简报写入当前草稿大纲</button>
        <button
          v-if="assistant.messages.value.length"
          type="button"
          class="as-btn ghost"
          @click="assistant.resetGuide()"
        >重新开始</button>
      </div>
    </div>

    <!-- 评分与建议 -->
    <div v-else class="as-body">
      <p v-if="!current" class="as-empty">先在「草稿」里打开一份草稿，再来评分。</p>
      <template v-else>
        <p v-if="!canScore" class="as-empty">正文至少 {{ MIN_SCORE_LENGTH }} 字才能评分。</p>
        <div class="as-row">
          <button
            type="button"
            class="as-btn"
            :disabled="!canScore || assistant.scoring.value"
            @click="runScore"
          >{{ assistant.scoring.value ? '评分中…' : '内容评分' }}</button>
          <button
            type="button"
            class="as-btn ghost"
            :disabled="!canScore || assistant.suggesting.value"
            @click="runSuggest"
          >{{ assistant.suggesting.value ? '生成中…' : '优化建议' }}</button>
          <button
            v-if="canCheckCoverage"
            type="button"
            class="as-btn ghost"
            :disabled="assistant.checkingCoverage.value"
            @click="runCoverage"
          >{{ assistant.checkingCoverage.value ? '检查中…' : '任务覆盖检查' }}</button>
        </div>

        <p v-if="assistant.scoreError.value" class="as-err">{{ assistant.scoreError.value }}</p>
        <div v-if="assistant.score.value" class="as-score">
          <p class="as-overall">综合得分 {{ assistant.score.value.overall }}</p>
          <div
            v-for="dim in assistant.score.value.dimensions"
            :key="dim.dimension"
            class="as-dim"
          >
            <div class="as-dim-head">
              <span class="as-dim-name">{{ dim.dimension }}</span>
              <span class="as-dim-score">{{ dim.score }}</span>
            </div>
            <p class="as-dim-advice">{{ dim.advice }}</p>
          </div>
        </div>

        <p v-if="assistant.suggestError.value" class="as-err">{{ assistant.suggestError.value }}</p>
        <pre v-if="assistant.suggestion.value" class="as-suggestion">{{ assistant.suggestion.value }}</pre>

        <p v-if="assistant.coverageError.value" class="as-err">{{ assistant.coverageError.value }}</p>
        <div v-if="assistant.coverage.value" class="as-coverage">
          <p v-if="assistant.coverage.value.covered" class="as-ok">任务要求已全部覆盖。</p>
          <p v-else class="as-warn">还有未覆盖的任务要求：</p>
          <ul v-if="assistant.coverage.value.gaps.length" class="as-gaps">
            <li v-for="(gap, index) in assistant.coverage.value.gaps" :key="index" class="as-gap">
              <span class="as-gap-req">{{ gap.requirement }}</span>
              <span class="as-gap-status">{{ gap.status }}</span>
              <span v-if="gap.hint" class="as-gap-hint">{{ gap.hint }}</span>
            </li>
          </ul>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.assistant { display: flex; flex-direction: column; gap: 12px; }
.as-tabs { display: flex; gap: 8px; }
.as-tab {
  padding: 6px 14px; border: 1px solid var(--border-color, #d0d7de);
  border-radius: 999px; background: transparent; cursor: pointer; font-size: 14px;
}
.as-tab.active { background: var(--accent-color, #2563eb); color: #fff; border-color: transparent; }
.as-body { display: flex; flex-direction: column; gap: 12px; }
.as-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.as-input {
  flex: 1 1 220px; padding: 8px 10px;
  border: 1px solid var(--border-color, #d0d7de); border-radius: 6px;
}
.as-btn {
  padding: 8px 16px; border: none; border-radius: 6px;
  background: var(--accent-color, #2563eb); color: #fff; cursor: pointer;
}
.as-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.as-btn.ghost {
  background: transparent; color: var(--accent-color, #2563eb);
  border: 1px solid var(--accent-color, #2563eb);
}
.as-link {
  background: none; border: none; padding: 0;
  color: var(--accent-color, #2563eb); cursor: pointer; text-decoration: underline;
}
.as-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.as-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border: 1px solid var(--border-color, #d0d7de); border-radius: 6px;
}
.as-item.active { border-color: var(--accent-color, #2563eb); }
.as-item-open {
  flex: 1; display: flex; justify-content: space-between; gap: 8px;
  background: none; border: none; cursor: pointer; text-align: left;
}
.as-item-title { font-weight: 500; }
.as-item-meta, .as-editor-title { font-size: 12px; opacity: 0.7; }
.as-item-del { background: none; border: none; color: #b91c1c; cursor: pointer; font-size: 12px; }
.as-editor { display: flex; flex-direction: column; gap: 8px; }
.as-editor-head { display: flex; justify-content: space-between; align-items: center; }
.as-editor-title { font-size: 14px; font-weight: 500; opacity: 1; }
.as-save { font-size: 12px; opacity: 0.75; }
.as-save.saved { color: #15803d; }
.as-save.conflict, .as-save.error { color: #b45309; }
.as-textarea {
  width: 100%; padding: 10px; border-radius: 6px; resize: vertical;
  border: 1px solid var(--border-color, #d0d7de); font: inherit;
}
.as-chat {
  display: flex; flex-direction: column; gap: 10px; max-height: 360px; overflow-y: auto;
  padding: 10px; border: 1px solid var(--border-color, #d0d7de); border-radius: 6px;
}
.as-msg { max-width: 88%; padding: 8px 12px; border-radius: 10px; }
.as-msg.user { align-self: flex-end; background: var(--accent-color, #2563eb); color: #fff; }
.as-msg.assistant { align-self: flex-start; background: rgba(127, 127, 127, 0.12); }
.as-msg-text { margin: 0; white-space: pre-wrap; }
.as-brief { margin: 8px 0 0; display: flex; flex-direction: column; gap: 4px; }
.as-brief-row { display: flex; gap: 8px; font-size: 13px; }
.as-brief-row dt { min-width: 36px; opacity: 0.7; }
.as-brief-row dd { margin: 0; }
.as-inferred {
  margin-left: 6px; padding: 1px 6px; border-radius: 999px; font-size: 11px;
  background: #fef3c7; color: #92400e;
}
.as-typing, .as-empty, .as-alert { font-size: 13px; opacity: 0.75; margin: 0; }
.as-err { color: #b91c1c; font-size: 13px; margin: 0; }
.as-ok { color: #15803d; font-size: 13px; margin: 0; }
.as-warn { color: #b45309; font-size: 13px; margin: 0; }
.as-conflict {
  margin: 0; padding: 8px 10px; border-radius: 6px; font-size: 13px;
  background: #fef3c7; color: #92400e;
}
.as-score { display: flex; flex-direction: column; gap: 8px; }
.as-overall { margin: 0; font-size: 15px; font-weight: 600; }
.as-dim {
  padding: 8px 10px; border-radius: 6px;
  border: 1px solid var(--border-color, #d0d7de);
}
.as-dim-head { display: flex; justify-content: space-between; }
.as-dim-name { font-weight: 500; }
.as-dim-advice { margin: 4px 0 0; font-size: 13px; opacity: 0.85; }
.as-suggestion {
  margin: 0; padding: 10px; border-radius: 6px; white-space: pre-wrap;
  background: rgba(127, 127, 127, 0.1); font: inherit;
}
.as-coverage { display: flex; flex-direction: column; gap: 6px; }
.as-gaps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.as-gap {
  display: flex; gap: 8px; flex-wrap: wrap; align-items: baseline;
  padding: 8px 10px; border-radius: 6px; border: 1px solid #fcd34d;
}
.as-gap-req { font-weight: 500; }
.as-gap-status { font-size: 12px; padding: 1px 6px; border-radius: 999px; background: #fef3c7; color: #92400e; }
.as-gap-hint { font-size: 13px; opacity: 0.8; }
</style>
