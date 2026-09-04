<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { useCrossAppJump } from '../composables/useCrossAppToken'
import { STORE_MEDIA_KINDS, STORE_MEDIA_KIND_META } from '../types/grassland'
import type { StoreMediaKind, StoreMediaManageItem } from '../types/grassland'

/**
 * 门店媒体库管理（任务书 #42 Stage 3）：四类分组上传/解绑/调序。
 *
 * - 上传编排取 {@link useGrassland().uploadStoreMediaFile}（代开票 → presigned → confirm，不压缩），
 *   confirm 成功后显式 {@link bindStoreMedia} 绑定——两步分离，上传失败不污染绑定集。
 * - 多文件上传限并发 ≤2（避免大视频挤爆带宽/浏览器连接池）。
 * - kind→accept/帽全部取 {@link STORE_MEDIA_KIND_META} 单一映射；帽满隐藏上传按钮。
 * - 写端点（bind/unbind/reorder）成功后用返回的整店 items 覆盖本地，与服务端排序权威一致。
 * - 缩略图自愈（与公开画廊对称）：管理读 URL TTL 默认 300s，停留超时必破图——首次
 *   @error 重拉一次 getStoreMedia 换新短时 URL，重拉后仍失败落占位（failedMediaIds 模式）。
 */

interface Props {
  orgId: string
  storeId: string
}

const props = defineProps<Props>()

const grassland = useGrassland()
const { jumpToAiApp } = useCrossAppJump()
const creating = ref(false)

/** 门店深链（任务书 #76 卡 C）：?entry=store&org=&store= + xat → AI 应用锁定态创作。 */
async function createFromStore(): Promise<void> {
  creating.value = true
  try {
    await jumpToAiApp('/', { entry: 'store', org: props.orgId, store: props.storeId })
  } finally {
    creating.value = false
  }
}

const items = ref<StoreMediaManageItem[]>([])
const loaded = ref(false)
const readError = ref('')

/** 上传中的逐文件状态（按 kind 渲染在对应分组下）。 */
interface UploadEntry {
  id: number
  kind: StoreMediaKind
  name: string
  status: 'uploading' | 'error' | 'rejected'
  error: string
}
const uploads = ref<UploadEntry[]>([])
let uploadSeq = 0

/** 两步删除确认（避免误删；window.confirm 在测试环境不稳定，刻意用内联确认）。 */
const confirmingMediaId = ref('')

/** 门店切换守卫：丢弃前一门店的迟到响应（同 MerchantKybCard 的 version 模式）。 */
let loadVersion = 0

/** 已耗尽重拉机会的媒体 id —— 再 @error 直接落占位（同 StoreMediaGallery 的 failedMediaIds 模式）。 */
const failedMediaIds = ref<Set<string>>(new Set())
/** 管理读 URL TTL 默认 300s：首次缩略图失败只重拉一次整店换新短时 URL，不无限重拉。 */
let thumbRetryUsed = false

const grouped = computed(() => {
  const groups: Record<StoreMediaKind, StoreMediaManageItem[]> = {
    storefront: [], environment: [], menu: [], video: [],
  }
  for (const item of items.value) groups[item.kind].push(item)
  for (const kind of STORE_MEDIA_KINDS) groups[kind].sort((a, b) => a.position - b.position)
  return groups
})

const uploadsByKind = computed(() => {
  const byKind: Record<StoreMediaKind, UploadEntry[]> = {
    storefront: [], environment: [], menu: [], video: [],
  }
  for (const entry of uploads.value) byKind[entry.kind].push(entry)
  return byKind
})

async function loadMedia(orgId: string, storeId: string, version: number): Promise<void> {
  loaded.value = false
  readError.value = ''
  items.value = []
  const result = await grassland.getStoreMedia(orgId, storeId)
  if (loadVersion !== version || props.orgId !== orgId || props.storeId !== storeId) return
  loaded.value = true
  if (result) {
    items.value = result.items ?? []
    failedMediaIds.value = new Set()
    thumbRetryUsed = false
  } else if (grassland.error.value) {
    readError.value = grassland.error.value
  }
}

