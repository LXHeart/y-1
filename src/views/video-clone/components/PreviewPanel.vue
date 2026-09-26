<script setup lang="ts">
/**
 * PreviewPanel.vue — C107-21：预览（输入 session/frame；事件 close/retry）。
 * iframe sandbox 仅脚本能力；不 v-html 作者 HTML；加载失败可重试，预览不自动
 * 触发收费生成。
 */
import { computed } from 'vue';
import type { HypitSessionCreated } from '../../../types/hypit';

const props = defineProps<{
  session: HypitSessionCreated | null;
  loading: boolean;
  error: string | null;
  currentFrame: number | null;
  currentTime: number | null;
}>();

const emit = defineEmits<{ close: []; retry: [] }>();

const previewSrc = computed(() => {
  const url = props.session?.ticketUrl;
  return url === undefined || url === null ? null : url;
});
</script>

<template>
  <section class="gl-zone" data-testid="clone-preview-panel" aria-label="预览">
    <header class="clone-preview-head">
      <h2>预览</h2>
      <button v-if="props.session" type="button" class="gl-btn-secondary" @click="emit('close')">关闭预览</button>
      <button v-else type="button" class="gl-btn-secondary" data-testid="clone-preview-retry" :disabled="props.loading"
        @click="emit('retry')">{{ props.loading ? '打开中…' : '打开预览' }}</button>
    </header>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" aria-live="polite">正在打开预览会话…</p>
    <template v-else-if="previewSrc !== null">
      <iframe class="clone-preview-frame" :src="previewSrc" sandbox="allow-scripts"
        :title="`工程预览会话 ${props.session?.sessionId ?? ''}`"></iframe>
      <p v-if="props.currentFrame !== null" class="clone-preview-clock" aria-live="off">
        帧 {{ props.currentFrame }} · {{ (props.currentTime ?? 0).toFixed(2) }}s
      </p>
    </template>
    <p v-else class="clone-empty">预览未打开。打开后可逐帧检查当前画面。</p>
  </section>
</template>

<style scoped>
.clone-preview-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-preview-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-preview-frame { width: 100%; aspect-ratio: 9 / 16; max-height: 70vh; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: #000; }
.clone-preview-clock { font-size: 12px; color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
</style>
