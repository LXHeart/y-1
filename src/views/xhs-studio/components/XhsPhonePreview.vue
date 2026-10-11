<template>
  <section class="xhs-phone" aria-label="笔记预览">
    <header class="xhs-phone-head">
      <span class="xhs-phone-head-title">笔记预览</span>
      <span v-if="saving" class="xhs-phone-saving" role="status">
        <i class="xhs-phone-dot" aria-hidden="true" />同步中
      </span>
    </header>

    <div class="xhs-phone-scroll">
      <p class="xhs-phone-meta">{{ authorName }}</p>
      <h3 class="xhs-phone-title" :data-empty="title ? undefined : 'true'">{{ title || '未命名笔记' }}</h3>

      <button
        v-if="coverUrl"
        type="button"
        class="xhs-phone-cover-btn"
        aria-label="放大预览首图"
        @click="emit('open', coverUrl)"
      >
        <img class="xhs-phone-cover" :src="coverUrl" alt="笔记首图预览">
      </button>
      <div v-else class="xhs-phone-empty">配图生成后在此预览首图</div>

      <p v-if="body" class="xhs-phone-body">{{ body }}</p>

      <div v-if="topics.length" class="xhs-phone-topics">
        <span v-for="topic in topics" :key="topic" class="xhs-phone-tag">#{{ topic }}</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 右栏手机实时预览（方案 §4.5/§3 preview-phone）：固定浅色 .xhs-phone 壳（预览媒体画布
 * 不随主题，token 见 --xhs-phone-*），只渲染真实字段——标题（已选标题‖当前选题）、正文、
 * 话题（deliveryValue.topics）、封面（position 最小的成功图卡）、作者（当前登录昵称）。
 * 数据经 useXhsStudioContext() 只读派生【F7】，props 仅作测试覆写口；互动数/关注按钮/
 * 编辑时间属地等演示字段一律不渲染。预览内容随四步流程实时演进：pick 显选题、创作步起
 * 显所选标题与流式正文，全程与草稿同源。
 */
import { computed } from 'vue'
import { useAuth } from '../../../composables/useAuth'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'

const props = defineProps<{
  /** 测试覆写口（缺省用 inject 派生的实时值）。 */
  title?: string
  body?: string
  topics?: string[]
  coverUrl?: string
  authorName?: string
  saving?: boolean
}>()

const emit = defineEmits<{
  /** 封面点击放大（视图接 ArticleLightbox）。 */
  open: [url: string]
}>()

const context = useXhsStudioContext()
const auth = useAuth()

/** 标题=已选标题，未选回落当前选题（四步全程实时：pick 显选题，创作步起显所选标题）。 */
const derivedTitle = computed(() => {
  const engine = context.engine
  return engine.selectedTitle.value.trim() || engine.topic.value
})
const derivedBody = computed(() => context.engine.content.value)
const derivedTopics = computed(() =>
  (context.autosave.deliveryValue.value.topics ?? []).filter(topic => topic.trim() !== ''),
)

/** 封面=成功卡中 position 最小者（经 cardId 对齐 planned 位置，缺省按 results 顺序）。 */
const derivedCoverUrl = computed(() => {
  const positionByCardId = new Map<string, number>()
  for (const card of context.cards.cards.value) {
    if (card.cardId != null) positionByCardId.set(card.cardId, card.position ?? Number.MAX_SAFE_INTEGER)
  }
  const succeeded = context.cards.results.value
    .filter(result => result.ok && result.url)
    .map(result => ({ url: result.url, position: positionByCardId.get(result.cardId ?? '') ?? result.index }))
  if (succeeded.length === 0) return ''
  succeeded.sort((a, b) => a.position - b.position)
  return succeeded[0].url
})

/** 作者行=当前登录用户昵称（真实值，空→「我」）。 */
const derivedAuthorName = computed(() => auth.currentUser.value?.displayName?.trim() || '我')
const derivedSaving = computed(() => context.autosave.saveState.value === 'saving')

const title = computed(() => props.title ?? derivedTitle.value)
const body = computed(() => props.body ?? derivedBody.value)
const topics = computed(() => props.topics ?? derivedTopics.value)
const coverUrl = computed(() => props.coverUrl ?? derivedCoverUrl.value)
const authorName = computed(() => props.authorName ?? derivedAuthorName.value)
// Boolean 型 prop 缺省会被 Vue 铸为 false（absent-cast），?? 无法回落派生值——
// 覆写口仅在显式 true 时生效，其余一律走 inject 实时派生。
const saving = computed(() => props.saving || derivedSaving.value)
</script>

<style scoped>
/* 卡头随手机壳固定浅色（--xhs-phone-*，不随主题）。 */
.xhs-phone-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-md);
  background: var(--xhs-phone-surface);
  border-bottom: 1px solid var(--xhs-phone-border);
  color: var(--xhs-phone-text-secondary);
  font-size: var(--type-caption);
}

.xhs-phone-head-title {
  font-weight: var(--weight-heading);
}

.xhs-phone-saving {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xxs);
}

.xhs-phone-dot {
  width: var(--space-xs);
  height: var(--space-xs);
  border-radius: var(--radius-pill);
  background: var(--xhs-phone-accent);
  animation: xhs-phone-breathe 1.6s ease-in-out infinite;
}

@keyframes xhs-phone-breathe {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}

.xhs-phone-title[data-empty='true'] {
  color: var(--xhs-phone-muted);
}

.xhs-phone-cover-btn {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: zoom-in;
}

.xhs-phone-cover-btn:focus-visible {
  outline: 2px solid var(--xhs-phone-accent);
  outline-offset: 2px;
}
</style>