watch(() => [props.orgId, props.storeId], ([orgId, storeId]) => {
  const version = ++loadVersion
  uploads.value = []
  confirmingMediaId.value = ''
  failedMediaIds.value = new Set()
  thumbRetryUsed = false
  if (orgId && storeId) void loadMedia(orgId, storeId, version)
}, { immediate: true })

// ---------- 缩略图自愈（管理读 URL TTL 默认 300s，停留超时必破图）----------

/**
 * 缩略图加载失败（多为短时 URL 过期）：首次重拉一次 getStoreMedia 换新 URL；
 * 重拉失败或换后仍破 → 该项落占位，不连累其余条目。
 */
async function handleThumbError(item: StoreMediaManageItem): Promise<void> {
  const { orgId, storeId } = props
  const version = loadVersion
  if (!thumbRetryUsed && orgId && storeId) {
    thumbRetryUsed = true
    const result = await grassland.getStoreMedia(orgId, storeId)
    if (loadVersion !== version || props.orgId !== orgId || props.storeId !== storeId) return
    if (result) {
      items.value = result.items ?? []
      failedMediaIds.value = new Set()
      return
    }
  }
  failedMediaIds.value = new Set([...failedMediaIds.value, item.mediaId])
}

// ---------- 上传（并发 ≤2）----------

/** 等待槽位：同时在途上传最多 2 个。 */
let activeUploads = 0
const uploadQueue: Array<() => void> = []
const MAX_CONCURRENT_UPLOADS = 2

function acquireUploadSlot(): Promise<void> {
  if (activeUploads < MAX_CONCURRENT_UPLOADS) {
    activeUploads += 1
    return Promise.resolve()
  }
  return new Promise((resolve) => {
    uploadQueue.push(() => {
      activeUploads += 1
      resolve()
    })
  })
}

function releaseUploadSlot(): void {
  activeUploads -= 1
  const next = uploadQueue.shift()
  if (next) next()
}

/**
 * 选文件即前端预检（选错当场拒绝，不打开票端点）：MIME ∈ kind accept、大小 ≤ 帽、
 * 剩余名额（maxCount − 已绑定 − 本批已占位）≥1。
 */
async function handleFilesSelected(event: Event, kind: StoreMediaKind): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  const version = loadVersion
  const meta = STORE_MEDIA_KIND_META[kind]
  let slots = meta.maxCount - grouped.value[kind].length
  for (const file of files) {
    const entry: UploadEntry = {
      id: ++uploadSeq, kind, name: file.name, status: 'uploading', error: '',
    }
    if (!meta.accept.split(',').includes(file.type)) {
      entry.status = 'rejected'
      entry.error = `不支持的文件类型${file.type ? `（${file.type}）` : ''}，仅支持：${meta.accept.split(',').join(' / ')}`
      uploads.value = [...uploads.value, entry]
      continue
    }
    if (file.size > meta.maxBytes) {
      entry.status = 'rejected'
      entry.error = `文件大小超出 ${Math.round(meta.maxBytes / (1024 * 1024))}MB 上限`
      uploads.value = [...uploads.value, entry]
      continue
    }
    if (slots <= 0) {
      entry.status = 'rejected'
      entry.error = `「${meta.label}」最多 ${meta.maxCount} 个，已达上限`
      uploads.value = [...uploads.value, entry]
      continue
    }
    slots -= 1
    uploads.value = [...uploads.value, entry]
    void processUpload(entry, file, version)
  }
}

async function processUpload(entry: UploadEntry, file: File, version: number): Promise<void> {
  const { orgId, storeId } = props
  const kind = entry.kind
  await acquireUploadSlot()
  try {
    // 第一步：三步上传（不压缩、不自动绑定，见 composable 注释）。
    const mediaId = await grassland.uploadStoreMediaFile(orgId, storeId, kind, file)
    if (mediaId === null) {
      markUploadFailed(entry, grassland.error.value || '上传失败', version)
      return
    }
    // 第二步：显式绑定（fail-closed）；后端返回更新后整店，直接覆盖本地。
    const updated = await grassland.bindStoreMedia(orgId, storeId, kind, [mediaId])
    if (loadVersion !== version || props.orgId !== orgId || props.storeId !== storeId) return
    if (updated) {
      items.value = updated.items ?? []
      failedMediaIds.value = new Set()
      thumbRetryUsed = false
      removeUploadEntry(entry.id)
    } else {
      markUploadFailed(entry, grassland.error.value || '绑定失败', version)
    }
  } finally {
    releaseUploadSlot()
  }
}

