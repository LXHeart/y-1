<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { ContentAsset, ContentAssetCategory, ContentAssetVersion } from '../types/grassland'

/**
 * 素材历史快照对比（PRD §4.8「更新不覆盖历史快照」）。
 *
 * 后端每次编辑/门店迁移都会落一条 content_asset_version；此处列出版本并支持勾选
 * 两个快照做字段级对比（参照草稿版本比较 #39 的交互）。历史只读——素材无回滚端点，
 * 如需恢复某版内容，按快照字段重新编辑即可。
 */
const props = defineProps<{ asset: ContentAsset }>()

const grassland = useGrassland()
const versions = ref<ContentAssetVersion[]>([])
const loading = ref(false)
const error = ref('')
const selected = ref<number[]>([])

const CATEGORY_LABELS: Record<ContentAssetCategory, string> = {
  store: '门店',
  product: '产品',
  campaign: '活动',
  scene: '场景',
  brand: '品牌',
  copy: '文案',
  other: '其他',
}

interface CompareRow {
  key: string
  label: string
  read: (version: ContentAssetVersion) => string
}

const rows: ReadonlyArray<CompareRow> = [
  { key: 'title', label: '标题', read: (v) => v.title || '未命名素材' },
  { key: 'category', label: '分类', read: (v) => CATEGORY_LABELS[v.category] ?? v.category },
  { key: 'tags', label: '标签', read: (v) => (v.tags.length > 0 ? v.tags.join('、') : '无') },
  { key: 'mimeType', label: '媒体类型', read: (v) => v.mimeType || '—' },
  {
    key: 'sizeBytes',
    label: '大小',
    read: (v) => (v.sizeBytes == null ? '—' : v.sizeBytes < 1024
      ? `${v.sizeBytes} B`
      : v.sizeBytes < 1024 * 1024 ? `${(v.sizeBytes / 1024).toFixed(0)} KB` : `${(v.sizeBytes / 1024 / 1024).toFixed(1)} MB`),
  },
  { key: 'validUntil', label: '有效期至', read: (v) => formatTime(v.validUntil) || '长期' },
  { key: 'storeId', label: '归属门店', read: (v) => v.storeId || '组织级/个人' },
  { key: 'snapshottedAt', label: '快照时间', read: (v) => formatTime(v.snapshottedAt) || '—' },
  { key: 'snapshottedBy', label: '快照操作人', read: (v) => v.snapshottedBy || '—' },
]

const selectedVersions = computed(() => selected.value
  .map((version) => versions.value.find((item) => item.version === version))
  .filter((item): item is ContentAssetVersion => Boolean(item)))

function formatTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function isChanged(row: CompareRow): boolean {
  if (selectedVersions.value.length !== 2) return false
  return row.read(selectedVersions.value[0]) !== row.read(selectedVersions.value[1])
}

function toggleVersion(version: number, checked: boolean): void {
  if (!checked) {
    selected.value = selected.value.filter((item) => item !== version)
    return
  }
  if (selected.value.length >= 2) return
  selected.value = [...selected.value, version]
}

onMounted(async () => {
  loading.value = true
  error.value = ''
  const result = await grassland.listContentAssetVersions(props.asset.id)
  if (result) {
    versions.value = [...result.items].sort((a, b) => b.version - a.version)
    selected.value = versions.value.slice(0, 2).map((item) => item.version)
  } else {
    error.value = grassland.error.value || '历史快照加载失败'
  }
  loading.value = false
})
</script>

<template>
  <section class="cavh" :aria-label="`素材「${asset.title}」历史快照`">
    <p v-if="loading" class="cavh-message">正在加载历史快照…</p>
    <p v-else-if="error" class="cavh-message cavh-error">{{ error }}</p>
    <template v-else>
      <p class="cavh-hint">勾选两个版本对比字段变化（最多两个，再选无效）。</p>
      <ul class="cavh-list">
        <li v-for="version in versions" :key="version.version" class="cavh-item">
          <label :class="{ 'cavh-item-checked': selected.includes(version.version) }">
            <input type="checkbox" :checked="selected.includes(version.version)"
              :disabled="!selected.includes(version.version) && selected.length >= 2"
              :aria-label="`选择版本 ${version.version}`"
              @change="toggleVersion(version.version, ($event.target as HTMLInputElement).checked)" />
            <span class="cavh-version">v{{ version.version }}</span>
            <span class="cavh-snapshot">{{ version.title }} · {{ formatTime(version.snapshottedAt) }}</span>
          </label>
        </li>
      </ul>

      <div v-if="selectedVersions.length === 2" class="cavh-compare" data-testid="asset-version-compare">
        <div class="cavh-compare-head cavh-grid">
          <span>字段</span>
          <span>v{{ selectedVersions[0].version }}（{{ formatTime(selectedVersions[0].snapshottedAt) }}）</span>
          <span>v{{ selectedVersions[1].version }}（{{ formatTime(selectedVersions[1].snapshottedAt) }}）</span>
        </div>
        <div v-for="row in rows" :key="row.key" class="cavh-grid" :class="{ 'cavh-changed': isChanged(row) }">
          <span class="cavh-field">{{ row.label }}</span>
          <span>{{ row.read(selectedVersions[0]) }}</span>
          <span>{{ row.read(selectedVersions[1]) }}</span>
        </div>
      </div>
      <p v-else class="cavh-message">该素材暂无可对比的第二个版本。</p>
    </template>
  </section>
</template>

<style scoped>
.cavh { display: flex; flex-direction: column; gap: 8px; margin-top: 6px; padding-top: 8px; border-top: 1px dashed var(--color-border); }
.cavh-message, .cavh-hint { margin: 0; font-size: 11px; opacity: 0.7; }
.cavh-error { color: var(--color-danger); opacity: 1; }
.cavh-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.cavh-item label { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 2px 4px; border-radius: 4px; cursor: pointer; }
.cavh-item label.cavh-item-checked { background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.cavh-item input { width: 14px; height: 14px; margin: 0; accent-color: var(--color-accent); flex: 0 0 auto; }
.cavh-item input:disabled { cursor: not-allowed; }
.cavh-version { font-weight: 600; font-variant-numeric: tabular-nums; }
.cavh-snapshot { opacity: 0.75; word-break: break-all; }
.cavh-compare { display: flex; flex-direction: column; gap: 2px; font-size: 11px; }
.cavh-grid { display: grid; grid-template-columns: 84px 1fr 1fr; gap: 6px; padding: 3px 4px; border-radius: 4px; }
.cavh-compare-head { font-weight: 600; opacity: 0.85; }
.cavh-field { opacity: 0.7; }
.cavh-changed { background: color-mix(in srgb, var(--color-warning) 12%, transparent); }
</style>
