<template>
  <div class="hot-picker">
    <div class="hot-picker-head">
      <p class="hot-picker-note">{{ metaNote }}</p>
      <button type="button" class="secondary-command hot-refresh" :disabled="loading" @click="$emit('refresh')">
        {{ loading ? '刷新中…' : '刷新热点' }}
      </button>
    </div>

    <div v-if="taxonomy" class="hot-filters" aria-label="热点筛选">
      <label>
        <span>行业</span>
        <select :value="filters.industry || ''" :disabled="loading" @change="updateSelectFilter('industry', $event)">
          <option value="">全部行业</option>
          <option v-for="option in taxonomy.industries" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <label>
        <span>城市</span>
        <select :value="filters.city || ''" :disabled="loading" @change="updateSelectFilter('city', $event)">
          <option value="">全部城市</option>
          <option v-for="city in taxonomy.cities" :key="city" :value="city">{{ city }}</option>
        </select>
      </label>
      <label>
        <span>内容类型</span>
        <select :value="filters.contentType || ''" :disabled="loading" @change="updateSelectFilter('contentType', $event)">
          <option value="">全部类型</option>
          <option v-for="option in taxonomy.contentTypes" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <label class="hot-expired-toggle">
        <input
          type="checkbox"
          :checked="filters.includeExpired === true"
          :disabled="loading"
          @change="updateExpiredFilter"
        >
        <span>显示已过期</span>
      </label>
    </div>

    <div v-if="loading && !hasContent" class="hot-skeleton-list" aria-hidden="true">
      <div v-for="index in 4" :key="index" class="hot-skeleton"></div>
    </div>

    <p v-else-if="error" class="error-state" role="alert">{{ error }}</p>

    <template v-else-if="hasContent">
      <div v-if="groups.length > 1" class="hot-tabs" role="tablist" aria-label="热点平台" @keydown="handleTabKeydown">
        <button
          v-for="group in groups"
          :key="group.platform"
          type="button"
          role="tab"
          :aria-selected="activePlatform === group.platform"
          :class="{ active: activePlatform === group.platform }"
          @click="activePlatform = group.platform"
         :tabindex="(activePlatform === group.platform) ? 0 : -1">{{ group.label }}</button>
      </div>

      <ol class="hot-list">
        <li
          v-for="item in activeItems"
          :key="`${item.rank}-${item.title}`"
          class="hot-item"
          :class="{ 'hot-item-expired': item.expired }"
        >
          <span class="hot-rank">{{ item.rank }}</span>
          <div class="hot-main">
            <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
            <p v-else class="hot-title">{{ item.title }}</p>
            <span class="hot-meta">
              <template v-if="item.hotValue">热度 {{ item.hotValue }}</template>
              <template v-if="item.hotValue && item.sourceLabel"> · </template>
              <template v-if="item.sourceLabel">{{ item.sourceLabel }}</template>
            </span>
            <div v-if="item.tags" class="hot-tag-row">
              <span
                v-for="industry in item.tags.industries"
                :key="industry"
                class="hot-tag hot-tag-industry"
              >{{ industryLabel(industry) }}</span>
              <span v-if="item.tags.city" class="hot-tag hot-tag-city">{{ item.tags.city }}</span>
              <span v-if="item.tags.contentType" class="hot-tag hot-tag-type">
                {{ contentTypeLabel(item.tags.contentType) }}
              </span>
            </div>
            <span v-if="item.validUntil" class="hot-validity" :class="{ expired: item.expired }">
              {{ validityNote(item) }}
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
import { handleTabKeydown } from '../../../lib/tab-navigation'
import { computed, ref, watch } from 'vue'
import type {
  HomepageHotFilters,
  HomepageHotItem,
  HomepageHotItemGroup,
  HomepageHotTaxonomy,
} from '../../../types/homepage-hot'

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
  taxonomy: HomepageHotTaxonomy | null
  filters: HomepageHotFilters
  loading: boolean
  error: string
  selectedTitle: string
  pickedTitle: string
  resolvingTopic: boolean
  topicError: string
  structuredTopic: StructuredTopic | null
}>()

