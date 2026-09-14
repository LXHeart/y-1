<script setup lang="ts">
import { computed, onScopeDispose, ref, toRef, watch } from 'vue'
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
  epoch?: number
}>()
const authenticated = toRef(props, 'authenticated')

const emit = defineEmits<{
  (e: 'add-media', asset: GraphMediaAsset): void
  /** 拉取成功回传可用性投影——父级喂给 useCanvasGraph.mediaAssets（addUserNode 校验源）。 */
  (e: 'loaded', assets: GraphMediaAsset[]): void
  (e: 'collapse'): void
}>()

const assets = ref<GraphMediaAsset[]>([])
const loading = ref(false)
const error = ref('')
const lastRequestedAt = ref('')
let generation = 0

async function loadMedia(): Promise<void> {
  if (!authenticated.value) return
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const items = await fetchPersonalMediaAssets()
    if (ticket !== generation) return
    assets.value = items.map(item => ({
      id: item.mediaId,
      assetId: item.assetId,
      mimeType: item.mimeType,
      refType: 'media',
      name: item.title || '未命名素材',
      // 个人库行都是本人资产（授权概念只在被授权视图出现）；非 active 状态不可新增消费
      status: item.status === 'active' && (!item.validUntil || Date.parse(item.validUntil) > Date.now()) ? 'active' : 'inactive',
      authorized: true,
    }))
    lastRequestedAt.value = new Date().toISOString()
    emit('loaded', assets.value)
  } catch (err: unknown) {
    if (ticket === generation) error.value = err instanceof Error ? err.message : '素材读取失败'
  } finally {
    if (ticket === generation) loading.value = false
  }
}

function availability(asset: GraphMediaAsset): { label: string; usable: boolean } {
  if (asset.status === 'deleted') return { label: '已删除', usable: false }
  if (asset.status === 'revoked' || !asset.authorized) return { label: '授权已撤销', usable: false }
  if (asset.status === 'inactive') return { label: '暂不可用', usable: false }
  return { label: '可用', usable: true }
}

const empty = computed(() => !loading.value && !error.value && assets.value.length === 0)

watch(() => [props.authenticated, props.epoch], () => {
  generation++; assets.value = []; error.value = ''; loading.value = false; emit('loaded', [])
  void loadMedia()
}, { immediate: true })
onScopeDispose(() => { generation++ })
</script>

<template>
  <aside class="asset-rail gl-zone" data-test="canvas-asset-rail" aria-label="素材与来源">
    <header class="asset-rail-head">
      <h3 class="panel-title">素材与来源</h3>
      <button type="button" class="gl-btn-ghost" aria-label="收起素材栏" data-test="canvas-asset-toggle" @click="emit('collapse')">收起</button>
      <button type="button" class="gl-btn-ghost asset-reload" data-test="canvas-asset-reload"
        :disabled="loading" @click="loadMedia">刷新</button>
    </header>
    <p class="field-note">作为参考不等于用于成片制作。</p>
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
        <button v-else type="button" class="gl-btn-ghost asset-add"
          :data-test="`canvas-asset-add-${asset.id}`" @click="emit('add-media', asset)">
          加为参考
        </button>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.asset-rail { width: 100%; min-width: 0; }
.asset-rail-head { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-xs); }
.panel-title { font-family: var(--font-display); font-size: var(--type-section-title); margin: 0; }
.asset-list { list-style: none; margin: 0; padding: 0; }
.asset-item { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-xs); padding-block: var(--space-sm); border-bottom: var(--border-width) solid var(--color-border); }
.asset-name { overflow-wrap: anywhere; flex: 1; min-width: 0; }
</style>
