<script setup lang="ts">
/**
 * ReviewPanel.vue — C107-21：审片与评论（输入 review；事件 add/resolve/revise）。
 * 评论真相只有服务端 FEEDBACK 一份——本组件只投影并转发操作，不复制可编辑正文。
 */
import { ref } from 'vue';
import type { HypitFeedbackComment } from '../../../types/hypit';

const props = defineProps<{
  comments: HypitFeedbackComment[];
  hash: string;
  loading: boolean;
  error: string | null;
  revising: boolean;
}>();

const emit = defineEmits<{
  add: [text: string, at: number];
  resolve: [comment: HypitFeedbackComment];
  revise: [];
}>();

const draftText = ref('');
const draftAt = ref('0');

function submit(): void {
  const text = draftText.value.trim();
  if (text.length === 0) return;
  const seconds = Number.parseFloat(draftAt.value);
  emit('add', text, Number.isFinite(seconds) && seconds >= 0 ? seconds : 0);
  draftText.value = '';
  draftAt.value = '0';
}

function clock(at: number): string {
  const ticks = Math.round(at * 100);
  const minutes = Math.floor(ticks / 6000).toString().padStart(2, '0');
  const seconds = Math.floor((ticks / 100) % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}.${ticks % 100}`;
}
</script>

<template>
  <section class="gl-zone" data-testid="clone-review-panel" aria-label="审片与评论">
    <h2>审片</h2>
    <form class="clone-review-form" @submit.prevent="submit">
      <label class="gl-field clone-review-time">
        <span>时间点（秒）</span>
        <input v-model="draftAt" type="number" min="0" step="0.1" data-testid="clone-review-at" />
      </label>
      <label class="gl-field clone-review-text">
        <span>意见</span>
        <input v-model="draftText" type="text" maxlength="200" data-testid="clone-review-text"
          placeholder="例如：字幕放大" />
      </label>
      <button type="submit" class="gl-btn-secondary" data-testid="clone-review-add">添加评论</button>
    </form>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" aria-live="polite">正在读取评论…</p>
    <p v-else-if="props.comments.length === 0" class="clone-empty">还没有评论。审看预览后按时间点添加意见。</p>
    <ul v-else class="clone-review-list">
      <li v-for="comment in props.comments" :key="comment.id" class="clone-review-item"
        :class="{ 'clone-review-item--resolved': comment.resolved }">
        <span class="clone-review-clock">{{ clock(comment.at) }}</span>
        <span class="clone-review-text">{{ comment.text }}</span>
        <button v-if="!comment.resolved" type="button" class="gl-btn-secondary" @click="emit('resolve', comment)">
          标记已解决
        </button>
        <span v-else class="clone-review-resolved">已解决</span>
      </li>
    </ul>
    <button type="button" class="gl-btn-primary" data-testid="clone-review-revise"
      :disabled="props.revising || props.comments.every((comment) => comment.resolved)" @click="emit('revise')">
      {{ props.revising ? '修改中…' : '按未解决评论修改' }}
    </button>
  </section>
</template>

<style scoped>
.clone-review-form { display: flex; align-items: flex-end; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.clone-review-time { width: 110px; }
.clone-review-text { flex: 1; min-width: 180px; }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-review-list { list-style: none; margin: 0 0 12px; padding: 0; display: grid; gap: 8px; }
.clone-review-item { display: flex; align-items: center; gap: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-review-item--resolved { opacity: 0.6; }
.clone-review-clock { font-variant-numeric: tabular-nums; font-size: 12px; color: var(--color-text-secondary); }
.clone-review-text { flex: 1; }
.clone-review-resolved { font-size: 12px; color: var(--color-success, #4c9e6b); }
</style>
