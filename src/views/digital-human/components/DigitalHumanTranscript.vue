<template>
  <section class="dh-transcript" aria-labelledby="dh-transcript-title" data-testid="dh-transcript">
    <div class="dh-transcript-head">
      <h3 id="dh-transcript-title" class="dh-transcript-title">字幕</h3>
      <label class="dh-transcript-consent">
        <input
          :checked="saved"
          type="checkbox"
          data-testid="dh-transcript-save-toggle"
          :disabled="busy"
          @change="emit('toggle-save', ($event.target as HTMLInputElement).checked)"
        />
        <span>保存字幕（默认不保存；开启后保存之后完成的最终文字）</span>
      </label>
    </div>

    <p class="dh-hint" role="status" aria-live="polite" data-testid="dh-transcript-status">{{ statusText }}</p>

    <!-- 生成中 delta 与 final 分开展示；中断文本带标记，不当完整播报（E-05 步骤1）。 -->
    <div v-if="live" class="dh-transcript-live" data-testid="dh-transcript-live">
      <span class="badge badge-info">生成中</span>
      <span v-if="live.interrupted" class="badge badge-warning">已中断</span>
      <p class="dh-transcript-delta">{{ live.text }}<span class="dh-caret">…</span></p>
    </div>

    <div class="dh-transcript-list" data-testid="dh-transcript-list" role="log" aria-label="会话字幕" style="max-height: 320px; overflow-y: auto;">
      <p v-if="entries.length === 0 && !live" class="dh-hint">还没有字幕；发送第一条消息后在这里查看。</p>
      <article v-for="entry in entries" :key="entry.id" class="dh-transcript-entry" :data-role="entry.role">
        <header class="dh-transcript-entry-head">
          <span class="dh-transcript-role">{{ entry.role === 'assistant' ? '数字人' : '我' }}</span>
          <span v-if="entry.status === 'interrupted'" class="badge badge-warning">已中断</span>
          <span v-else-if="entry.status === 'truncated'" class="badge badge-warning">已截断</span>
        </header>
        <p class="dh-transcript-text" style="user-select: text;">{{ entry.text }}</p>
      </article>
    </div>

    <div class="gl-actions">
      <button v-if="saveEnabled" type="button" class="gl-btn-primary" :disabled="busy" data-testid="dh-transcript-save-now" @click="emit('save-now')">
        保存当前字幕
      </button>
      <button v-if="entries.length > 0" type="button" class="gl-btn-secondary" :disabled="busy" data-testid="dh-transcript-export" @click="emit('export')">
        导出已保存文本
      </button>
      <button v-if="hasSaved" type="button" class="gl-btn-secondary" data-testid="dh-transcript-delete" @click="confirmDelete = true">
        删除已保存字幕
      </button>
    </div>

    <GlModal v-if="confirmDelete" title="删除已保存字幕" :persistent="deleting" @close="confirmDelete = false">
      <p>将删除本场已保存的字幕文本，立即生效且不可恢复；未保存的当前内容不会被上传。</p>
      <p class="dh-hint">已另存到素材库的录制视频/配音是独立产物，不受本次删除影响。</p>
      <template #actions>
        <button type="button" class="gl-btn-secondary" :disabled="deleting" @click="confirmDelete = false">取消</button>
        <button type="button" class="gl-btn-primary" :disabled="deleting" data-testid="dh-transcript-delete-confirm" @click="emit('delete')">
          {{ deleting ? '删除中…' : '确认删除' }}
        </button>
      </template>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：final/delta 分栏、保存同意、删除需 GlModal 确认；aria-live 只报状态。
import { computed, ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import type { TranscriptEntry } from '../../../types/digital-human'
import type { LiveGeneration } from '../composables/useDigitalHumanTranscript'

const props = defineProps<{
  entries: TranscriptEntry[]
  live: LiveGeneration | null
  saved: boolean
  loading?: boolean
  busy?: boolean
  /** 会话已结束且仍在保存窗口内。 */
  saveEnabled?: boolean
  /** 服务端已有保存内容（决定删除/导出入口）。 */
  hasSaved: boolean
  deletedNotice?: string | null
}>()

const emit = defineEmits<{
  (e: 'toggle-save', value: boolean): void
  (e: 'save-now'): void
  (e: 'export'): void
  (e: 'delete'): void
}>()

const confirmDelete = ref(false)
const deleting = computed(() => props.busy === true)

const statusText = computed(() => {
  if (props.deletedNotice) return props.deletedNotice
  if (props.loading) return '正在加载字幕…'
  if (props.saved) return '字幕保存已开启（只保存最终文字，不含语音）。'
  return '当前不保存字幕；开启后只保存最终文字。'
})
</script>

<style scoped>
.dh-transcript { display: grid; gap: var(--space-xs); }
.dh-transcript-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); flex-wrap: wrap; }
.dh-transcript-title {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-transcript-consent { display: flex; gap: var(--space-xs); align-items: center; font-size: var(--type-caption); color: var(--color-text-secondary); }
.dh-transcript-live { display: grid; gap: var(--space-xxs); padding: var(--space-sm); border: 1px dashed var(--color-border); border-radius: var(--radius-md); }
.dh-transcript-live .badge { justify-self: start; }
.dh-transcript-delta { margin: 0; font-size: var(--type-body-sm); color: var(--color-text-secondary); }
.dh-caret { color: var(--color-text-muted); }
.dh-transcript-list { display: grid; gap: var(--space-sm); }
.dh-transcript-entry { display: grid; gap: var(--space-xxs); padding: var(--space-sm); border-radius: var(--radius-md); background: var(--surface-muted); }
.dh-transcript-entry[data-role='assistant'] { background: var(--color-surface-highlight); }
.dh-transcript-entry-head { display: flex; gap: var(--space-xxs); align-items: center; }
.dh-transcript-role { font-size: var(--type-caption); font-weight: var(--weight-heading); color: var(--color-text-secondary); }
.dh-transcript-text { margin: 0; font-size: var(--type-body-sm); color: var(--color-text); white-space: pre-wrap; }
</style>
