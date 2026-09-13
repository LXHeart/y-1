<script setup lang="ts">
import type { CanvasShot } from '../useVideoCanvas'
import type { GraphNode } from '../composables/useCanvasGraph'
import EmptyState from '../../../components/shared/EmptyState.vue'
defineProps<{ shots: CanvasShot[]; selectedNodeIds: string[]; focusedShotId: string | null; references?: GraphNode[] }>()
const emit = defineEmits<{ select: [id: string, additive?: boolean]; selectNode: [id: string, additive?: boolean] }>()
</script>

<template>
  <section class="canvas-shot-list" data-test="canvas-shot-list" aria-label="镜头列表">
    <EmptyState v-if="!shots.length" title="尚无镜头" description="从快速模式生成分镜，或在画布助手中补充创作要求。" />
    <ol class="canvas-shot-rows">
      <li v-for="shot in shots" :key="shot.id" :class="{ 'canvas-shot-current': focusedShotId === shot.id }" :data-test="`canvas-list-shot-${shot.seq}`">
        <div class="canvas-shot-row-heading">
          <label class="canvas-touch-choice"><input type="checkbox" :checked="selectedNodeIds.includes(`shot:${shot.id}`)"
            :aria-label="`选择镜头 ${shot.seq}`" :data-test="`canvas-list-check-${shot.seq}`" @change="emit('select', shot.id, true)"><span>镜头 {{ shot.seq }}</span></label>
          <span class="field-note gl-num">{{ shot.plannedSeconds }} 秒 · {{ shot.source?.kind === 'own-media' ? '自有素材' : 'AI 候选' }}</span>
        </div>
        <h3>{{ shot.visual }}</h3><p class="field-note">{{ shot.narration || '暂无旁白' }}</p>
        <button type="button" class="gl-btn-ghost" :aria-expanded="focusedShotId === shot.id" :data-test="`canvas-list-edit-${shot.seq}`" @click="emit('select', shot.id)">属性与预览</button>
      </li>
    </ol>
    <section v-if="references?.length" class="canvas-list-references" aria-label="素材与备注">
      <h3>素材与备注</h3>
      <div v-for="node in references.filter(node => ['note','media','brief'].includes(node.kind))" :key="node.id" class="canvas-reference-row">
        <label class="canvas-touch-choice"><input type="checkbox" :checked="selectedNodeIds.includes(node.id)" :aria-label="`选择${node.label || '备注'}`"
          @change="emit('selectNode', node.id, true)"><span>{{ node.kind === 'note' ? '备注' : node.kind === 'brief' ? '创作要求' : '素材参考' }}</span></label>
        <p>{{ node.text || node.label }}</p><span v-if="node.unavailableReason" class="field-note">{{ node.unavailableReason }}</span>
        <button type="button" class="gl-btn-ghost" @click="emit('selectNode', node.id)">编辑参考</button>
      </div>
    </section>
  </section>
</template>