function markUploadFailed(entry: UploadEntry, error: string, version: number): void {
  if (loadVersion !== version) return
  uploads.value = uploads.value.map((item) =>
    item.id === entry.id ? { ...item, status: 'error' as const, error } : item)
}

function removeUploadEntry(id: number): void {
  uploads.value = uploads.value.filter((item) => item.id !== id)
}

// ---------- 解绑 / 调序 ----------

async function unbind(item: StoreMediaManageItem): Promise<void> {
  const { orgId, storeId } = props
  const version = loadVersion
  confirmingMediaId.value = ''
  const result = await grassland.unbindStoreMedia(orgId, storeId, item.mediaId)
  if (result !== null && loadVersion === version
    && props.orgId === orgId && props.storeId === storeId) {
    items.value = items.value.filter((existing) => existing.mediaId !== item.mediaId)
  }
}

async function move(item: StoreMediaManageItem, delta: -1 | 1): Promise<void> {
  const { orgId, storeId } = props
  const version = loadVersion
  const group = [...grouped.value[item.kind]]
  const index = group.findIndex((existing) => existing.mediaId === item.mediaId)
  const target = index + delta
  if (index < 0 || target < 0 || target >= group.length) return
  ;[group[index], group[target]] = [group[target], group[index]]
  const orderedMediaIds = group.map((member) => member.mediaId)
  const updated = await grassland.reorderStoreMedia(orgId, storeId, item.kind, orderedMediaIds)
  if (updated && loadVersion === version
    && props.orgId === orgId && props.storeId === storeId) {
    items.value = updated.items ?? []
    failedMediaIds.value = new Set()
    thumbRetryUsed = false
  }
}
</script>

