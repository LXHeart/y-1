<template>
  <div class="store-media-moderation-panel">
    <section aria-labelledby="store-media-moderation-title">
      <div class="panel-toolbar">
        <div>
          <h3 id="store-media-moderation-title">门店媒体人工复核</h3>
          <p>自动审核存疑（review）的门店公开媒体：通过恢复公开展示，驳回即拦截展示。</p>
        </div>
        <div class="queue-controls">
          <label>状态<select v-model="statusFilter" @change="void loadQueue()">
            <option value="review">待复核</option>
            <option value="blocked">已拦截</option>
            <option value="pass">已通过</option>
          </select></label>
          <button class="refresh-btn" type="button" :disabled="loading" @click="loadQueue">刷新</button>
        </div>
      </div>
      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="items.length" class="moderation-grid">
        <article v-for="item in items" :key="item.mediaId" class="moderation-item">
          <div class="media-preview">
            <img v-if="isImage(item) && item.downloadUrl" :src="item.downloadUrl" :alt="`媒体 ${item.mediaId}`">
            <video v-else-if="item.downloadUrl" :src="item.downloadUrl" controls muted></video>
            <span v-else>暂无预览</span>
          </div>
          <div class="media-body">
            <div class="media-heading">
              <h4>{{ mediaTypeLabel(item) }} · {{ formatBytes(item.sizeBytes) }}</h4>
              <span class="type-tag" :class="'status-' + item.status">{{ statusLabel(item.status) }}</span>
            </div>
            <p class="td-time">门店 {{ item.storeId || '-' }} · 送审 {{ formatDateTime(item.moderatedAt) }}</p>
            <ul v-if="item.findings.length" class="findings-list">
              <li v-for="(finding, index) in item.findings" :key="index">
                {{ finding.category }}（{{ finding.severity }}）{{ finding.advice || '' }}
              </li>
            </ul>
            <p v-else class="findings-empty">自动审核无 findings</p>
            <dl v-if="item.reviewedBy" class="review-trail">
              <dt>人工裁决</dt>
              <dd>{{ statusLabel(item.status) }} · {{ formatDateTime(item.reviewedAt) }}<template v-if="item.reviewNote"> · {{ item.reviewNote }}</template></dd>
            </dl>
            <label v-if="item.status === 'review'">复核备注
              <input v-model="notes[item.mediaId]" class="field-input" type="text" maxlength="500" placeholder="驳回时必填">
            </label>
            <div v-if="item.status === 'review'" class="review-actions">
              <button class="approve-btn" type="button" :disabled="reviewingIds.has(item.mediaId)" @click="review(item, 'approve')">通过</button>
              <button class="reject-btn" type="button" :disabled="reviewingIds.has(item.mediaId)" @click="review(item, 'reject')">驳回</button>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="td-empty">{{ emptyHint }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { StoreMediaModerationQueueItem } from '../../../types/grassland'

const grassland = useGrassland()
const items = ref<StoreMediaModerationQueueItem[]>([])
const statusFilter = ref<'review' | 'pass' | 'blocked'>('review')
const notes = ref<Record<string, string>>({})
const reviewingIds = ref(new Set<string>())
const loading = ref(false)
const error = ref('')

const emptyHint = computed(() => statusFilter.value === 'review'
  ? '暂无待复核的门店媒体'
  : statusFilter.value === 'blocked' ? '暂无已拦截的门店媒体' : '暂无已通过的门店媒体')

async function loadQueue(): Promise<void> {
  loading.value = true; error.value = ''
  const result = await grassland.listStoreMediaModerationQueue(statusFilter.value)
  if (!result) { error.value = grassland.error.value || '门店媒体复核队列加载失败'; loading.value = false; return }
  items.value = [...result.items]; loading.value = false
}

async function review(item: StoreMediaModerationQueueItem, decision: 'approve' | 'reject'): Promise<void> {
  const note = (notes.value[item.mediaId] || '').trim()
  if (decision === 'reject' && !note) { error.value = '驳回门店媒体必须填写原因'; return }
  if (!item.moderatedAt) { error.value = '该记录缺少送审时间，无法裁决'; return }
  reviewingIds.value = new Set([...reviewingIds.value, item.mediaId]); error.value = ''
  const result = await grassland.reviewStoreMediaModeration(item.mediaId, decision, item.moderatedAt, note || undefined)
  const next = new Set(reviewingIds.value); next.delete(item.mediaId); reviewingIds.value = next
  if (!result) { error.value = grassland.error.value || '门店媒体复核失败'; return }
  await loadQueue()
}

function isImage(item: StoreMediaModerationQueueItem): boolean {
  return (item.mimeType || '').startsWith('image/')
}
function mediaTypeLabel(item: StoreMediaModerationQueueItem): string {
  return isImage(item) ? '图片' : '视频'
}
function statusLabel(status: string): string {
  return ({ review: '待复核', pass: '已通过', blocked: '已拦截' } as Record<string, string>)[status] || status
}
function formatBytes(size: number): string {
  if (!Number.isFinite(size) || size <= 0) return '-'
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))}KB`
  return `${Math.round(size / (1024 * 1024))}MB`
}
function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}
onMounted(() => void loadQueue())
</script>

<style scoped>
.store-media-moderation-panel > section { display: grid; gap: 16px; }
.panel-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.panel-toolbar h3, .panel-toolbar p { margin: 0; }
.panel-toolbar h3 { font-size: 1rem; }
.panel-toolbar p { margin-top: 4px; color: var(--color-text-muted); font-size: 0.82rem; }
.queue-controls { display: flex; align-items: end; gap: 8px; }
.queue-controls label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: 0.78rem; }
.queue-controls select { min-height: 34px; padding: 6px 9px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.refresh-btn, .approve-btn, .reject-btn { min-height: 32px; padding: 0 12px; border-radius: var(--radius-sm); font-size: 0.78rem; cursor: pointer; }
.refresh-btn { border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); }
.refresh-btn:disabled, .approve-btn:disabled, .reject-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.approve-btn { border: 1px solid color-mix(in srgb, var(--color-success) 35%, transparent); background: color-mix(in srgb, var(--color-success) 8%, transparent); color: var(--color-success); }
.reject-btn { border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); background: color-mix(in srgb, var(--color-danger) 7%, transparent); color: var(--color-danger); }
.review-actions { display: flex; gap: 6px; }
.type-tag { display: inline-block; padding: 3px 7px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--surface-muted); white-space: nowrap; }
.type-tag.status-review { border-color: var(--color-warning); color: var(--color-warning); }
.type-tag.status-blocked { border-color: var(--color-danger); color: var(--color-danger); }
.type-tag.status-pass { border-color: var(--color-success); color: var(--color-success); }
.loading-state { padding: var(--space-xl); text-align: center; color: var(--color-text-muted); font-size: 0.9rem; }
.error-msg { padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm); background: color-mix(in srgb, var(--color-danger) 10%, transparent); border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent); color: var(--color-danger); font-size: 0.86rem; margin: 0; }
.td-time { white-space: nowrap; color: var(--color-text-muted); }
.td-empty { text-align: center; padding: var(--space-xl); color: var(--color-text-muted); }
.moderation-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.moderation-item { display: grid; grid-template-columns: 128px minmax(0, 1fr); min-height: 164px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); overflow: hidden; }
.media-preview { display: grid; place-items: center; min-height: 164px; background: var(--surface-muted); color: var(--color-text-muted); font-size: 0.76rem; }
.media-preview img, .media-preview video { width: 100%; height: 100%; object-fit: cover; }
.media-body { display: grid; align-content: start; gap: 8px; min-width: 0; padding: 12px; }
.media-heading { display: flex; align-items: start; justify-content: space-between; gap: 8px; }
.media-heading h4, .media-body p, .findings-list { margin: 0; }
.media-heading h4 { min-width: 0; font-size: 0.9rem; overflow-wrap: anywhere; }
.media-body p { color: var(--color-text-muted); font-size: 0.76rem; }
.findings-list { padding-left: 18px; color: var(--color-text-muted); font-size: 0.76rem; display: grid; gap: 4px; }
.findings-empty { color: var(--color-text-muted); font-size: 0.76rem; font-style: italic; }
.review-trail { margin: 0; display: grid; gap: 2px; font-size: 0.76rem; }
.review-trail dt { color: var(--color-text-muted); }
.review-trail dd { margin: 0; overflow-wrap: anywhere; }
.media-body label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: 0.78rem; }
.media-body input { width: 100%; min-height: 36px; padding: 6px 9px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); box-sizing: border-box; }
@media (max-width: 640px) { .moderation-item { grid-template-columns: 96px minmax(0, 1fr); } }
</style>
