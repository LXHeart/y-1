<template>
  <div class="hot-picker">
    <div class="hot-picker-head">
      <p class="hot-picker-note">{{ metaNote }}</p>
      <button type="button" class="secondary-command hot-refresh" :disabled="loading" @click="$emit('refresh')">
        {{ loading ? '刷新中…' : '刷新热点' }}
      </button>
    </div>

    <div v-if="loading && !hasContent" class="hot-skeleton-list" aria-hidden="true">
      <div v-for="index in 4" :key="index" class="hot-skeleton"></div>
    </div>

    <p v-else-if="error" class="error-state" role="alert">{{ error }}</p>

    <template v-else-if="hasContent">
      <div v-if="groups.length > 1" class="hot-tabs" role="tablist" aria-label="热点平台">
        <button
          v-for="group in groups"
          :key="group.platform"
          type="button"
          role="tab"
          :aria-selected="activePlatform === group.platform"
          :class="{ active: activePlatform === group.platform }"
          @click="activePlatform = group.platform"
        >{{ group.label }}</button>
      </div>

      <ol class="hot-list">
        <li v-for="item in activeItems" :key="`${item.rank}-${item.title}`" class="hot-item">
          <span class="hot-rank">{{ item.rank }}</span>
          <div class="hot-main">
            <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
            <p v-else class="hot-title">{{ item.title }}</p>
            <span class="hot-meta">
              <template v-if="item.hotValue">热度 {{ item.hotValue }}</template>
              <template v-if="item.hotValue && item.sourceLabel"> · </template>
              <template v-if="item.sourceLabel">{{ item.sourceLabel }}</template>
            </span>
          </div>
          <button
            type="button"
            class="hot-pick"
            :class="{ selected: selectedTitle === item.title }"
            @click="$emit('pick', item.title)"
          >{{ selectedTitle === item.title ? '已选' : '选为选题' }}</button>
        </li>
      </ol>
    </template>

    <p v-else class="hot-empty-note">暂无热点数据，稍后点击「刷新热点」重试。</p>

    <div v-if="pickedTitle" class="hot-refine">
      <button
        type="button"
        class="secondary-command"
        :disabled="resolvingTopic"
        @click="$emit('refine')"
      >{{ resolvingTopic ? '生成选题中…' : 'AI 拆解为结构化选题' }}</button>
      <p v-if="topicError" class="error-state" role="alert">{{ topicError }}</p>
      <dl v-else-if="structuredTopic" class="hot-topic-detail">
        <div><dt>选题</dt><dd>{{ structuredTopic.topic }}</dd></div>
        <div><dt>角度</dt><dd>{{ structuredTopic.angle }}</dd></div>
        <div><dt>立意</dt><dd>{{ structuredTopic.thesis }}</dd></div>
        <div><dt>受众</dt><dd>{{ structuredTopic.audience }}</dd></div>
        <div v-if="structuredTopic.entryPoints.length">
          <dt>切入点</dt>
          <dd>
            <ul class="hot-entry-points">
              <li v-for="(point, index) in structuredTopic.entryPoints" :key="index">
                {{ point }}
              </li>
            </ul>
          </dd>
        </div>
      </dl>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { HomepageHotItem, HomepageHotItemGroup } from '../../../types/homepage-hot'

interface StructuredTopic {
  topic: string
  angle: string
  thesis: string
  audience: string
  entryPoints: string[]
}

const props = defineProps<{
  items: HomepageHotItem[]
  groups: HomepageHotItemGroup[]
  provider: string
  fetchedAt: string
  loading: boolean
  error: string
  selectedTitle: string
  pickedTitle: string
  resolvingTopic: boolean
  topicError: string
  structuredTopic: StructuredTopic | null
}>()

defineEmits<{
  refresh: []
  pick: [title: string]
  refine: []
}>()

const activePlatform = ref('')

const hasContent = computed(() => props.groups.length > 0 || props.items.length > 0)
const activeItems = computed(() => {
  const group = props.groups.find((entry) => entry.platform === activePlatform.value)
  return group ? group.items : props.items
})
const metaNote = computed(() => {
  const notes = [props.provider === 'alapi' ? '来源 ALAPI' : '来源 60s']
  const parsedTime = props.fetchedAt ? new Date(props.fetchedAt) : null
  if (parsedTime && !Number.isNaN(parsedTime.getTime())) {
    notes.push(`抓取于 ${formatHotFetchedTime(parsedTime)}`)
  }
  return notes.join(' · ')
})

watch(() => props.groups, (groups) => {
  if (groups.length === 0) {
    activePlatform.value = ''
  } else if (!groups.some((group) => group.platform === activePlatform.value)) {
    activePlatform.value = groups[0].platform
  }
}, { immediate: true })

function formatHotFetchedTime(value: Date): string {
  const pad = (num: number) => String(num).padStart(2, '0')
  return `${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}`
}
</script>

<style scoped>
.hot-picker { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface); }
.hot-picker-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.hot-refresh { padding: 6px 12px; font-size: 0.8rem; }
.hot-refresh:disabled { opacity: 0.5; cursor: not-allowed; }
.hot-picker-note { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }
.hot-tabs { display: flex; gap: 4px; flex-wrap: wrap; }
.hot-tabs button { padding: 5px 12px; border: 1px solid var(--color-border); border-radius: 999px; background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 0.8rem; }
.hot-tabs button.active { border-color: var(--color-accent); color: var(--color-accent); font-weight: 600; }
.hot-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; max-height: 324px; overflow-y: auto; }
.hot-item { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-bottom: 1px solid var(--color-border); }
.hot-item:last-child { border-bottom: 0; }
.hot-rank { flex: 0 0 22px; text-align: center; color: var(--color-text-muted); font-size: 0.8rem; font-weight: 700; }
.hot-main { flex: 1; min-width: 0; display: grid; gap: 2px; }
.hot-title, .hot-title-link { margin: 0; color: var(--color-text); font-size: 0.86rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-title-link { text-decoration: none; }
.hot-title-link:hover { color: var(--color-accent); }
.hot-meta { color: var(--color-text-muted); font-size: 0.74rem; }
.hot-pick { flex: 0 0 auto; padding: 5px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; font-size: 0.78rem; }
.hot-pick.selected { border-color: var(--color-accent); color: var(--color-accent); font-weight: 600; }
.hot-skeleton-list { display: grid; gap: 8px; }
.hot-skeleton { height: 34px; border-radius: 6px; background: var(--color-surface-muted); animation: hot-pulse 1.2s ease-in-out infinite; }
.hot-empty-note { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; }
.hot-refine { display: grid; gap: 8px; padding-top: 8px; border-top: 1px solid var(--color-border); }
.hot-topic-detail { display: grid; gap: 6px; margin: 0; }
.hot-topic-detail > div { display: flex; gap: 8px; font-size: 0.84rem; }
.hot-topic-detail dt { flex: 0 0 44px; color: var(--color-text-muted); }
.hot-topic-detail dd { margin: 0; }
.hot-entry-points { margin: 0; padding-left: 18px; }
@keyframes hot-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
.error-state { margin: 0; color: var(--color-danger); font-size: 0.84rem; }
.secondary-command { padding: 9px 15px; border-radius: 6px; cursor: pointer; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); }
</style>
