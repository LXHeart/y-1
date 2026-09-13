<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { VisualJobItem } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-11（§8.2 候选比较）：原图/交付画幅切换预览与选择事件。
 * 媒体经 GET /api/media/{id} 签名 URL（owner 短时有效，失败可重试）；
 * 尺寸标签真实（交付尺寸取 artifact.width/height，原图标注供应商原始尺寸，不冒充交付规格）。
 * 选择≠保存成功——本组件只 emit 引用，「已采用」判定属 12 卡（§8.2）。
 */
const props = defineProps<{
  item: VisualJobItem
  selected?: boolean
  /** C101-12：该候选交付媒体已被服务端采用（权威标记）。 */
  adopted?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', selection: { itemId: string; artifactId: string }): void
  (e: 'zoom', url: string): void
}>()

const view = ref<'delivery' | 'original'>('delivery')
const deliveryUrl = ref('')
const originalUrl = ref('')
const loading = ref(false)
const imageError = ref('')

const artifact = computed(() => props.item.artifact)

async function loadUrl(mediaId: string | undefined): Promise<string> {
  if (!mediaId) return ''
  const response = await fetch(`/api/media/${mediaId}`, { method: 'GET' })
  const body = await response.json().catch(() => null) as { success?: boolean; data?: { url?: string } } | null
  if (!response.ok || !body?.success || !body.data?.url) {
    throw new Error('media fetch failed')
  }
  return body.data.url
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  imageError.value = ''
  try {
    const [delivery, original] = await Promise.all([
      loadUrl(artifact.value?.deliveryMediaRef?.id),
      loadUrl(artifact.value?.originalMediaRef?.id),
    ])
    deliveryUrl.value = delivery
    // 原图不可取（如未保存原图权限）时退回交付图，不空挂
    originalUrl.value = original || delivery
  } catch {
    imageError.value = '预览加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })

const activeUrl = computed(() => (view.value === 'delivery' ? deliveryUrl.value : originalUrl.value))
const hasOriginal = computed(() => Boolean(artifact.value?.originalMediaRef?.id
  && artifact.value.originalMediaRef.id !== artifact.value.deliveryMediaRef?.id))
const sizeLabel = computed(() => {
  if (view.value === 'delivery' && artifact.value?.width && artifact.value.height) {
    return `交付 ${artifact.value.width}×${artifact.value.height}`
  }
  return '原图（供应商原始尺寸）'
})

function onSelect(): void {
  if (!artifact.value) return
  emit('select', { itemId: props.item.itemId, artifactId: artifact.value.id })
}

function onZoom(): void {
  if (activeUrl.value) emit('zoom', activeUrl.value)
}
</script>

<template>
  <figure class="candidate-card" :data-test="`visual-candidate-${item.position}`">
    <div class="preview" data-test="visual-candidate-preview">
      <img
        v-if="activeUrl && !imageError"
        :src="activeUrl"
        :alt="`第 ${item.position} 张候选图`"
        loading="lazy"
        @click="onZoom"
      >
      <div v-else-if="loading" class="placeholder">加载预览…</div>
      <div v-else class="placeholder error" data-test="visual-candidate-error">
        {{ imageError || '暂无预览' }}
        <button type="button" class="secondary" data-test="visual-candidate-retry" @click="load">重试</button>
      </div>
      <div v-if="hasOriginal && deliveryUrl" class="view-toggle" role="group" aria-label="预览切换">
        <button
          type="button"
          :class="{ active: view === 'delivery' }"
          data-test="visual-candidate-delivery"
          @click="view = 'delivery'"
        >交付图</button>
        <button
          type="button"
          :class="{ active: view === 'original' }"
          data-test="visual-candidate-original"
          @click="view = 'original'"
        >原图</button>
      </div>
    </div>
    <figcaption>
      <span class="size-label" data-test="visual-candidate-size">{{ sizeLabel }}</span>
      <div class="actions">
        <button
          type="button"
          class="secondary"
          data-test="visual-candidate-zoom"
          :disabled="!activeUrl"
          @click="onZoom"
        >放大</button>
        <button
          type="button"
          class="primary gl-btn-primary"
          :class="{ selected }"
          data-test="visual-candidate-select"
          :disabled="adopted"
          @click="onSelect"
        >{{ adopted ? '已采用' : selected ? '已选择（待确认采用）' : '选择这张' }}</button>
      </div>
    </figcaption>
  </figure>
</template>

<style scoped>
.candidate-card { margin: 0; border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; background: var(--color-surface); }
.preview { position: relative; aspect-ratio: 3 / 4; background: var(--color-surface-hover); }
.preview img { width: 100%; height: 100%; object-fit: contain; cursor: zoom-in; }
.placeholder { height: 100%; display: grid; place-content: center; gap: 8px; text-align: center; color: var(--color-text-muted); font-size: .85rem; }
.placeholder.error { color: var(--color-danger); }
.view-toggle { position: absolute; left: 8px; bottom: 8px; display: flex; border-radius: var(--radius-pill); overflow: hidden; }
.view-toggle button { border: none; padding: 3px 10px; font-size: .78rem; background: var(--color-surface); color: var(--color-text-secondary); }
.view-toggle button.active { background: var(--color-primary); color: #fff; }
figcaption { padding: 10px 12px; display: grid; gap: 8px; font-size: .84rem; }
.size-label { color: var(--color-text-muted); }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.actions .selected { outline: 2px solid var(--color-success); }
</style>
