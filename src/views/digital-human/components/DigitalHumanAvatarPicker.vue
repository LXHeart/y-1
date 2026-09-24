<template>
  <fieldset class="dh-avatar-picker" :disabled="disabled">
    <legend class="gl-label">形象</legend>
    <p v-if="avatars.length === 0" class="dh-hint">目录暂无可用形象。</p>
    <div class="dh-avatar-picker-grid" role="radiogroup" aria-label="选择形象">
      <button
        v-for="avatar in avatars"
        :key="avatar.id"
        type="button"
        class="gl-chip"
        :class="{
          'gl-chip-active': avatar.id === modelValue && isSelectable(avatar),
          'dh-avatar-unavailable': !isSelectable(avatar),
        }"
        role="radio"
        :aria-checked="avatar.id === modelValue && isSelectable(avatar)"
        :aria-describedby="isSelectable(avatar) ? undefined : `dh-avatar-reason-${avatar.id}`"
        :disabled="disabled || !isSelectable(avatar)"
        :data-testid="`dh-avatar-${avatar.id}`"
        @click="emit('update:modelValue', avatar.id)"
      >
        {{ avatar.name }}
        <span v-if="avatar.source === 'personal'" class="dh-avatar-tag">个人</span>
      </button>
      <span
        v-for="avatar in unavailableAvatars"
        :id="`dh-avatar-reason-${avatar.id}`"
        :key="`reason-${avatar.id}`"
        class="dh-hint dh-avatar-reason"
      >
        {{ avatar.name }}：{{ reasonText(avatar) }}
      </span>
    </div>
  </fieldset>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：只展示可用预设与失效说明；不发起请求、不持有业务状态。
import { computed } from 'vue'
import type { AvatarItem } from '../../../types/digital-human'

const props = defineProps<{
  avatars: AvatarItem[]
  modelValue: string
  /** 可用（approved）后端集合——兼容性由调用方按目录算好传入。 */
  approvedBackendIds: string[]
  disabled?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

/** 可选 = ready 且与任一可用后端兼容；已撤/处理中/失败的形象不可直接开始（K02）。 */
function isSelectable(avatar: AvatarItem): boolean {
  return avatar.state === 'ready'
    && avatar.compatibleBackendIds.some((id) => props.approvedBackendIds.includes(id))
}

const unavailableAvatars = computed(() => props.avatars.filter((avatar) => !isSelectable(avatar)))

function reasonText(avatar: AvatarItem): string {
  if (avatar.state === 'revoked') return avatar.reasonCode ? `已撤下（${avatar.reasonCode}）` : '已撤下'
  if (avatar.state === 'processing') return '处理中，暂不可选'
  if (avatar.state === 'failed') return avatar.reasonCode ? `不可用（${avatar.reasonCode}）` : '不可用'
  return '与当前可用后端不兼容'
}
</script>

<style scoped>
.dh-avatar-picker { border: 0; padding: 0; margin: 0; display: grid; gap: var(--space-xs); }
.dh-avatar-picker-grid { display: flex; flex-wrap: wrap; gap: var(--space-xxs); align-items: center; }
.dh-avatar-unavailable { opacity: 0.6; }
.dh-avatar-tag { font-size: var(--type-caption); color: var(--color-text-muted); }
.dh-avatar-reason { width: 100%; }
</style>
