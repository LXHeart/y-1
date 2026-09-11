<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { fetchApi } from '../../../composables/grassland-http'
import type { GraphMediaAsset } from '../composables/useCanvasGraph'

/**
 * 任务书 #100 C100-10：画布左侧素材轨——账号自有媒体入口与可用性。
 *
 * 媒体列表来自既有 /api/media/media（分页只取首页）；「添加到画布」只保存 mediaId
 * 引用节点（§6.3：引用媒体仅保存 ID，不复制内容）。失效/撤销素材显示原位占位与
 * 原因、可重新选择（TC-023）；移动端选择后由父级收起抽屉返回镜头列表（焦点保留）。
 */
const props = defineProps<{
  authenticated: boolean
}>()
const authenticated = toRef(props, 'authenticated')

const emit = defineEmits<{
  (e: 'add-media', asset: GraphMediaAsset): void
}>()

interface MediaReferenceRow {
  id: string
  fileName: string | null
  status: string
  contentType: string | null
}

const assets = ref<GraphMediaAsset[]>([])
const loading = ref(false)
const error = ref('')
const lastRequestedAt = ref('')

async function loadMedia(): Promise<void> {
  if (!authenticated.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await fetchApi('/api/media/media?limit=50')
    if (!response.ok) throw new Error('素材读取失败')
    const body = await response.json() as { success: boolean; data?: { items?: MediaReferenceRow[] } }
    const items = body.data?.items ?? []
    assets.value = items.map(item => ({
      id: item.id,
      name: item.fileName ?? '未命名素材',
      status: item.status === 'deleted' ? 'deleted' : item.status === 'revoked' ? 'revoked' : 'active',
      // 服务端已过滤无权媒体；授权位由状态派生（revoked=撤销授权）
      authorized: item.status !== 'revoked',
    }))
    lastRequestedAt.value = new Date().toISOString()
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '素材读取失败'
  } finally {
    loading.value = false
  }
}

function availability(asset: GraphMediaAsset): { label: string; usable: boolean } {
  if (asset.status === 'deleted') return { label: '已删除', usable: false }
  if (asset.status === 'revoked' || !asset.authorized) return { label: '授权已撤销', usable: false }
  return { label: '可用', usable: true }
}

const empty = computed(() => !loading.value && !error.value && assets.value.length === 0)

loadMedia()
</script>

<template>
  <aside class="asset-rail gl-zone" data-test="canvas-asset-rail" aria-label="素材与来源">
    <header class="asset-rail-head">
      <h3 class="panel-title">素材与来源</h3>
      <button type="button" class="gl-btn-ghost asset-reload" data-test="canvas-asset-reload"
        :disabled="loading" @click="loadMedia">刷新</button>
    </header>
    <p class="field-note">引用素材只保存 ID；作为参考不等于用于成片制作。</p>
    <p v-if="loading" class="panel-empty" data-test="canvas-asset-loading">素材读取中…</p>
    <p v-else-if="error" class="panel-empty" role="alert" data-test="canvas-asset-error">
      {{ error }}
      <button type="button" class="gl-btn-ghost" @click="loadMedia">重试</button>
    </p>
    <p v-else-if="empty" class="panel-empty" data-test="canvas-asset-empty">
      尚无素材——先在媒体库上传
    </p>
    <ul v-else class="asset-list" data-test="canvas-asset-list">
      <li v-for="asset in assets" :key="asset.id" class="asset-item"
        :data-test="`canvas-asset-${asset.id}`" :class="{ 'asset-unavailable': !availability(asset).usable }">
        <span class="asset-name" :title="asset.name">{{ asset.name }}</span>
        <span v-if="!availability(asset).usable" class="badge badge-warning"
          :data-test="`canvas-asset-state-${asset.id}`">
          {{ availability(asset).label }}·保留位置
        </span>
        <button v-else type="button" class="gl-btn-primary asset-add"
          :data-test="`canvas-asset-add-${asset.id}`" @click="emit('add-media', asset)">
          加为参考
        </button>
      </li>
    </ul>
  </aside>
</template>
