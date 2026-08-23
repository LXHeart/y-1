<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useCreationDraftVersions } from '../composables/useCreationDraftVersions'
import type {
  CreationDraft,
  CreationDraftVersion,
} from '../types/creation-assistant'

const props = defineProps<{ draft: CreationDraft }>()
const emit = defineEmits<{
  close: []
  restore: [snapshot: CreationDraftVersion]
}>()

const history = useCreationDraftVersions()
const selectedVersions = ref<number[]>([])

const fields: ReadonlyArray<{ key: keyof CreationDraftVersion; label: string; long?: boolean }> = [
  { key: 'title', label: '草稿标题' },
  { key: 'status', label: '状态' },
  { key: 'sourceType', label: '来源' },
  { key: 'taskId', label: '任务 ID' },
  { key: 'taskVersion', label: '任务版本' },
  { key: 'storeId', label: '门店 ID' },
  { key: 'platform', label: '发布平台' },
  { key: 'contentForm', label: '内容形式' },
  { key: 'topic', label: '主题', long: true },
  { key: 'articleTitle', label: '文章标题', long: true },
  { key: 'outline', label: '大纲', long: true },
  { key: 'content', label: '正文', long: true },
]

const selectedSnapshots = computed(() => selectedVersions.value
  .map(version => history.snapshots.value[version])
  .filter((snapshot): snapshot is CreationDraftVersion => Boolean(snapshot)))

function displayValue(snapshot: CreationDraftVersion, key: keyof CreationDraftVersion): string {
  const value = snapshot[key]
  return value === undefined || value === null || value === '' ? '未填写' : String(value)
}