const emit = defineEmits<{
  refresh: []
  filter: [filters: HomepageHotFilters]
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
  if (props.taxonomy?.version) notes.push(`分类 ${props.taxonomy.version}`)
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

function updateSelectFilter(key: 'industry' | 'city' | 'contentType', event: Event): void {
  const value = (event.target as HTMLSelectElement).value || undefined
  emit('filter', { ...props.filters, [key]: value })
}

function updateExpiredFilter(event: Event): void {
  emit('filter', { ...props.filters, includeExpired: (event.target as HTMLInputElement).checked })
}

function industryLabel(value: string): string {
  return props.taxonomy?.industries.find((option) => option.value === value)?.label ?? value
}

function contentTypeLabel(value: string): string {
  return props.taxonomy?.contentTypes.find((option) => option.value === value)?.label ?? value
}

function validityNote(item: HomepageHotItem): string {
  if (item.expired) return '已过期'
  const parsed = item.validUntil ? new Date(item.validUntil) : null
  if (!parsed || Number.isNaN(parsed.getTime())) return ''
  const remainingMs = parsed.getTime() - Date.now()
  const remaining = remainingMs < 60 * 60 * 1000 ? '不足 1 小时' : `${Math.ceil(remainingMs / 3_600_000)} 小时`
  return `有效期至 ${formatHotFetchedTime(parsed)} · 剩余 ${remaining}`
}
</script>

<style scoped>
.hot-picker { display: grid; gap: var(--space-sm); padding: var(--space-sm); border-radius: var(--radius-md); background: var(--surface-furrow); }
.hot-picker-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.hot-refresh { padding: var(--space-xs) var(--space-sm); font-size: var(--type-caption); }
.hot-refresh:disabled { opacity: 0.5; cursor: not-allowed; }
.hot-picker-note { margin: 0; color: var(--color-text-muted); font-size: var(--type-caption); }
.hot-filters { display: grid; grid-template-columns: repeat(3, minmax(110px, 1fr)) auto; gap: var(--space-xs); align-items: end; }
.hot-filters label { display: grid; gap: var(--space-xxs); color: var(--color-text-muted); font-size: var(--type-caption); }
.hot-filters select { width: 100%; min-width: 0; padding: 6px var(--space-xs); border: 1px solid var(--color-border-control); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.hot-expired-toggle { display: flex !important; min-height: var(--control-height); align-items: center; grid-template-columns: auto minmax(0, 1fr); white-space: nowrap; }
.hot-expired-toggle input { margin: 0; }
.hot-tabs { display: flex; gap: var(--space-xxs); flex-wrap: wrap; }
.hot-tabs button { padding: var(--space-xxs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: var(--type-caption); }
.hot-tabs button.active { border-color: var(--color-accent); color: var(--color-accent-2); font-weight: var(--weight-heading); }
.hot-list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-micro); max-height: 324px; overflow-y: auto; }
.hot-item { display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-xs) var(--space-xs); border-bottom: 1px solid var(--color-border); }
.hot-item:last-child { border-bottom: 0; }
.hot-item-expired { opacity: 0.56; background: var(--surface-muted); }
.hot-rank { flex: 0 0 22px; text-align: center; color: var(--color-text-muted); font-size: var(--type-caption); font-weight: var(--weight-heading); }
.hot-main { flex: 1; min-width: 0; display: grid; gap: var(--space-micro); }
.hot-title, .hot-title-link { margin: 0; color: var(--color-text); font-size: var(--type-body-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-title-link { text-decoration: none; }
.hot-title-link:hover { color: var(--color-accent-2); }
.hot-meta { color: var(--color-text-muted); font-size: var(--type-caption); }
.hot-tag-row { display: flex; flex-wrap: wrap; gap: var(--space-xxs); }
.hot-tag { padding: var(--space-micro) var(--space-xs); border: 1px solid var(--color-border); border-radius: var(--radius-pill); color: var(--color-text-secondary); font-size: var(--type-caption); line-height: 1.3; }
.hot-tag-industry { border-color: color-mix(in srgb, var(--color-accent) 45%, var(--color-border)); color: var(--color-accent-2); }
.hot-tag-city { border-color: color-mix(in srgb, var(--color-success) 45%, var(--color-border)); color: var(--color-success); }
.hot-tag-type { border-color: color-mix(in srgb, var(--color-warning) 45%, var(--color-border)); color: var(--color-warning); }
.hot-validity { color: var(--color-text-muted); font-size: var(--type-caption); }
.hot-validity.expired { color: var(--color-danger); font-weight: var(--weight-heading); }
.hot-pick { flex: 0 0 auto; padding: var(--space-xxs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; font-size: var(--type-caption); }
.hot-pick.selected { border-color: var(--color-accent); color: var(--color-accent-2); font-weight: var(--weight-heading); }
.hot-skeleton-list { display: grid; gap: var(--space-xs); }
.hot-skeleton { height: 34px; border-radius: var(--radius-sm); background: var(--surface-muted); animation: hot-pulse 1.2s ease-in-out infinite; }
.hot-empty-note { margin: 0; color: var(--color-text-muted); font-size: var(--type-caption); }
.hot-refine { display: grid; gap: var(--space-xs); padding-top: var(--space-xs); border-top: 1px solid var(--color-border); }
.hot-topic-detail { display: grid; gap: var(--space-xs); margin: 0; }
.hot-topic-detail > div { display: flex; gap: var(--space-xs); font-size: var(--type-caption); }
.hot-topic-detail dt { flex: 0 0 44px; color: var(--color-text-muted); }
.hot-topic-detail dd { margin: 0; }
.hot-entry-points { margin: 0; padding-left: var(--space-md); }
@keyframes hot-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
.error-state { margin: 0; color: var(--color-danger); font-size: var(--type-caption); }
.secondary-command { padding: var(--space-xs) var(--space-md); border-radius: var(--radius-sm); cursor: pointer; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); }
@media (max-width: 720px) {
  .hot-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
