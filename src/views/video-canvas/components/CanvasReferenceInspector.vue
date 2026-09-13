<script setup lang="ts">
import { computed, onDeactivated, onUnmounted, ref, watch } from 'vue'
import type { GraphMediaAsset, GraphNode } from '../composables/useCanvasGraph'
import type { CanvasReferenceEdge } from '../../../types/video-canvas'

const props = defineProps<{
  node: GraphNode
  noteText: string
  targets: GraphNode[]
  edges: CanvasReferenceEdge[]
  mediaOptions: GraphMediaAsset[]
  readonly?: boolean
  saving?: boolean
  error: string
  previewUrl?: string
  previewMime?: string
  previewLoading?: boolean
  parentShotId?: string | null
}>()
const emit = defineEmits<{
  'update-note': [text: string]
  'add-reference': [target: string]
  'remove-reference': [id: string]
  'replace-media': [asset: GraphMediaAsset]
  'remove-node': []
  'preview': []
  'retry': []
  'focus-shot': [id: string]
}>()
const target = ref(''); const replacement = ref(''); const video = ref<HTMLVideoElement | null>(null)
const canConnect = computed(() => ['brief', 'media', 'note'].includes(props.node.kind))
const mediaMime = computed(() => props.previewMime || props.mediaOptions.find(asset => asset.id === props.node.refId)?.mimeType || '')
watch(() => props.node.id, () => { target.value = ''; replacement.value = '' })
function replace(): void {
  const asset = props.mediaOptions.find(asset => asset.id === replacement.value)
  if (asset) emit('replace-media', asset)
}
const pause = () => video.value?.pause()
onDeactivated(pause); onUnmounted(pause)
</script>

<template>
  <aside class="reference-inspector gl-zone" data-test="canvas-reference-inspector" aria-label="参考详情">
    <h3>{{ node.kind === 'note' ? '备注' : node.kind === 'media' ? '素材参考' : node.label || '项目节点' }}</h3>
    <label v-if="node.kind === 'note'" class="gl-form-field">
      <span class="field-label">备注内容</span>
      <textarea :value="noteText" rows="5" :disabled="readonly || saving" data-test="canvas-note-input"
        aria-describedby="canvas-note-limit" @input="emit('update-note', ($event.target as HTMLTextAreaElement).value)"></textarea>
      <span id="canvas-note-limit" class="field-note">{{ [...noteText].length }}/1000 字</span>
    </label>
    <template v-if="node.kind === 'media'">
      <p class="field-note">{{ node.label || '参考素材' }}。作为参考不等于用于成片制作。</p>
      <p v-if="node.unavailableReason" class="field-note" role="status">{{ node.unavailableReason }}</p>
      <button type="button" class="gl-btn-ghost" :disabled="previewLoading || !!node.unavailableReason" data-test="canvas-reference-preview" @click="emit('preview')">
        {{ previewLoading ? '载入预览…' : '预览素材' }}
      </button>
      <img v-if="previewUrl && mediaMime.startsWith('image/')" :src="previewUrl" :alt="node.label || '参考素材预览'" class="reference-preview">
      <video v-else-if="previewUrl" ref="video" :src="previewUrl" controls playsinline preload="none" class="reference-preview"></video>
      <label class="gl-form-field"><span class="field-label">重新选择素材</span>
        <select v-model="replacement" :disabled="readonly || saving" data-test="canvas-reference-replacement">
          <option value="">选择可用素材</option>
          <option v-for="asset in mediaOptions.filter(asset => asset.authorized && asset.status === 'active')" :key="asset.id" :value="asset.id">{{ asset.name }}</option>
        </select>
      </label>
      <button type="button" class="gl-btn-ghost" :disabled="readonly || saving || !replacement" data-test="canvas-reference-replace" @click="replace">替换此参考</button>
    </template>
    <button v-if="parentShotId" type="button" class="gl-btn-ghost" @click="emit('focus-shot', parentShotId)">查看所属镜头与候选</button>
    <template v-if="canConnect">
      <label class="gl-form-field"><span class="field-label">连接到镜头{{ node.kind === 'note' ? '或素材' : '' }}</span>
        <select v-model="target" :disabled="readonly || saving || !!node.unavailableReason" data-test="canvas-reference-target">
          <option value="">选择连接目标</option>
          <option v-for="item in targets.filter(item => item.id !== node.id)" :key="item.id" :value="item.id">{{ item.label || '参考素材' }}</option>
        </select>
      </label>
      <button type="button" class="gl-btn-ghost" :disabled="readonly || saving || !target || !!node.unavailableReason" data-test="canvas-reference-add-edge" @click="emit('add-reference', target)">添加参考线</button>
    </template>
    <ul v-if="edges.length" class="reference-edge-list" aria-label="参考线" data-test="canvas-reference-edges">
      <li v-for="edge in edges" :key="edge.id">
        <span class="field-note">{{ targets.find(node => node.id === edge.toNodeId)?.label || '参考关系' }}</span>
        <button type="button" class="gl-btn-ghost" :disabled="readonly || saving" data-test="canvas-reference-remove-edge" @click="emit('remove-reference', edge.id)">移除参考线</button>
      </li>
    </ul>
    <button v-if="node.kind === 'media' || node.kind === 'note'" type="button" class="gl-btn-ghost" :disabled="readonly || saving" data-test="canvas-reference-remove-node" @click="emit('remove-node')">
      {{ node.kind === 'note' ? '删除备注' : '移除参考节点' }}
    </button>
    <p v-if="node.kind === 'media'" class="field-note">移除节点会同时移除相连的参考线，素材库中的文件会保留。</p>
    <p v-if="saving" class="field-note" role="status">参考保存中…</p>
    <p v-if="error" class="field-note" role="alert" data-test="canvas-reference-error">{{ error }}
      <button type="button" class="gl-btn-ghost" @click="emit('retry')">重试保存</button>
    </p>
  </aside>
</template>

<style scoped>
.reference-inspector { display: flex; flex-direction: column; gap: var(--space-sm); flex: 0 0 var(--layout-rail); width: var(--layout-rail); max-width: 100%; overflow-y: auto; overflow-wrap: anywhere; }
.reference-inspector h3 { font-family: var(--font-display); font-size: var(--text-lg); margin: 0; }
.reference-inspector p { margin: 0; }
.reference-inspector textarea { width: 100%; font-size: var(--text-lg); font-family: var(--font-body); }
.reference-edge-list { list-style: none; padding: 0; margin: 0; }
.reference-edge-list li { display: flex; align-items: center; justify-content: space-between; gap: var(--space-xs); }
.reference-preview { width: 100%; max-height: var(--layout-rail); object-fit: contain; background: var(--color-media-backdrop); border-radius: var(--radius-md); }
</style>