function isChanged(key: keyof CreationDraftVersion): boolean {
  if (selectedSnapshots.value.length !== 2) return false
  return displayValue(selectedSnapshots.value[0], key) !== displayValue(selectedSnapshots.value[1], key)
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function selectVersion(version: number, checked: boolean): Promise<void> {
  if (!checked) {
    selectedVersions.value = selectedVersions.value.filter(item => item !== version)
    return
  }
  if (selectedVersions.value.length >= 2) return
  selectedVersions.value = [...selectedVersions.value, version]
  await history.getVersion(props.draft.id, version)
}

async function loadMore(): Promise<void> {
  await history.listVersions(props.draft.id, true)
}

async function restoreFromList(version: number): Promise<void> {
  const snapshot = await history.getVersion(props.draft.id, version)
  if (snapshot) emit('restore', snapshot)
}

onMounted(async () => {
  const page = await history.listVersions(props.draft.id)
  if (!page) return
  selectedVersions.value = page.items.slice(0, 2).map(item => item.version)
  await Promise.all(selectedVersions.value.map(version => history.getVersion(props.draft.id, version)))
})
</script>

<template>
  <section class="version-history" aria-label="草稿版本历史">
    <header class="vh-head">
      <div>
        <h3>版本历史</h3>
        <p>选择两个版本查看字段变化。</p>
      </div>
      <button type="button" class="vh-close" aria-label="关闭版本历史" title="关闭" @click="emit('close')">×</button>
    </header>

    <p v-if="history.error.value" class="vh-error">{{ history.error.value }}</p>
    <p v-if="history.loading.value && !history.versions.value.length" class="vh-muted">正在加载版本…</p>

    <ul v-else class="vh-list">
      <li v-for="item in history.versions.value" :key="item.version" class="vh-item">
        <label class="vh-select">
          <input
            type="checkbox"
            :checked="selectedVersions.includes(item.version)"
            :disabled="!selectedVersions.includes(item.version) && selectedVersions.length >= 2"
            @change="selectVersion(item.version, ($event.target as HTMLInputElement).checked)"
          >
          <span class="vh-version">v{{ item.version }}</span>
          <span v-if="item.version === props.draft.version" class="vh-current">当前版本</span>
          <span class="vh-title">{{ item.title }}</span>
          <time :datetime="item.createdAt">{{ formatTime(item.createdAt) }}</time>
        </label>
        <button
          v-if="item.version !== props.draft.version"
          type="button"
          class="vh-list-restore"
          :disabled="history.loading.value"
          @click="restoreFromList(item.version)"
        >载入此版本</button>
      </li>
    </ul>

    <button
      v-if="history.nextCursor.value"
      type="button"
      class="vh-more"
      :disabled="history.loading.value"
      @click="loadMore"
    >{{ history.loading.value ? '加载中…' : '加载更多' }}</button>

    <p v-if="selectedVersions.length !== 2" class="vh-muted">请选择两个版本进行比较。</p>
    <div v-else-if="selectedSnapshots.length === 2" class="vh-compare-scroll">
      <div class="vh-compare">
        <div class="vh-corner">比较字段</div>
        <div v-for="snapshot in selectedSnapshots" :key="snapshot.version" class="vh-column-head">
          <strong>v{{ snapshot.version }}</strong>
          <span>{{ formatTime(snapshot.createdAt) }}</span>
          <button
            v-if="snapshot.version !== props.draft.version"
            type="button"
            class="vh-restore"
            @click="emit('restore', snapshot)"
          >载入此版本</button>
          <span v-else class="vh-current-text">当前版本</span>
        </div>

        <template v-for="field in fields" :key="field.key">
          <div :class="['vh-field-label', { changed: isChanged(field.key) }]">
            <span>{{ field.label }}</span>
            <span v-if="isChanged(field.key)" class="vh-changed">变更</span>
          </div>
          <div
            v-for="snapshot in selectedSnapshots"
            :key="`${snapshot.version}-${field.key}`"
            :class="['vh-value', { changed: isChanged(field.key), long: field.long }]"
          >{{ displayValue(snapshot, field.key) }}</div>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.version-history { display: flex; flex-direction: column; gap: 10px; padding-top: 4px; }
.vh-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.vh-head h3 { margin: 0; font-size: 15px; }
.vh-head p, .vh-muted { margin: 2px 0 0; font-size: 12px; opacity: 0.7; }
.vh-close { width: 30px; height: 30px; border: 0; background: transparent; cursor: pointer; font-size: 22px; line-height: 1; }
.vh-error { margin: 0; color: var(--color-danger); font-size: 13px; }
.vh-list { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--color-border); }
.vh-item { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; border-bottom: 1px solid var(--color-border); }
.vh-select { display: grid; grid-template-columns: auto auto auto minmax(90px, 1fr) auto; align-items: center; gap: 8px; min-height: 42px; cursor: pointer; font-size: 13px; }
.vh-version { font-weight: 600; }
.vh-current, .vh-changed { padding: 2px 6px; border-radius: var(--radius-pill); font-size: 11px; background: color-mix(in srgb, var(--color-success) 12%, transparent); color: var(--color-success); }
.vh-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.vh-item time { font-size: 12px; opacity: 0.65; }
.vh-list-restore { border: 0; background: transparent; color: var(--color-accent); padding: 4px; cursor: pointer; font-size: 12px; }
.vh-list-restore:disabled { opacity: 0.5; cursor: not-allowed; }
.vh-more, .vh-restore { align-self: flex-start; border: 1px solid var(--color-accent); border-radius: var(--radius-sm); background: transparent; color: var(--color-accent); padding: 5px 9px; cursor: pointer; }
.vh-more:disabled { opacity: 0.5; cursor: not-allowed; }
.vh-compare-scroll { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.vh-compare { display: grid; grid-template-columns: 130px repeat(2, minmax(220px, 1fr)); min-width: 620px; }
.vh-corner, .vh-column-head, .vh-field-label, .vh-value { padding: 9px; border-bottom: 1px solid var(--color-border); border-right: 1px solid var(--color-border); }
.vh-column-head { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.vh-column-head span { font-size: 11px; opacity: 0.7; }
.vh-field-label { display: flex; align-items: center; justify-content: space-between; gap: 5px; font-size: 12px; font-weight: 600; }
.vh-field-label.changed, .vh-value.changed { background: color-mix(in srgb, var(--color-warning) 8%, transparent); }
.vh-changed { background: color-mix(in srgb, var(--color-warning) 14%, transparent); color: var(--color-warning); }
.vh-value { min-width: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.vh-value.long { max-height: 180px; overflow: auto; }
.vh-current-text { color: var(--color-success); }
@media (max-width: 640px) {
  .vh-select { grid-template-columns: auto auto auto minmax(0, 1fr); padding: 7px 0; }
  .vh-select time { grid-column: 4; }
}
</style>
