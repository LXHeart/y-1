<template>
  <section class="workspace-hot-topics" aria-labelledby="workspace-hot-title" :aria-busy="loading">
    <div class="workspace-hot-heading">
      <div>
        <h3 id="workspace-hot-title">热点灵感</h3>
        <p class="gl-hint">看看大家正在关注什么，把感兴趣的话题变成下一篇作品。</p>
      </div>
      <button type="button" class="gl-btn-secondary" :disabled="loading" @click="loadHotItems()">
        {{ loading ? '刷新中…' : '刷新热点' }}
      </button>
    </div>
    <p v-if="updatedAt" class="workspace-hot-updated">更新于 {{ updatedAt }}</p>
    <p v-if="error" class="workspace-hot-error" role="alert">{{ hasContent ? '热点更新失败，保留上次结果。' : '热点加载失败。' }}{{ error }} 请重试刷新。</p>
    <p v-if="loading && !hasContent" class="workspace-hot-message" role="status">正在加载热点…</p>
    <template v-else-if="hasContent">
      <div v-if="groups.length > 1" class="workspace-hot-tabs" role="tablist" aria-label="热点榜单" @keydown="handleTabKeydown">
        <button v-for="group in groups" :id="`home-hot-tab-${group.platform}`" :key="group.platform" type="button" role="tab"
          :aria-selected="activePlatform === group.platform" aria-controls="home-hot-panel"
          :tabindex="activePlatform === group.platform ? 0 : -1" @click="activePlatform = group.platform; showAll = false">
          {{ group.label }}
        </button>
      </div>
      <div id="home-hot-panel" :role="groups.length > 1 ? 'tabpanel' : undefined" :aria-labelledby="groups.length > 1 ? `home-hot-tab-${activePlatform}` : undefined">
        <ol v-if="activeItems.length" id="home-hot-list" class="workspace-hot-list">
          <li v-for="item in visibleItems" :key="`${item.rank}-${item.title}`">
            <span class="workspace-hot-rank gl-num">{{ item.rank }}</span>
            <div class="workspace-hot-content">
              <p>{{ item.title }}</p>
              <span v-if="item.hotValue || item.sourceLabel" class="workspace-hot-meta">{{ [item.sourceLabel, item.hotValue && `热度 ${item.hotValue}`].filter(Boolean).join(' · ') }}</span>
            </div>
            <RouterLink class="gl-btn-secondary" :to="{ name: 'create', query: { entry: 'hot', title: item.title } }" :aria-label="`用「${item.title}」创作`">去创作</RouterLink>
          </li>
        </ol>
        <EmptyState v-else title="这个榜单暂无热点" description="可以切换榜单，或稍后刷新。" />
      </div>
      <button v-if="activeItems.length > props.previewCount" type="button" class="gl-btn-secondary workspace-hot-expand" aria-controls="home-hot-list" :aria-expanded="showAll" @click="showAll = !showAll">
        {{ showAll ? '收起热点' : `展开全部 ${activeItems.length} 条热点` }}
      </button>
    </template>
    <EmptyState v-else-if="!error" title="暂无热点" description="稍后刷新看看，也可以先选择上方平台开始创作。" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import EmptyState from '../../components/shared/EmptyState.vue'
import { useHomepageHotItems } from '../../composables/useHomepageHotItems'
import { handleTabKeydown } from '../../lib/tab-navigation'

const { items, groups, fetchedAt, loading, error, loadHotItems } = useHomepageHotItems({ keepPreviousData: true })
/** 收起态展示条数：主栏默认 6 条；创作首页侧栏传 3 保持卡片紧凑。 */
const props = withDefaults(defineProps<{ previewCount?: number }>(), { previewCount: 6 })
const activePlatform = ref('')
const showAll = ref(false)
const hasContent = computed(() => items.value.length > 0 || groups.value.length > 0)
const activeItems = computed(() => groups.value.find(group => group.platform === activePlatform.value)?.items ?? items.value)
const visibleItems = computed(() => showAll.value ? activeItems.value : activeItems.value.slice(0, props.previewCount))
const updatedAt = computed(() => {
  const date = new Date(fetchedAt.value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
})
watch(groups, next => {
  if (!next.some(group => group.platform === activePlatform.value)) activePlatform.value = next[0]?.platform ?? ''
})
onMounted(() => { void loadHotItems() })
</script>
