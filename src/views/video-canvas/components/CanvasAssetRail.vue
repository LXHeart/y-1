<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import type { GraphMediaAsset } from '../composables/useCanvasGraph'
import { fetchPersonalMediaAssets } from '../composables/usePersonalMediaLibrary'

/**
 * 任务书 #100 C100-10：画布左侧素材轨——账号自有媒体入口与可用性。
 *
 * 媒体列表来自既有 /api/content-assets?libraryType=personal（C100-20 补缺：此前误连
 * 不存在的 /api/media/media）；「添加到画布」只保存 mediaId 引用节点（§6.3：引用媒体
 * 仅保存 ID，不复制内容）。失效/撤销素材显示原位占位与原因、可重新选择（TC-023）；
 * 移动端选择后由父级收起抽屉返回镜头列表（焦点保留）。
 */
const props = defineProps<{
  authenticated: boolean
}>()
const authenticated = toRef(props, 'authenticated')

const emit = defineEmits<{
  (e: 'add-media', asset: GraphMediaAsset): void
  /** 拉取成功回传可用性投影——父级喂给 useCanvasGraph.mediaAssets（addUserNode 校验源）。 */
  (e: 'loaded', assets: GraphMediaAsset[]): void
}>()

const assets = ref<GraphMediaAsset[]>([])
const loading = ref(false)
const error = ref('')
const lastRequestedAt = ref('')

async function loadMedia(): Promise<void> {
  if (!authenticated.value) return
  loading.value = true
  error.value = ''
  try {
    const items = await fetchPersonalMediaAssets()
    assets.value = items.map(item => ({
      id: item.mediaId,
      name: item.title || '未命名素材',
      // 个人库行都是本人资产（授权概念只在被授权视图出现）；非 active 状态不可新增消费
      status: item.status === 'active' ? 'active' : 'inactive',
      authorized: true,
    }))
    lastRequestedAt.value = new Date().toISOString()
    emit('loaded', assets.value)
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '素材读取失败'
  } finally {
    loading.value = false
  }
}

function availability(asset: GraphMediaAsset): { label: string; usable: boolean } {
  if (asset.status === 'deleted') return { label: '已删除', usable: false }
  if (asset.status === 'revoked' || !asset.authorized) return { label: '授权已撤销', usable: false }
  if (asset.status === 'inactive') return { label: '暂不可用', usable: false }
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