<template>
  <div class="store-media-section">
    <div class="sm-section-head">
      <h4>门店媒体</h4>
      <!-- 任务书 #76 卡 D：门店创作入口收进商家工作台——深链跳 AI 应用锁定态（执行走组织预算） -->
      <button
        type="button"
        class="sm-create-link"
        :disabled="creating || !props.storeId"
        @click="createFromStore"
      >{{ creating ? '正在跳转…' : '从门店创作' }}</button>
    </div>
    <p v-if="readError" class="sm-error" role="alert">{{ readError }}</p>
    <p v-else-if="!loaded" class="sm-hint">加载中...</p>

    <template v-for="kind in STORE_MEDIA_KINDS" :key="kind">
      <div class="sm-kind">
        <div class="sm-kind-head">
          <span class="sm-kind-label">{{ STORE_MEDIA_KIND_META[kind].label }}</span>
          <span class="sm-kind-count">{{ grouped[kind].length }}/{{ STORE_MEDIA_KIND_META[kind].maxCount }}</span>
        </div>

        <div v-if="grouped[kind].length > 0" class="sm-list">
          <div v-for="(item, index) in grouped[kind]" :key="item.mediaId" class="sm-item">
            <div class="sm-thumb">
              <span v-if="failedMediaIds.has(item.mediaId)" class="sm-thumb-broken">预览不可用</span>
              <video
                v-else-if="kind === 'video'"
                :src="item.downloadUrl ?? undefined"
                preload="metadata"
                muted
                playsinline
                @error="handleThumbError(item)"
              />
              <img
                v-else-if="item.downloadUrl"
                :src="item.downloadUrl"
                :alt="STORE_MEDIA_KIND_META[kind].label"
                loading="lazy"
                decoding="async"
                @error="handleThumbError(item)"
              />
              <span v-else class="sm-thumb-broken">预览不可用</span>
            </div>
            <div class="sm-item-actions">
              <button
                type="button"
                :aria-label="`上移 ${STORE_MEDIA_KIND_META[kind].label}第 ${index + 1} 项`"
                :disabled="grassland.loading.value || index === 0"
                @click="move(item, -1)"
              >↑</button>
              <button
                type="button"
                :aria-label="`下移 ${STORE_MEDIA_KIND_META[kind].label}第 ${index + 1} 项`"
                :disabled="grassland.loading.value || index === grouped[kind].length - 1"
                @click="move(item, 1)"
              >↓</button>
              <template v-if="confirmingMediaId === item.mediaId">
                <button
                  type="button"
                  class="sm-confirm"
                  :disabled="grassland.loading.value"
                  @click="unbind(item)"
                >确认删除</button>
                <button
                  type="button"
                  :disabled="grassland.loading.value"
                  @click="confirmingMediaId = ''"
                >取消</button>
              </template>
              <button
                v-else
                type="button"
                :disabled="grassland.loading.value"
                @click="confirmingMediaId = item.mediaId"
              >删除</button>
            </div>
          </div>
        </div>
        <p v-else-if="loaded" class="sm-empty">暂无{{ STORE_MEDIA_KIND_META[kind].label }}</p>

        <p
          v-for="entry in uploadsByKind[kind]"
          :key="entry.id"
          :class="entry.status === 'uploading' ? 'sm-upload-progress' : 'sm-upload-error'"
          role="status"
        >
          <template v-if="entry.status === 'uploading'">{{ entry.name }} 上传中...</template>
          <template v-else>{{ entry.name }}：{{ entry.error }}</template>
        </p>

        <!-- 帽满隐藏上传按钮（D7：storefront≤6 / environment≤12 / menu≤12 / video≤3） -->
        <label
          v-if="loaded && grouped[kind].length < STORE_MEDIA_KIND_META[kind].maxCount"
          class="sm-upload"
        >
          上传{{ STORE_MEDIA_KIND_META[kind].label }}
          <input
            type="file"
            multiple
            :accept="STORE_MEDIA_KIND_META[kind].accept"
            :disabled="grassland.loading.value"
            :data-kind="kind"
            @change="(event) => handleFilesSelected(event, kind)"
          />
        </label>
      </div>
    </template>
  </div>
</template>

<style scoped>
.sm-section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sm-create-link { min-height: 32px; padding: 0 14px; border: 1px solid var(--color-border-accent); border-radius: var(--radius-pill); background: var(--color-surface-highlight); color: var(--color-accent-2); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.sm-create-link:disabled { opacity: 0.5; cursor: default; }
.store-media-section {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.store-media-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
}

.sm-error {
  margin: 0 0 12px;
  color: var(--color-danger);
  font-size: 13px;
}

.sm-hint {
  margin: 0 0 12px;
  color: var(--color-text-muted);
  font-size: 13px;
}

.sm-kind {
  margin-bottom: 16px;
}

.sm-kind-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.sm-kind-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.sm-kind-count {
  font-size: 12px;
  color: var(--color-text-muted);
}

.sm-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}

.sm-item {
  padding: 8px;
  background: var(--surface-muted);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.sm-thumb {
  aspect-ratio: 4 / 3;
  margin-bottom: 6px;
  background: var(--color-surface-strong);
  border-radius: var(--radius-xs);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sm-thumb img,
.sm-thumb video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sm-thumb-broken {
  font-size: 12px;
  color: var(--color-text-muted);
}

.sm-item-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.sm-item-actions button {
  padding: 2px 8px;
  font-size: 12px;
  border: 1px solid var(--color-border);
  background: white;
  border-radius: var(--radius-xs);
  cursor: pointer;
}

.sm-item-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.sm-item-actions .sm-confirm {
  border-color: var(--color-danger);
  color: var(--color-danger);
}

.sm-empty {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.sm-upload-progress {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}

.sm-upload-error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-danger);
}

.sm-upload {
  display: inline-flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.sm-upload input[type="file"] {
  padding: 6px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
</style>
