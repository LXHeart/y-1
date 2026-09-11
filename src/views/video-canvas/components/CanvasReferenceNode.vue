<script setup lang="ts">
/**
 * 任务书 #100 C100-10：画布引用/备注节点（media/note）。
 *
 * 与 ShotNode（业务镜头节点）并列的用户内容节点：media 只存引用（缩略按需懒加载），
 * note 是纯文本便签；失效媒体原位占位、给出原因与「重新选择」入口（TC-023）。
 * 点击节点 = 选中（供 DirectorPanel 编辑/连线），拖拽仍走专用手柄语义（CanvasBoard）。
 */
defineProps<{
  id: string
  kind: 'media' | 'note'
  label: string | null
  text: string | null
  x: number
  y: number
  selected: boolean
  unavailableReason: string | null
}>()

const emit = defineEmits<{
  (e: 'select', nodeId: string): void
  (e: 'request-reselect', nodeId: string): void
}>()
</script>

<template>
  <div
    class="canvas-node glass-card reference-node"
    :class="{ 'canvas-node-selected': selected, 'reference-node-unavailable': !!unavailableReason }"
    :data-test="`canvas-ref-${kind}-${id}`"
    :style="{ left: `${x}px`, top: `${y}px` }"
    role="option"
    :aria-selected="selected"
    :aria-label="kind === 'media' ? `素材节点 ${label ?? id}` : `备注节点`"
    tabindex="0"
    @pointerdown="emit('select', id)"
    @keydown.enter.prevent="emit('select', id)"
    @keydown.space.prevent="emit('select', id)"
  >
    <span class="badge" :class="kind === 'media' ? 'badge-accent' : 'badge-info'">
      {{ kind === 'media' ? '素材' : '备注' }}
    </span>
    <p v-if="kind === 'note'" class="reference-note-text" data-test="canvas-note-text">{{ text }}</p>
    <template v-else>
      <p class="reference-media-name">{{ label ?? '素材' }}</p>
      <p v-if="unavailableReason" class="field-note reference-unavailable" role="status"
        data-test="canvas-ref-unavailable">
        {{ unavailableReason }}
        <button type="button" class="gl-btn-ghost" data-test="canvas-ref-reselect"
          @pointerdown.stop @click.stop="emit('request-reselect', id)">重新选择</button>
      </p>
    </template>
  </div>
</template>
