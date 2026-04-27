<template>
  <div class="home-view fade-in">
    <section class="hero glass-card">
      <div class="hero-copy">
        <p class="eyebrow">工作台</p>
        <h2 class="hero-title">把提取、分析与创作收进一个更顺手的入口。</h2>
        <p class="hero-note">先选任务，再直接进入对应流程；下方热点区可以继续帮你找题材、抓趋势、扩展灵感。</p>
      </div>
      <div class="hero-meta">
        <div class="meta-pill">
          <span class="meta-dot"></span>
          <span>本地优先处理</span>
        </div>
        <div class="meta-pill">
          <span class="meta-dot meta-dot-cyan"></span>
          <span>热点实时拉取</span>
        </div>
      </div>
    </section>

    <section class="feature-grid" aria-label="功能入口">
      <button
        v-for="feature in features"
        :key="feature.view"
        class="feature-card glass-card"
        type="button"
        @click="emit('open-view', feature.view)"
      >
        <div class="feature-head">
          <p class="eyebrow">{{ feature.eyebrow }}</p>
          <span class="feature-badge">进入</span>
        </div>
        <h3 class="feature-title">{{ feature.title }}</h3>
        <p class="feature-copy">{{ feature.copy }}</p>
        <ul class="feature-points">
          <li v-for="point in feature.points" :key="point">{{ point }}</li>
        </ul>
      </button>
    </section>

    <section class="hot-panel glass-card">
      <header class="card-head">
        <div class="card-head-row">
          <div>
            <p class="eyebrow">多平台热点</p>
            <h2 class="card-title">热门话题</h2>
          </div>
          <button class="btn-secondary btn-sm" type="button" :disabled="loading" @click="loadHotItems">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
        </div>
        <p class="field-note">热点数据由服务端拉取并归一化展示，可直接作为视频分析或文章选题参考。</p>
      </header>

      <div v-if="loading && !activeItems.length" class="hot-skeleton-list" aria-hidden="true">
        <div v-for="index in 5" :key="index" class="hot-skeleton"></div>
      </div>

      <section v-else-if="error" class="empty-card hot-empty">
        <h3 class="empty-title">热点暂时不可用</h3>
        <p class="empty-copy">{{ error }}</p>
      </section>

      <template v-else-if="hasContent">
        <div v-if="showTabs" class="hot-tabs" role="tablist">
          <button
            v-for="group in groups"
            :key="group.platform"
            class="hot-tab"
            :class="{ 'hot-tab-active': activePlatform === group.platform }"
            role="tab"
            :aria-selected="activePlatform === group.platform"
            type="button"
            @click="activePlatform = group.platform"
          >
            {{ group.label }}
            <span class="hot-tab-count">{{ group.items.length }}</span>
          </button>
        </div>

        <ol class="hot-list">
          <li v-for="item in activeItems" :key="`${item.rank}-${item.title}`" class="hot-item">
            <div class="hot-rank">{{ item.rank }}</div>
            <div class="hot-main">
              <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">
                {{ item.title }}
              </a>
              <p v-else class="hot-title">{{ item.title }}</p>
              <div class="hot-meta-row">
                <span v-if="item.hotValue" class="hot-value">热度 {{ item.hotValue }}</span>
                <span v-if="item.sourceLabel" class="hot-source">{{ item.sourceLabel }}</span>
              </div>
            </div>
            <img v-if="item.cover" class="hot-cover" :src="item.cover" :alt="item.title">
            <button class="hot-action-btn" type="button" @click.stop="emit('create-article', item.title)">写文章</button>
          </li>
        </ol>
      </template>

      <section v-else class="empty-card hot-empty">
        <h3 class="empty-title">暂无热点数据</h3>
        <p class="empty-copy">当前没有可展示的热点内容，稍后可点击刷新重试。</p>
      </section>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useHomepageHotItems } from '../composables/useHomepageHotItems'

type HomeFeatureView = 'video' | 'image' | 'article' | 'image-gen'

interface FeatureCard {
  view: HomeFeatureView
  eyebrow: string
  title: string
  copy: string
  points: string[]
}

const emit = defineEmits<{
  'open-view': [view: HomeFeatureView]
  'create-article': [topic: string]
}>()

const { items, groups, provider, loading, error, loadHotItems } = useHomepageHotItems()

const activePlatform = ref('')

const showTabs = computed(() => groups.value.length > 0 && provider.value === '60s')

const activeItems = computed(() => {
  if (showTabs.value) {
    const activeGroup = groups.value.find((g) => g.platform === activePlatform.value)
    return activeGroup?.items ?? []
  }

  return items.value
})

const hasContent = computed(() => {
  if (showTabs.value) {
    return groups.value.some((g) => g.items.length > 0)
  }

  return items.value.length > 0
})

watch(groups, (newGroups) => {
  if (newGroups.length > 0) {
    const exists = newGroups.some((g) => g.platform === activePlatform.value)
    if (!exists) {
      activePlatform.value = newGroups[0].platform
    }
  }
}, { immediate: true })

const features: FeatureCard[] = [
  {
    view: 'video',
    eyebrow: '视频提取分析',
    title: '提取抖音 / B 站视频并进入分析链路',
    copy: '粘贴分享文本或链接后，直接预览、下载，并衔接后端分析流程。',
    points: ['支持抖音与 B 站', '预览与下载都走后端代理', '提取成功后可继续做内容分析'],
  },
  {
    view: 'image',
    eyebrow: '图片评价文案',
    title: '上传图片，生成更像真人写的评价文案',
    copy: '支持自定义目标字数和补充感受，适合商品图、外卖图等场景。',
    points: ['支持最多 6 张图片', '可指定目标字数', '默认输出自然口语化好评'],
  },
  {
    view: 'article',
    eyebrow: '爆款文章',
    title: '从主题到标题、大纲与正文，一站式完成创作',
    copy: '进入多阶段创作流程，先定标题，再流式生成大纲与正文。',
    points: ['先生成候选标题', '支持流式生成大纲与正文', '适合结合热点快速成稿'],
  },
  {
    view: 'image-gen',
    eyebrow: '图片生成',
    title: '输入描述提示词，AI 帮你生成图片',
    copy: '支持自定义图片比例，生成的图片可下载和放大查看。',
    points: ['支持多种尺寸比例', '可查看 AI 优化后的提示词', '点击放大和下载'],
  },
]

onMounted(() => {
  void loadHotItems()
})
</script>

<style scoped>
.home-view {
  display: grid;
  gap: var(--space-lg);
}

.hero {
  display: grid;
  gap: var(--space-lg);
  grid-template-columns: minmax(0, 1.7fr) minmax(220px, 0.9fr);
  align-items: stretch;
}

.hero-copy,
.hot-panel,
.card-head {
  display: grid;
  gap: var(--space-sm);
}

.eyebrow {
  margin: 0;
  font-size: 0.76rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.hero-title,
.card-title,
.feature-title {
  margin: 0;
  line-height: 1.15;
  letter-spacing: -0.03em;
}

.hero-title {
  font-size: clamp(1.7rem, 2.4vw, 2.8rem);
  max-width: 12ch;
}

.hero-note,
.feature-copy,
.empty-copy {
  margin: 0;
  color: var(--color-text-secondary);
}

.hero-note {
  max-width: 58ch;
  font-size: 0.95rem;
}

.field-note {
  margin: 0;
  max-width: 55ch;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  line-height: 1.5;
}

.hero-meta {
  display: grid;
  gap: var(--space-sm);
  align-self: stretch;
}

.meta-pill {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 0 16px;
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.meta-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--color-accent);
}

.meta-dot-cyan {
  background: var(--color-accent-2);
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-md);
}

.feature-card {
  display: grid;
  gap: var(--space-md);
  text-align: left;
  cursor: pointer;
  border: 1px solid var(--color-border);
  background: var(--gradient-surface);
  transition:
    transform var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.feature-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-border-hover);
  background: linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(255,255,255,0.02) 100%);
  box-shadow: var(--shadow-elevated);
}

.feature-head,
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.feature-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  padding: 5px 10px;
  border-radius: 999px;
  background: var(--color-surface-highlight);
  border: 1px solid var(--color-border-accent);
  color: var(--color-text-secondary);
  font-size: 0.75rem;
}

.feature-title {
  font-size: 1.18rem;
}

.feature-copy {
  font-size: 0.92rem;
  line-height: 1.55;
}

.feature-points {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: var(--radius-md);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-secondary:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.btn-sm {
  min-height: 34px;
  padding: 0 12px;
  font-size: 0.82rem;
}

.hot-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.hot-tabs::-webkit-scrollbar {
  display: none;
}

.hot-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  background: var(--surface-page);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.hot-tab:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.hot-tab-active {
  background: var(--surface-card);
  border-color: var(--color-border-accent);
  color: var(--color-text);
}

.hot-tab-count {
  font-size: 0.72rem;
  opacity: 0.62;
}

.hot-skeleton-list {
  display: grid;
  gap: var(--space-sm);
}

.hot-skeleton {
  height: 76px;
  border-radius: var(--radius-lg);
  background: linear-gradient(90deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0.07) 50%, rgba(255,255,255,0.03) 100%);
  background-size: 200% 100%;
  animation: shimmer 1.4s linear infinite;
}

.hot-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.hot-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  gap: var(--space-md);
  align-items: center;
  padding: 14px 16px;
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.hot-rank {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  background: var(--color-surface-highlight);
  color: var(--color-text);
  font-weight: 700;
}

.hot-main {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.hot-title,
.hot-title-link {
  margin: 0;
  color: var(--color-text);
  font-size: 0.98rem;
  font-weight: 600;
  text-decoration: none;
  overflow-wrap: anywhere;
}

.hot-title-link:hover {
  color: var(--color-accent);
}

.hot-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.hot-cover {
  width: 84px;
  height: 56px;
  object-fit: cover;
  border-radius: 12px;
  border: 1px solid var(--color-border);
}

.hot-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: var(--radius-md);
  background: var(--color-accent);
  color: white;
  border: none;
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.hot-action-btn:hover {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.empty-card {
  display: grid;
  gap: 8px;
  place-items: start;
  padding: 20px;
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.empty-title {
  margin: 0;
  font-size: 1rem;
}

.hot-empty {
  min-height: 132px;
  align-content: center;
}

@media (max-width: 960px) {
  .hero,
  .feature-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .hot-item {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .hot-action-btn {
    grid-column: 2;
  }

  .hot-cover {
    grid-column: 1 / -1;
    width: 100%;
    height: 140px;
  }
}
</style>
